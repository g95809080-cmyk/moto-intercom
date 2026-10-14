package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.audio.AudioRecordDataCallback

@RunWith(AndroidJUnit4::class)
class VoxBackgroundInstrumentationTest {
    @Test fun strongerStationaryBackgroundClosesActualPcmWithoutStoppingCapture() {
        val context = permittedContext()
        val errors = LinkedBlockingQueue<Throwable>()
        val runtimeState = AtomicReference(VoxRuntimeState.IDLE)
        val first = RiderAudioEngine(context, onEngineError = { errors.offer(it) },
            onVoxStateChanged = { _, state -> runtimeState.set(state) })
        val second = RiderAudioEngine(context, onEngineError = { errors.offer(it) })
        val amplitude = AtomicInteger(34) // Synthetic RMS levels: 30 -> 70 -> 45. Not semantic road audio.
        val captured = AtomicInteger()
        val foreground = AtomicReference(CountDownLatch(6))
        val backgroundZeros = AtomicReference(CountDownLatch(30))
        val callbackTimes = LinkedBlockingQueue<Long>()
        val connected = CountDownLatch(2)
        try {
            val rtc = field(first, "rtc") as ExecutorService
            val recorder = rtc.submit<Any> {
                val adm = field(first, "audioDeviceModule")!!
                adm.javaClass.getDeclaredField("audioInput").apply { isAccessible = true }.get(adm)
            }.get(5, TimeUnit.SECONDS)
            val callbackField = recorder.javaClass.getField("motoCaptureCallback")
            val production = callbackField.get(recorder) as AudioRecordDataCallback
            callbackField.set(recorder, AudioRecordDataCallback { format, channels, rate, buffer ->
                val input = amplitude.get()
                for (index in 0 until buffer.capacity() / 2) {
                    val sample = if (index % 16 < 8) input else -input
                    buffer.put(index * 2, sample.toByte())
                    buffer.put(index * 2 + 1, (sample shr 8).toByte())
                }
                val start = System.nanoTime()
                production.onAudioDataRecorded(format, channels, rate, buffer)
                callbackTimes.offer(System.nanoTime() - start)
                captured.incrementAndGet()
                val silent = (0 until buffer.capacity()).all { buffer.get(it) == 0.toByte() }
                if (input == 184 && silent) backgroundZeros.get().countDown()
                if (input == 3_277 && !silent) foreground.get().countDown()
            })
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            offerer = first.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(it) },
                onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { errors.offer(it) }, isSessionCurrent = { true }))
            answerer = second.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { errors.offer(it) }, isSessionCurrent = { true }))
            offerer.createOffer()
            assertTrue("Native peers did not connect: $errors", connected.await(15, TimeUnit.SECONDS))
            awaitCondition { captured.get() >= 80 }
            amplitude.set(3_277)
            assertTrue("Foreground failed to open JNI input", foreground.get().await(3, TimeUnit.SECONDS))
            awaitCondition { runtimeState.get() == VoxRuntimeState.OPEN }
            // Keep a real foreground segment before returning to stronger background.
            val speechFrames = captured.get()
            awaitCondition { captured.get() >= speechFrames + 60 }
            val backgroundAt = SystemClock.elapsedRealtime()
            amplitude.set(184)
            awaitCondition { runtimeState.get() == VoxRuntimeState.LISTENING }
            val closedInMs = SystemClock.elapsedRealtime() - backgroundAt
            assertTrue("Stationary background did not release within its evidence + tail window", closedInMs < 2_000)
            backgroundZeros.set(CountDownLatch(30)) // Count fresh frames after observing LISTENING.
            val stillCapturing = captured.get()
            assertTrue("LISTENING did not clear actual nonzero JNI input", backgroundZeros.get().await(3, TimeUnit.SECONDS))
            assertTrue("VOX closing stopped the recorder", captured.get() > stillCapturing)
            foreground.set(CountDownLatch(6))
            val reopenedAt = SystemClock.elapsedRealtime()
            amplitude.set(3_277)
            assertTrue("New speech was not heard while closed", foreground.get().await(3, TimeUnit.SECONDS))
            val times = callbackTimes.toList().sorted()
            val p95 = times[(times.size * 0.95).toInt().coerceAtMost(times.lastIndex)] / 1_000.0
            android.util.Log.i("MotoVoxBackground", "native closeMs=$closedInMs reopenWith6FramesMs=${SystemClock.elapsedRealtime() - reopenedAt} captureFrames=${captured.get()} javaGateP95Us=$p95 (excludes native APM/codec)")
            assertTrue("Native engine failed: $errors", errors.isEmpty())
        } finally { closeAndAwait(first); closeAndAwait(second) }
    }

    @Test fun producerReplacedWhilePcmWaitsForControlsCannotChangeDetector() {
        // Real engine method, simulated owner handover; actual native producer is covered above.
        val engine = RiderAudioEngine(permittedContext())
        val oldPcm = tone()
        val freshPcm = tone()
        val errors = LinkedBlockingQueue<Throwable>()
        try {
            (field(engine, "rtc") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val owner = field(engine, "recordingOwner") as NativeAudioProducerOwner
            val gate = field(engine, "voxGate") as VoxGate
            val before = noiseFloor(gate)
            val handle = RiderAudioEngine::class.java.getDeclaredMethod("handleCapturedPcm",
                Int::class.javaPrimitiveType, ByteBuffer::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
            val revision = (field(engine, "audioIoGate") as AudioIoGate).revision()
            val old = Thread { runCatching { handle.invoke(engine, AudioFormat.ENCODING_PCM_16BIT, oldPcm, revision) }
                .onFailure { errors.offer(it) } }
            val fresh = Thread { runCatching { handle.invoke(engine, AudioFormat.ENCODING_PCM_16BIT, freshPcm, revision) }
                .onFailure { errors.offer(it) } }
            owner.start(old, { true }) {}
            synchronized(field(engine, "audioControlLock")!!) {
                old.start()
                awaitCondition { old.state == Thread.State.BLOCKED }
                // Same gate revision: a second owner check is needed after the controls wait.
                owner.start(fresh, { true }) {}
            }
            old.join(3_000)
            assertFalse("Old PCM did not return", old.isAlive)
            assertEquals("Revoked producer changed detector history", before, noiseFloor(gate), 0.0)
            assertTrue("Revoked producer leaked PCM", (0 until oldPcm.capacity()).all { oldPcm.get(it) == 0.toByte() })
            fresh.start(); fresh.join(3_000)
            assertFalse(fresh.isAlive)
            assertTrue("Current producer cannot advance detection", noiseFloor(gate) > before)
            assertTrue("Capture invocation failed: $errors", errors.isEmpty())
        } finally { closeAndAwait(engine) }
    }

    private fun permittedContext(): android.content.Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return instrumentation.targetContext.also {
            instrumentation.uiAutomation.grantRuntimePermission(it.packageName, Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5)
        assertTrue("Condition did not arrive", condition())
    }
    private fun tone() = ByteBuffer.allocateDirect(960).apply {
        for (index in 0 until capacity() / 2) { put(index * 2, 0); put(index * 2 + 1, 32) }
    }
    private fun noiseFloor(gate: VoxGate) = VoxGate::class.java.getDeclaredField("noiseFloor")
        .apply { isAccessible = true }.getDouble(gate)
    private fun field(engine: RiderAudioEngine, name: String) = RiderAudioEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(engine)
    private fun closeAndAwait(engine: RiderAudioEngine) {
        engine.close()
        assertTrue((field(engine, "rtc") as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
    }
}
