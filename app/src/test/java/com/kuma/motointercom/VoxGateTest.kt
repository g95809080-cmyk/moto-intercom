package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun higherSensitivityUsesALowerOpeningThresholdWhileDefaultIsUnchanged() {
        val low = VoxGate(enabled = true, sensitivity = 0).update(20.0, 100)
        val normal = VoxGate(enabled = true, sensitivity = 50).update(20.0, 100)
        val high = VoxGate(enabled = true, sensitivity = 100).update(20.0, 100)

        assertTrue(low.openThreshold > normal.openThreshold)
        assertEquals(40.0, normal.openThreshold, 0.0)
        assertTrue(normal.openThreshold > high.openThreshold)
    }

    @Test
    fun sensitivityAlsoChangesTheOpeningThresholdAboveTheAdaptiveNoiseFloor() {
        val gates = listOf(0, 50, 100).map { VoxGate(true, it) }
        for (time in 100L..620L step 20L) gates.forEach { it.update(44.0, time) }
        val decisions = gates.map { it.update(44.0, 640L) }
        assertTrue(decisions[0].openThreshold > decisions[1].openThreshold)
        assertTrue(decisions[1].openThreshold > decisions[2].openThreshold)

        gates.forEach { it.update(50.0, 700L) }
        val voice = gates.map { it.update(50.0, 730L) }
        assertEquals(VoxGate.State.LISTENING, voice[0].state)
        assertEquals(VoxGate.State.LISTENING, voice[1].state)
        assertEquals(VoxGate.State.OPEN, voice[2].state)
    }

    @Test
    fun draggingSensitivityKeepsLearnedNoiseAndDoesNotRestartCalibration() {
        val gate = VoxGate(true, 0)
        for (time in 100L..620L step 20L) gate.update(44.0, time)
        val before = gate.update(44.0, 640L)
        gate.updateSettings(true, 100)
        val after = gate.update(60.0, 700L)
        assertEquals(before.noiseFloor, after.noiseFloor, 0.0)
        assertTrue(after.openThreshold < before.openThreshold)
        assertEquals(VoxGate.State.OPEN, gate.update(60.0, 730L).state)
    }

    @Test
    fun highSensitivityStillClosesWhenOnlyTheLearnedBackgroundRemains() {
        val gate = VoxGate(true, 100)
        for (time in 100L..620L step 20L) gate.update(44.0, time)
        gate.update(60.0, 700L)
        assertEquals(VoxGate.State.OPEN, gate.update(60.0, 730L).state)
        assertEquals(VoxGate.State.HANGOVER, gate.update(44.0, 850L).state)
        assertEquals(VoxGate.State.LISTENING, gate.update(44.0, 1_550L).state)
    }
}
