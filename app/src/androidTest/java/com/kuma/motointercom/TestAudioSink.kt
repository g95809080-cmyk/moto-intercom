package com.kuma.motointercom

import kotlin.math.abs
import kotlin.math.sqrt

internal data class TestAudioMetrics(
    val frameCount: Int,
    val normalizedRms: Double,
    val dominantFrequencyHz: Double,
    val droppedFrames: Int,
    val dropRate: Double,
    val firstFrameLatencyMillis: Double,
    val rejectedStreamFrames: Int,
    val rejectedStoppedFrames: Int
)

internal class TestAudioSink {
    private var activeStreamId: String? = null
    private var paused = false
    private var terminallyStopped = false
    private var frameCount = 0
    private var minimumSequence: Int? = null
    private var maximumSequence: Int? = null
    private val sequences = linkedSetOf<Int>()
    private var sampleCount = 0L
    private var sumSquares = 0.0
    private var zeroCrossings = 0L
    private var previousSample: Short? = null
    private var sampleRate = 0
    private var firstFrameLatencyNanos: Long? = null
    private var rejectedStreamFrames = 0
    private var rejectedStoppedFrames = 0

    fun start(streamId: String) {
        require(streamId.isNotBlank())
        check(!terminallyStopped) { "Sink is terminally stopped" }
        val active = activeStreamId
        check(active == null || active == streamId) {
            "A second media stream cannot become active: active=$active requested=$streamId"
        }
        activeStreamId = streamId
        paused = false
    }

    fun pause() {
        check(activeStreamId != null) { "No active stream" }
        paused = true
    }

    fun resume(streamId: String) {
        check(activeStreamId == streamId) { "Cannot resume a different stream" }
        check(!terminallyStopped) { "Sink is terminally stopped" }
        paused = false
    }

    fun stop() {
        paused = false
        terminallyStopped = true
        activeStreamId = null
    }

    fun accept(frame: SyntheticPcmFrame, receivedAtNanos: Long = System.nanoTime()): Boolean {
        if (terminallyStopped || paused || activeStreamId == null) {
            rejectedStoppedFrames++
            return false
        }
        if (frame.streamId != activeStreamId) {
            rejectedStreamFrames++
            return false
        }
        require(frame.sampleRate > 0)
        if (sampleRate == 0) sampleRate = frame.sampleRate
        require(sampleRate == frame.sampleRate) { "Sample rate changed within a stream" }

        if (firstFrameLatencyNanos == null) {
            firstFrameLatencyNanos = (receivedAtNanos - frame.sentAtNanos).coerceAtLeast(0L)
        }
        frameCount++
        sequences += frame.sequence
        minimumSequence = minOf(minimumSequence ?: frame.sequence, frame.sequence)
        maximumSequence = maxOf(maximumSequence ?: frame.sequence, frame.sequence)

        for (sample in frame.samples) {
            val value = sample.toDouble()
            sumSquares += value * value
            if (sample != 0.toShort()) {
                val previous = previousSample
                if (previous != null && (previous < 0) != (sample < 0)) {
                    zeroCrossings++
                }
                previousSample = sample
            }
            sampleCount++
        }
        return true
    }

    fun metrics(): TestAudioMetrics {
        val expectedFrames = if (minimumSequence == null || maximumSequence == null) {
            0
        } else {
            maximumSequence!! - minimumSequence!! + 1
        }
        val droppedFrames = (expectedFrames - sequences.size).coerceAtLeast(0)
        val normalizedRms = if (sampleCount == 0L) 0.0 else {
            sqrt(sumSquares / sampleCount) / Short.MAX_VALUE
        }
        val durationSeconds = if (sampleCount == 0L || sampleRate == 0) 0.0 else {
            sampleCount.toDouble() / sampleRate
        }
        val frequency = if (durationSeconds == 0.0) 0.0 else {
            zeroCrossings / (2.0 * durationSeconds)
        }
        return TestAudioMetrics(
            frameCount = frameCount,
            normalizedRms = normalizedRms,
            dominantFrequencyHz = abs(frequency),
            droppedFrames = droppedFrames,
            dropRate = if (expectedFrames == 0) 0.0 else droppedFrames.toDouble() / expectedFrames,
            firstFrameLatencyMillis = (firstFrameLatencyNanos ?: 0L) / 1_000_000.0,
            rejectedStreamFrames = rejectedStreamFrames,
            rejectedStoppedFrames = rejectedStoppedFrames
        )
    }
}
