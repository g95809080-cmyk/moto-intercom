package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection
import java.io.Closeable

class AudioSessionControllerTest {
    @Test
    fun mediaReplacementReusesPlatformResourcesAndRejectsStaleCallbacks() {
        val closeOrder = mutableListOf<String>()
        val engine = FakeEngine(closeOrder)
        val route = RecordingCloseable("route", closeOrder)
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
        val controller = AudioSessionController(engine, RecordingCloseable("route"))
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
        val controller = AudioSessionController(engine, RecordingCloseable("route"))
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
        val controller = AudioSessionController(engine, RecordingCloseable("route"))

        assertThrows(IllegalStateException::class.java) {
            controller.openMediaSession(callbacks())
        }

        val retry = controller.openMediaSession(callbacks())
        assertEquals(2, engine.openCount)

        controller.closeMediaSession(retry)
        controller.close()
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

        override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession {
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

    private class RecordingCloseable(
        private val name: String,
        private val closeOrder: MutableList<String> = mutableListOf()
    ) : Closeable {
        var closeCount = 0

        override fun close() {
            closeCount++
            closeOrder += name
        }
    }
}
