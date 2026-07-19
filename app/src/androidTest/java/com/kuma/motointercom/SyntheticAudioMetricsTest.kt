package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyntheticAudioMetricsTest {
    @Test
    fun pcmMetricsAreDeterministic() {
        var now = 1_000_000_000L
        val source = SyntheticAudioSource(nowNanos = { now })
        val sink = TestAudioSink().also { it.start(SyntheticAudioSource.PRIMARY_STREAM) }

        source.frames(50).forEach { frame ->
            now += 2_000_000L
            assertTrue(sink.accept(frame, now))
        }

        val metrics = sink.metrics()
        assertEquals(50, metrics.frameCount)
        assertTrue(metrics.normalizedRms in 0.34..0.37)
        assertTrue(metrics.dominantFrequencyHz in 990.0..1_010.0)
        assertEquals(0, metrics.droppedFrames)
        assertEquals(0.0, metrics.dropRate, 0.0)
        assertEquals(2.0, metrics.firstFrameLatencyMillis, 0.01)
    }

    @Test
    fun pauseAndResumeContinueTheSameStream() {
        val source = SyntheticAudioSource(nowNanos = { 0L })
        val sink = TestAudioSink().also { it.start(SyntheticAudioSource.PRIMARY_STREAM) }
        source.frames(5).forEach { assertTrue(sink.accept(it, 1L)) }

        sink.pause()
        assertFalse(sink.accept(source.nextFrame(sequence = 5), 1L))
        sink.resume(SyntheticAudioSource.PRIMARY_STREAM)
        source.frames(5, firstSequence = 5).forEach { assertTrue(sink.accept(it, 1L)) }

        val metrics = sink.metrics()
        assertEquals(10, metrics.frameCount)
        assertEquals(0, metrics.droppedFrames)
        assertEquals(1, metrics.rejectedStoppedFrames)
    }

    @Test
    fun secondStreamAndFramesAfterStopAreRejected() {
        val source = SyntheticAudioSource(nowNanos = { 0L })
        val sink = TestAudioSink().also { it.start("winner") }
        assertTrue(sink.accept(source.nextFrame("winner", 0), 1L))
        assertThrows(IllegalStateException::class.java) { sink.start("loser") }
        assertFalse(sink.accept(source.nextFrame("loser", 0), 1L))
        sink.stop()
        assertFalse(sink.accept(source.nextFrame("winner", 1), 1L))

        val metrics = sink.metrics()
        assertEquals(1, metrics.frameCount)
        assertEquals(1, metrics.rejectedStreamFrames)
        assertEquals(1, metrics.rejectedStoppedFrames)
    }
}
