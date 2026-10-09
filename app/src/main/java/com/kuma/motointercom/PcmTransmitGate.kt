package com.kuma.motointercom

import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.sqrt

/** Reads PCM16 before muting; absolute access preserves the recorder's buffer position and limit. */
internal object PcmTransmitGate {
    fun approximateLevel(buffer: ByteBuffer): Double {
        var squares = 0.0
        val samples = buffer.capacity() / 2
        if (samples == 0) return 0.0
        for (index in 0 until samples) {
            val sample = (buffer.get(index * 2 + 1).toInt() shl 8) or (buffer.get(index * 2).toInt() and 0xff)
            squares += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(squares / samples)
        return if (rms == 0.0) 0.0 else (20 * log10(rms / Short.MAX_VALUE) + 90.0).coerceAtLeast(0.0)
    }

    fun silence(buffer: ByteBuffer) {
        for (index in 0 until buffer.capacity()) buffer.put(index, 0)
    }
}
