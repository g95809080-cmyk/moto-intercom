package com.kuma.motointercom

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AudioIoGateTest {
    private val queue = ArrayDeque<() -> Unit>()
    private val applied = mutableListOf<Boolean>()
    private val lock = Any()
    private val gate = AudioIoGate(lock, false, { queue.addLast(it) }, { applied += it })
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    @Test fun queuedGrantCannotOpenAudioAfterNewInterruption() {
        gate.request(true); gate.request(false); drain()
        assertEquals(listOf(false), applied)
    }
    @Test fun revocationStopsOldProducerBeforeReplacementGrant() {
        gate.request(false); gate.request(true); drain()
        assertEquals(listOf(false, true), applied)
        gate.applyCurrent { assertTrue(it) }
    }
    @Test fun pcCreatedDuringSuspensionRemainsOff() {
        gate.request(true); gate.request(false)
        gate.applyCurrent { assertFalse(it) }; drain()
        assertFalse(applied.any { it })
    }
    @Test fun closeRevokesPendingAndFutureGrants() {
        gate.request(true); gate.close(); gate.request(true); drain()
        assertEquals(listOf(false), applied)
        gate.applyCurrent { assertFalse(it) }
    }
    @Test fun closedMediaInvalidatesOldQueuedGrantAndStats() {
        gate.request(true)
        val old = gate.revision()
        gate.invalidate(); drain()
        assertTrue(applied.isEmpty())
        assertFalse(gate.allows(old))
    }
    @Test fun pcmPermissionReadNeverWaitsForNativeStopHoldingRegistryLock() {
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Thread { synchronized(lock) { held.countDown(); release.await(2, TimeUnit.SECONDS) } }
        worker.start(); assertTrue(held.await(1, TimeUnit.SECONDS))
        val read = CountDownLatch(1)
        val pcm = Thread { gate.allows(gate.revision()); read.countDown() }
        try { pcm.start(); assertTrue("PCM read blocked native stop/join", read.await(200, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); worker.join(2_000); pcm.join(2_000) }
    }
}
