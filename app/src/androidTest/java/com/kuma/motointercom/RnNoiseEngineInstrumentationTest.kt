package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.audio.AudioRecordDataCallback

/** Deterministic callback injection. VoxTransmitInstrumentationTest separately uses real native producers. */
@RunWith(AndroidJUnit4::class)
class RnNoiseEngineInstrumentationTest {
    @Test fun replacementProducerAtSameRevisionStartsWithFreshHistory() {
        val errors = LinkedBlockingQueue<Throwable>()
        val engine = engine(AudioControlSettings(voxEnabled = false, rnnoiseEnabled = true), errors)
        try {
            val callback = callback(engine)
            val pcm = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
            val random = Random(289)
            repeat(40) {
                repeat(480) { pcm.putShort(it * 2, (random.nextInt(20_001) - 10_000).toShort()) }
                callback.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, pcm)
            }
            assertEquals(40L, field(engine, "rnnoiseFrames"))
            val gate = field(engine, "audioIoGate") as AudioIoGate
            val revision = gate.revision()
            val replacement = Thread {
                runCatching {
                    RnNoiseProcessor().use { reference ->
                        repeat(12) {
                            repeat(480) { pcm.putShort(it * 2, 0) }
                            val expected = ByteBuffer.allocateDirect(960)
                            reference.process(expected, 48_000, 1)
                            callback.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, pcm)
                            repeat(960) { assertEquals("Previous producer history escaped at byte $it", expected.get(it), pcm.get(it)) }
                        }
                    }
                }.onFailure { errors.offer(it) }
            }
            (field(engine, "recordingOwner") as NativeAudioProducerOwner).start(replacement, { true }) {}
            replacement.start()
            replacement.join(10_000)
            assertFalse("Replacement capture did not return", replacement.isAlive)
            assertEquals("Test must keep the same I/O revision", revision, gate.revision())
            assertEquals(52L, field(engine, "rnnoiseFrames"))
            assertNull("Replacement capture failed", errors.poll())
        } finally { close(engine) }
    }

    @Test fun muteAndVoxCloseDropHistoryBeforeReopening() {
        val errors = LinkedBlockingQueue<Throwable>()
        val settings = AudioControlSettings(voxEnabled = false, rnnoiseEnabled = true)
        val engine = engine(settings, errors)
        try {
            val callback = callback(engine)
            val pcm = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
            val random = Random(281)
            fun deliver() = callback.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, pcm)
            fun noisyFrames() = repeat(40) {
                repeat(480) { pcm.putShort(it * 2, (random.nextInt(20_001) - 10_000).toShort()) }
                deliver()
            }
            fun freshSilence() = RnNoiseProcessor().use { reference ->
                repeat(12) {
                    repeat(480) { pcm.putShort(it * 2, 0) }
                    val expected = ByteBuffer.allocateDirect(960)
                    reference.process(expected, 48_000, 1)
                    deliver()
                    repeat(960) { assertEquals("Suppressed history escaped at byte $it", expected.get(it), pcm.get(it)) }
                }
            }
            noisyFrames()
            val beforeMute = field(engine, "rnnoiseFrames") as Long
            engine.updateAudioControls(VersionedAudioControls(1, settings.copy(muted = true)))
            noisyFrames()
            assertEquals(beforeMute, field(engine, "rnnoiseFrames"))
            assertTrue((0 until 960).all { pcm.get(it) == 0.toByte() })
            engine.updateAudioControls(VersionedAudioControls(2, settings))
            freshSilence()
            noisyFrames()
            engine.updateAudioControls(VersionedAudioControls(3, settings.copy(voxEnabled = true)))
            repeat(480) { pcm.putShort(it * 2, 0) }
            val beforeVox = field(engine, "rnnoiseFrames") as Long
            deliver()
            assertEquals("Closed VOX advanced RNNoise", beforeVox, field(engine, "rnnoiseFrames"))
            engine.updateAudioControls(VersionedAudioControls(4, settings))
            freshSilence()
            assertNull("Unexpected engine failure", errors.poll())
        } finally { close(engine) }
    }

    @Test fun wrongActualFormatIsSilencedAndReportedOnceWithoutAdvancingTheModel() {
        val errors = LinkedBlockingQueue<Throwable>()
        val engine = engine(AudioControlSettings(voxEnabled = false, rnnoiseEnabled = true), errors)
        try {
            val callback = callback(engine)
            val pcm = ByteBuffer.allocateDirect(960)
            repeat(960) { pcm.put(it, 85) }
            callback.onAudioDataRecorded(AudioFormat.ENCODING_PCM_8BIT, 1, 48_000, pcm)
            assertTrue("Invalid capture escaped", (0 until 960).all { pcm.get(it) == 0.toByte() })
            assertNotNull("Invalid format left the runtime silently active", errors.poll(5, TimeUnit.SECONDS))
            assertEquals(0L, field(engine, "rnnoiseFrames"))
            repeat(960) { pcm.put(it, 85) }
            callback.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, pcm)
            assertTrue((0 until 960).all { pcm.get(it) == 0.toByte() })
            assertEquals(0L, field(engine, "rnnoiseFrames"))
            assertNull(errors.poll(150, TimeUnit.MILLISECONDS))
        } finally { close(engine) }
    }

    private fun engine(settings: AudioControlSettings, errors: LinkedBlockingQueue<Throwable>): RiderAudioEngine {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val engine = RiderAudioEngine(context, onEngineError = { errors.offer(it) },
            initialAudioControls = VersionedAudioControls(0, settings))
        (field(engine, "rtc") as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
        (field(engine, "recordingOwner") as NativeAudioProducerOwner).start(Thread.currentThread(), { true }) {}
        return engine
    }

    private fun callback(engine: RiderAudioEngine): AudioRecordDataCallback {
        val module = field(engine, "audioDeviceModule")!!
        val recorder = module.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module)!! }
        return recorder.javaClass.getField("motoCaptureCallback").get(recorder) as AudioRecordDataCallback
    }

    private fun field(engine: RiderAudioEngine, name: String): Any? =
        RiderAudioEngine::class.java.getDeclaredField(name).run { isAccessible = true; get(engine) }

    private fun close(engine: RiderAudioEngine) {
        engine.close()
        assertTrue((field(engine, "rtc") as ExecutorService).awaitTermination(15, TimeUnit.SECONDS))
    }
}
