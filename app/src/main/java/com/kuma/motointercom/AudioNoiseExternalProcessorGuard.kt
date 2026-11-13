package com.kuma.motointercom

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a capture post-processor fail-safe: one over-budget frame permanently
 * bypasses the experimental processor for the current audio runtime.
 */
internal class AudioNoiseExternalProcessorGuard(
    private val nowNanos: () -> Long = System::nanoTime,
    private val onFrameFinished: (processingNanos: Long) -> Boolean,
    private val onProcessingFailure: (Throwable) -> Unit = {}
) {
    @Volatile private var bypassed = false
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    fun process(
        numBands: Int,
        numFrames: Int,
        buffer: ByteBuffer,
        processBlock: (Int, Int, ByteBuffer) -> Unit = { _, _, _ -> }
    ) {
        if (bypassed || closed.get()) return
        inFlight.incrementAndGet()
        if (bypassed || closed.get()) {
            inFlight.decrementAndGet()
            return
        }
        val startedAt = nowNanos()
        var processingFailed = false
        try {
            processBlock(numBands, numFrames, buffer)
        } catch (t: Throwable) {
            processingFailed = true
            bypassed = true
            runCatching { onProcessingFailure(t) }
        } finally {
            val elapsed = (nowNanos() - startedAt).coerceAtLeast(0L)
            if (!processingFailed && !runCatching { onFrameFinished(elapsed) }.getOrDefault(false)) {
                bypassed = true
            }
            inFlight.decrementAndGet()
        }
    }

    /** Called after capture has stopped and before the native factory is destroyed. */
    fun close() {
        closed.set(true)
        bypassed = true
        while (inFlight.get() > 0) {
            Thread.yield()
        }
    }

    fun isBypassed(): Boolean = bypassed
}
