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
    fun presenceSelectionCreatesTargetLockBeforeOpeningTransport() {
        val transition = requireNotNull(
            reduceIntercomState(
                IntercomState.Discovering(runtime),
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = runtime,
                    attemptId = ConnectionAttemptId("attempt-presence"),
                    targetDeviceId = "peer-b",
                    targetSessionId = RuntimeSessionId("peer-b-session"),
                    availableTransports = setOf(Transport.WIFI_DIRECT, Transport.LAN),
                    deadlineElapsedRealtimeMs = 10_000L
                )
            )
        )

        val connecting = transition.state as IntercomState.Connecting
        val effect = transition.effects.single() as SessionEffect.OpenTargetedTransport
        assertEquals(TargetLock("peer-b", RuntimeSessionId("peer-b-session")), connecting.attempt.targetLock)
        assertEquals(ChannelPlan.single(Transport.LAN), connecting.attempt.channelPlan)
        assertEquals(connecting.attempt, effect.attempt)
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
        assertEquals(recovery.id, effect.attempt.id)
        assertEquals("peer-a", effect.attempt.targetDeviceId)
        assertEquals(ConnectionTrigger.RECOVERY, effect.attempt.trigger)
        assertEquals(setOf(Transport.LAN), plannedDiscoveryTransports(effect.attempt))
        assertEquals(setOf(Transport.LAN, Transport.WIFI_DIRECT), plannedDiscoveryTransports(null))
    }

    @Test
    fun connectionPhaseTerminalStatesOutputAttemptAbortEffect() {
        val connectionStates = listOf<IntercomState>(
            IntercomState.Connecting(attempt, peer),
            IntercomState.Optimizing(attempt, peer)
        )
        val terminalStates = listOf(
            WebRtcConnectionState.DISCONNECTED,
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CLOSED
        )

        connectionStates.forEach { connectionState ->
            terminalStates.forEach { terminalState ->
                val transition = requireNotNull(
                    reduceIntercomState(
                        connectionState,
                        SessionEvent.WebRtcStateChanged(
                            runtime,
                            attempt.id,
                            terminalState,
                            1L,
                            recovery
                        )
                    )
                )

                assertTrue(transition.state is IntercomState.Discovering)
                assertEquals(
                    SessionEffect.AbortAttemptAndResumeDiscovery(runtime, attempt.id),
                    transition.effects.single()
                )
            }
        }

        val signalingTransition = requireNotNull(
            reduceIntercomState(
                IntercomState.Connecting(attempt, peer),
                SessionEvent.SignalingDisconnected(runtime, attempt.id, recovery)
            )
        )
        assertEquals(
            SessionEffect.AbortAttemptAndResumeDiscovery(runtime, attempt.id),
            signalingTransition.effects.single()
        )
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
    fun tunnelCannotCreateAnImplicitAttemptOrUseTheWrongTransport() {
        assertNull(
            nextIntercomState(
                IntercomState.Discovering(runtime),
                SessionEvent.TunnelReady(
                    attempt,
                    "peer-a",
                    Transport.LAN,
                    IdentityVerificationSource.SOCKET_HANDSHAKE
                )
            )
        )
        assertNull(
            nextIntercomState(
                IntercomState.Connecting(attempt, peer),
                SessionEvent.TunnelReady(
                    attempt,
                    "peer-a",
                    Transport.WIFI_DIRECT,
                    IdentityVerificationSource.NONE
                )
            )
        )
        assertNull(
            nextIntercomState(
                IntercomState.Connecting(attempt, peer),
                SessionEvent.TunnelReady(
                    attempt,
                    "peer-c",
                    Transport.LAN,
                    IdentityVerificationSource.SOCKET_HANDSHAKE
                )
            )
        )
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
        targetLock = TargetLock(target, RuntimeSessionId("session-$target")),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 10_000L
    )
}
