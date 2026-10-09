package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.audio.AudioRecordDataCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Native peers invoke the patched callback immediately before handing the actual frame to JNI. */
@RunWith(AndroidJUnit4::class)
class VoxTransmitInstrumentationTest {
    @Test fun mutedNativeCaptureContinuesAndVoxReopensActualPcm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val errors = LinkedBlockingQueue<Throwable>()
        val connected = CountDownLatch(2)
        val captured = CountDownLatch(6)
        val zeroFrames = AtomicReference(CountDownLatch(6))
        val audibleFrames = AtomicReference(CountDownLatch(6))
        val initial = VersionedAudioControls(0, AudioControlSettings(muted = true, voxEnabled = true))
        val first = RiderAudioEngine(context, onEngineError = { errors.offer(it) }, initialAudioControls = initial)
        val second = RiderAudioEngine(context, onEngineError = { errors.offer(it) }, initialAudioControls = initial)
        lateinit var offerer: RiderMediaSession
        lateinit var answerer: RiderMediaSession
        try {
            val recorder = awaitRecorder(first)
            val callbackField = recorder.javaClass.getField("motoCaptureCallback")
            val callback = callbackField.get(recorder) as AudioRecordDataCallback
            // Inject a test microphone tone, then run the production gate on the actual JNI buffer.
            callbackField.set(recorder, AudioRecordDataCallback { format, channels, rate, buffer ->
                for (index in 0 until buffer.capacity() / 2) {
                    val sample = if (index % 16 < 8) 8192 else -8192
                    buffer.put(index * 2, sample.toByte())
                    buffer.put(index * 2 + 1, (sample shr 8).toByte())
                }
                callback.onAudioDataRecorded(format, channels, rate, buffer)
                if ((0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() }) zeroFrames.get().countDown()
                else audibleFrames.get().countDown()
            })
            offerer = first.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(it) },
                onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onAudioLevelChanged = { captured.countDown() }, onError = { errors.offer(it) }, isSessionCurrent = { true }
            ))
            answerer = second.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { errors.offer(it) }, isSessionCurrent = { true }
            ))
            offerer.createOffer()
            assertTrue("Native peers did not connect: ${errors.peek()}", connected.await(15, TimeUnit.SECONDS))
            assertTrue("Initial mute did not clear actual JNI input", zeroFrames.get().await(5, TimeUnit.SECONDS))
            assertTrue("Muted sender stopped VOX input: ${errors.peek()}", captured.await(5, TimeUnit.SECONDS))
            first.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = true)))
            assertTrue("VOX failed to reopen JNI input on speech", audibleFrames.get().await(5, TimeUnit.SECONDS))
            zeroFrames.set(CountDownLatch(6))
            first.updateAudioControls(VersionedAudioControls(2, AudioControlSettings(muted = true, voxEnabled = false)))
            assertTrue("Manual mute did not clear JNI input", zeroFrames.get().await(5, TimeUnit.SECONDS))
            audibleFrames.set(CountDownLatch(1))
            first.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            assertFalse("Older controls reopened a muted sender", audibleFrames.get().await(200, TimeUnit.MILLISECONDS))
            first.updateAudioControls(VersionedAudioControls(3, AudioControlSettings(voxEnabled = false)))
            assertTrue("Disabling VOX failed to reopen JNI input", audibleFrames.get().await(5, TimeUnit.SECONDS))
            assertNull("Unexpected native engine failure", errors.poll())
        } finally { first.close(); second.close() }
    }

    private fun awaitRecorder(engine: RiderAudioEngine): Any {
        val field = RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val adm = field.get(engine)
            if (adm != null) return adm.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(adm)!! }
            SystemClock.sleep(10)
        }
        error("AudioDeviceModule was not initialized")
    }
}
