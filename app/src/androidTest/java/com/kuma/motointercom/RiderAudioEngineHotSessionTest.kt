package com.kuma.motointercom

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RiderAudioEngineHotSessionTest {
    @Test
    fun suspendAndResumeToggleAudioGateWithoutClosingHotSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO
            )
        }

        val errors = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(
            context = context,
            onEngineError = errors::offer,
            isRuntimeCurrent = { true }
        )
        try {
            val sdp = CountDownLatch(1)
            val session = engine.openSession(callbacks(sdp, errors))
            session.createOffer()
            assertTrue("SDP was not generated", sdp.await(10, TimeUnit.SECONDS))
            assertFalse(audioSuspended(engine))
            val hotPeer = field(session, "peerConnection")
            assertTrue(hotPeer != null)

            engine.suspendAudio()
            assertTrue("suspendAudio did not close the audio gate", audioSuspended(engine))
            assertSame("suspendAudio must keep the hot PeerConnection", hotPeer, field(session, "peerConnection"))

            engine.resumeAudio()
            assertFalse("resumeAudio did not reopen the audio gate", audioSuspended(engine))
            assertSame("resumeAudio must keep the hot PeerConnection", hotPeer, field(session, "peerConnection"))
            assertNull("unexpected media error", errors.poll(2, TimeUnit.SECONDS))

            session.close()
        } finally {
            engine.close()
        }
    }

    @Test
    fun sequentialPeerConnectionsReuseHotAudioPlatformResources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO
            )
        }

        val errors = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(
            context = context,
            onEngineError = errors::offer,
            isRuntimeCurrent = { true }
        )
        try {
            val firstSdp = CountDownLatch(1)
            val first = engine.openSession(callbacks(firstSdp, errors))
            first.createOffer()
            assertTrue("first SDP was not generated", firstSdp.await(10, TimeUnit.SECONDS))

            val firstPlatform = platformResources(engine)
            val firstPeer = field(first, "peerConnection")
            assertTrue(firstPlatform.all { it != null })
            assertTrue(firstPeer != null)

            assertThrows(IllegalStateException::class.java) {
                engine.openSession(callbacks(CountDownLatch(1), errors))
            }

            first.close()

            val secondSdp = CountDownLatch(1)
            val second = engine.openSession(callbacks(secondSdp, errors))
            second.createOffer()
            assertTrue("second SDP was not generated", secondSdp.await(10, TimeUnit.SECONDS))

            val secondPlatform = platformResources(engine)
            val secondPeer = field(second, "peerConnection")
            firstPlatform.zip(secondPlatform).forEach { (before, after) ->
                assertSame(before, after)
            }
            assertNotSame(firstPeer, secondPeer)
            assertNull("unexpected media error", errors.poll())
            assertThrows(IllegalStateException::class.java) {
                engine.openSession(callbacks(CountDownLatch(1), errors))
            }

            second.close()
            assertTrue("second PeerConnection was not disposed", awaitPeerClosed(second))
        } finally {
            engine.close()
        }
    }

    @Test
    fun threeGroupPeersShareOneNativePlatformAndReplacementPreservesTheOtherPeers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val errors = LinkedBlockingQueue<Throwable>()
        val disposed = CountDownLatch(1)
        val disposalCount = AtomicInteger()
        val engine = RiderAudioEngine(context, onEngineError = errors::offer,
            mediaMode = RiderMediaMode.GROUP,
            onDisposed = { disposalCount.incrementAndGet(); disposed.countDown() })
        try {
            val sessions = (1..3).map {
                val sdp = CountDownLatch(1)
                val session = engine.openSession(callbacks(sdp, errors))
                session.createOffer()
                assertTrue("group peer SDP absent: $errors", sdp.await(10, TimeUnit.SECONDS))
                session
            }
            val peers = sessions.map { field(it, "peerConnection") }
            assertTrue(peers.all { it != null })
            assertEquals(3, peers.toSet().size)
            val platform = platformResources(engine)
            assertTrue(platform.all { it != null })
            assertThrows(IllegalStateException::class.java) {
                engine.openSession(callbacks(CountDownLatch(1), errors))
            }
            sessions[1].close()
            assertTrue("closed group peer retained native resources", awaitPeerClosed(sessions[1]))
            assertSame(peers[0], field(sessions[0], "peerConnection"))
            assertSame(peers[2], field(sessions[2], "peerConnection"))
            platform.zip(platformResources(engine)).forEach { (before, after) -> assertSame(before, after) }
            val replacementSdp = CountDownLatch(1)
            val replacement = engine.openSession(callbacks(replacementSdp, errors))
            replacement.createOffer()
            assertTrue("replacement SDP absent: $errors", replacementSdp.await(10, TimeUnit.SECONDS))
            assertNotSame(peers[1], field(replacement, "peerConnection"))
            platform.zip(platformResources(engine)).forEach { (before, after) -> assertSame(before, after) }
            assertNull("unexpected group native error", errors.poll())
            sessions.forEach { it.close() }
            replacement.close()
        } finally {
            engine.close()
            assertTrue("native platform disposal did not complete", disposed.await(10, TimeUnit.SECONDS))
        }
        engine.close()
        assertEquals(1, disposalCount.get())
    }

    @Test
    fun closingOneGroupPeerKeepsTheOtherPeerSendingAndReceivingNativeAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val errors = LinkedBlockingQueue<Throwable>()
        val disposed = CountDownLatch(3)
        val controls = VersionedAudioControls(0, AudioControlSettings(voxEnabled = false))
        val engine = RiderAudioEngine(context, onEngineError = errors::offer, initialAudioControls = controls,
            mediaMode = RiderMediaMode.GROUP, onDisposed = { disposed.countDown() })
        val remotes = (1..2).map {
            RiderAudioEngine(context, onEngineError = errors::offer, initialAudioControls = controls,
                onDisposed = { disposed.countDown() })
        }
        val connected = CountDownLatch(4)
        try {
            engine.resumeAudio()
            val pairs = remotes.map { remote ->
                lateinit var local: RiderMediaSession
                lateinit var answerer: RiderMediaSession
                local = engine.openSession(RiderMediaSessionCallbacks(
                    onLocalSdpGenerated = { answerer.createAnswer(it) },
                    onLocalIceCandidateGenerated = { answerer.addRemoteIceCandidate(it) },
                    onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                    onError = errors::offer, isSessionCurrent = { true }))
                answerer = remote.openSession(RiderMediaSessionCallbacks(
                    onLocalSdpGenerated = { local.setRemoteAnswer(it) },
                    onLocalIceCandidateGenerated = { local.addRemoteIceCandidate(it) },
                    onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                    onError = errors::offer, isSessionCurrent = { true }))
                local.createOffer()
                local to answerer
            }
            assertTrue("group loopback did not connect: $errors", connected.await(20, TimeUnit.SECONDS))
            awaitAudioEvidence(pairs[0].first) { true }
            val before = awaitAudioEvidence(pairs[1].first) { true }
            pairs[0].first.close()
            pairs[0].second.close()
            val after = awaitAudioEvidence(pairs[1].first) {
                it.counters.streamIds == before.counters.streamIds &&
                    it.counters.sent > before.counters.sent && it.counters.received > before.counters.received
            }
            assertTrue(after.audioIoEnabled)
            engine.suspendAudio()
            assertTrue(audioSuspended(engine))
            engine.resumeAudio()
            awaitAudioEvidence(pairs[1].first) { it.gateRevision != after.gateRevision }
            assertNull("closing one group peer damaged the survivor", errors.poll())
        } finally {
            engine.close()
            remotes.forEach { it.close() }
            assertTrue("group loopback cleanup did not complete", disposed.await(15, TimeUnit.SECONDS))
        }
    }

    private fun awaitAudioEvidence(
        session: RiderMediaSession,
        predicate: (RiderMediaEvidence) -> Boolean
    ): RiderMediaEvidence {
        val samples = LinkedBlockingQueue<RiderMediaEvidence>()
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            session.queryEvidence { if (it != null) samples.offer(it) }
            val sample = samples.poll(500, TimeUnit.MILLISECONDS)
            if (sample != null && sample.connected && sample.remoteTrack && sample.audioIoEnabled &&
                sample.counters.sent > 0 && sample.counters.received > 0 && predicate(sample)) return sample
            SystemClock.sleep(100)
        }
        error("healthy native audio evidence absent")
    }

    private fun callbacks(
        sdp: CountDownLatch,
        errors: LinkedBlockingQueue<Throwable>
    ): RiderMediaSessionCallbacks = RiderMediaSessionCallbacks(
        onLocalSdpGenerated = { sdp.countDown() },
        onLocalIceCandidateGenerated = {},
        onError = errors::offer,
        isSessionCurrent = { true }
    )

    private fun platformResources(engine: RiderAudioEngine): List<Any?> = listOf(
        field(engine, "audioDeviceModule"),
        field(engine, "factory"),
        field(engine, "audioSource"),
        field(engine, "localAudioTrack")
    )

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(owner)
        }

    private fun audioSuspended(engine: RiderAudioEngine): Boolean {
        val gate = field(engine, "audioIoGate") as AudioIoGate
        return !gate.allows(gate.revision())
    }

    private fun awaitPeerClosed(session: RiderMediaSession): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (field(session, "peerConnection") == null) return true
            SystemClock.sleep(10L)
        }
        return false
    }
}
