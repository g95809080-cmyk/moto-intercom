package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

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

    @Test fun strongerStationaryBackgroundAfterSpeechClosesAtEverySensitivity() {
        for (sensitivity in listOf(20, 50, 80)) {
            val gate = calibrated(sensitivity)
            for (time in 2_000L..2_990L step 10) gate.update(70.0, time)
            var closedAt: Long? = null
            var openAfterTail = 0
            for (time in 3_000L..32_990L step 10) {
                val decision = gate.update(45.0, time)
                if (decision.state == VoxGate.State.LISTENING && closedAt == null) closedAt = time
                if (time >= 5_000L && decision.trackVolume > 0.0) openAfterTail++
            }
            assertTrue("sensitivity=$sensitivity never released road background", closedAt != null && closedAt <= 5_000L)
            assertEquals("Background must not repeatedly reopen", 0, openAfterTail)
            assertEquals(45.0, gate.update(45.0, 33_000).noiseFloor, 0.1)
        }
    }

    @Test fun energyBetweenClosingAndOpeningThresholdsCannotRenewHangoverForever() {
        val gate = calibrated()
        gate.update(70.0, 2_000)
        gate.update(70.0, 2_030)
        assertEquals(VoxGate.State.HANGOVER, gate.update(0.0, 2_150).state)
        for (time in 2_160L..4_500L step 10) gate.update(37.0, time)
        assertEquals(VoxGate.State.LISTENING, gate.currentState())
    }

    @Test fun sustainedAndModulatedSpeechHaveNoMaximumTransmissionTime() {
        val steady = calibrated()
        val modulated = calibrated()
        for (time in 2_000L..62_000L step 10) {
            val constant = steady.update(60.0, time)
            val changing = modulated.update(64.0 + 10.0 * sin(2 * PI * 3.0 * time / 1_000), time)
            if (time >= 2_040L) {
                assertTrue("Sustained foreground was cut at $time", constant.trackVolume > 0)
                assertTrue("Modulated foreground was cut at $time", changing.trackVolume > 0)
            }
        }
        assertEquals(30.0, steady.update(60.0, 62_010).noiseFloor, 0.1)
    }

    @Test fun softerSustainedSpeechAndShortSyllableDipsAreNotLearnedAsBackground() {
        val gate = calibrated()
        for (time in 2_000L..2_990L step 10) gate.update(70.0, time)
        for (time in 3_000L..12_000L step 10) {
            val level = if (time % 1_000L < 100L) 45.0 else 60.0
            assertTrue("Speech/tail was cut at $time", gate.update(level, time).trackVolume > 0)
        }
        assertEquals(30.0, gate.update(60.0, 12_010).noiseFloor, 0.1)
    }

    @Test fun learnedRoadBackgroundTracksSlowNoiseRiseAndNewSpeechStillOpensQuickly() {
        val gate = calibrated()
        for (time in 2_000L..2_990L step 10) gate.update(80.0, time)
        for (time in 3_000L..5_000L step 10) gate.update(45.0, time)
        assertEquals(VoxGate.State.LISTENING, gate.currentState())
        for (time in 5_010L..35_000L step 10) {
            assertEquals(VoxGate.State.LISTENING, gate.update(45.0 + (time - 5_000) / 2_000.0, time).state)
        }
        assertEquals(60.0, gate.update(60.0, 35_010).noiseFloor, 0.5)
        gate.update(80.0, 35_020)
        assertEquals(VoxGate.State.OPEN, gate.update(80.0, 35_050).state)
    }

    @Test fun sensitivityDragChangesActualOpeningAboveLearnedRoadNoiseWithoutReset() {
        val gates = listOf(20, 80).map { calibrated(it) }
        for (time in 2_000L..2_990L step 10) gates.forEach { it.update(70.0, time) }
        for (time in 3_000L..5_000L step 10) gates.forEach { it.update(45.0, time) }
        val before = gates[0].update(45.0, 5_010)
        gates.forEach { it.update(52.0, 5_020) }
        assertEquals(VoxGate.State.LISTENING, gates[0].update(52.0, 5_050).state)
        assertEquals(VoxGate.State.OPEN, gates[1].update(52.0, 5_050).state)
        gates[0].updateSettings(true, 80)
        val after = gates[0].update(52.0, 5_060)
        assertTrue(after.openThreshold < before.openThreshold)
        gates[0].update(52.0, 5_090)
        assertEquals(VoxGate.State.OPEN, gates[0].currentState())
    }

    @Test fun shortLoudPulsesCannotEstablishAStationaryBackground() {
        val gate = calibrated()
        // A 10ms horn/impact remains below the opening debounce; this is not semantic horn rejection.
        gate.update(85.0, 2_000)
        assertEquals(VoxGate.State.LISTENING, gate.update(30.0, 2_010).state)
        for (time in 2_020L..3_000L step 10) gate.update(70.0, time)
        for (time in 3_010L..5_000L step 10) {
            val level = if (time % 300L < 40L) 85.0 else 60.0
            assertTrue(gate.update(level, time).trackVolume > 0)
        }
        assertEquals(30.0, gate.update(60.0, 5_010).noiseFloor, 0.1)
    }

    @Test fun softerSlowModulationAboveRoadFloorRemainsForeground() {
        val gate = VoxGate(true)
        for (time in 100L..1_990L step 10) gate.update(45.0, time)
        for (time in 2_000L..2_990L step 10) gate.update(70.0, time)
        for (time in 3_000L..33_000L step 10) {
            val level = 58.0 + 3.0 * sin(2 * PI * (time - 3_000) / 4_000)
            assertTrue("Slow foreground modulation was cut at $time", gate.update(level, time).trackVolume > 0)
        }
        assertEquals(45.0, gate.update(58.0, 33_010).noiseFloor, 0.1)
    }

    @Test fun longOrReversedCaptureGapCannotCountAsStationaryEvidence() {
        for (nextTime in listOf(10_000L, 3_050L)) {
            val gate = calibrated()
            for (time in 2_000L..2_990L step 10) gate.update(70.0, time)
            for (time in 3_000L..3_300L step 10) gate.update(45.0, time)
            val floor = gate.update(45.0, 3_310).noiseFloor
            assertEquals("Missing/reversed PCM counted as continuous background", floor, gate.update(45.0, nextTime).noiseFloor, 0.0)
        }
    }

    @Test fun shortPulseCannotRaiseAcceptedForegroundPeakAndSilenceSofterSpeech() {
        val gate = VoxGate(true)
        for (time in 100L..1_990L step 10) gate.update(45.0, time)
        for (time in 2_000L..2_990L step 10) gate.update(60.0, time)
        gate.update(85.0, 3_000) // One 10ms horn-like energy spike, not accepted foreground.
        for (time in 3_010L..13_000L step 10) {
            assertTrue("A pulse poisoned later speech at $time", gate.update(60.0, time).trackVolume > 0)
        }
        assertEquals(45.0, gate.update(60.0, 13_010).noiseFloor, 0.1)
    }

    @Test fun newForegroundDoesNotInheritPeakFromPreviouslyClosedSegment() {
        val gate = calibrated()
        for (time in 2_000L..2_990L step 10) gate.update(90.0, time)
        for (time in 3_000L..5_000L step 10) gate.update(45.0, time)
        assertEquals(VoxGate.State.LISTENING, gate.currentState())
        for (time in 5_010L..6_000L step 10) gate.update(60.0, time)
        for (time in 6_010L..16_000L step 10) {
            assertTrue("Old peak relabeled a new foreground at $time", gate.update(50.0, time).trackVolume > 0)
        }
    }

    private fun calibrated(sensitivity: Int = 50): VoxGate = VoxGate(true, sensitivity).apply {
        for (time in 100L..1_990L step 10) update(30.0, time)
    }
}
