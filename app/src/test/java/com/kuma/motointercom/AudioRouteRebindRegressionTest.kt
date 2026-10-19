package com.kuma.motointercom

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Service rebind preserves a proven active route; explicit retry still reapplies it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioRouteRebindRegressionTest {
    @Test fun healthyActivityRebindDoesNotInterruptAudioButExplicitRetryStillReapplies() {
        Harness().use { h ->
            val application = ApplicationProvider.getApplicationContext<Application>()
            val database = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            database.set(null, null)
            val owner = Robolectric.buildService(IntercomService::class.java).create()
            val service = owner.get()
            field(service, "audioSessionController").set(service, h.controller)
            try {
                service.setPreferredAudioRoute(AudioRouteSelection.EARPIECE, AudioRouteRequestSource.RUNTIME_START)
                h.open()
                assertEquals(AudioInterruptionState.NORMAL, h.coordinator.currentState())
                val before = h.engine.operations.toList()
                val selections = h.selections.size
                service.setPreferredAudioRoute(AudioRouteSelection.EARPIECE, AudioRouteRequestSource.ACTIVITY_REBIND)
                assertEquals(before, h.engine.operations)
                assertEquals(selections, h.selections.size)
                service.setPreferredAudioRoute(AudioRouteSelection.EARPIECE, AudioRouteRequestSource.USER_SELECTION)
                assertEquals(before + listOf("suspend", "resume"), h.engine.operations)
                assertEquals(selections + 1, h.selections.size)
                h.route.suspendForInterruption(false)
                service.setPreferredAudioRoute(AudioRouteSelection.EARPIECE, AudioRouteRequestSource.ACTIVITY_REBIND)
                assertEquals(selections + 2, h.selections.size)
                assertTrue(h.controller.routeEvidence().ready)
            } finally {
                val scope = field(service, "serviceScope").get(service) as CoroutineScope
                val stopped = CountDownLatch(1)
                scope.coroutineContext[Job]?.invokeOnCompletion { stopped.countDown() } ?: stopped.countDown()
                owner.destroy()
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                (database.get(null) as? PairingDatabase)?.close()
                database.set(null, null)
            }
        }
    }

    @Test fun permanentFocusLossDoesNotRecoverByOnlySelectingARoute() {
        Harness().use { h ->
            h.open()
            h.focus.callback!!.invoke(AudioManager.AUDIOFOCUS_LOSS)
            val operations = h.engine.operations.toList()
            val selections = h.selections.size
            h.controller.updateAudioRoute(AudioRouteSelection.EARPIECE)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(AudioInterruptionState.FOCUS_LOST, h.coordinator.currentState())
            assertEquals(operations, h.engine.operations)
            assertEquals(selections, h.selections.size)
            assertEquals(1, h.focus.requests)
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private class Harness : AutoCloseable {
        val engine = Engine()
        val focus = Focus()
        val selections = mutableListOf<AudioRouteSelection>()
        val route = object : RiderAudioRoute {
            var proof = AudioRouteEvidence(0, false)
            override fun evidence() = proof
            override fun select(selection: AudioRouteSelection) {
                selections += selection
                proof = AudioRouteEvidence(proof.revision + 1, true)
                coordinator.onRouteReady()
            }
            override fun suspendForInterruption(restoreMode: Boolean) { proof = proof.copy(revision = proof.revision + 1, ready = false) }
            override fun close() = Unit
        }
        val coordinator: CommunicationAudioCoordinator = CommunicationAudioCoordinator(engine, route, focus, object : IntercomPhoneState {
            override fun start(listener: (PhoneCallState) -> Unit) = Unit
            override fun currentState() = PhoneCallState.IDLE
            override fun close() = Unit
        }, activateRoute = { route.select(controller.preferredAudioRoute()) }, audioPrompt = object : IntercomAudioPrompt {
            override fun playResumePrompt() = Unit
            override fun close() = Unit
        })
        val controller: AudioSessionController = AudioSessionController(engine, route, AudioRouteSelection.EARPIECE, coordinator)
        init { coordinator.start() }
        fun open() = controller.openMediaSession(RiderMediaSessionCallbacks({}, {}, isSessionCurrent = { true }))
        override fun close() = controller.close()
    }
    private class Engine : RiderMediaEngine {
        val operations = mutableListOf<String>()
        override fun updateAudioControls(controls: VersionedAudioControls) = Unit
        override fun openSession(callbacks: RiderMediaSessionCallbacks) = object : RiderMediaSession {
            override fun createOffer() = Unit
            override fun createAnswer(remoteSdpJson: String) = Unit
            override fun setRemoteAnswer(remoteSdpJson: String) = Unit
            override fun addRemoteIceCandidate(candidateJson: String) = Unit
            override fun close() = Unit
        }
        override fun suspendAudio() { operations += "suspend" }
        override fun resumeAudio() { operations += "resume" }
        override fun close() = Unit
    }
    private class Focus : IntercomAudioFocus {
        var callback: ((Int) -> Unit)? = null
        var requests = 0
        override fun setListener(listener: (Int) -> Unit) { this.callback = listener }
        override fun request(): AudioFocusResult { requests++; return AudioFocusResult.GRANTED }
        override fun abandon() = Unit
        override fun close() = Unit
    }
}

