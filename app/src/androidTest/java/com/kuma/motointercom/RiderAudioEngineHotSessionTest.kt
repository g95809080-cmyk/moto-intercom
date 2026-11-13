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
            assertTrue("suspendAudio must keep the hot PeerConnection", field(engine, "peerConnection") != null)

            engine.resumeAudio()
            assertFalse("resumeAudio did not reopen the audio gate", audioSuspended(engine))
            assertTrue("resumeAudio must keep the hot PeerConnection", field(engine, "peerConnection") != null)
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

    @Test
    fun debugExternalCapturePostProcessingHooksSurviveSessionRebuildAndRepeatedClose() {
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
            isRuntimeCurrent = { true },
            noiseReductionConfig = AudioNoiseReductionConfig(
                requestedMode = AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING,
                internalExperimentEnabled = true
            )
        )
        try {
            awaitNoiseSnapshot(engine) {
                it.activeMode == AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING
            }
            assertTrue("external factory was not created", field(engine, "externalAudioProcessingFactory") != null)

            val firstSdp = CountDownLatch(1)
            val first = engine.openSession(callbacks(firstSdp, errors))
            first.createOffer()
            assertTrue("first SDP was not generated", firstSdp.await(10, TimeUnit.SECONDS))
            awaitNoiseSnapshot(engine) { it.externalProcessorFrameCount > 0L }
            first.close()

            val secondSdp = CountDownLatch(1)
            val second = engine.openSession(callbacks(secondSdp, errors))
            second.createOffer()
            assertTrue("second SDP was not generated", secondSdp.await(10, TimeUnit.SECONDS))
            second.close()
            assertNull("unexpected media error", errors.poll(2, TimeUnit.SECONDS))
        } finally {
            engine.close()
            engine.close()
            assertTrue(
                "audio platform resources were not cleared after repeated close",
                awaitPlatformResourcesCleared(engine)
            )
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

    private fun audioSuspended(engine: RiderAudioEngine): Boolean =
        field(engine, "audioSuspended") as Boolean

    private fun awaitPeerClosed(engine: RiderAudioEngine): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (field(engine, "peerConnection") == null) return true
            SystemClock.sleep(10L)
        }
        return false
    }

    private fun awaitPlatformResourcesCleared(engine: RiderAudioEngine): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (
                field(engine, "audioDeviceModule") == null &&
                field(engine, "factory") == null &&
                field(engine, "externalAudioProcessingFactory") == null &&
                field(engine, "externalCaptureProcessor") == null &&
                field(engine, "externalProcessorGuard") == null
            ) {
                return true
            }
            SystemClock.sleep(10L)
        }
        return false
    }

    private fun awaitNoiseSnapshot(
        engine: RiderAudioEngine,
        predicate: (AudioNoiseRuntimeSnapshot) -> Boolean
    ): AudioNoiseRuntimeSnapshot {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var last: AudioNoiseRuntimeSnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val diagnostics = field(engine, "noiseDiagnostics") as? AudioNoiseDiagnostics
            diagnostics?.snapshot()?.let {
                last = it
                if (predicate(it)) return it
            }
            SystemClock.sleep(20L)
        }
        throw AssertionError("audio noise diagnostic condition was not met: $last")
    }
}
