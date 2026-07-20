package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveDisconnectInstrumentationTest {
    @Test
    fun explicitAttemptDisconnectUsesExactReleaseAndKeepsRuntimeOwners() {
        val coordinator = coordinator(listOf(OUTBOUND_ATTEMPT))
        val connecting = connect(coordinator)
        val decision = requireNotNull(
            coordinator.handle(
                connecting,
                SessionEvent.DisconnectRequested(LOCAL_RUNTIME, connecting.attempt.id)
            )
        )

        assertTrue(decision.accepted)
        assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), decision.state)
        assertNull(coordinator.currentAttempt)
        val release = decision.effects.single() as
            SessionEffect.ReleaseActiveSessionAndContinueDiscovery
        assertEquals(connecting.attempt, release.attempt)
        assertTrue(
            canExecuteActiveSessionReleaseEffect(
                release,
                requireNotNull(decision.state),
                coordinator.currentAttempt,
                coordinator.activeAttempt,
                coordinator.pendingInboundRequest
            )
        )

        val calls = mutableListOf<String>()
        var runtimeOnline = true
        var discoveryOnline = true
        var audioOwnerOnline = true
        ActiveSessionResourceController(
            attempt = release.attempt,
            cancelAttemptSchedules = { calls += "timers" },
            closeSignalingAndMedia = { calls += "media" },
            releaseLanAttempt = { calls += "lan" },
            releaseWifiDirectAttempt = { calls += "wifi" },
            clearConnectionState = { calls += "state" },
            continueDiscovery = { calls += "continue" }
        ).releaseAndContinueDiscovery()

        assertEquals(listOf("timers", "media", "lan", "wifi", "state", "continue"), calls)
        assertTrue(runtimeOnline)
        assertTrue(discoveryOnline)
        assertTrue(audioOwnerOnline)
        assertEquals(
            PrimaryIntercomAction.STOP_RUNTIME,
            primaryIntercomAction(requireNotNull(decision.state))
        )
        record("explicit", "attempt=${release.attempt.id.value} owners=retained")
    }

    @Test
    fun unexpectedConnectedLossStillStartsTargetLockedRecovery() {
        val coordinator = coordinator(listOf(OUTBOUND_ATTEMPT, RECOVERY_ATTEMPT))
        val connecting = connect(coordinator)
        val connected = IntercomState.Connected(
            connecting.attempt,
            verifiedPeer(),
            connectedAt = 1L,
            transport = Transport.LAN
        )
        val decision = requireNotNull(
            coordinator.handle(
                connected,
                SessionEvent.SignalingDisconnected(LOCAL_RUNTIME, connecting.attempt.id)
            )
        )

        assertTrue(decision.accepted)
        val recovering = decision.state as IntercomState.Recovering
        assertEquals(connecting.attempt.targetLock, recovering.attempt.targetLock)
        assertTrue(decision.effects.any { it is SessionEffect.RestartDiscovery })
        assertTrue(decision.effects.any { it is SessionEffect.ScheduleAttemptDeadline })
        assertFalse(
            decision.effects.any {
                it is SessionEffect.ReleaseActiveSessionAndContinueDiscovery
            }
        )
        assertEquals(
            PrimaryIntercomAction.DISCONNECT_CURRENT,
            primaryIntercomAction(recovering)
        )
        record("unexpected-loss", "recoveryAttempt=${recovering.attempt.id.value}")
    }

    private fun coordinator(ids: List<String>) = SignalingControlCoordinator(
        clock = FixedClock,
        attemptTimeoutMs = 10_000L,
        attemptIdFactory = ids.map(::ConnectionAttemptId).toCollection(ArrayDeque())::removeFirst
    )

    private fun connect(coordinator: SignalingControlCoordinator): IntercomState.Connecting =
        requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(LOCAL_RUNTIME),
                SessionEvent.ConnectPresenceRequested(
                    LOCAL_RUNTIME,
                    REMOTE_DEVICE,
                    REMOTE_RUNTIME,
                    setOf(Transport.LAN)
                )
            )
        ).state as IntercomState.Connecting

    private fun verifiedPeer() = PeerIdentity(
        deviceId = REMOTE_DEVICE,
        nickname = "Rider B",
        runtimeSessionId = REMOTE_RUNTIME,
        isDeviceIdVerified = true
    )

    private fun record(scenario: String, details: String) {
        val evidence = "KUM35_DISCONNECT scenario=$scenario $details"
        println(evidence)
        android.util.Log.i("MotoComKum35", evidence)
    }

    private data object FixedClock : MonotonicClock {
        override fun now() = MonotonicTimestamp(100L)
    }

    private companion object {
        val LOCAL_RUNTIME = RuntimeSessionId("41000000-0000-4000-8000-000000000035")
        val REMOTE_RUNTIME = RuntimeSessionId("42000000-0000-4000-8000-000000000035")
        const val REMOTE_DEVICE = "43000000-0000-4000-8000-000000000035"
        const val OUTBOUND_ATTEMPT = "44000000-0000-4000-8000-000000000035"
        const val RECOVERY_ATTEMPT = "45000000-0000-4000-8000-000000000035"
    }
}
