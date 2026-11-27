package com.kuma.motointercom.group.network

import org.junit.Assert.*
import org.junit.Test

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
