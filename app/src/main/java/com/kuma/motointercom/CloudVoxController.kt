package com.kuma.motointercom

// Keep GME's capture pipeline running and gate only the upstream stream. The
// SDK's 0..100 mic score drives VOX and is not a calibrated PCM dB value.
internal class CloudVoxController(initial: VersionedAudioControls) {
    data class Output(val sendEnabled: Boolean, val state: VoxRuntimeState, val level: Float)
    private var controls = initial.normalized()
    private var gate = VoxGate(controls.settings.voxEnabled,controls.settings.voxSensitivity)

    fun update(next: VersionedAudioControls): Boolean {
        if (next.revision < controls.revision) return false
        val value=next.normalized()
        if (value == controls) return false
        val muteChanged=value.settings.muted != controls.settings.muted
        controls=value
        if (muteChanged) reset()
        else gate.updateSettings(value.settings.voxEnabled,value.settings.voxSensitivity)
        return true
    }
    fun reset() { gate=VoxGate(controls.settings.voxEnabled,controls.settings.voxSensitivity) }
    fun settings(): VersionedAudioControls = controls
    fun current(active: Boolean): Output {
        val state=when {
            controls.settings.muted -> VoxRuntimeState.MUTED
            !controls.settings.voxEnabled -> VoxRuntimeState.DISABLED
            !active -> VoxRuntimeState.IDLE
            else -> effectiveVoxRuntimeState(controls.settings,gate.currentState())
        }
        val sendEnabled=active && !controls.settings.muted &&
            (!controls.settings.voxEnabled || gate.currentState()!=VoxGate.State.LISTENING)
        return Output(sendEnabled,state,0f)
    }
    fun sample(captureLevel: Int, nowMs: Long): Output {
        val level=captureLevel.coerceIn(0,100)
        val decision=gate.update(level.toDouble(),nowMs)
        return Output(effectiveTrackVolume(controls.settings,decision.trackVolume)>0,
            effectiveVoxRuntimeState(controls.settings,decision.state),level/100f)
    }
}
