package com.kuma.motointercom

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNoiseExternalProcessorGuardTest {
    @Test
    fun passThroughLeavesCaptureBufferUnchanged() {
        val before = byteArrayOf(1, 2, 3, 4, 5, 6)
        val buffer = ByteBuffer.wrap(before.copyOf())
        val guard = AudioNoiseExternalProcessorGuard(
            nowNanos = { 1L },
            onFrameFinished = { true }
        )

        guard.process(1, 3, buffer)

        assertArrayEquals(before, buffer.array())
        assertEquals(false, guard.isBypassed())
    }

    @Test
    fun overBudgetFramePermanentlyBypassesFutureExperimentalProcessing() {
        var now = 0L
        var processCalls = 0
        val guard = AudioNoiseExternalProcessorGuard(
            nowNanos = { now },
            onFrameFinished = { elapsedNanos -> elapsedNanos <= 10_000_000L }
        )
        val buffer = ByteBuffer.allocate(4)

        guard.process(1, 2, buffer) { _, _, _ ->
            processCalls++
            now = 11_000_000L
        }
        guard.process(1, 2, buffer) { _, _, _ -> processCalls++ }

        assertTrue(guard.isBypassed())
        assertEquals(1, processCalls)
    }

    @Test
    fun processingFailureIsSwallowedAndPermanentlyBypassesFutureProcessing() {
        var failure: Throwable? = null
        var processCalls = 0
        val guard = AudioNoiseExternalProcessorGuard(
            nowNanos = { 0L },
            onFrameFinished = { true },
            onProcessingFailure = { failure = it }
        )
        val buffer = ByteBuffer.allocate(4)

        guard.process(1, 2, buffer) { _, _, _ ->
            processCalls++
            error("synthetic processor failure")
        }
        guard.process(1, 2, buffer) { _, _, _ -> processCalls++ }

        assertTrue(guard.isBypassed())
        assertEquals(1, processCalls)
        assertTrue(failure?.message?.contains("synthetic") == true)
    }
}
