package com.kuma.motointercom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomStateMachineTest {
    private val runtime = RuntimeSessionId("runtime-current")
    private val peer = PeerIdentity(
        deviceId = "peer-a",
        nickname = "Rider A",
        deviceName = "Phone A",
        runtimeSessionId = RuntimeSessionId("session-peer-a"),
        isDeviceIdVerified = true
    )
    private val attempt = attempt("attempt-current")

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
    fun followsLegalServiceStartAndStopLifecycle() {
        var state: IntercomState = IntercomState.Offline
        state = requireNotNull(nextIntercomState(state, SessionEvent.RuntimeStarted(runtime)))
        state = requireNotNull(nextIntercomState(state, SessionEvent.StopRequested(runtime)))
        state = requireNotNull(nextIntercomState(state, SessionEvent.RuntimeStopped(runtime)))

        assertEquals(IntercomState.Offline, state)
    }

    @Test
    fun presenceSelectionIsReservedForCoordinator() {
        assertNull(
            reduceIntercomState(
                IntercomState.Discovering(runtime),
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = runtime,
                    targetDeviceId = "peer-b",
                    targetSessionId = RuntimeSessionId("peer-b-session"),
                    availableTransports = setOf(Transport.WIFI_DIRECT, Transport.LAN)
                )
            )
        )

    }

    @Test
    fun webRtcTerminalEventsAreReservedForCoordinator() {
        val connecting = IntercomState.Connecting(attempt, peer)
        assertNull(
            nextIntercomState(
                connecting,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    1L
                )
            )
        )
        assertNull(
            nextIntercomState(
                connecting,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    attempt.id,
                    WebRtcConnectionState.DISCONNECTED,
                    2L
                )
            )
        )

    }

    @Test
    fun signalingDisconnectIsReservedForCoordinator() {
        val connected = IntercomState.Connected(
            attempt = attempt,
            peer = peer.copy(isDeviceIdVerified = true),
            connectedAt = 1L,
            transport = Transport.LAN
        )

        assertNull(
            reduceIntercomState(
                connected,
                SessionEvent.SignalingDisconnected(runtime, attempt.id)
            )
        )
    }

    @Test
    fun connectionPhaseTerminalStatesAreReservedForCoordinator() {
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
                assertNull(
                    reduceIntercomState(
                        connectionState,
                        SessionEvent.WebRtcStateChanged(
                            runtime,
                            attempt.id,
                            terminalState,
                            1L
                        )
                    )
                )

            }
        }

        assertNull(
            reduceIntercomState(
                IntercomState.Connecting(attempt, peer),
                SessionEvent.SignalingDisconnected(runtime, attempt.id)
            )
        )
    }

    @Test
    fun rejectsIllegalAndStoppedTransitions() {
        val connected = SessionEvent.WebRtcStateChanged(
            runtime,
            attempt.id,
            WebRtcConnectionState.CONNECTED,
            1L
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
                    peer,
                    Transport.LAN
                )
            )
        )
        assertNull(
            nextIntercomState(
                IntercomState.Connecting(attempt, peer),
                SessionEvent.TunnelReady(
                    attempt,
                    peer,
                    Transport.WIFI_DIRECT
                )
            )
        )
        assertNull(
            nextIntercomState(
                IntercomState.Connecting(attempt, peer),
                SessionEvent.TunnelReady(
                    attempt,
                    peer.copy(deviceId = "peer-c"),
                    Transport.LAN
                )
            )
        )
    }

    @Test
    fun socketIdentityMustMatchBothLockedDeviceAndRuntime() {
        val connecting = IntercomState.Connecting(attempt)

        assertNull(
            nextIntercomState(
                connecting,
                SessionEvent.TunnelReady(
                    attempt,
                    peer.copy(runtimeSessionId = null),
                    Transport.LAN
                )
            )
        )
        assertNull(
            nextIntercomState(
                connecting,
                SessionEvent.TunnelReady(
                    attempt,
                    peer.copy(runtimeSessionId = RuntimeSessionId("session-new")),
                    Transport.LAN
                )
            )
        )
        assertEquals(
            peer,
            (requireNotNull(
                nextIntercomState(
                    connecting,
                    SessionEvent.TunnelReady(attempt, peer, Transport.LAN)
                )
            ) as IntercomState.Connecting).peer
        )
    }

    @Test
    fun openFailureAndTimeoutAreReservedForCoordinator() {
        val connecting = IntercomState.Connecting(attempt)
        assertNull(
            reduceIntercomState(
                connecting,
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    attempt.id,
                    Transport.LAN,
                    "adapter unavailable"
                )
            )
        )
        assertNull(
            reduceIntercomState(
                connecting,
                SessionEvent.AttemptTimedOut(
                    runtime,
                    attempt.id,
                    attempt.deadlineElapsedRealtimeMs
                )
            )
        )
        assertNull(
            reduceIntercomState(
                connecting,
                SessionEvent.AttemptTimedOut(
                    runtime,
                    ConnectionAttemptId("attempt-old"),
                    attempt.deadlineElapsedRealtimeMs
                )
            )
        )
        assertNull(
            reduceIntercomState(
                connecting,
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    attempt.id,
                    Transport.WIFI_DIRECT,
                    "stale transport"
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
                    1L
                )
            )
        )
        assertNull(
            reduceIntercomState(
                state,
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    attempt.id,
                    Transport.LAN,
                    "late failure"
                )
            )
        )
        assertNull(
            reduceIntercomState(
                state,
                SessionEvent.AttemptTimedOut(
                    runtime,
                    attempt.id,
                    attempt.deadlineElapsedRealtimeMs
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
