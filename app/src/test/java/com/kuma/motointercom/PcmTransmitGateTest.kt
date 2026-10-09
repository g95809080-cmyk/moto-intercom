package com.kuma.motointercom

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmTransmitGateTest {
    @Test fun measuresOriginalFrameThenSilencesActualDirectBufferWithoutChangingCursor() {
        val frame = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 480) frame.putShort(index * 2, if (index % 2 == 0) 8192 else -8192)
        frame.position(960)
        val before = PcmTransmitGate.approximateLevel(frame)
        assertTrue(before > 70.0)
        PcmTransmitGate.silence(frame)
        assertEquals(960, frame.position())
        assertEquals(960, frame.limit())
        assertEquals(0.0, PcmTransmitGate.approximateLevel(frame), 0.0)
        for (index in 0 until frame.capacity()) assertEquals(0, frame.get(index).toInt())
    }

    @Test fun measuresNegativeAndPositivePcm16AtEqualEnergy() {
        val positive = ByteBuffer.wrap(byteArrayOf(0, 32))
        val negative = ByteBuffer.wrap(byteArrayOf(0, -32))
        assertEquals(PcmTransmitGate.approximateLevel(positive), PcmTransmitGate.approximateLevel(negative), 0.0)
    }
}
