package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kum36SprintFinalAcceptanceTest {
    @Test
    fun thirdNodeCannotReplaceBAndReturningBBecomesOnlyWinnerBeforeFallback() {
        val clock = FakeMonotonicClock()
        val coordinator = coordinator(clock, OUTBOUND_ATTEMPT, RECOVERY_ATTEMPT_1)
        val connecting = connect(coordinator)
        val recoveryDecision = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    peerB(),
                    connectedAt = 1L,
                    transport = Transport.WIFI_DIRECT
                ),
                SessionEvent.WebRtcStateChanged(
                    LOCAL_RUNTIME,
                    connecting.attempt.id,
                    WebRtcConnectionState.DISCONNECTED,
                    occurredAt = 2L
                )
            )
        )
        val recovering = recoveryDecision.state as IntercomState.Recovering
        val milestone = (
            recoveryDecision.effects.single { it is SessionEffect.ScheduleAttemptMilestone }
                as SessionEffect.ScheduleAttemptMilestone
            ).milestone as AttemptMilestone.FallbackTransport

        assertEquals(connecting.attempt.targetLock, recovering.attempt.targetLock)
        assertEquals(10_000L, recovering.attempt.deadlineElapsedRealtimeMs)
        assertEquals(Transport.WIFI_DIRECT, recovering.attempt.preferredTransport)
        assertEquals(MonotonicTimestamp(3_000L), milestone.scheduledAt)
        assertEquals(REMOTE_DEVICE_B, recovering.peer.deviceId)
        assertTrue(recoveryStatusText(recovering.peer).contains(recovering.peer.nickname))

        val thirdChannel = responderChannelC()
        val thirdVerified = requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.ControlChannelVerified(LOCAL_RUNTIME, thirdChannel)
            )
        )
        assertTrue(thirdVerified.accepted)
        val thirdState = thirdVerified.state ?: recovering
        val thirdRequest = requireNotNull(
            coordinator.handle(
                thirdState,
                SessionEvent.IncomingConnectRequest(
                    LOCAL_RUNTIME,
                    thirdChannel.channelId,
                    thirdChannel.wireRequestKey,
                    RequestTrigger.USER,
                    Transport.LAN,
                    occurredAtElapsedMs = 100L
                ),
                IncomingRequestPolicy(
                    paired = true,
                    confirmationAvailability = ConfirmationAvailability(
                        appForeground = true,
                        notificationAvailable = true
                    )
                )
            )
        )
        assertTrue(thirdRequest.accepted)
        val busy = thirdRequest.effects.single() as SessionEffect.SendBusy
        assertEquals(thirdChannel.channelId, busy.channelId)
        assertEquals(recovering.attempt, coordinator.currentAttempt)

        val busySent = requireNotNull(
            coordinator.handle(
                thirdRequest.state ?: thirdState,
                SessionEvent.SignalingMessageSent(
                    LOCAL_RUNTIME,
                    busy.attemptId,
                    busy.channelId,
                    SignalingMessageTypeV2.BUSY
                )
            )
        )
        assertTrue(busySent.accepted)
        assertEquals(thirdChannel.channelId, (busySent.effects.single() as
            SessionEffect.CloseControlChannel).channelId)

        val targetChannel = requesterChannel(recovering.attempt, Transport.WIFI_DIRECT)
        val verified = requireNotNull(
            coordinator.handle(
                busySent.state ?: thirdRequest.state ?: thirdState,
                SessionEvent.ControlChannelVerified(LOCAL_RUNTIME, targetChannel)
            )
        )
        assertTrue(verified.accepted)
        val accepted = requireNotNull(
            coordinator.handle(
                verified.state ?: recovering,
                SessionEvent.RemoteConnectAccepted(
                    LOCAL_RUNTIME,
                    recovering.attempt.id,
                    targetChannel.channelId,
                    targetChannel.wireRequestKey
                )
            )
        )
        assertTrue(accepted.accepted)
        assertTrue(accepted.effects.single() is SessionEffect.StartWebRtc)
        assertEquals(
            targetChannel.channelId,
            requireNotNull(coordinator.activeAttempt).mediaOwnerChannelId
        )

        clock.advanceBy(2_999L)
        val connected = requireNotNull(
            coordinator.handle(
                accepted.state ?: recovering,
                SessionEvent.WebRtcStateChanged(
                    LOCAL_RUNTIME,
                    recovering.attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    occurredAt = clock.now().elapsedRealtimeMs
                )
            )
        )
        assertTrue(connected.accepted)
        assertEquals(REMOTE_DEVICE_B, (connected.state as IntercomState.Connected).peer.deviceId)

        clock.advanceBy(1L)
        val lateFallback = requireNotNull(
            coordinator.handle(
                requireNotNull(connected.state),
                SessionEvent.AttemptMilestoneElapsed(milestone)
            )
        )
        assertFalse(lateFallback.accepted)
        assertTrue(lateFallback.effects.isEmpty())
        assertEquals(
            targetChannel.channelId,
            requireNotNull(coordinator.activeAttempt).mediaOwnerChannelId
        )
    }

    @Test
    fun thirdFinalFailureResetsOnceAndOnlyExactCompletionReturnsDiscovery() {
        val clock = FakeMonotonicClock()
        val coordinator = coordinator(
            clock,
            OUTBOUND_ATTEMPT,
            RECOVERY_ATTEMPT_1,
            RECOVERY_ATTEMPT_2,
            RECOVERY_ATTEMPT_3
        )
        val connecting = connect(coordinator)
        var state: IntercomState = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    peerB(),
                    connectedAt = 1L,
                    transport = Transport.LAN
                ),
                SessionEvent.SignalingDisconnected(LOCAL_RUNTIME, connecting.attempt.id)
            )
        ).state as IntercomState.Recovering
        val recoveryAttemptIds = mutableListOf<ConnectionAttemptId>()

        repeat(3) { index ->
            val recovering = state as IntercomState.Recovering
            recoveryAttemptIds += recovering.attempt.id
            clock.advanceBy(
                recovering.attempt.deadlineElapsedRealtimeMs -
                    clock.now().elapsedRealtimeMs
            )
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
                assertEquals(
                    clock.now().elapsedRealtimeMs + 10_000L,
                    retry.attempt.deadlineElapsedRealtimeMs
                )
                assertEquals(
                    1,
                    decision.effects.count { it is SessionEffect.ScheduleAttemptDeadline }
                )
                assertEquals(0, decision.effects.count { it is SessionEffect.ResetWirelessEnvironment })
            } else {
                assertEquals(
                    1,
                    decision.effects.count { it is SessionEffect.ResetWirelessEnvironment }
                )
            }
        }

        val resetting = state as IntercomState.Resetting
        assertEquals(3, resetting.consecutiveFinalFailures)
        assertEquals(REMOTE_DEVICE_B, resetting.targetDeviceId)
        assertNull(coordinator.currentAttempt)
        assertNull(coordinator.activeAttempt)
        assertNull(coordinator.pendingInboundRequest)

        val stale = requireNotNull(
            coordinator.handle(
                resetting,
                SessionEvent.TargetedTransportOpenFailed(
                    LOCAL_RUNTIME,
                    recoveryAttemptIds[1],
                    Transport.LAN,
                    "stale transport"
                )
            )
        )
        assertFalse(stale.accepted)
        assertNull(
            reduceIntercomState(
                resetting,
                SessionEvent.ResetCompleted(LOCAL_RUNTIME, recoveryAttemptIds[1])
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
    }

    @Test
    fun userCancellationWinsQueuedFailureAndFullStopRemainsSeparate() {
        val clock = FakeMonotonicClock()
        val coordinator = coordinator(clock, OUTBOUND_ATTEMPT, RECOVERY_ATTEMPT_1)
        val connecting = connect(coordinator)
        val recovering = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    peerB(),
                    connectedAt = 1L,
                    transport = Transport.LAN
                ),
                SessionEvent.SignalingDisconnected(LOCAL_RUNTIME, connecting.attempt.id)
            )
        ).state as IntercomState.Recovering

        val canceled = requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.DisconnectRequested(LOCAL_RUNTIME, recovering.attempt.id)
            )
        )
        assertTrue(canceled.accepted)
        val discovering = canceled.state as IntercomState.Discovering
        assertEquals(
            ConnectionAttemptTerminalOutcome.CANCELED,
            coordinator.terminalOutcome(recovering.attempt.id)
        )
        assertNull(coordinator.currentAttempt)
        assertNull(coordinator.activeAttempt)
        assertTrue(
            canceled.effects.any {
                it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(recovering.attempt)
            }
        )

        clock.advanceBy(recovering.attempt.deadlineElapsedRealtimeMs)
        val lateTimeout = requireNotNull(
            coordinator.handle(
                discovering,
                SessionEvent.AttemptTimedOut(
                    LOCAL_RUNTIME,
                    recovering.attempt.id,
                    recovering.attempt.deadlineElapsedRealtimeMs
                )
            )
        )
        assertFalse(lateTimeout.accepted)
        assertTrue(lateTimeout.effects.none { it is SessionEffect.ResetWirelessEnvironment })
        assertTrue(canDeliverRuntimeAudioCallback(true, LOCAL_RUNTIME, LOCAL_RUNTIME))
        assertFalse(canDeliverRuntimeAudioCallback(true, LOCAL_RUNTIME, REMOTE_RUNTIME_C))
        assertEquals(
            IntercomState.Stopping(LOCAL_RUNTIME),
            nextIntercomState(discovering, SessionEvent.StopRequested(LOCAL_RUNTIME))
        )
    }

    private fun coordinator(
        clock: MonotonicClock,
        vararg attemptIds: String
    ): SignalingControlCoordinator {
        val ids = attemptIds.map(::ConnectionAttemptId).toCollection(ArrayDeque())
        return SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = ids::removeFirst
        )
    }

    private fun connect(coordinator: SignalingControlCoordinator): IntercomState.Connecting =
        requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(LOCAL_RUNTIME),
                SessionEvent.ConnectPresenceRequested(
                    LOCAL_RUNTIME,
                    REMOTE_DEVICE_B,
                    REMOTE_RUNTIME_B,
                    setOf(Transport.LAN, Transport.WIFI_DIRECT)
                )
            )
        ).state as IntercomState.Connecting

    private fun requesterChannel(
        attempt: ConnectionAttempt,
        transport: Transport
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(CHANNEL_B),
        transport = transport,
        requestRole = RequestRole.REQUESTER,
        wireRequestKey = WireRequestKey(
            DeviceId.parse(LOCAL_DEVICE_A),
            LOCAL_RUNTIME,
            attempt.id,
            DeviceId.parse(REMOTE_DEVICE_B)
        ),
        targetLock = attempt.targetLock,
        peer = peerB(),
        originatingAttempt = attempt
    )

    private fun responderChannelC() = VerifiedControlChannel(
        channelId = ControlChannelId.parse(CHANNEL_C),
        transport = Transport.LAN,
        requestRole = RequestRole.RESPONDER,
        wireRequestKey = WireRequestKey(
            DeviceId.parse(REMOTE_DEVICE_C),
            REMOTE_RUNTIME_C,
            ConnectionAttemptId(ATTEMPT_C),
            DeviceId.parse(LOCAL_DEVICE_A)
        ),
        targetLock = TargetLock(REMOTE_DEVICE_C, REMOTE_RUNTIME_C),
        peer = PeerIdentity(
            deviceId = REMOTE_DEVICE_C,
            nickname = "Rider C",
            runtimeSessionId = REMOTE_RUNTIME_C,
            isDeviceIdVerified = true
        ),
        originatingAttempt = null
    )

    private fun peerB() = PeerIdentity(
        deviceId = REMOTE_DEVICE_B,
        nickname = "Rider B",
        runtimeSessionId = REMOTE_RUNTIME_B,
        isDeviceIdVerified = true
    )

    private companion object {
        val LOCAL_RUNTIME = RuntimeSessionId("41000000-0000-4000-8000-000000000036")
        val REMOTE_RUNTIME_B = RuntimeSessionId("42000000-0000-4000-8000-000000000036")
        val REMOTE_RUNTIME_C = RuntimeSessionId("43000000-0000-4000-8000-000000000036")
        const val LOCAL_DEVICE_A = "a1000000-0000-4000-8000-000000000036"
        const val REMOTE_DEVICE_B = "b1000000-0000-4000-8000-000000000036"
        const val REMOTE_DEVICE_C = "c1000000-0000-4000-8000-000000000036"
        const val OUTBOUND_ATTEMPT = "d1000000-0000-4000-8000-000000000036"
        const val RECOVERY_ATTEMPT_1 = "d2000000-0000-4000-8000-000000000036"
        const val RECOVERY_ATTEMPT_2 = "d3000000-0000-4000-8000-000000000036"
        const val RECOVERY_ATTEMPT_3 = "d4000000-0000-4000-8000-000000000036"
        const val ATTEMPT_C = "d5000000-0000-4000-8000-000000000036"
        const val CHANNEL_B = "e1000000-0000-4000-8000-000000000036"
        const val CHANNEL_C = "e2000000-0000-4000-8000-000000000036"
    }
}
