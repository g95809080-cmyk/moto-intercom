package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxGateTest {
    @Test
    fun opensHangsOverAndCloses() {
        val gate = VoxGate(enabled = true)
        assertEquals(VoxGate.State.LISTENING, gate.update(20.0, 0).state)
        gate.update(60.0, 501)
        assertEquals(VoxGate.State.OPEN, gate.update(60.0, 526).state)
        assertEquals(VoxGate.State.HANGOVER, gate.update(0.0, 646).state)
        assertEquals(VoxGate.State.LISTENING, gate.update(0.0, 1_346).state)
    }

    @Test
    fun bypassAlwaysKeepsTrackOpen() {
        val decision = VoxGate(enabled = false).update(0.0, 0)
        assertEquals(VoxGate.State.BYPASS, decision.state)
        assertEquals(1.0, decision.trackVolume, 0.0)
    }
}
