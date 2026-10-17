package com.kuma.motointercom

import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.AudioTrackSink
import org.webrtc.audio.AudioRecordDataCallback
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** One endpoint per process/device. No microphone is needed: only this test replaces capture PCM. */
@RunWith(AndroidJUnit4::class)
class RelayMediaProbeTest {
    @Test fun twoDeviceNativeRelay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configName = InstrumentationRegistry.getArguments().getString("relayConfig")
        assumeTrue("Requires explicit private relayConfig; see tools/relay/README.md", configName != null)
        require(configName!!.matches(Regex("[A-Za-z0-9_-]+\\.json")))
        val context = instrumentation.targetContext
        val output = File(context.filesDir, "relay-evidence.json")
        output.writeText(JSONObject().put("pass", false).put("stage", "starting").toString())
        val config = JSONObject(File(context.filesDir, configName).readText())
        val deviceId = config.getString("deviceId")
        val peerId = config.getString("peerId")
        require(deviceId != peerId)
        val runtimeId = UUID.randomUUID().toString()
        val pairId = config.getString("pairId")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = if (config.optBoolean("requireCellular", false)) {
            connectivity.allNetworks.firstOrNull { network -> connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true } ?: error("No cellular Network available")
        } else connectivity.activeNetwork ?: error("No active Network")
        val client = RelaySignalingClient(config.getString("endpoint"), network,
            RelaySignalingClient.trustedCa(config.getString("caPem")))
        val owner = LegacyRuntimeOwnership.acquire() ?: error("Production runtime already owns audio")
        val current = AtomicBoolean(true)
        val errors = LinkedBlockingQueue<Throwable>()
        val inbox = LinkedBlockingQueue<JSONObject>(128)
        val publisher = Executors.newSingleThreadExecutor()
        val poller = Executors.newSingleThreadExecutor()
        var controller: AudioSessionController? = null
        var engine: RiderAudioEngine? = null
        var session: RiderMediaSession? = null
        var sessionPath: String? = null
        var leaseToken: String? = null
        var callId: String? = null
        var callExpiry = 0L
        var signalSequence = 0
        val tone = DecodedTone(config.getDouble("receiveHz"), config.getDouble("sendHz"))
        try {
            for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE) +
                if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
            val grant = client.request("POST", "/v1/sessions", config.getString("deviceToken"), JSONObject()
                .put("deviceId", deviceId).put("runtimeId", runtimeId).put("pairId", pairId))
            check(grant.getString("deviceId") == deviceId && grant.getString("runtimeId") == runtimeId &&
                grant.getString("pairId") == pairId && grant.getString("peerId") == peerId)
            check(grant.getLong("expiresAt") > System.currentTimeMillis() + 60_000)
            sessionPath = "/v1/sessions/${UUID.fromString(grant.getString("sessionId"))}"
            leaseToken = grant.getString("leaseToken")
            val path = sessionPath
            val token = leaseToken
            val servers = grant.getJSONArray("iceServers")
            val policy = MediaIcePolicy.Relay((0 until servers.length()).map { i ->
                val server = servers.getJSONObject(i)
                val urls = server.getJSONArray("urls")
                TurnCredentials((0 until urls.length()).map(urls::getString), server.getString("username"),
                    server.getString("credential"), server.getLong("expiresAt"))
            })
            policy.rtcConfiguration() // Reject invalid credentials before creating an ADM.
            poller.execute {
                var cursor = 0L
                try {
                    while (current.get()) {
                        val response = client.request("GET", "$path/events?after=$cursor", token)
                        val events = response.getJSONArray("events")
                        for (i in 0 until events.length()) {
                            val event = events.getJSONObject(i)
                            check(event.getLong("sequence") == cursor + 1) { "Signaling sequence gap" }
                            check(inbox.offer(event)) { "Probe inbox full" }
                            cursor = event.getLong("sequence")
                        }
                        if (response.getBoolean("closed")) break
                    }
                } catch (error: Throwable) { if (current.get()) errors.offer(error) }
            }
            fun publish(kind: String, payload: String) {
                if (!current.get()) return
                publisher.execute {
                    try {
                        check(current.get() && System.currentTimeMillis() < callExpiry) { "Call no longer current" }
                        client.request("POST", "$path/signals", token, JSONObject().put("callId", callId)
                            .put("sequence", ++signalSequence).put("kind", kind).put("payload", payload))
                    } catch (error: Throwable) { if (current.get()) errors.offer(error) }
                }
            }
            val readyDeadline = SystemClock.elapsedRealtime() + 45_000
            var peerRuntime: String? = null
            while (session == null && SystemClock.elapsedRealtime() < readyDeadline) {
                errors.poll()?.let { throw it }
                val event = inbox.poll(100, TimeUnit.MILLISECONDS) ?: continue
                check(event.getString("type") == "ready") { "Call closed before peer authorization" }
                check(event.getJSONObject("self").getString("deviceId") == deviceId &&
                    event.getJSONObject("self").getString("runtimeId") == runtimeId &&
                    event.getJSONObject("peer").getString("deviceId") == peerId)
                peerRuntime = event.getJSONObject("peer").getString("runtimeId")
                UUID.fromString(peerRuntime)
                callId = UUID.fromString(event.getString("callId")).toString()
                callExpiry = event.getLong("expiresAt")
                check(callExpiry <= grant.getLong("expiresAt") && callExpiry > System.currentTimeMillis() + 45_000)
                val role = event.getString("role")
                check(role == grant.getString("role") && role in listOf("offerer", "answerer"))
                controller = AudioSessionController.start(context, {}, {}, {}, errors::offer,
                    isRuntimeCurrent = { current.get() && System.currentTimeMillis() < callExpiry },
                    initialAudioControls = VersionedAudioControls(0, AudioControlSettings(voxEnabled = false)),
                    initialAudioRoute = AudioRouteSelection.SPEAKER)
                engine = field(controller, "engine") as RiderAudioEngine
                installTone(engine, config.getDouble("sendHz"))
                check(current.get() && System.currentTimeMillis() < callExpiry)
                session = controller.openMediaSession(RiderMediaSessionCallbacks(
                    onLocalSdpGenerated = { payload -> publish(JSONObject(payload).getString("type"), payload) },
                    onLocalIceCandidateGenerated = { publish("ice", it) },
                    onRemoteAudioTrack = { it.addSink(tone.sink) },
                    onError = errors::offer,
                    isSessionCurrent = { current.get() && System.currentTimeMillis() < callExpiry }
                ), policy)
                if (role == "offerer") session.createOffer()
            }
            val active = session ?: error("Timed out waiting for authorized peer")
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var baseline: RiderMediaEvidence? = null
            var lastMedia: RiderMediaEvidence? = null
            var transport: RelayTransportEvidence? = null
            var verified = false
            while (SystemClock.elapsedRealtime() < deadline) {
                errors.poll()?.let { throw it }
                check(System.currentTimeMillis() < callExpiry) { "Call expired" }
                val event = inbox.poll(200, TimeUnit.MILLISECONDS)
                if (event != null) {
                    check(event.getString("callId") == callId) { "Stale call event" }
                    if (event.getString("type") == "closed") { check(verified) { "Peer closed before media verification" }; break }
                    check(event.getString("type") == "signal" && event.getJSONObject("from").getString("deviceId") == peerId &&
                        event.getJSONObject("from").getString("runtimeId") == peerRuntime) { "Unexpected sender" }
                    val payload = event.getString("payload")
                    when (event.getString("kind")) {
                        "offer" -> { check(grant.getString("role") == "answerer"); active.createAnswer(payload) }
                        "answer" -> { check(grant.getString("role") == "offerer"); active.setRemoteAnswer(payload) }
                        "ice" -> active.addRemoteIceCandidate(payload)
                        else -> error("Unexpected signal kind")
                    }
                }
                val media = query<RiderMediaEvidence> { active.queryEvidence(it) }
                if (media != null) {
                    if (baseline == null) baseline = media
                    lastMedia = media
                }
                transport = query<RelayTransportEvidence> { active.queryTransportEvidence(it) } ?: transport
                val before = baseline
                verified = media != null && before != null && media.connected && media.remoteTrack && media.audioIoEnabled &&
                    media.counters.streamIds == before.counters.streamIds && media.counters.sent > before.counters.sent + 1000 &&
                    media.counters.received > before.counters.received + 1000 && transport?.state == "succeeded" &&
                    transport.localType == "relay" && transport.remoteType == "relay" &&
                    SystemClock.elapsedRealtime() - transport.sampledAtElapsedMs in 0..2000 && tone.validFrames() >= 100
            }
            check(verified) { "Missing selected relay pair, RTP growth, active audio I/O or decoded peer tone" }
            output.writeText(JSONObject().put("pass", true).put("deviceId", deviceId).put("callId", callId)
                .put("runtimeId", runtimeId).put("signalingNetwork", network.toString())
                .put("cellularRequested", config.optBoolean("requireCellular", false))
                .put("transport", JSONObject().put("sessionId", transport!!.sessionId).put("pairId", transport.pairId)
                    .put("state", transport.state).put("localType", transport.localType).put("remoteType", transport.remoteType)
                    .put("protocol", transport.protocol).put("networkType", transport.networkType)
                    .put("rttSeconds", transport.roundTripTimeSeconds).put("bytesSent", transport.bytesSent).put("bytesReceived", transport.bytesReceived))
                .put("rtpSent", lastMedia!!.counters.sent).put("rtpReceived", lastMedia.counters.received)
                .put("rtpSentGrowth", lastMedia.counters.sent - baseline!!.counters.sent)
                .put("rtpReceivedGrowth", lastMedia.counters.received - baseline.counters.received)
                .put("decodedPeerToneFrames", tone.validFrames()).put("expectedPeerHz", config.getDouble("receiveHz"))
                .put("scope", "developer native relay probe; not public cellular or latency acceptance").toString(2))
        } catch (error: Throwable) {
            output.writeText(JSONObject().put("pass", false).put("deviceId", deviceId).put("errorClass", error.javaClass.simpleName).toString())
            throw error
        } finally {
            current.set(false)
            publisher.shutdownNow()
            if (sessionPath != null && leaseToken != null) runCatching { client.request("DELETE", sessionPath, leaseToken) }
            poller.shutdownNow()
            controller?.close()
            // close() enqueues native disposal: release the process owner only after it finishes.
            val rtc = engine?.let { field(it, "rtc") as ExecutorService }
            val disposed = rtc == null || rtc.awaitTermination(10, TimeUnit.SECONDS)
            if (disposed) LegacyRuntimeOwnership.release(owner)
            check(disposed) { "Native disposal timed out; ownership deliberately retained" }
        }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).run {
        isAccessible = true; get(instance)
    }

    private fun installTone(engine: RiderAudioEngine, hz: Double) {
        require(hz in 200.0..2000.0)
        val deadline = SystemClock.elapsedRealtime() + 5000
        var module: Any? = null
        while (module == null && SystemClock.elapsedRealtime() < deadline) { module = field(engine, "audioDeviceModule"); SystemClock.sleep(10) }
        val recorder = field(checkNotNull(module), "audioInput")!!
        val callback = recorder.javaClass.getField("motoCaptureCallback")
        val original = callback.get(recorder) as AudioRecordDataCallback
        var phase = 0.0
        callback.set(recorder, AudioRecordDataCallback { format, channels, rate, buffer ->
            require(channels == 1 && rate > 0)
            for (i in 0 until buffer.capacity() / 2) {
                val sample = if (phase < 0.5) 8192 else -8192
                buffer.put(i * 2, sample.toByte()); buffer.put(i * 2 + 1, (sample shr 8).toByte())
                phase = (phase + hz / rate) % 1.0
            }
            original.onAudioDataRecorded(format, channels, rate, buffer)
        })
    }

    private fun <T> query(start: (((T?) -> Unit)) -> Unit): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        start { result = it; latch.countDown() }
        check(latch.await(3, TimeUnit.SECONDS)) { "Native evidence query timed out" }
        return result
    }

    private class DecodedTone(private val expectedHz: Double, private val ownHz: Double) {
        private var accepted = 0L
        @Synchronized fun validFrames() = accepted
        val sink = AudioTrackSink { data, bits, rate, channels, frames, _ ->
            if (bits == 16 && rate > 0 && channels in 1..2 && frames > 0 && data.remaining() >= frames * channels * 2) {
                val pcm = data.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val samples = DoubleArray(frames) { pcm.getShort(pcm.position() + it * channels * 2).toDouble() }
                val energy = samples.sumOf { it * it }
                fun power(hz: Double): Double {
                    val coefficient = 2 * cos(2 * PI * hz / rate)
                    var previous = 0.0; var older = 0.0
                    for (sample in samples) { val next = sample + coefficient * previous - older; older = previous; previous = next }
                    return previous * previous + older * older - coefficient * previous * older
                }
                val expected = power(expectedHz)
                if (sqrt(energy / frames) >= 5 && expected > energy * frames * 0.05 && expected > power(ownHz) * 2) {
                    synchronized(this) { accepted++ }
                }
            }
        }
    }
}
