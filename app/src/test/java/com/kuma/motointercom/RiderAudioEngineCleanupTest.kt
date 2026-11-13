package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RiderAudioEngineCleanupTest {
    @Test
    fun platformCleanupStillRunsWhenMediaCleanupFails() {
        val calls = mutableListOf<String>()
        val mediaFailure = IllegalStateException("injected media cleanup failure")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runAllCleanupSteps(
                {
                    calls += "media"
                    throw mediaFailure
                },
                { calls += "platform" }
            )
        }

        assertSame(mediaFailure, thrown)
        assertEquals(listOf("media", "platform"), calls)
    }

    @Test
    fun laterCleanupFailuresAreAggregated() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runAllCleanupSteps({ throw first }, { throw second })
        }

        assertSame(first, thrown)
        assertEquals(listOf(second), thrown.suppressed.toList())
    }

    @Test
    fun audioPlatformCleanupStopsAudioBeforeDestroyingExternalProcessor() {
        val calls = mutableListOf<String>()

        runAudioPlatformCleanup(
            stopAudio = { calls += "stop-audio" },
            closeExternalProcessor = { calls += "external-guard" },
            disposeTrack = { calls += "track" },
            disposeSource = { calls += "source" },
            disposeFactory = { calls += "factory" },
            destroyExternalProcessorFactory = { calls += "external-factory" },
            releaseAudioDevice = { calls += "audio-device" }
        )

        assertEquals(
            listOf(
                "stop-audio",
                "external-guard",
                "track",
                "source",
                "factory",
                "external-factory",
                "audio-device"
            ),
            calls
        )
    }
}
