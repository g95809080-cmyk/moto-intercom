package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule

/** Actual SDK mute branch, producer thread and JNI frame; no test-side normalization. */
@RunWith(AndroidJUnit4::class)
class NativeCaptureCursorInstrumentationTest {
    @Test fun sdkMuteSurvivesCallbackWritingNonzeroPcm() = verifyBridgeMute(true, true)
    @Test fun unmuteDuringCallbackCannotExposeSdkMutedFrame() = verifyBridgeMute(true, false)
    @Test fun muteDuringCallbackClearsCurrentFrame() = verifyBridgeMute(false, true)
    @Test fun unmutedCallbackPreservesPcmAndSdkCursor() = verifyBridgeMute(false, false)

    private fun verifyBridgeMute(frameMuted: Boolean, muteAfterCallback: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val engine = RiderAudioEngine(context)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(9_600, AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)))
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, record.state)
            val recorder = awaitRecorder(engine)
            val callback = recorder.javaClass.getField("motoCaptureCallback")
            val mute = recorder.javaClass.getDeclaredField("microphoneMute").apply { isAccessible = true }
            val owned = ByteBuffer.allocateDirect(960).apply { position(capacity()) }
            callback.set(recorder, AudioRecordDataCallback { _, _, _, view ->
                assertNotSame(owned, view)
                assertEquals(0, view.position())
                assertEquals(960, view.remaining())
                for (index in 0 until view.capacity()) view.put(index, 42)
                mute.setBoolean(recorder, muteAfterCallback)
            })
            mute.setBoolean(recorder, frameMuted)
            recorder.javaClass.getMethod("motoBeforeNativeRecord", AudioRecord::class.java,
                ByteBuffer::class.java, Boolean::class.javaPrimitiveType).invoke(recorder, record, owned, frameMuted)
            val expected = if (frameMuted || muteAfterCallback) 0.toByte() else 42.toByte()
            assertTrue("SDK mute must survive callback output", (0 until owned.capacity()).all { owned.get(it) == expected })
            assertEquals(960, owned.position())
            assertEquals(960, owned.limit())
        } finally {
            record.release()
            engine.close()
            assertTrue((field(engine, "rtc") as ExecutorService).awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test fun rnnoiseKeepsProcessingSdkMutedNativeFrames() = verifyCursor(true)
    @Test fun webRtcKeepsProcessingSdkMutedNativeFrames() = verifyCursor(false)

    private fun verifyCursor(rnnoise: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val errors = LinkedBlockingQueue<Throwable>()
        val remoteErrors = LinkedBlockingQueue<Throwable>()
        val connected = CountDownLatch(2)
        val baseline = CountDownLatch(6)
        val mutedFrames = CountDownLatch(12)
        val armed = AtomicBoolean(false)
        val badShape = AtomicReference<String>()
        val first = RiderAudioEngine(context, onEngineError = { errors.offer(it) },
            initialAudioControls = VersionedAudioControls(0, AudioControlSettings(voxEnabled = false, rnnoiseEnabled = rnnoise)))
        val second = RiderAudioEngine(context, onEngineError = { remoteErrors.offer(it) },
            initialAudioControls = VersionedAudioControls(0, AudioControlSettings(voxEnabled = false)))
        lateinit var offerer: RiderMediaSession
        lateinit var answerer: RiderMediaSession
        try {
            val recorder = awaitRecorder(first)
            awaitRecorder(second)
            val callbackField = recorder.javaClass.getField("motoCaptureCallback")
            val production = callbackField.get(recorder) as AudioRecordDataCallback
            callbackField.set(recorder, AudioRecordDataCallback { format, channels, rate, buffer ->
                try {
                    val owned = Thread.currentThread().javaClass.getDeclaredField("motoOwnedBuffer").run {
                        isAccessible = true; get(Thread.currentThread()) as ByteBuffer
                    }
                    val position = owned.position()
                    val limit = owned.limit()
                    val muted = armed.get() && frameMuted()
                    if (buffer === owned || buffer.position() != 0 || buffer.remaining() != owned.capacity()) {
                        badShape.compareAndSet(null, "capacity=${buffer.capacity()} position=${buffer.position()} remaining=${buffer.remaining()}")
                    }
                    production.onAudioDataRecorded(format, channels, rate, buffer)
                    // Challenge the bridge's final mute enforcement even when the microphone
                    // or model happens to produce zero throughout this device run.
                    if (muted) buffer.put(0, 42)
                    if (owned.position() != position || owned.limit() != limit) badShape.compareAndSet(null, "SDK cursor changed")
                    if (!armed.get()) baseline.countDown()
                } catch (failure: Throwable) { errors.offer(failure) }
            })
            recorder.javaClass.getDeclaredField("audioSamplesReadyCallback").apply { isAccessible = true }
                .set(recorder, JavaAudioDeviceModule.SamplesReadyCallback { samples ->
                    try {
                        // SDK tail runs after the patched callback and native delivery.
                        if (armed.get() && frameMuted()) {
                            assertTrue("Muted native frame was rewritten", samples.data.all { it == 0.toByte() })
                            mutedFrames.countDown()
                        }
                    } catch (failure: Throwable) { errors.offer(failure) }
                })
            offerer = first.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(it) },
                onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { errors.offer(it) }, isSessionCurrent = { true }))
            answerer = second.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { remoteErrors.offer(it) }, isSessionCurrent = { true }))
            offerer.createOffer()
            assertTrue("Native peers did not connect: ${errors.peek()}", connected.await(15, TimeUnit.SECONDS))
            assertTrue("Native capture baseline missing: ${errors.peek()}", baseline.await(5, TimeUnit.SECONDS))
            assertNull(errors.peek())
            val before = field(first, "rnnoiseFrames") as Long
            assertTrue(!rnnoise || before > 0)
            armed.set(true)
            (field(first, "audioDeviceModule") as JavaAudioDeviceModule).setMicrophoneMute(true)
            assertTrue("No SDK-muted native frames: ${errors.peek()}", mutedFrames.await(5, TimeUnit.SECONDS))
            val after = field(first, "rnnoiseFrames") as Long
            assertNull("Callback frame view was invalid", badShape.get())
            assertNull("Native capture failed", errors.poll(200, TimeUnit.MILLISECONDS))
            assertNull(remoteErrors.poll())
            assertFalse((field(first, "captureFailed") as AtomicBoolean).get())
            assertTrue("RNNoise stopped at SDK mute", !rnnoise || after > before)
        } finally {
            first.close(); second.close()
            assertTrue((field(first, "rtc") as ExecutorService).awaitTermination(15, TimeUnit.SECONDS))
            assertTrue((field(second, "rtc") as ExecutorService).awaitTermination(15, TimeUnit.SECONDS))
        }
    }
    private fun frameMuted(): Boolean = Thread.currentThread().javaClass.getDeclaredField("motoFrameMuted").run {
        isAccessible = true; getBoolean(Thread.currentThread())
    }
    private fun awaitRecorder(engine: RiderAudioEngine): Any {
        (field(engine, "rtc") as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
        val module = checkNotNull(field(engine, "audioDeviceModule"))
        return module.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module)!! }
    }
    private fun field(engine: RiderAudioEngine, name: String): Any? =
        RiderAudioEngine::class.java.getDeclaredField(name).run { isAccessible = true; get(engine) }
}
