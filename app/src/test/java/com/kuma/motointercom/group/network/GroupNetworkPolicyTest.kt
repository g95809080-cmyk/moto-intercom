package com.kuma.motointercom.group.network

import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GroupNetworkPolicyTest {
    @Test fun negotiatedMtuReassemblesAtBoundariesAndReducesAttRoundTrips() {
        for (mtu in listOf(0, 22, 23, 64, 247, 517)) {
            val packet = GroupBleChunks.packetBytes(mtu)
            assertTrue(packet in 20..244)
            for (size in listOf(1, 16, 17, 240, 241, 8192)) {
                val bytes = ByteArray(size) { it.toByte() }
                val chunks = GroupBleChunks.split(bytes, packet)
                val assembler = GroupBleAssembler()
                chunks.dropLast(1).forEach { assertNull(assembler.accept(it)) }
                assertArrayEquals(bytes, assembler.accept(chunks.last()))
                assertTrue(chunks.all { it.size <= packet })
            }
        }
        assertEquals(35, GroupBleChunks.split(ByteArray(8192), 244).size)
        assertEquals(512, GroupBleChunks.split(ByteArray(8192), 20).size)
    }
    @Test fun burstQueueIsBoundedAndTerminalOverflowIsCoalesced() {
        val queue = ArrayDeque<() -> Unit>()
        var delivered = 0
        var overflow = 0
        val callbacks = GroupBoundedCallbacks({ queue.addLast(it) }, { overflow++ }, 4)
        repeat(10_000) { callbacks.post { delivered++ } }
        assertEquals(5, queue.size)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(0, delivered); assertEquals(1, overflow)
        callbacks.post { delivered++ }; assertTrue(queue.isEmpty())
    }
    @Test fun normalCallbackCompletionReturnsCapacityAndCancellationDropsQueuedWork() {
        val queue = ArrayDeque<() -> Unit>()
        var count = 0
        val callbacks = GroupBoundedCallbacks({ queue.addLast(it) }, { fail("overflow") }, 1)
        repeat(10) { callbacks.post { count++ }; queue.removeFirst()() }
        assertEquals(10, count)
        callbacks.post { count++ }; callbacks.close(); queue.removeFirst()()
        assertEquals(10, count)
    }
    @Test fun minimumMtuTransportsFullAuthAndEncryptedMessagesWithoutAliasing() {
        for (size in listOf(1, 16, 17, 4096, 8192)) {
            val input = ByteArray(size) { it.toByte() }
            val chunks = GroupBleChunks.split(input)
            input.fill(0)
            val assembler = GroupBleAssembler()
            chunks.dropLast(1).forEach { assertNull(assembler.accept(it)) }
            assertArrayEquals(ByteArray(size) { it.toByte() }, assembler.accept(chunks.last()))
            assertArrayEquals(byteArrayOf(99), assembler.accept(GroupBleChunks.split(byteArrayOf(99)).single()))
        }
    }
    @Test fun oversizedTruncatedReorderedAndDuplicateFragmentsPoisonAssembler() {
        val fragments = GroupBleChunks.split(ByteArray(33))
        val bad = listOf(byteArrayOf(), ByteArray(21), byteArrayOf(32, 1, 0, 0, 1),
            fragments[1], fragments[0].copyOf(19), byteArrayOf(0, 0, 0, 0, 0))
        bad.forEach { bytes ->
            val assembler = GroupBleAssembler()
            assertThrows(Exception::class.java) { assembler.accept(bytes) }
            assertThrows(Exception::class.java) { assembler.accept(fragments[0]) }
        }
        val duplicate = GroupBleAssembler()
        duplicate.accept(fragments[0])
        assertThrows(Exception::class.java) { duplicate.accept(fragments[0]) }
        val wrongTotal = GroupBleAssembler()
        wrongTotal.accept(fragments[0])
        assertThrows(Exception::class.java) { wrongTotal.accept(fragments[1].copyOf().also { it[1] = 32 }) }
    }
    @Test fun noEmptyOrOversizedOutgoingAndClosedAssemblerNeverReopens() {
        assertThrows(Exception::class.java) { GroupBleChunks.split(byteArrayOf()) }
        assertThrows(Exception::class.java) { GroupBleChunks.split(ByteArray(8193)) }
        val assembler = GroupBleAssembler(); assembler.close()
        assertThrows(Exception::class.java) { assembler.accept(GroupBleChunks.split(byteArrayOf(1)).single()) }
    }
    @Test fun onlineGuessBudgetIsGlobalAndPerAddressAndDoesNotConsumeForFullCapacity() {
        var time = 0L
        val budget = GroupBleBudget { time }
        assertFalse(budget.acquire("a", 4))
        repeat(3) { assertTrue(budget.acquire("a", 0)) }
        assertFalse(budget.acquire("a", 0))
        repeat(9) { assertTrue(budget.acquire("b$it", 0)) }
        assertFalse(budget.acquire("new-address", 0))
        time = 59_999; assertFalse(budget.acquire("a", 0))
        time = 60_000; assertTrue(budget.acquire("a", 0))
        time = 1; assertThrows(Exception::class.java) { budget.acquire("a", 0) }
    }
    @Test fun concurrentLastGlobalPermitSerializesClockAndAccounting() {
        assertConcurrentLastPermit(globalLimit = true)
    }
    @Test fun concurrentLastAddressPermitSerializesClockAndAccounting() {
        assertConcurrentLastPermit(globalLimit = false)
    }
    private fun assertConcurrentLastPermit(globalLimit: Boolean) {
        val race = AtomicBoolean()
        val calls = AtomicInteger()
        val firstClock = CountDownLatch(1)
        val secondClock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val budget = GroupBleBudget {
            if (!race.get()) 0L else if (calls.incrementAndGet() == 1) {
                firstThread.set(Thread.currentThread())
                firstClock.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                100L
            } else { secondClock.countDown(); 200L }
        }
        if (globalLimit) repeat(11) { assertTrue(budget.acquire("seed-$it", 0)) }
        else repeat(2) { assertTrue(budget.acquire("target", 0)) }
        race.set(true)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit(Callable { budget.acquire("target", 0) })
            assertTrue(firstClock.await(5, TimeUnit.SECONDS))
            val second = workers.submit(Callable {
                secondThread.set(Thread.currentThread())
                budget.acquire("target", 0)
            })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var clockSerialized = false
            val threads = ManagementFactory.getThreadMXBean()
            while (System.nanoTime() < deadline && secondClock.count != 0L) {
                val info = secondThread.get()?.let { threads.getThreadInfo(it.id) }
                if (info?.threadState == Thread.State.BLOCKED &&
                    info.lockInfo?.identityHashCode == System.identityHashCode(budget) &&
                    info.lockOwnerId == firstThread.get().id) {
                    clockSerialized = true
                    break
                }
                Thread.yield()
            }
            // On the old implementation B records time 200 before A resumes at 100.
            if (!clockSerialized) assertTrue(second.get(5, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertFalse(second.get(5, TimeUnit.SECONDS))
            assertTrue("The actual budget monitor must also protect clock reads", clockSerialized)
            assertEquals(2, calls.get())
            assertFalse(budget.acquire("target", 0))
            assertEquals(!globalLimit, budget.acquire("other", 0))
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    @Test fun timeoutDoesNotReleasePendingCreateAndOldLeaseCannotReleaseSuccessor() {
        val ownership = GroupGoOwnership()
        val old = ownership.acquire()!!
        ownership.creating(old)
        assertFalse(ownership.releaseConfirmed(old))
        assertNull(ownership.acquire())
        assertTrue(ownership.createFinished(old))
        assertTrue(ownership.releaseConfirmed(old))
        val next = ownership.acquire()!!
        assertFalse(ownership.createFinished(old))
        assertFalse(ownership.releaseConfirmed(old))
        assertTrue(ownership.owns(next))
    }
    @Test fun credentialsHaveBoundsAndRedactedDiagnostics() {
        val credentials = GroupWifiCredentials("DIRECT-ab-MotoCom", "secret-pass")
        assertFalse(credentials.toString().contains("secret-pass"))
        assertFalse(credentials.toString().contains("DIRECT"))
        assertThrows(Exception::class.java) { GroupWifiCredentials("a".repeat(33), "secret-pass") }
        assertThrows(Exception::class.java) { GroupWifiCredentials("正常", "short") }
        assertThrows(Exception::class.java) { GroupWifiCredentials("a\u0000", "secret-pass") }
    }
}
