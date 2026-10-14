package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioRecordDataCallback

/** Same deterministic input -> real JNI encode/transport/decode. Synthetic, not road listening. */
@RunWith(AndroidJUnit4::class)
class NativeNoiseGainInstrumentationTest {
    @Test fun compareNativeNoiseSuppressionAndFixedDigitalGainOnSameInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val results = JSONArray()
        for (ns in listOf(false, true)) for (agc in listOf(false, true)) {
            val errors = LinkedBlockingQueue<Throwable>()
            val controls = VersionedAudioControls(0, AudioControlSettings(voxEnabled = false))
            val sender = RiderAudioEngine(context, onEngineError = { errors.offer(it) }, initialAudioControls = controls)
            val receiver = RiderAudioEngine(context, onEngineError = { errors.offer(it) }, initialAudioControls = controls)
            val firstCapture = CountDownLatch(1)
            val releaseCapture = CountDownLatch(1)
            val sinkReady = CountDownLatch(1)
            val captured = AtomicInteger()
            val callbackTimes = LinkedBlockingQueue<Long>()
            val measured = DecodedMeasurements()
            val wave = DeterministicInput()
            val inputFormat = AtomicReference<String>()
            val inputCrc = CRC32()
            val gatedCrc = CRC32()
            val warmupSamples = AtomicInteger()
            val offerDelayMs = if (ns && agc) 500L else 0L
            try {
                val rtc = field(sender, "rtc") as ExecutorService
                rtc.submit {
                    assertNull("Only replace test source before native media exists", field(sender, "peerConnection"))
                    val factory = field(sender, "factory") as PeerConnectionFactory
                    val previousTrack = field(sender, "localAudioTrack") as AudioTrack
                    val previousSource = field(sender, "audioSource") as AudioSource
                    val constraints = MediaConstraints().apply {
                        optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                        optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", ns.toString()))
                        optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", agc.toString()))
                    }
                    val source = factory.createAudioSource(constraints)
                    val track = factory.createAudioTrack("rider_audio_track", source).apply { setEnabled(true) }
                    setField(sender, "audioSource", source)
                    setField(sender, "localAudioTrack", track)
                    previousTrack.dispose(); previousSource.dispose()
                    val module = field(sender, "audioDeviceModule")!!
                    val recorder = module.javaClass.getDeclaredField("audioInput").apply { isAccessible = true }.get(module)
                    val callbackField = recorder.javaClass.getField("motoCaptureCallback")
                    val production = callbackField.get(recorder) as AudioRecordDataCallback
                    callbackField.set(recorder, AudioRecordDataCallback { format, channels, rate, buffer ->
                        check(format == AudioFormat.ENCODING_PCM_16BIT && rate > 0 && channels > 0)
                        // ICE/DTLS and sink registration must complete before ANY PCM reaches APM.
                        // The release in finally also prevents close() from joining a blocked recorder.
                        if (captured.get() == 0) {
                            firstCapture.countDown()
                            check(releaseCapture.await(25, TimeUnit.SECONDS))
                        }
                        val frameNumber = captured.get()
                        val mode = when { frameNumber < 400 -> 0; frameNumber < 800 -> 1; else -> 2 }
                        val actualFormat = "$format/$rate/$channels/${buffer.capacity()}"
                        inputFormat.compareAndSet(null, actualFormat)
                        check(inputFormat.get() == actualFormat)
                        wave.begin(mode)
                        for (frame in 0 until buffer.capacity() / (2 * channels)) {
                            val sample = wave.next(rate, mode == 1).toInt().coerceIn(-32_767, 32_767)
                            for (channel in 0 until channels) {
                                val index = (frame * channels + channel) * 2
                                buffer.put(index, sample.toByte()); buffer.put(index + 1, (sample shr 8).toByte())
                            }
                        }
                        if (frameNumber < 200) {
                            for (index in 0 until buffer.capacity()) inputCrc.update(buffer.get(index).toInt() and 0xff)
                            warmupSamples.addAndGet(buffer.capacity() / 2)
                        }
                        val start = System.nanoTime()
                        production.onAudioDataRecorded(format, channels, rate, buffer)
                        callbackTimes.offer(System.nanoTime() - start)
                        if (frameNumber < 200) {
                            for (index in 0 until buffer.capacity()) gatedCrc.update(buffer.get(index).toInt() and 0xff)
                        }
                        captured.incrementAndGet()
                    })
                }.get(5, TimeUnit.SECONDS)

                lateinit var offerer: RiderMediaSession
                lateinit var answerer: RiderMediaSession
                val connected = CountDownLatch(2)
                offerer = sender.openSession(RiderMediaSessionCallbacks(
                    onLocalSdpGenerated = { sdp ->
                        Handler(Looper.getMainLooper()).postDelayed({ answerer.createAnswer(sdp) }, offerDelayMs)
                    },
                    onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                    onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                    onError = { errors.offer(it) }, isSessionCurrent = { true }))
                answerer = receiver.openSession(RiderMediaSessionCallbacks(
                    onLocalSdpGenerated = { offerer.setRemoteAnswer(it) },
                    onLocalIceCandidateGenerated = { offerer.addRemoteIceCandidate(it) },
                    onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                    onRemoteAudioTrack = { it.addSink(measured.sink); sinkReady.countDown() },
                    onError = { errors.offer(it) }, isSessionCurrent = { true }))
                offerer.createOffer()
                assertTrue("Actual peers did not connect: $errors", connected.await(15, TimeUnit.SECONDS))
                assertTrue("Actual receiver sink absent", sinkReady.await(5, TimeUnit.SECONDS))
                assertTrue("Actual recorder never reached test barrier", firstCapture.await(5, TimeUnit.SECONDS))
                assertEquals("Connection delay must not change APM history", 0, captured.get())
                releaseCapture.countDown()
                awaitCaptured(captured, 200)
                assertEquals("Bypass must preserve actual warmup input", inputCrc.value, gatedCrc.value)
                val noiseStart = captured.get()
                val noise = measured.collect(80)
                val noiseEnd = captured.get()
                assertTrue("Noise window crossed into speech", noiseEnd < 400)
                awaitCaptured(captured, 480)
                val voiceStart = captured.get()
                val voiceMix = measured.collect(80)
                val voiceEnd = captured.get()
                assertTrue("Speech window crossed into returning noise", voiceEnd < 800)
                awaitCaptured(captured, 920)
                val returningStart = captured.get()
                val returningNoise = measured.collect(80)
                val returningEnd = captured.get()
                assertTrue("Returning-noise window exceeded stable segment", returningEnd < 1_200)
                assertTrue("Decoded PCM must include voice-like input", voiceMix.rms > 5.0)
                assertTrue("Measurement needs actual decode", noise.samples > 0 && returningNoise.samples > 0)
                assertTrue("Native engine failed: $errors", errors.isEmpty())
                val times = callbackTimes.toList().sorted()
                results.put(JSONObject().put("ns", ns).put("agc", agc)
                    .put("noiseRms", noise.rms).put("voiceMixRms", voiceMix.rms)
                    .put("returningNoiseRms", returningNoise.rms)
                    .put("noiseSamples", noise.samples).put("voiceSamples", voiceMix.samples)
                    .put("decodedRate", voiceMix.rate).put("decodedChannels", voiceMix.channels)
                    .put("inputFormat", inputFormat.get()).put("warmupFrames", 200)
                    .put("warmupSamples", warmupSamples.get()).put("warmupInputCrc32", inputCrc.value)
                    .put("warmupGatedCrc32", gatedCrc.value).put("offerDelayMs", offerDelayMs)
                    .put("noiseSourceFrames", "$noiseStart..$noiseEnd")
                    .put("voiceSourceFrames", "$voiceStart..$voiceEnd")
                    .put("returningNoiseSourceFrames", "$returningStart..$returningEnd")
                    .put("captureFrames", captured.get())
                    .put("productionCallbackP95Us", times[(times.size * 0.95).toInt().coerceAtMost(times.lastIndex)] / 1_000.0))
            } finally {
                releaseCapture.countDown()
                sender.close(); receiver.close()
                assertTrue((field(sender, "rtc") as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
                assertTrue((field(receiver, "rtc") as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
            }
        }
        val first = results.getJSONObject(0)
        for (index in 1 until results.length()) {
            val next = results.getJSONObject(index)
            for (key in listOf("inputFormat", "warmupSamples", "warmupInputCrc32", "warmupGatedCrc32")) {
                assertEquals("A/B input differs for $key", first.get(key), next.get(key))
            }
        }
        val report = JSONObject().put("scope", "synthetic same-input native capture/encode/decode; not road intelligibility")
            .put("timingScope", "productionCallbackP95Us measures Java gate only; excludes native APM/codec and end-to-end latency")
            .put("alignment", "source windows remain in stable segments; receiver frames are not individually aligned to source frames")
            .put("cases", results)
        java.io.File(context.cacheDir, "native-noise-gain-ab.json").writeText(report.toString(2))
        android.util.Log.i("MotoNativeNoise", report.toString())
        assertEquals(4, results.length())
    }

    private fun awaitCaptured(count: AtomicInteger, target: Int) {
        val until = SystemClock.elapsedRealtime() + 8_000L
        while (count.get() < target && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10)
        assertTrue("Current capture did not continue", count.get() >= target)
    }

    private class DeterministicInput {
        private val random = Random(92L)
        private var sample = 0L
        private var phase = -1
        fun begin(value: Int) {
            if (phase != value) { phase = value; sample = 0; random.setSeed(92L) }
        }
        fun next(rate: Int, voice: Boolean): Double {
            val t = sample++.toDouble() / rate
            val background = 1_500.0 * sin(2 * PI * 90 * t) + (random.nextDouble() * 2 - 1) * 600
            if (!voice) return background
            val envelope = 0.35 + 0.65 * kotlin.math.abs(sin(2 * PI * 3.2 * t))
            return background + envelope * (1_800 * sin(2 * PI * 220 * t) +
                900 * sin(2 * PI * 440 * t) + 650 * sin(2 * PI * 880 * t) + 450 * sin(2 * PI * 1_320 * t))
        }
    }

    private data class Power(val squares: Double, val samples: Long, val rate: Int, val channels: Int) {
        val rms get() = sqrt(squares / samples)
    }
    private class DecodedMeasurements {
        private val frames = LinkedBlockingQueue<Power>()
        val sink = AudioTrackSink { data, bits, rate, channels, _, _ ->
            if (bits == 16 && data.remaining() >= 2 && rate > 0 && channels > 0) {
                val copy = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                var power = 0.0
                var n = 0L
                while (copy.remaining() >= 2) { val v = copy.short.toDouble(); power += v * v; n++ }
                frames.offer(Power(power, n, rate, channels))
            }
        }
        fun collect(count: Int): Power {
            frames.clear()
            var power = 0.0
            var samples = 0L
            var latest: Power? = null
            repeat(count) {
                val next = frames.poll(2, TimeUnit.SECONDS) ?: error("Actual remote decoded frames absent")
                power += next.squares; samples += next.samples; latest = next
            }
            return Power(power, samples, requireNotNull(latest).rate, requireNotNull(latest).channels)
        }
    }
    private fun field(engine: RiderAudioEngine, name: String) = RiderAudioEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(engine)
    private fun setField(engine: RiderAudioEngine, name: String, value: Any) = RiderAudioEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(engine, value)
}
