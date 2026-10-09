package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ExecutorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.AudioTrackSink
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class NativeAudioIoGateInstrumentationTest {
    @Test fun delayedSdkReadCannotClearOrStopReplacementRecorder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val peer = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val replacementFrame = AtomicReference<ByteBuffer>()
        val blockReplacement = AtomicBoolean(false)
        val delayed = object : AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2) {
            override fun read(buffer: ByteBuffer, bytes: Int): Int {
                readEntered.countDown()
                releaseRead.await(8, TimeUnit.SECONDS)
                repeat(bytes) { buffer.put(it, 0x33.toByte()) }
                return bytes
            }
        }
        try {
            engine.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            peer.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
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
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            awaitHealthyEvidence(offerer)
            val module = RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").run {
                isAccessible = true; get(engine) as JavaAudioDeviceModule
            }
            val recorder = module.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
            val oldThread = NativeCaptureDiagnostics.currentProducer(module, true)!!
            val ownedRecord = oldThread.javaClass.getDeclaredField("motoOwnedRecord").apply { isAccessible = true }
            val callback = recorder.javaClass.getField("motoCaptureCallback")
            val original = callback.get(recorder) as AudioRecordDataCallback
            callback.set(recorder, AudioRecordDataCallback { format, channels, rate, frame ->
                if (Thread.currentThread() !== oldThread && blockReplacement.compareAndSet(true, false)) {
                    repeat(frame.capacity()) { frame.put(it, 0x5a.toByte()) }
                    replacementFrame.set(frame)
                    replacementEntered.countDown()
                    releaseReplacement.await(5, TimeUnit.SECONDS)
                }
                original.onAudioDataRecorded(format, channels, rate, frame)
            })
            // Substitute only this old thread's read, leaving the adapter's actual recorder and new producer intact.
            ownedRecord.set(oldThread, delayed)
            assertTrue("Old SDK read was not held", readEntered.await(3, TimeUnit.SECONDS))
            engine.suspendAudio()
            blockReplacement.set(true)
            engine.resumeAudio()
            assertTrue("Replacement SDK input did not start", replacementEntered.await(5, TimeUnit.SECONDS))
            module.setMicrophoneMute(true)
            releaseRead.countDown()
            oldThread.join(1_000)
            assertFalse("Old read producer failed to retire", oldThread.isAlive)
            val frame = replacementFrame.get()!!
            assertTrue("Old SDK mute path cleared replacement buffer", (0 until frame.capacity()).all { frame.get(it) == 0x5a.toByte() })
            val replacement = recorder.javaClass.getDeclaredField("audioRecord").run { isAccessible = true; get(recorder) as AudioRecord }
            assertEquals("Old SDK tail stopped replacement input", AudioRecord.RECORDSTATE_RECORDING, replacement.recordingState)
            module.setMicrophoneMute(false)
            releaseReplacement.countDown()
            awaitHealthyEvidence(offerer)
            engine.suspendAudio(); engine.resumeAudio()
            awaitHealthyEvidence(offerer)
            assertNull("Old read damaged replacement media", failures.poll())
        } finally {
            releaseRead.countDown(); releaseReplacement.countDown()
            engine.close(); peer.close(); delayed.release()
        }
    }

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
        val tailBlockNext = AtomicBoolean(false)
        val tailReached = CountDownLatch(1)
        val enterTail = CountDownLatch(1)
        val tailReturned = CountDownLatch(1)
        try {
            engine.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            peer.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            injectTone(peer)
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
                if (tailBlockNext.compareAndSet(true, false)) {
                    tailReached.countDown()
                    enterTail.await(5, TimeUnit.SECONDS)
                    capture.onAudioDataRecorded(format, channels, rate, frame)
                    tailReturned.countDown()
                    return@AudioRecordDataCallback
                }
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
            val before = awaitHealthyEvidence(offerer)
            val output = offerer.javaClass.getDeclaredField("playout").run { isAccessible = true; get(offerer) as DecodedAudioPlayout }
            val firstOutput = output.snapshot() ?: error("No real Android output writes")
            tailBlockNext.set(true)
            assertTrue(tailReached.await(5, TimeUnit.SECONDS))
            RiderAudioEngine::class.java.getDeclaredField("lastAudioLevelAt").apply { isAccessible = true }.setLong(engine, 0)
            val registry = RiderAudioEngine::class.java.getDeclaredField("sessionLock").run { isAccessible = true; get(engine)!! }
            synchronized(registry) {
                enterTail.countDown()
                assertTrue("Production PCM tail waited on the lock held by native stop", tailReturned.await(500, TimeUnit.MILLISECONDS))
            }
            blockNext.set(true)
            assertTrue("No native PCM callback", blocked.await(5, TimeUnit.SECONDS))
            engine.suspendAudio()
            assertNull("Paused Android output still owns a device", output.snapshot())
            val io = RiderAudioEngine::class.java.getDeclaredField("ioEvidence").run {
                isAccessible = true; get(engine) as RiderAudioIoEvidence
            }
            assertFalse("Pause must immediately revoke capture/playout evidence", io.ready(io.revision(), SystemClock.elapsedRealtime()))
            engine.resumeAudio()
            release.countDown()
            assertTrue("An old native producer crossed pause/resume", rejectedOldFrame.await(5, TimeUnit.SECONDS))
            oldProducer.get().join(1_000)
            assertFalse("Stopped native producer did not exit after its delayed callback", oldProducer.get().isAlive)
            assertTrue("Resume did not restart real unmuted capture", freshFrames.await(5, TimeUnit.SECONDS))
            assertNotSame(oldProducer.get(), newProducer.get())
            val freshDeadline = SystemClock.elapsedRealtime() + 2_000
            while (!io.ready(io.revision(), SystemClock.elapsedRealtime()) && SystemClock.elapsedRealtime() < freshDeadline) {
                SystemClock.sleep(10)
            }
            assertTrue("Requested enable alone is insufficient: native I/O must be running", io.ready(io.revision(), SystemClock.elapsedRealtime()))
            val resumedOutput = output.snapshot() ?: error("Resumed output did not write")
            assertNotSame("Resume reused the stopped output thread", firstOutput.thread, resumedOutput.thread)
            assertNotEquals("Resume reused the released Android output", firstOutput.sessionId, resumedOutput.sessionId)
            val after = awaitHealthyEvidence(offerer)
            assertTrue(after.counters.sent > before.counters.sent && after.counters.received > before.counters.received)
            val toneDeadline = SystemClock.elapsedRealtime() + 3_000
            while (output.snapshot()?.nonzeroWrittenBytes == 0L && SystemClock.elapsedRealtime() < toneDeadline) SystemClock.sleep(10)
            assertTrue("Decoded nonzero PCM never reached Android output", output.snapshot()!!.nonzeroWrittenBytes > 0)
            repeat(2) {
                val previous = output.snapshot()!!
                engine.suspendAudio(); assertNull(output.snapshot()); engine.resumeAudio()
                awaitHealthyEvidence(offerer)
                assertNotSame(previous.thread, output.snapshot()!!.thread)
            }
            assertNull("Route pause/resume was reported as a fatal device error", failures.poll())
        } finally { enterTail.countDown(); release.countDown(); engine.close(); peer.close() }
    }

    @Test fun publicAudioSinkStillReceivesDecodedFramesAfterSdkPlayoutStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val peer = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        val decoded = AtomicReference(CountDownLatch(3))
        val installed = AtomicBoolean(false)
        val sink = AudioTrackSink { data, bits, rate, channels, frames, _ ->
            if (bits == 16 && rate > 0 && channels > 0 && frames > 0 && data.remaining() > 0) decoded.get().countDown()
        }
        try {
            engine.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            peer.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            val connected = CountDownLatch(2)
            offerer = engine.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(it) },
                onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onRemoteAudioTrack = { if (installed.compareAndSet(false, true)) it.addSink(sink) },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
            answerer = peer.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
            offerer.createOffer()
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            assertTrue("No decoded PCM from public SDK sink", decoded.get().await(3, TimeUnit.SECONDS))
            engine.suspendAudio()
            val rtc = RiderAudioEngine::class.java.getDeclaredField("rtc").run { isAccessible = true; get(engine) as ExecutorService }
            rtc.submit {}.get(3, TimeUnit.SECONDS)
            decoded.set(CountDownLatch(3))
            assertTrue("SDK playout stop also stopped sink decoding", decoded.get().await(3, TimeUnit.SECONDS))
            assertNull(failures.poll())
        } finally { engine.close(); peer.close() }
    }

    private fun awaitHealthyEvidence(session: RiderMediaSession): RiderMediaEvidence {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = LinkedBlockingQueue<RiderMediaEvidence>()
            session.queryEvidence { if (it != null) result.offer(it) }
            val evidence = result.poll(500, TimeUnit.MILLISECONDS)
            if (evidence?.audioIoEnabled == true && evidence.counters.sent > 0 && evidence.counters.received > 0) return evidence
            SystemClock.sleep(20)
        }
        error("Current native record/write and bidirectional RTP evidence did not recover")
    }

    private fun injectTone(engine: RiderAudioEngine) {
        val field = RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var module: Any? = null
        while (module == null && SystemClock.elapsedRealtime() < deadline) {
            module = field.get(engine); if (module == null) SystemClock.sleep(10)
        }
        val record = module!!.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
        val callback = record.javaClass.getField("motoCaptureCallback")
        val capture = callback.get(record) as AudioRecordDataCallback
        callback.set(record, AudioRecordDataCallback { format, channels, rate, frame ->
            for (index in 0 until frame.capacity() / 2) {
                val sample = if (index % 16 < 8) 8192 else -8192
                frame.put(index * 2, sample.toByte()); frame.put(index * 2 + 1, (sample shr 8).toByte())
            }
            capture.onAudioDataRecorded(format, channels, rate, frame)
        })
    }
}
