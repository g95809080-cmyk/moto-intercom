package com.kuma.motointercom

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            val firstPeer = field(engine, "peerConnection")
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
            val secondPeer = field(engine, "peerConnection")
            firstPlatform.zip(secondPlatform).forEach { (before, after) ->
                assertSame(before, after)
            }
            assertNotSame(firstPeer, secondPeer)
            assertNull("unexpected media error", errors.poll())
            assertThrows(IllegalStateException::class.java) {
                engine.openSession(callbacks(CountDownLatch(1), errors))
            }

            second.close()
            assertTrue("second PeerConnection was not disposed", awaitPeerClosed(engine))
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

    private fun field(engine: RiderAudioEngine, name: String): Any? =
        RiderAudioEngine::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(engine)
        }

    private fun awaitPeerClosed(engine: RiderAudioEngine): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (field(engine, "peerConnection") == null) return true
            SystemClock.sleep(10L)
        }
        return false
    }
}
