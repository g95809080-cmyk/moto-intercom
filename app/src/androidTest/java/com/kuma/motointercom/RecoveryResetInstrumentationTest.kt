package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryResetInstrumentationTest {
    @Test
    fun thirdFinalFailureEntersExactResetAndRejectsStaleCallbacks() {
        val clock = MutableClock()
        val ids = ArrayDeque(
            listOf(
                OUTBOUND_ATTEMPT,
                RECOVERY_ATTEMPT_1,
                RECOVERY_ATTEMPT_2,
                RECOVERY_ATTEMPT_3
            ).map(::ConnectionAttemptId)
        )
        val coordinator = SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = ids::removeFirst
        )
        val connecting = requireNotNull(
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
        var state: IntercomState = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    verifiedPeer(),
                    connectedAt = 1L,
                    transport = Transport.LAN
                ),
                SessionEvent.SignalingDisconnected(LOCAL_RUNTIME, connecting.attempt.id)
            )
        ).state as IntercomState.Recovering

        repeat(3) { index ->
            val recovering = state as IntercomState.Recovering
            clock.elapsedMs = recovering.attempt.deadlineElapsedRealtimeMs
            val decision = requireNotNull(
                coordinator.handle(
                    recovering,
                    SessionEvent.AttemptTimedOut(
                        LOCAL_RUNTIME,
                        recovering.attempt.id,
                        recovering.attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertTrue(decision.accepted)
            state = requireNotNull(decision.state)
            if (index < 2) {
                val retry = state as IntercomState.Recovering
                assertEquals(index + 1, retry.consecutiveFinalFailures)
                assertEquals(connecting.attempt.targetLock, retry.attempt.targetLock)
            } else {
                assertEquals(1, decision.effects.count { it is SessionEffect.ResetWirelessEnvironment })
            }
        }

        val resetting = state as IntercomState.Resetting
        assertEquals(3, resetting.consecutiveFinalFailures)
        assertNull(coordinator.currentAttempt)
        assertFalse(
            requireNotNull(
                coordinator.handle(
                    resetting,
                    SessionEvent.TargetedTransportOpenFailed(
                        LOCAL_RUNTIME,
                        ConnectionAttemptId(RECOVERY_ATTEMPT_2),
                        Transport.LAN,
                        "stale"
                    )
                )
            ).accepted
        )
        assertNull(
            reduceIntercomState(
                resetting,
                SessionEvent.ResetCompleted(
                    LOCAL_RUNTIME,
                    ConnectionAttemptId(RECOVERY_ATTEMPT_2)
                )
            )
        )
        assertEquals(
            IntercomState.Discovering(LOCAL_RUNTIME),
            requireNotNull(
                reduceIntercomState(
                    resetting,
                    SessionEvent.ResetCompleted(LOCAL_RUNTIME, resetting.failedAttemptId)
                )
            ).state
        )
        record(
            "threshold",
            "target=${resetting.targetDeviceId} failedAttempt=${resetting.failedAttemptId.value} " +
                "count=${resetting.consecutiveFinalFailures}"
        )
    }

    @Test
    fun productionCloseSequencePreservesRequiredOrder() {
        val calls = mutableListOf<String>()
        val action: (String) -> ((() -> Unit) -> Unit) = { name ->
            { complete ->
                calls += name
                complete()
            }
        }

        WifiDirectCloseSequence(
            cancelConnect = action("cancelConnect"),
            removeGroup = action("removeGroup"),
            clearServiceRequests = action("clearServiceRequests"),
            clearLocalServices = action("clearLocalServices"),
            closeChannel = { calls += "close" },
            postDelayed = { _, _ -> Unit },
            removeCallbacks = {},
            stepTimeoutMillis = 1L
        ).start()

        assertEquals(
            listOf(
                "cancelConnect",
                "removeGroup",
                "clearServiceRequests",
                "clearLocalServices",
                "close"
            ),
            calls
        )
        record("cleanup", "order=${calls.joinToString(">")}")
    }

    private fun verifiedPeer() = PeerIdentity(
        deviceId = REMOTE_DEVICE,
        nickname = "Rider B",
        runtimeSessionId = REMOTE_RUNTIME,
        isDeviceIdVerified = true
    )

    private fun record(scenario: String, details: String) {
        val evidence = "KUM34_RESET scenario=$scenario $details"
        println(evidence)
        android.util.Log.i("MotoComKum34", evidence)
    }

    private class MutableClock(var elapsedMs: Long = 0L) : MonotonicClock {
        override fun now() = MonotonicTimestamp(elapsedMs)
    }

    private companion object {
        val LOCAL_RUNTIME = RuntimeSessionId("40000000-0000-4000-8000-000000000011")
        val REMOTE_RUNTIME = RuntimeSessionId("40000000-0000-4000-8000-000000000012")
        const val REMOTE_DEVICE = "10000000-0000-4000-8000-000000000012"
        const val OUTBOUND_ATTEMPT = "20000000-0000-4000-8000-000000000011"
        const val RECOVERY_ATTEMPT_1 = "20000000-0000-4000-8000-000000000012"
        const val RECOVERY_ATTEMPT_2 = "20000000-0000-4000-8000-000000000013"
        const val RECOVERY_ATTEMPT_3 = "20000000-0000-4000-8000-000000000014"
    }
}
