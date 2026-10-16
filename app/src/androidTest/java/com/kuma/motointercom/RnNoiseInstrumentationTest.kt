package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the packaged model through JNI on the device's actual ABI. */
@RunWith(AndroidJUnit4::class)
class RnNoiseInstrumentationTest {
    @Test fun reportsStreamingProcessingCostWithoutLoggingAudio() {
        val pcm = frame()
        val random = Random(518L)
        val times = LongArray(1_000)
        RnNoiseProcessor().use { processor ->
            repeat(1_050) { index ->
                fillNoise(pcm, random, 12_000)
                val start = System.nanoTime()
                assertProbability(processor.process(pcm, 48_000, 1))
                if (index >= 50) times[index - 50] = System.nanoTime() - start
            }
        }
        times.sort()
        android.util.Log.i("RNNoiseBenchmark", "n=${times.size} avgUs=${times.average() / 1_000} p50Us=${times[500] / 1_000} p95Us=${times[950] / 1_000} maxUs=${times.last() / 1_000} over10ms=${times.count { it > 10_000_000L }}")
    }

    @Test fun stationaryNoiseIsChangedAndReducedAfterWarmup() {
        val pcm = frame()
        val random = Random(731L)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        var changedSamples = 0
        val before = ShortArray(RnNoiseProcessor.FRAME_SAMPLES)
        RnNoiseProcessor().use { processor ->
            repeat(250) { frameIndex ->
                for (i in before.indices) {
                    val sample = (random.nextInt(12_001) - 6_000).toShort()
                    before[i] = sample
                    pcm.putShort(i * 2, sample)
                }
                assertProbability(processor.process(pcm, 48_000, 1))
                if (frameIndex >= 50) {
                    for (i in before.indices) {
                        val input = before[i].toDouble()
                        val output = pcm.getShort(i * 2).toDouble()
                        inputEnergy += input * input
                        outputEnergy += output * output
                        if (output.toInt() != before[i].toInt()) changedSamples++
                    }
                }
            }
        }
        assertTrue("Native model must modify PCM", changedSamples > 90_000)
        assertTrue("Noise RMS should decrease: energy ratio=${outputEnergy / inputEnergy}",
            outputEnergy < inputEnergy * 0.8)
    }

    @Test fun resetProducesExactlyTheSameStreamAsFreshInstance() {
        val random = Random(91L)
        val reused = frame()
        val fresh = frame()
        RnNoiseProcessor().use { processor ->
            repeat(40) { fillNoise(reused, random, 20_000); processor.process(reused, 48_000, 1) }
            processor.reset()
            RnNoiseProcessor().use { reference ->
                repeat(12) {
                    fillNoise(reused, random, 8_000)
                    copyFrame(reused, fresh)
                    assertEquals(reference.process(fresh, 48_000, 1), processor.process(reused, 48_000, 1), 0f)
                    for (i in 0 until RnNoiseProcessor.FRAME_SAMPLES) {
                        assertEquals("Reset leaked previous audio at sample $i", fresh.getShort(i * 2), reused.getShort(i * 2))
                    }
                }
            }
        }
    }

    @Test fun freshSilenceStaysSilent() {
        val pcm = frame()
        RnNoiseProcessor().use { processor ->
            repeat(20) {
                assertProbability(processor.process(pcm, 48_000, 1))
                for (i in 0 until RnNoiseProcessor.FRAME_SAMPLES) assertEquals(0, pcm.getShort(i * 2).toInt())
            }
        }
    }

    @Test fun fullScalePcmAndUnalignedFrameRespectBufferBounds() {
        val prefix = 7
        val suffix = 9
        val pcm = ByteBuffer.allocateDirect(prefix + RnNoiseProcessor.FRAME_BYTES + suffix).order(ByteOrder.nativeOrder())
        for (i in 0 until pcm.capacity()) pcm.put(i, 0x5A.toByte())
        pcm.position(prefix)
        pcm.limit(prefix + RnNoiseProcessor.FRAME_BYTES)
        RnNoiseProcessor().use { processor ->
            repeat(80) { index ->
                for (i in 0 until RnNoiseProcessor.FRAME_SAMPLES) {
                    pcm.putShort(prefix + i * 2, if ((i + index) % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE)
                }
                assertProbability(processor.process(pcm, 48_000, 1))
                assertEquals(prefix, pcm.position())
                assertEquals(prefix + RnNoiseProcessor.FRAME_BYTES, pcm.limit())
            }
        }
        pcm.clear()
        for (i in 0 until prefix) assertEquals(0x5A.toByte(), pcm.get(i))
        for (i in prefix + RnNoiseProcessor.FRAME_BYTES until pcm.capacity()) assertEquals(0x5A.toByte(), pcm.get(i))
    }

    @Test fun slicedDirectBufferProcessesOnlyItsOwnFrame() {
        val backing = ByteBuffer.allocateDirect(RnNoiseProcessor.FRAME_BYTES + 4).order(ByteOrder.nativeOrder())
        backing.putShort(0, 12345.toShort())
        backing.putShort(backing.capacity() - 2, (-12345).toShort())
        backing.position(2)
        backing.limit(backing.capacity() - 2)
        val pcm = backing.slice().order(ByteOrder.nativeOrder())
        fillNoise(pcm, Random(52L), 8_000)
        RnNoiseProcessor().use { assertProbability(it.process(pcm, 48_000, 1)) }
        backing.clear()
        assertEquals(12345, backing.getShort(0).toInt())
        assertEquals(-12345, backing.getShort(backing.capacity() - 2).toInt())
    }

    @Test fun unsupportedFormatsAndFramesFailExplicitly() {
        RnNoiseProcessor().use { processor ->
            assertInvalid { processor.process(frame(), 16_000, 1) }
            assertInvalid { processor.process(frame(), 48_000, 2) }
            assertInvalid { processor.process(ByteBuffer.allocate(RnNoiseProcessor.FRAME_BYTES), 48_000, 1) }
            assertInvalid { processor.process(frame().asReadOnlyBuffer(), 48_000, 1) }
            assertInvalid { processor.process(ByteBuffer.allocateDirect(RnNoiseProcessor.FRAME_BYTES - 2), 48_000, 1) }
            assertInvalid { processor.process(ByteBuffer.allocateDirect(RnNoiseProcessor.FRAME_BYTES + 2), 48_000, 1) }
            assertInvalid { processor.process(frame().apply { limit(0) }, 48_000, 1) }
            // Invalid calls must not contaminate the native stream.
            val pcm = frame()
            fillNoise(pcm, Random(13L), 8_000)
            val expected = frame()
            copyFrame(pcm, expected)
            RnNoiseProcessor().use { reference ->
                assertEquals(reference.process(expected, 48_000, 1), processor.process(pcm, 48_000, 1), 0f)
                for (i in 0 until RnNoiseProcessor.FRAME_SAMPLES) assertEquals(expected.getShort(i * 2), pcm.getShort(i * 2))
            }
        }
    }

    @Test fun closeIsIdempotentAndSerializesWithProcessing() {
        val processor = RnNoiseProcessor()
        val firstFrame = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val worker = Thread {
            val pcm = frame()
            try {
                repeat(200) {
                    processor.process(pcm, 48_000, 1)
                    firstFrame.countDown()
                }
            } catch (_: IllegalStateException) {
                // A close between frames is a supported lifecycle outcome.
            } catch (error: Throwable) {
                errors.set(error)
            } finally {
                firstFrame.countDown()
            }
        }
        try {
            worker.start()
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            processor.close()
            processor.close()
            worker.join(5_000)
            assertFalse("Capture worker did not stop", worker.isAlive)
            errors.get()?.let { throw AssertionError("Concurrent close/process failed", it) }
            assertClosed { processor.process(frame(), 48_000, 1) }
            assertClosed { processor.reset() }
        } finally {
            processor.close()
        }
    }

    private fun frame() = ByteBuffer.allocateDirect(RnNoiseProcessor.FRAME_BYTES).order(ByteOrder.nativeOrder())

    private fun fillNoise(pcm: ByteBuffer, random: Random, amplitude: Int) {
        for (i in 0 until RnNoiseProcessor.FRAME_SAMPLES) {
            pcm.putShort(i * 2, (random.nextInt(amplitude * 2 + 1) - amplitude).toShort())
        }
    }

    private fun copyFrame(from: ByteBuffer, to: ByteBuffer) {
        for (i in 0 until RnNoiseProcessor.FRAME_SAMPLES) to.putShort(i * 2, from.getShort(i * 2))
    }

    private fun assertProbability(value: Float) = assertTrue("Invalid VAD: $value", value.isFinite() && value in 0f..1f)

    private inline fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }

    private inline fun assertClosed(block: () -> Unit) {
        try { block(); fail("Expected IllegalStateException") } catch (_: IllegalStateException) { }
    }
}
