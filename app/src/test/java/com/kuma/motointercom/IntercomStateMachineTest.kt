package com.kuma.motointercom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntercomStateMachineTest {
    private val runtime = RuntimeSessionId("runtime-current")
    private val attempt = ConnectionAttemptId("attempt-current")
    private val peer = PeerIdentity("peer-a", "Rider A", "Phone A")

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
            nextIntercomState(state, SessionEvent.ConnectRequested(runtime, attempt, peer.deviceId))
        )
        state = requireNotNull(
            nextIntercomState(state, SessionEvent.TransportOptimizing(runtime, attempt))
        )
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.WebRtcConnected(runtime, attempt, peer, 100L, "LAN")
            )
        )
        val recoveryAttempt = ConnectionAttemptId("attempt-recovery")
        state = requireNotNull(
            nextIntercomState(
                state,
                SessionEvent.ConnectionLost(runtime, attempt, recoveryAttempt)
            )
        )
        state = requireNotNull(
            nextIntercomState(state, SessionEvent.RecoveryExhausted(runtime, recoveryAttempt))
        )
        state = requireNotNull(nextIntercomState(state, SessionEvent.ResetCompleted(runtime)))
        state = requireNotNull(nextIntercomState(state, SessionEvent.StopRequested(runtime)))
        state = requireNotNull(nextIntercomState(state, SessionEvent.RuntimeStopped(runtime)))

        assertEquals(IntercomState.Offline, state)
    }

    @Test
    fun rejectsIllegalTransitions() {
        assertNull(
            nextIntercomState(
                IntercomState.Offline,
                SessionEvent.WebRtcConnected(runtime, attempt, peer, 1L, "LAN")
            )
        )
        assertNull(nextIntercomState(IntercomState.Offline, SessionEvent.StopRequested(runtime)))
    }

    @Test
    fun ignoresOldRuntimeSessionEvents() {
        val discovering = IntercomState.Discovering(runtime)
        val oldRuntime = RuntimeSessionId("runtime-old")

        assertNull(
            nextIntercomState(
                discovering,
                SessionEvent.ConnectRequested(oldRuntime, attempt, peer.deviceId)
            )
        )
    }

    @Test
    fun ignoresOldConnectionAttemptEvents() {
        val connecting = IntercomState.Connecting(runtime, attempt, peer.deviceId)
        val oldAttempt = ConnectionAttemptId("attempt-old")

        assertNull(
            nextIntercomState(
                connecting,
                SessionEvent.WebRtcConnected(runtime, oldAttempt, peer, 1L, "LAN")
            )
        )
    }
}
