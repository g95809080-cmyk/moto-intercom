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
import java.util.concurrent.atomic.AtomicReference
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
        val firstRelease = NativeAudioRelease("media replacement first")
        val secondRelease = NativeAudioRelease("media replacement second")
        val first = firstRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) }, onDisposed = firstRelease::onDisposed))
        val second = secondRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) }, onDisposed = secondRelease::onDisposed))
        val blocked = CountDownLatch(1)
        val released = CountDownLatch(1)
        val oldErrorDelivered = CountDownLatch(1)
        val blockNext = AtomicBoolean(false)
        val freshFrames = AtomicReference(CountDownLatch(1))
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
                freshFrames.get().countDown()
            })
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            var connected = CountDownLatch(2)
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
            firstRelease.observeProducers(); secondRelease.observeProducers()
            blockNext.set(true)
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            offerer.close()
            released.countDown()
            assertTrue(oldErrorDelivered.await(5, TimeUnit.SECONDS))
            assertNull("Old producer stopped its online runtime", failures.poll(300, TimeUnit.MILLISECONDS))
            answerer.close()
            connected = CountDownLatch(2)
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
            assertTrue("Replacement native peers did not connect", connected.await(15, TimeUnit.SECONDS))
            freshFrames.set(CountDownLatch(6))
            assertTrue("Replacement native capture did not restart", freshFrames.get().await(5, TimeUnit.SECONDS))
            assertNull("Teardown permanently failed the reused engine", failures.poll())
            firstRelease.observeProducers(); secondRelease.observeProducers()
            offerer.close()
        } finally { released.countDown(); NativeAudioRelease.closeAll(firstRelease, secondRelease) }
    }

    @Test fun nativeReadFailureReportsOnceMutesFramesAndIgnoresErrorsAfterClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val engineRelease = NativeAudioRelease("capture failure")
        val peerRelease = NativeAudioRelease("capture failure peer")
        val engine = engineRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) }, onDisposed = engineRelease::onDisposed))
        val peer = peerRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) }, onDisposed = peerRelease::onDisposed))
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
            engineRelease.observeProducers(); peerRelease.observeProducers()
            inject.set(true)
            assertTrue(failures.poll(5, TimeUnit.SECONDS)?.message.orEmpty().contains("capture:read"))
            assertTrue("Failed native producer did not silence JNI input", failedFrame.await(5, TimeUnit.SECONDS))
            errors.onWebRtcAudioRecordInitError("duplicate native failure")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
            val frame = ByteBuffer.allocateDirect(960)
            for (index in 0 until 960) frame.put(index, 32)
            capture.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, frame)
            assertTrue((0 until 960).all { frame.get(it) == 0.toByte() })
            engineRelease.observeProducers(); peerRelease.observeProducers()
            engine.close()
            errors.onWebRtcAudioRecordError("late closed capture error")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
        } finally { NativeAudioRelease.closeAll(engineRelease, peerRelease) }
    }
}
