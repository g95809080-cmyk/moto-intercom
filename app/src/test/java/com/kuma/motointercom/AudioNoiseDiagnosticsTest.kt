package com.kuma.motointercom

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNoiseDiagnosticsTest {
    @Test
    fun recordsFrameMetadataAndCountsBudgetOverruns() {
        val diagnostics = AudioNoiseDiagnostics(
            deviceModel = "test-device",
            requestedMode = AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS,
            initialActiveMode = AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS,
            initialBluetoothProfile = AudioBluetoothProfile.CLASSIC_SCO,
            hardwareAecAvailable = false,
            hardwareNsAvailable = false
        )

        diagnostics.recordAudioFrame(
            sampleRate = 16_000,
            channelCount = 1,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            frameBytes = 320,
            processingNanos = 11_000_000L
        )
        diagnostics.recordExternalProcessorFrame()
        val snapshot = diagnostics.snapshot()

        assertEquals(16_000, snapshot.sampleRate)
        assertEquals(1, snapshot.channelCount)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, snapshot.audioFormat)
        assertEquals(320, snapshot.frameBytes)
        assertEquals(1L, snapshot.frameCount)
        assertEquals(1L, snapshot.overrunCount)
        assertEquals(1L, snapshot.externalProcessorFrameCount)
        assertTrue(snapshot.toLogLine().contains("profile=classic_sco"))
    }

    @Test
    fun fallbackChangesActiveModeAndKeepsReason() {
        val diagnostics = AudioNoiseDiagnostics(
            deviceModel = "test-device",
            requestedMode = AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING,
            initialActiveMode = AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING,
            initialBluetoothProfile = AudioBluetoothProfile.UNKNOWN,
            hardwareAecAvailable = false,
            hardwareNsAvailable = false
        )

        diagnostics.recordFallback("factory_create_failed")

        val snapshot = diagnostics.snapshot()
        assertEquals(AudioNoiseReductionMode.BASELINE, snapshot.activeMode)
        assertEquals("factory_create_failed", snapshot.fallbackReason)
    }

    @Test
    fun externalProcessorOverrunActivatesBaselineFallback() {
        val diagnostics = AudioNoiseDiagnostics(
            deviceModel = "test-device",
            requestedMode = AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING,
            initialActiveMode = AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING,
            initialBluetoothProfile = AudioBluetoothProfile.UNKNOWN,
            hardwareAecAvailable = false,
            hardwareNsAvailable = false
        )

        assertEquals(false, diagnostics.recordExternalProcessorFrame(11_000_000L))

        val snapshot = diagnostics.snapshot()
        assertEquals(AudioNoiseReductionMode.BASELINE, snapshot.activeMode)
        assertEquals(1L, snapshot.externalProcessorOverrunCount)
        assertEquals("external_processor_overrun", snapshot.fallbackReason)
    }
}
