package com.kuma.motointercom

/**
 * Internal-only audio processing modes. The product default is always [BASELINE].
 * Experimental modes are intentionally not exposed through the user-facing UI.
 */
internal enum class AudioNoiseReductionMode {
    BASELINE,
    WEBRTC_SOFTWARE_NS,
    EXTERNAL_CAPTURE_POST_PROCESSING
}

internal data class AudioNoiseReductionConfig(
    val requestedMode: AudioNoiseReductionMode = AudioNoiseReductionMode.BASELINE,
    val internalExperimentEnabled: Boolean = false
) {
    fun effectiveMode(isDebugBuild: Boolean): AudioNoiseReductionMode {
        if (requestedMode == AudioNoiseReductionMode.BASELINE) {
            return AudioNoiseReductionMode.BASELINE
        }
        return if (isDebugBuild && internalExperimentEnabled) {
            requestedMode
        } else {
            AudioNoiseReductionMode.BASELINE
        }
    }

    companion object {
        fun baseline(): AudioNoiseReductionConfig = AudioNoiseReductionConfig()
    }
}
