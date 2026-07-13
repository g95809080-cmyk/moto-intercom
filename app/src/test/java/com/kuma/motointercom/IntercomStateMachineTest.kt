package com.kuma.motointercom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomStateMachineTest {
    private val runtime = RuntimeSessionId("runtime-current")
    private val peer = PeerIdentity("peer-a", "Rider A", "Phone A")
    private val attempt = attempt("attempt-current")
    private val recovery = RecoveryAttemptSpec(ConnectionAttemptId("attempt-recovery"), 20_000L)

    @Test
    fun exposesTheNineProductStates() {
        assertArrayEquals(
            arrayOf(
                SessionState.OFFLINE,
                SessionState.DISCOVERING,
                SessionState.INCOMING_CONFIRMATION,
                SessionState.CONNECTING,
                SessionState.OPTIMIZING,
                SessionState.CONNECTED,
                SessionState.RECOVERING,
                SessionState.RESETTING,
                SessionState.STOPPING
            ),
            SessionState.values()
        )
    }

    @Test
    fun followsLegalConnectRecoverResetAndStopLifecycle() {
        var state: IntercomState = IntercomState.Offline
        state = requireNotNull(nextIntercomState(state, SessionEvent.RuntimeStarted(runtime)))
        state = requireNotNull(
            nextIntercomState(state, SessionEvent.ConnectRequested(attempt))
        )
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.RemoteIdentityReceived(runtime, attempt.id, peer)
            )
        )
        state = requireNotNull(
            nextIntercomState(state, SessionEvent.TransportOptimizing(runtime, attempt.id))
        )
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    100L,
                    recovery
                )
            )
        )
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.SignalingDisconnected(runtime, attempt.id, recovery)
            )
        )
        state = requireNotNull(
            nextIntercomState(state, SessionEvent.RecoveryExhausted(runtime, recovery.id))
        )
        state = requireNotNull(nextIntercomState(state, SessionEvent.ResetCompleted(runtime)))
        state = requireNotNull(nextIntercomState(state, SessionEvent.StopRequested(runtime)))
        state = requireNotNull(nextIntercomState(state, SessionEvent.RuntimeStopped(runtime)))

        assertEquals(IntercomState.Offline, state)
    }

    @Test
    fun connectedThenDisconnectedMovesToRecovering() {
        var state: IntercomState = IntercomState.Connecting(attempt, peer)
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    1L,
                    recovery
                )
            )
        )
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    attempt.id,
                    WebRtcConnectionState.DISCONNECTED,
                    2L,
                    recovery
                )
            )
        )

        assertTrue(state is IntercomState.Recovering)
        assertEquals(recovery.id, (state as IntercomState.Recovering).attemptId)
    }

    @Test
    fun signalingDisconnectOutputsRecoveryAttemptOwnedByReducer() {
        val connected = IntercomState.Connected(
            attempt = attempt,
            peer = peer.copy(isDeviceIdVerified = true),
            connectedAt = 1L
        )

        val transition = requireNotNull(
            reduceIntercomState(
                connected,
                SessionEvent.SignalingDisconnected(runtime, attempt.id, recovery)
            )
        )
        val effect = transition.effects.single() as SessionEffect.RestartDiscovery

        assertTrue(transition.state is IntercomState.Recovering)
        assertEquals(recovery.id, effect.attempt?.id)
        assertEquals("peer-a", effect.attempt?.targetDeviceId)
        assertEquals(ConnectionTrigger.RECOVERY, effect.attempt?.trigger)
    }

    @Test
    fun rejectsIllegalAndStoppedTransitions() {
        val connected = SessionEvent.WebRtcStateChanged(
            runtime,
            attempt.id,
            WebRtcConnectionState.CONNECTED,
            1L,
            recovery
        )
        assertNull(nextIntercomState(IntercomState.Offline, connected))
        assertNull(nextIntercomState(IntercomState.Offline, SessionEvent.StopRequested(runtime)))

        val stopping = IntercomState.Stopping(runtime)
        assertNull(nextIntercomState(stopping, connected))
    }

    @Test
    fun ignoresOldRuntimeSessionEvents() {
        val discovering = IntercomState.Discovering(runtime)
        val oldAttempt = attempt(
            id = "attempt-old-runtime",
            runtimeSessionId = RuntimeSessionId("runtime-old")
        )

        assertNull(
            nextIntercomState(discovering, SessionEvent.ConnectRequested(oldAttempt))
        )
    }

    @Test
    fun ignoresOldConnectionAttemptEventsAfterReplacement() {
        val replacement = attempt("attempt-replacement", target = "peer-b")
        var state: IntercomState = IntercomState.Connecting(attempt, peer)
        state = requireNotNull(
            nextIntercomState(state, SessionEvent.AttemptReplaced(replacement))
        )

        assertNull(
            nextIntercomState(
                state,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    1L,
                    recovery
                )
            )
        )
        assertEquals(replacement.id, (state as IntercomState.Connecting).attemptId)
    }

    private fun attempt(
        id: String,
        runtimeSessionId: RuntimeSessionId = runtime,
        target: String = "peer-a"
    ) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = runtimeSessionId,
        targetDeviceId = target,
        trigger = ConnectionTrigger.USER,
        preferredTransport = Transport.LAN,
        deadlineElapsedRealtimeMs = 10_000L
    )
}
