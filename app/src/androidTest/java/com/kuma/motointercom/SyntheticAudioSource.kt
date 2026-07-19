package com.kuma.motointercom

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class SyntheticPcmFrame(
    val streamId: String,
    val sequence: Int,
    val sentAtNanos: Long,
    val sampleRate: Int,
    val samples: ShortArray
)

internal class SyntheticAudioSource(
    private val sampleRate: Int = 16_000,
    private val frequencyHz: Double = 1_000.0,
    private val amplitude: Double = 0.5,
    private val frameDurationMillis: Int = 20,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init {
        require(sampleRate > 0)
        require(frequencyHz > 0.0 && frequencyHz < sampleRate / 2.0)
        require(amplitude in 0.0..1.0)
        require(frameDurationMillis > 0)
    }

    private val samplesPerFrame = sampleRate * frameDurationMillis / 1_000
    private var sampleCursor = 0L

    fun nextFrame(streamId: String = PRIMARY_STREAM, sequence: Int): SyntheticPcmFrame {
        require(streamId.isNotBlank())
        val samples = ShortArray(samplesPerFrame) { offset ->
            val sampleIndex = sampleCursor + offset
            val phase = 2.0 * PI * frequencyHz * sampleIndex / sampleRate
            (sin(phase) * amplitude * Short.MAX_VALUE).roundToInt().toShort()
        }
        sampleCursor += samplesPerFrame
        return SyntheticPcmFrame(
            streamId = streamId,
            sequence = sequence,
            sentAtNanos = nowNanos(),
            sampleRate = sampleRate,
            samples = samples
        )
    }

    fun frames(
        count: Int,
        streamId: String = PRIMARY_STREAM,
        firstSequence: Int = 0
    ): List<SyntheticPcmFrame> {
        require(count >= 0)
        return List(count) { nextFrame(streamId, firstSequence + it) }
    }

    companion object {
        const val PRIMARY_STREAM = "primary"
    }
}
