package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection

class AudioSessionControllerTest {
    @Test
    fun routeCloseFailureStillRegistersNativeDisposalCompletion() {
        var completeNative: (() -> Unit)? = null
        var completed = false
        val engine = object : RiderMediaEngine {
            override fun updateAudioControls(controls: VersionedAudioControls) = Unit
            override fun openSession(callbacks: RiderMediaSessionCallbacks, icePolicy: MediaIcePolicy): RiderMediaSession = error("unused")
            override fun close() = Unit
            override fun closeWhenDisposed(onDisposed: () -> Unit) { completeNative = onDisposed }
        }
        val route = object : RiderAudioRoute {
            override fun select(selection: AudioRouteSelection) = Unit
            override fun close() = error("injected route cleanup error")
        }
        val controller = AudioSessionController(engine, route)
        assertThrows(IllegalStateException::class.java) { controller.closeWhenDisposed { completed = true } }
        assertFalse(completed)
        requireNotNull(completeNative).invoke()
        assertTrue(completed)
    }

    @Test
    fun relayPolicyIsScopedToTheSingleReplaceableMediaSession() {
        val engine = FakeEngine()
        val controller = AudioSessionController(engine, RecordingRoute("route"))
        val relay = MediaIcePolicy.Relay(listOf(TurnCredentials(listOf("turn:127.0.0.1:3478"), "user", "temporary", Long.MAX_VALUE)))
        val first = controller.openMediaSession(callbacks(), relay)
        assertSame(relay, engine.policies.single())
        assertThrows(IllegalStateException::class.java) { controller.openMediaSession(callbacks()) }
        assertEquals(1, engine.policies.size)
        controller.closeMediaSession(first)
        controller.openMediaSession(callbacks())
        assertSame(MediaIcePolicy.Nearby, engine.policies.last())
        controller.close()
    }

    @Test
    fun mediaReplacementReusesPlatformResourcesAndRejectsStaleCallbacks() {
        val closeOrder = mutableListOf<String>()
        val engine = FakeEngine(closeOrder)
        val route = RecordingRoute("route", closeOrder)
        val controller = AudioSessionController(engine, route)
        val states = mutableListOf<PeerConnection.PeerConnectionState>()

        val first = controller.openMediaSession(callbacks(states)) as FakeSession
        assertTrue(first.callbacks.isSessionCurrent())
        first.emit(PeerConnection.PeerConnectionState.CONNECTED)

        controller.closeMediaSession(first)

        assertFalse(first.callbacks.isSessionCurrent())
        first.emit(PeerConnection.PeerConnectionState.FAILED)
        assertEquals(listOf(PeerConnection.PeerConnectionState.CONNECTED), states)
        assertEquals(1, first.closeCount)
        assertEquals(0, engine.closeCount)
        assertEquals(0, route.closeCount)

        val second = controller.openMediaSession(callbacks(states)) as FakeSession
        assertSame(engine, second.owner)
        assertEquals(2, engine.openCount)
        assertTrue(second.callbacks.isSessionCurrent())

        controller.closeMediaSession(first)
        second.emit(PeerConnection.PeerConnectionState.CONNECTED)
        assertEquals(0, second.closeCount)

        controller.close()
        controller.close()

        assertFalse(second.callbacks.isSessionCurrent())
        assertEquals(1, second.closeCount)
        assertEquals(1, engine.closeCount)
        assertEquals(1, route.closeCount)
        assertEquals(listOf("session-1", "session-2", "engine", "route"), closeOrder)
    }

    @Test
    fun concurrentMediaSessionFailsClosed() {
        val engine = FakeEngine()
        val controller = AudioSessionController(engine, RecordingRoute("route"))
        val first = controller.openMediaSession(callbacks())

        assertThrows(IllegalStateException::class.java) {
            controller.openMediaSession(callbacks())
        }
        assertEquals(1, engine.openCount)

        controller.closeMediaSession(first)
        controller.close()
    }

    @Test
    fun upstreamMediaContextAlsoInvalidatesCallbackLease() {
        val engine = FakeEngine()
        val controller = AudioSessionController(engine, RecordingRoute("route"))
        var upstreamCurrent = true
        val session = controller.openMediaSession(
            callbacks(isCurrent = { upstreamCurrent })
        ) as FakeSession

        assertTrue(session.callbacks.isSessionCurrent())
        upstreamCurrent = false
        assertFalse(session.callbacks.isSessionCurrent())

        controller.close()
    }

    @Test
    fun failedOpenClearsLeaseForRetry() {
        val engine = FakeEngine(failFirstOpen = true)
        val controller = AudioSessionController(engine, RecordingRoute("route"))

        assertThrows(IllegalStateException::class.java) {
            controller.openMediaSession(callbacks())
        }

        val retry = controller.openMediaSession(callbacks())
        assertEquals(2, engine.openCount)

        controller.closeMediaSession(retry)
        controller.close()
    }

    @Test
    fun audioControlsAreAppliedToTheOwnedEngineAndRejectedAfterClose() {
        val engine = FakeEngine()
        val controller = AudioSessionController(engine, RecordingRoute("route"))
        val controls = VersionedAudioControls(
            revision = 1,
            settings = AudioControlSettings(muted = true, voxSensitivity = 75)
        )

        controller.updateAudioControls(controls)

        assertEquals(listOf(controls), engine.controlUpdates)
        controller.close()
        assertThrows(IllegalStateException::class.java) {
            controller.updateAudioControls(
                VersionedAudioControls(2, AudioControlSettings())
            )
        }
    }

    @Test
    fun audioRouteSelectionsAreAppliedToTheOwnedRouteAndRejectedAfterClose() {
        val route = RecordingRoute("route")
        val controller = AudioSessionController(FakeEngine(), route)

        controller.updateAudioRoute(AudioRouteSelection.EARPIECE)
        controller.updateAudioRoute(AudioRouteSelection.SPEAKER)

        assertEquals(
            listOf(AudioRouteSelection.EARPIECE, AudioRouteSelection.SPEAKER),
            route.selections
        )
        controller.close()
        assertThrows(IllegalStateException::class.java) {
            controller.updateAudioRoute(AudioRouteSelection.BLUETOOTH)
        }
    }

    private fun callbacks(
        states: MutableList<PeerConnection.PeerConnectionState> = mutableListOf(),
        isCurrent: () -> Boolean = { true }
    ): RiderMediaSessionCallbacks = RiderMediaSessionCallbacks(
        onLocalSdpGenerated = {},
        onLocalIceCandidateGenerated = {},
        onConnectionStateChanged = states::add,
        isSessionCurrent = isCurrent
    )

    private class FakeEngine(
        private val closeOrder: MutableList<String> = mutableListOf(),
        private val failFirstOpen: Boolean = false
    ) : RiderMediaEngine {
        var openCount = 0
        var closeCount = 0
        val controlUpdates = mutableListOf<VersionedAudioControls>()
        val policies = mutableListOf<MediaIcePolicy>()

        override fun updateAudioControls(controls: VersionedAudioControls) {
            controlUpdates += controls
        }

        override fun openSession(callbacks: RiderMediaSessionCallbacks, icePolicy: MediaIcePolicy): RiderMediaSession {
            policies += icePolicy
            openCount++
            if (failFirstOpen && openCount == 1) error("injected open failure")
            return FakeSession(this, openCount, callbacks, closeOrder)
        }

        override fun close() {
            closeCount++
            closeOrder += "engine"
        }
    }

    private class FakeSession(
        val owner: FakeEngine,
        private val number: Int,
        val callbacks: RiderMediaSessionCallbacks,
        private val closeOrder: MutableList<String>
    ) : RiderMediaSession {
        var closeCount = 0

        fun emit(state: PeerConnection.PeerConnectionState) {
            if (callbacks.isSessionCurrent()) callbacks.onConnectionStateChanged(state)
        }

        override fun createOffer() = Unit
        override fun createAnswer(remoteSdpJson: String) = Unit
        override fun setRemoteAnswer(remoteSdpJson: String) = Unit
        override fun addRemoteIceCandidate(candidateJson: String) = Unit

        override fun close() {
            closeCount++
            closeOrder += "session-$number"
        }
    }

    private class RecordingRoute(
        private val name: String,
        private val closeOrder: MutableList<String> = mutableListOf()
    ) : RiderAudioRoute {
        var closeCount = 0
        val selections = mutableListOf<AudioRouteSelection>()

        override fun select(selection: AudioRouteSelection) {
            selections += selection
        }

        override fun close() {
            closeCount++
            closeOrder += name
        }
    }
}
