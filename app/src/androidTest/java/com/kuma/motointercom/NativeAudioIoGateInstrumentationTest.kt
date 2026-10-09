package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.audio.AudioRecordDataCallback

@RunWith(AndroidJUnit4::class)
class NativeAudioIoGateInstrumentationTest {
    @Test fun immediateResumeCannotRevivePausedProducerAndRealCaptureRestarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val peer = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val blockNext = AtomicBoolean(false)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejectedOldFrame = CountDownLatch(1)
        val freshFrames = CountDownLatch(6)
        val oldProducer = AtomicReference<Thread>()
        val newProducer = AtomicReference<Thread>()
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
            val callbackField = record.javaClass.getField("motoCaptureCallback")
            val capture = callbackField.get(record) as AudioRecordDataCallback
            callbackField.set(record, AudioRecordDataCallback { format, channels, rate, frame ->
                val pausedFrame = blockNext.compareAndSet(true, false)
                if (pausedFrame) {
                    oldProducer.set(Thread.currentThread())
                    blocked.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                for (index in 0 until frame.capacity()) frame.put(index, 32)
                capture.onAudioDataRecorded(format, channels, rate, frame)
                if (pausedFrame && (0 until frame.capacity()).all { frame.get(it) == 0.toByte() }) {
                    rejectedOldFrame.countDown()
                }
                if (oldProducer.get() != null && Thread.currentThread() !== oldProducer.get() &&
                    (0 until frame.capacity()).any { frame.get(it) != 0.toByte() }) {
                    newProducer.set(Thread.currentThread())
                    freshFrames.countDown()
                }
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
            blockNext.set(true)
            assertTrue("No native PCM callback", blocked.await(5, TimeUnit.SECONDS))
            engine.suspendAudio()
            val io = RiderAudioEngine::class.java.getDeclaredField("ioEvidence").run {
                isAccessible = true; get(engine) as RiderAudioIoEvidence
            }
            assertFalse("Pause must immediately revoke capture/playout evidence", io.ready(io.revision(), SystemClock.elapsedRealtime()))
            engine.resumeAudio()
            release.countDown()
            assertTrue("An old native producer crossed pause/resume", rejectedOldFrame.await(5, TimeUnit.SECONDS))
            assertTrue("Resume did not restart real unmuted capture", freshFrames.await(5, TimeUnit.SECONDS))
            assertNotSame(oldProducer.get(), newProducer.get())
            val freshDeadline = SystemClock.elapsedRealtime() + 2_000
            while (!io.ready(io.revision(), SystemClock.elapsedRealtime()) && SystemClock.elapsedRealtime() < freshDeadline) {
                SystemClock.sleep(10)
            }
            assertTrue("Requested enable alone is insufficient: native I/O must be running", io.ready(io.revision(), SystemClock.elapsedRealtime()))
            assertNull("Route pause/resume was reported as a fatal device error", failures.poll())
        } finally { release.countDown(); engine.close(); peer.close() }
    }
}
