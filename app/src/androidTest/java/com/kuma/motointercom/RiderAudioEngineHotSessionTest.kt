package com.kuma.motointercom

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RiderAudioEngineHotSessionTest {
    @Test
    fun groupPeersSharePlatformAndMiddleDisposalKeepsOtherNativeConnections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val errors = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(context, onEngineError = errors::offer, mediaMode = RiderMediaMode.GROUP)
        try {
            assertTrue(audioSuspended(engine))
            val sdps = CountDownLatch(3)
            val sessions = List(3) { engine.openSession(callbacks(sdps, errors)).also { it.createOffer() } }
            assertTrue("three native offers were not generated", sdps.await(20, TimeUnit.SECONDS))
            val platform = platformResources(engine)
            val peers = sessions.map { field(it, "peerConnection") }
            assertTrue(peers.all { it != null })
            assertNotSame(peers[0], peers[1]); assertNotSame(peers[1], peers[2])
            assertThrows(IllegalStateException::class.java) { engine.openSession(callbacks(CountDownLatch(1), errors)) }
            sessions[1].close()
            assertTrue(awaitPeerClosed(engine, sessions[1]))
            assertSame(peers[0], field(sessions[0], "peerConnection"))
            assertSame(peers[2], field(sessions[2], "peerConnection"))
            platform.zip(platformResources(engine)).forEach { (before, after) -> assertSame(before, after) }
            engine.resumeAudio(); engine.suspendAudio()
            assertTrue(audioSuspended(engine))
            sessions[0].close(); sessions[2].close()
            assertTrue(awaitPeerClosed(engine, sessions[2]))
            assertTrue(audioSuspended(engine))
            assertNull("unexpected native media error", errors.poll())
        } finally { engine.close() }
    }

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

            engine.suspendAudio()
            assertTrue("suspendAudio did not close the audio gate", audioSuspended(engine))
            assertTrue("suspendAudio must keep the hot PeerConnection", field(session, "peerConnection") != null)

            engine.resumeAudio()
            assertFalse("resumeAudio did not reopen the audio gate", audioSuspended(engine))
            assertTrue("resumeAudio must keep the hot PeerConnection", field(session, "peerConnection") != null)
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
            assertTrue("second PeerConnection was not disposed", awaitPeerClosed(engine, second))
        } finally {
            engine.close()
        }
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

    private fun field(engine: Any, name: String): Any? =
        engine.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(engine)
        }

    private fun audioSuspended(engine: RiderAudioEngine): Boolean {
        var suspended = true
        (field(engine, "audioIoGate") as AudioIoGate).applyCurrent { suspended = !it }
        return suspended
    }

    private fun awaitPeerClosed(engine: RiderAudioEngine, session: RiderMediaSession): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            (field(engine, "rtc") as java.util.concurrent.ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
            if (field(session, "peerConnection") == null) return true
            SystemClock.sleep(10L)
        }
        return false
    }
}
