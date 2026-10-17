package com.kuma.motointercom

import android.Manifest
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
                    val muted = armed.get() && position == 960 && owned.remaining() == 0
                    if (buffer === owned || buffer.position() != 0 || buffer.remaining() != owned.capacity()) {
                        badShape.compareAndSet(null, "capacity=${buffer.capacity()} position=${buffer.position()} remaining=${buffer.remaining()}")
                    }
                    production.onAudioDataRecorded(format, channels, rate, buffer)
                    if (owned.position() != position || owned.limit() != limit) badShape.compareAndSet(null, "SDK cursor changed")
                    if (!armed.get()) baseline.countDown()
                    if (muted) mutedFrames.countDown()
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
    private fun awaitRecorder(engine: RiderAudioEngine): Any {
        (field(engine, "rtc") as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
        val module = checkNotNull(field(engine, "audioDeviceModule"))
        return module.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module)!! }
    }
    private fun field(engine: RiderAudioEngine, name: String): Any? =
        RiderAudioEngine::class.java.getDeclaredField(name).run { isAccessible = true; get(engine) }
}
