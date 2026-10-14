package com.kuma.motointercom

import android.os.Looper
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.PeerConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntercomManagerReadinessTest {
    @Test fun productionManagerRequiresCurrentRouteAndNativeRtpProofAndRejectsLateQueries() {
        val signals = signalingPair()
        val engine = EvidenceEngine()
        val route = EvidenceRoute()
        val audio = AudioSessionController(engine, route)
        val ready = mutableListOf<Boolean>()
        val manager = IntercomManager(audio, signals.first, WebRtcRole.ANSWERER,
            onIntercomDisconnected = { fail(it.message) }, onAudioReadyChanged = ready::add,
            isSessionCurrent = { true })
        fun answer(n: Long) {
            val callback = engine.session.queries.poll(1, TimeUnit.SECONDS)
            assertNotNull("Manager did not request media evidence", callback)
            callback!!(RiderMediaEvidence(RiderRtpCounters(setOf("in", "out"), n, n),
                connected = true, remoteTrack = true, audioIoEnabled = true, gateRevision = 1, nativeRevision = 1))
        }
        fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        try {
            manager.start()
            engine.session.callbacks.onConnectionStateChanged(PeerConnection.PeerConnectionState.CONNECTED)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A connected PeerConnection cannot prove audible media", ready.isEmpty())
            answer(1)
            tick(); answer(2)
            assertEquals(listOf(true), ready)

            tick() // The pending query captured route revision 1.
            route.value = AudioRouteEvidence(2, true)
            answer(3)
            assertEquals(listOf(true, false), ready)
            tick(); answer(4)
            tick(); answer(5)
            assertEquals(listOf(true, false, true), ready)

            tick()
            val late = engine.session.queries.poll(1, TimeUnit.SECONDS)!!
            manager.close()
            late(RiderMediaEvidence(RiderRtpCounters(setOf("in", "out"), 6, 6), true, true, true, 1, 1))
            assertEquals(listOf(true, false, true, false), ready)
            assertTrue(signals.first.isClosed)
        } finally { manager.close(); audio.close(); signals.second.close() }
    }

    private class EvidenceRoute : RiderAudioRoute {
        var value = AudioRouteEvidence(1, true)
        override fun evidence() = value
        override fun select(selection: AudioRouteSelection) = Unit
        override fun close() = Unit
    }
    private class EvidenceEngine : RiderMediaEngine {
        lateinit var session: EvidenceSession
        override fun updateAudioControls(controls: VersionedAudioControls) = Unit
        override fun openSession(callbacks: RiderMediaSessionCallbacks) = EvidenceSession(callbacks).also { session = it }
        override fun close() = Unit
    }
    private class EvidenceSession(val callbacks: RiderMediaSessionCallbacks) : RiderMediaSession {
        val queries = LinkedBlockingQueue<(RiderMediaEvidence?) -> Unit>()
        override fun queryEvidence(callback: (RiderMediaEvidence?) -> Unit) { queries.offer(callback) }
        override fun createOffer() = Unit
        override fun createAnswer(remoteSdpJson: String) = Unit
        override fun setRemoteAnswer(remoteSdpJson: String) = Unit
        override fun addRemoteIceCandidate(candidateJson: String) = Unit
        override fun close() = Unit
    }

    private fun signalingPair(): Pair<SignalingSessionV2, SignalingSessionV2> {
        val deviceA = "a0000000-0000-4000-8000-000000000001"
        val deviceB = "b0000000-0000-4000-8000-000000000002"
        val runtimeA = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        val runtimeB = RuntimeSessionId("10000000-0000-4000-8000-000000000002")
        val attempt = ConnectionAttempt(ConnectionAttemptId("20000000-0000-4000-8000-000000000001"), runtimeA,
            TargetLock(deviceB, runtimeB), ConnectionTrigger.USER, ChannelPlan.single(Transport.LAN), 10_000)
        val sockets = ServerSocket(0).use { server ->
            val incoming = CompletableFuture.supplyAsync { server.accept() }
            val outgoing = Socket("127.0.0.1", server.localPort)
            outgoing to incoming.get(2, TimeUnit.SECONDS)
        }
        val clock = MonotonicClock { MonotonicTimestamp(0) }
        val first = CompletableFuture.supplyAsync {
            SignalingSessionV2.establish(sockets.first, Transport.LAN, PhysicalSocketRole.OPENER, 0,
                deviceA, runtimeA, "Rider A", "Phone A", attempt, monotonicClock = clock)
        }
        val second = CompletableFuture.supplyAsync {
            SignalingSessionV2.establish(sockets.second, Transport.LAN, PhysicalSocketRole.ACCEPTOR, 0,
                deviceB, runtimeB, "Rider B", "Phone B", null, TargetLock(deviceA, runtimeA), clock)
        }
        return try { first.get(2, TimeUnit.SECONDS) to second.get(2, TimeUnit.SECONDS) }
        catch (failure: Throwable) { sockets.first.close(); sockets.second.close(); throw failure }
    }
}
