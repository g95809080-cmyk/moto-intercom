package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.PeerConnection

@RunWith(AndroidJUnit4::class)
class NativeAudioFailureInstrumentationTest {
    @Test fun closingMediaRevokesOldNativeProducerBeforeDisposeAndReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val first = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val second = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val blocked = CountDownLatch(1)
        val released = CountDownLatch(1)
        val oldErrorDelivered = CountDownLatch(1)
        val blockNext = AtomicBoolean(false)
        try {
            val admField = RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").apply { isAccessible = true }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var module: Any? = null
            while (module == null && SystemClock.elapsedRealtime() < deadline) {
                module = admField.get(first)
                if (module == null) SystemClock.sleep(10)
            }
            assertNotNull(module)
            val record = module!!.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
            val errors = record.javaClass.getDeclaredField("errorCallback").run {
                isAccessible = true; get(record) as JavaAudioDeviceModule.AudioRecordErrorCallback
            }
            val field = record.javaClass.getField("motoCaptureCallback")
            val capture = field.get(record) as AudioRecordDataCallback
            field.set(record, AudioRecordDataCallback { format, channels, rate, frame ->
                if (blockNext.compareAndSet(true, false)) {
                    blocked.countDown()
                    if (released.await(5, TimeUnit.SECONDS)) {
                        errors.onWebRtcAudioRecordError("old media intentional teardown")
                        oldErrorDelivered.countDown()
                    }
                }
                capture.onAudioDataRecorded(format, channels, rate, frame)
            })
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            val connected = CountDownLatch(2)
            offerer = first.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(it) },
                onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
            answerer = second.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
            offerer.createOffer()
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            blockNext.set(true)
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            offerer.close()
            released.countDown()
            assertTrue(oldErrorDelivered.await(5, TimeUnit.SECONDS))
            assertNull("Old producer stopped its online runtime", failures.poll(300, TimeUnit.MILLISECONDS))
            val replacement = first.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = {}, onLocalIceCandidateGenerated = {}, isSessionCurrent = { true }
            ))
            val initialized = CountDownLatch(1)
            val rtc = RiderAudioEngine::class.java.getDeclaredField("rtc").run {
                isAccessible = true; get(first) as java.util.concurrent.ExecutorService
            }
            rtc.execute(initialized::countDown)
            assertTrue(initialized.await(5, TimeUnit.SECONDS))
            assertNull("Teardown permanently failed the reused engine", failures.poll())
            replacement.close()
        } finally { released.countDown(); first.close(); second.close() }
    }

    @Test fun nativeReadFailureReportsOnceMutesFramesAndIgnoresErrorsAfterClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val peer = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        try {
            val admField = RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").apply { isAccessible = true }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var module: Any? = null
            while (module == null && SystemClock.elapsedRealtime() < deadline) {
                module = admField.get(engine)
                if (module == null) SystemClock.sleep(10)
            }
            assertNotNull("Native ADM did not initialize", module)
            val record = module!!.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
            val nativeHardwareNs = record.javaClass.getDeclaredField("isNoiseSuppressorSupported").run {
                isAccessible = true; getBoolean(record)
            }
            assertFalse("Hardware NS must not replace the software path", nativeHardwareNs)
            val errors = record.javaClass.getDeclaredField("errorCallback").run {
                isAccessible = true; get(record) as JavaAudioDeviceModule.AudioRecordErrorCallback
            }
            val capture = record.javaClass.getField("motoCaptureCallback").get(record) as AudioRecordDataCallback
            errors.onWebRtcAudioRecordError("unowned native read error")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
            val inject = AtomicBoolean(false)
            val failedFrame = CountDownLatch(1)
            record.javaClass.getField("motoCaptureCallback").set(record, AudioRecordDataCallback { format, channels, rate, frame ->
                if (inject.compareAndSet(true, false)) {
                    errors.onWebRtcAudioRecordError("injected native read failure")
                    for (index in 0 until frame.capacity()) frame.put(index, 32)
                    capture.onAudioDataRecorded(format, channels, rate, frame)
                    if ((0 until frame.capacity()).all { frame.get(it) == 0.toByte() }) failedFrame.countDown()
                } else capture.onAudioDataRecorded(format, channels, rate, frame)
            })
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            val connected = CountDownLatch(2)
            offerer = engine.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(it) },
                onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
            answerer = peer.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
            offerer.createOffer()
            assertTrue("Native peers did not connect: ${failures.peek()}", connected.await(15, TimeUnit.SECONDS))
            inject.set(true)
            assertTrue(failures.poll(5, TimeUnit.SECONDS)?.message.orEmpty().contains("capture:read"))
            assertTrue("Failed native producer did not silence JNI input", failedFrame.await(5, TimeUnit.SECONDS))
            errors.onWebRtcAudioRecordInitError("duplicate native failure")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
            val frame = ByteBuffer.allocateDirect(960)
            for (index in 0 until 960) frame.put(index, 32)
            capture.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, frame)
            assertTrue((0 until 960).all { frame.get(it) == 0.toByte() })
            engine.close()
            errors.onWebRtcAudioRecordError("late closed capture error")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
        } finally { engine.close(); peer.close() }
    }
}
