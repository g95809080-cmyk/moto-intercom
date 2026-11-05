package com.kuma.motointercom

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Owns one RNNoise stream. Construct off the audio callback, and close with its
 * audio engine. Input is exactly 10 ms of little-endian mono PCM16 at 48 kHz.
 * Processing replaces [ByteBuffer.position]..[ByteBuffer.limit] in place,
 * preserving the buffer's position and limit. ByteBuffer.order does not change
 * the PCM byte format. The returned voice probability is diagnostic only.
 */
internal class RnNoiseProcessor : Closeable {
    private val lock = Any()
    private var nativeHandle = nativeCreate().also {
        check(it != 0L) { "Unable to create RNNoise processor" }
    }

    fun process(buffer: ByteBuffer, sampleRate: Int, channels: Int): Float = synchronized(lock) {
        check(nativeHandle != 0L) { "RNNoise processor is closed" }
        require(sampleRate == SAMPLE_RATE) { "RNNoise requires 48000 Hz PCM16" }
        require(channels == 1) { "RNNoise requires mono PCM16" }
        require(buffer.isDirect && !buffer.isReadOnly) { "RNNoise requires a writable direct ByteBuffer" }
        require(buffer.remaining() == FRAME_BYTES) { "RNNoise requires exactly 480 PCM16 samples (10 ms)" }
        nativeProcess(nativeHandle, buffer, buffer.position(), buffer.remaining())
    }

    /** Drops every previous stream's recurrent, pitch and look-ahead history. */
    fun reset() = synchronized(lock) {
        check(nativeHandle != 0L) { "RNNoise processor is closed" }
        nativeReset(nativeHandle)
    }

    override fun close() = synchronized(lock) {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Float
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 480
        const val FRAME_BYTES = FRAME_SAMPLES * 2

        init {
            System.loadLibrary("moto_rnnoise")
        }
    }
}
