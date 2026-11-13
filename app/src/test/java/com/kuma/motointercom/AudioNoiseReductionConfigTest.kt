package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioNoiseReductionConfigTest {
    @Test
    fun baselineIsAlwaysBaseline() {
        val config = AudioNoiseReductionConfig.baseline()

        assertEquals(
            AudioNoiseReductionMode.BASELINE,
            config.effectiveMode(isDebugBuild = true)
        )
        assertEquals(
            AudioNoiseReductionMode.BASELINE,
            config.effectiveMode(isDebugBuild = false)
        )
    }

    @Test
    fun experimentalModeRequiresDebugBuildAndExplicitOptIn() {
        val config = AudioNoiseReductionConfig(
            requestedMode = AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS,
            internalExperimentEnabled = true
        )

        assertEquals(
            AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS,
            config.effectiveMode(isDebugBuild = true)
        )
        assertEquals(
            AudioNoiseReductionMode.BASELINE,
            config.effectiveMode(isDebugBuild = false)
        )
    }

    @Test
    fun experimentalModeDefaultsBackToBaselineWithoutOptIn() {
        val config = AudioNoiseReductionConfig(
            requestedMode = AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING
        )

        assertEquals(
            AudioNoiseReductionMode.BASELINE,
            config.effectiveMode(isDebugBuild = true)
        )
    }
}
