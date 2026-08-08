package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioControlStateTest {
    @Test
    fun normalizationClampsSensitivityWithoutChangingUserChoices() {
        assertEquals(
            AudioControlSettings(muted = true, voxEnabled = false, voxSensitivity = 100),
            AudioControlSettings(muted = true, voxEnabled = false, voxSensitivity = 140).normalized()
        )
        assertEquals(
            AudioControlSettings(voxSensitivity = 0),
            AudioControlSettings(voxSensitivity = -1).normalized()
        )
    }

    @Test
    fun idleSnapshotClearsSessionMuteAndKeepsPersistentVoxSettings() {
        assertEquals(
            AudioControlSnapshot(
                controls = AudioControlSettings(
                    muted = false,
                    voxEnabled = true,
                    voxSensitivity = 72
                ),
                voxState = VoxRuntimeState.IDLE
            ),
            idleAudioControlSnapshot(
                AudioControlSettings(muted = true, voxEnabled = true, voxSensitivity = 72)
            )
        )
        assertEquals(
            VoxRuntimeState.DISABLED,
            idleAudioControlSnapshot(AudioControlSettings(voxEnabled = false)).voxState
        )
    }

    @Test
    fun manualMuteOverridesGateStateAndVolume() {
        val muted = AudioControlSettings(muted = true, voxEnabled = true)

        assertEquals(
            VoxRuntimeState.MUTED,
            effectiveVoxRuntimeState(muted, VoxGate.State.OPEN)
        )
        assertEquals(0.0, effectiveTrackVolume(muted, gateVolume = 1.0), 0.0)
    }

    @Test
    fun gateStateRemainsTheSingleSourceForUnmutedRuntimeState() {
        val enabled = AudioControlSettings(voxEnabled = true)
        assertEquals(
            VoxRuntimeState.LISTENING,
            effectiveVoxRuntimeState(enabled, VoxGate.State.LISTENING)
        )
        assertEquals(VoxRuntimeState.OPEN, effectiveVoxRuntimeState(enabled, VoxGate.State.OPEN))
        assertEquals(
            VoxRuntimeState.HANGOVER,
            effectiveVoxRuntimeState(enabled, VoxGate.State.HANGOVER)
        )

        val disabled = AudioControlSettings(voxEnabled = false)
        assertEquals(
            VoxRuntimeState.DISABLED,
            effectiveVoxRuntimeState(disabled, VoxGate.State.BYPASS)
        )
        assertEquals(1.0, effectiveTrackVolume(disabled, gateVolume = 1.0), 0.0)
    }
}
