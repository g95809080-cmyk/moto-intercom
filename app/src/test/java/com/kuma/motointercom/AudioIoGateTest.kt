package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class AudioIoGateTest {
    private val queue = ArrayDeque<() -> Unit>()
    private val applied = mutableListOf<Boolean>()
    private val lock = Any()
    private val gate = AudioIoGate(lock, false, { queue.addLast(it) }, { applied += it })
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }

    @Test fun queuedGrantCannotOpenAudioAfterNewInterruption() {
        gate.request(true)
        gate.request(false)
        drain()
        assertEquals(listOf(false), applied)
    }

    @Test fun lastSessionRevocationCannotOverwriteLaterReplacementGrant() {
        // The engine removes its last registry entry and requests pause under this same lock.
        synchronized(lock) { gate.request(false) }
        gate.request(true)
        drain()
        assertEquals(listOf(true), applied)
        gate.applyCurrent { assertTrue(it) }
    }

    @Test fun pcCreatedDuringSuspensionRemainsOff() {
        gate.request(true); gate.request(false)
        gate.applyCurrent { assertFalse(it) }
        drain()
        assertFalse(applied.any { it })
    }

    @Test fun closeRevokesAllPendingAndFutureGrants() {
        gate.request(true); gate.close(); gate.request(true)
        drain()
        assertEquals(listOf(false), applied)
        gate.applyCurrent { assertFalse(it) }
    }
}
