package com.kuma.motointercom

internal const val MIN_VOX_SENSITIVITY = 0
internal const val DEFAULT_VOX_SENSITIVITY = 50
internal const val MAX_VOX_SENSITIVITY = 100

internal data class AudioControlSettings(
    val muted: Boolean = false,
    val voxEnabled: Boolean = true,
    val voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY
) {
    fun normalized(): AudioControlSettings = copy(
        voxSensitivity = voxSensitivity.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
    )
}

internal enum class VoxRuntimeState {
    IDLE,
    DISABLED,
    MUTED,
    LISTENING,
    OPEN,
    HANGOVER
}

internal data class AudioControlSnapshot(
    val controls: AudioControlSettings,
    val voxState: VoxRuntimeState
)

internal fun idleAudioControlSnapshot(settings: AudioControlSettings): AudioControlSnapshot {
    val idleControls = settings.normalized().copy(muted = false)
    return AudioControlSnapshot(
        controls = idleControls,
        voxState = if (idleControls.voxEnabled) VoxRuntimeState.IDLE else VoxRuntimeState.DISABLED
    )
}

internal fun effectiveVoxRuntimeState(
    controls: AudioControlSettings,
    gateState: VoxGate.State
): VoxRuntimeState = when {
    controls.muted -> VoxRuntimeState.MUTED
    !controls.voxEnabled || gateState == VoxGate.State.BYPASS -> VoxRuntimeState.DISABLED
    gateState == VoxGate.State.LISTENING -> VoxRuntimeState.LISTENING
    gateState == VoxGate.State.OPEN -> VoxRuntimeState.OPEN
    else -> VoxRuntimeState.HANGOVER
}

internal fun effectiveTrackVolume(
    controls: AudioControlSettings,
    gateVolume: Double
): Double = if (controls.muted) 0.0 else gateVolume
