package com.kuma.motointercom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingControlCoordinatorTest {
    @Test
    fun requesterStartsWebRtcOnlyAfterRemoteAccept() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val channel = requesterChannel(CHANNEL_A, attempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, channel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertEquals(SignalingAttemptPhase.WAITING_REMOTE_DECISION, activePhase(harness))

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey
                    )
                )
            )

            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(channel.channelId, start.channelId)
            assertEquals(WebRtcRole.OFFERER, start.role)
            assertEquals(10_000L, start.attempt.deadlineElapsedRealtimeMs)
            assertEquals(start.attempt, harness.orchestrator.currentAttempt)
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
        }
    }

    @Test
    fun requestDeliveryAndRemoteAcceptDoNotRebaseTotalDeadline() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val channel = requesterChannel(CHANNEL_A, attempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, channel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        SignalingMessageTypeV2.CONNECT_REQUEST
                    )
                )
            )

            assertEquals(attempt, harness.orchestrator.currentAttempt)
            assertFalse(harness.hasPendingEffect())
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        SignalingMessageTypeV2.CONNECT_REQUEST
                    )
                )
            )
            assertEquals(attempt, harness.orchestrator.currentAttempt)
            assertFalse(harness.hasPendingEffect())

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey
                    )
                )
            )
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(10_000L, start.attempt.deadlineElapsedRealtimeMs)
            assertEquals(start.attempt, harness.orchestrator.currentAttempt)
        }
    }

    @Test
    fun timeoutUsesTheOriginalImmutableDeadline() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val channel = requesterChannel(CHANNEL_A, attempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, channel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        SignalingMessageTypeV2.CONNECT_REQUEST
                    )
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertEquals(attempt, harness.orchestrator.currentAttempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey
                    )
                )
            )
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(start.attempt, harness.orchestrator.currentAttempt)

            harness.advanceBy(9_900L)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        start.attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun responderAcceptMustBeSentBeforeWebRtcStarts() = runBlocking {
        harness().use { harness ->
            val channel = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, channel)

            assertNull(harness.orchestrator.currentAttempt)
            assertTrue(harness.orchestrator.pendingInboundRequest != null)
            assertTrue(acceptIncoming(harness))
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            assertEquals(10_100L, attempt.deadlineElapsedRealtimeMs)
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(setOf(channel.channelId), select.cohort.channelIds)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        channel.wireRequestKey,
                        channel.channelId
                    )
                )
            )
            val accept = harness.nextEffect() as SessionEffect.SendConnectAccept
            assertEquals(channel.channelId, accept.channelId)
            assertEquals(SignalingAttemptPhase.ACCEPTING, activePhase(harness))

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        channel.channelId,
                        SignalingMessageTypeV2.CONNECT_ACCEPT
                    )
                )
            )
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(WebRtcRole.ANSWERER, start.role)
            assertEquals(channel.channelId, start.channelId)
        }
    }

    @Test
    fun duplicateRequestOnTheCurrentOwnerIsIdempotent() = runBlocking {
        harness().use { harness ->
            val owner = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, owner)
            assertTrue(acceptIncoming(harness))
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(setOf(owner.channelId), select.cohort.channelIds)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        owner.wireRequestKey,
                        owner.channelId
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectAccept)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        owner.channelId,
                        SignalingMessageTypeV2.CONNECT_ACCEPT
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        owner.channelId,
                        owner.wireRequestKey,
                        RequestTrigger.USER,
                        null,
                        101L
                    )
                )
            )
            assertFalse(harness.hasPendingEffect())
            assertEquals(
                owner.channelId,
                harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId
            )
        }
    }

    @Test
    fun sentSupersededChannelIsRemovedFromTheActiveAttempt() = runBlocking {
        harness().use { harness ->
            val owner = responderChannel(CHANNEL_A)
            val superseded = responderChannel(CHANNEL_B)
            harness.startRuntime()
            registerIncomingRequest(harness, owner)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, superseded)
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.IncomingConnectRequest(
                    RUNTIME_B,
                    superseded.channelId,
                    superseded.wireRequestKey,
                    RequestTrigger.USER,
                    Transport.LAN,
                    101L
                )
            )
            acceptIncoming(harness)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.MediaChannelSelected(
                    RUNTIME_B,
                    attempt.id,
                    owner.wireRequestKey,
                    owner.channelId
                )
            )
            harness.nextEffect()
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        superseded.channelId,
                        SignalingMessageTypeV2.CONNECT_REJECT
                    )
                )
            )

            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertEquals(
                setOf(owner.channelId),
                harness.orchestrator.activeControlAttempt?.channelIds
            )
            assertEquals(SignalingAttemptPhase.ACCEPTING, activePhase(harness))
        }
    }

    @Test
    fun failedAcceptSendNeverStartsWebRtc() = runBlocking {
        harness().use { harness ->
            val channel = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, channel)
            acceptIncoming(harness)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.MediaChannelSelected(
                    RUNTIME_B,
                    attempt.id,
                    channel.wireRequestKey,
                    channel.channelId
                )
            )
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingSendFailed(
                        RUNTIME_B,
                        attempt.id,
                        channel.channelId,
                        SignalingMessageTypeV2.CONNECT_ACCEPT,
                        "socket closed"
                    )
                )
            )

            val effects = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(effects.any { it is SessionEffect.CloseControlChannel })
            assertTrue(effects.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertFalse(effects.any { it is SessionEffect.StartWebRtc })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun supersededChannelBeforeOwnerAcceptDoesNotRejectAttempt() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val first = requesterChannel(CHANNEL_A, attempt)
            val second = requesterChannel(CHANNEL_B, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, first)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, second)
            )
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectRejected(
                        RUNTIME_A,
                        attempt.id,
                        first.channelId,
                        first.wireRequestKey,
                        RejectReason.SUPERSEDED_CHANNEL,
                        retryable = false
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        second.channelId,
                        second.wireRequestKey
                    )
                )
            )
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(second.channelId, start.channelId)
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
        }
    }

    @Test
    fun attemptScopedRejectEndsAttemptWithoutStoppingRuntime() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val channel = requesterChannel(CHANNEL_A, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, channel)
            )
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectRejected(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey,
                        RejectReason.USER_REJECTED,
                        retryable = false
                    )
                )
            )
            val effects = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(effects.any { it is SessionEffect.CloseControlChannel })
            assertTrue(effects.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertEquals(RUNTIME_A, harness.orchestrator.state.value.runtimeSessionId)
        }
    }

    @Test
    fun recoveryRejectUsesActiveTerminalRootWithoutLosingFailureCount() = runBlocking {
        val recoveryIds = ArrayDeque(
            listOf(
                ConnectionAttemptId("30000000-0000-4000-8000-000000000002"),
                ConnectionAttemptId("30000000-0000-4000-8000-000000000003")
            )
        )
        harness(attemptIdFactory = recoveryIds::removeFirst).use { harness ->
            val connectedAttempt = outboundAttempt()
            harness.start(connectedAttempt)
            val connectedChannel = requesterChannel(CHANNEL_A, connectedAttempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, connectedChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        connectedAttempt.id,
                        connectedChannel.channelId,
                        connectedChannel.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        connectedAttempt.id,
                        WebRtcConnectionState.CONNECTED,
                        500L
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(RUNTIME_A, connectedAttempt.id)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.RestartDiscovery)
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val recovery1 = harness.orchestrator.state.value as IntercomState.Recovering
            val recoveryChannel = requesterChannel(CHANNEL_B, recovery1.attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, recoveryChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectRejected(
                        RUNTIME_A,
                        recovery1.attempt.id,
                        recoveryChannel.channelId,
                        recoveryChannel.wireRequestKey,
                        RejectReason.USER_REJECTED,
                        retryable = false
                    )
                )
            )

            val effects = listOf(
                harness.nextEffect(),
                harness.nextEffect(),
                harness.nextEffect()
            )
            val recovery2 = harness.orchestrator.state.value as IntercomState.Recovering
            assertEquals(1, recovery2.consecutiveFinalFailures)
            assertEquals(recovery1.attempt.targetLock, recovery2.attempt.targetLock)
            assertTrue(effects.any { it is SessionEffect.CloseControlChannel })
            assertTrue(
                effects.any {
                    it is SessionEffect.RestartDiscovery &&
                        it.attempt == recovery2.attempt &&
                        it.restartDelayMillis == 1_500L
                }
            )
            assertTrue(
                effects.any {
                    it == SessionEffect.ScheduleAttemptDeadline(recovery2.attempt)
                }
            )
        }
    }

    @Test
    fun sameTargetRecoveryGlareConvergesWithoutBusy() = runBlocking {
        val recoveryIds = ArrayDeque(listOf(ConnectionAttemptId(ATTEMPT_B)))
        harness(attemptIdFactory = recoveryIds::removeFirst).use { harness ->
            val localRecovery = enterRecovery(harness)
            val localChannel = requesterChannel(CHANNEL_B, localRecovery.attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, localChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)

            val remoteWinner = responderChannel(
                channelId = CHANNEL_A,
                requesterDeviceId = DEVICE_B,
                requesterRuntime = RUNTIME_B,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_A,
                originatingAttempt = localRecovery.attempt
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, remoteWinner)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_A,
                        remoteWinner.channelId,
                        remoteWinner.wireRequestKey,
                        RequestTrigger.RECOVERY,
                        Transport.LAN,
                        100L
                    )
                )
            )

            val effects = listOf(
                harness.nextEffect(),
                harness.nextEffect(),
                harness.nextEffect()
            )
            val converged = harness.orchestrator.state.value as IntercomState.Recovering
            assertFalse(effects.any { it is SessionEffect.SendBusy })
            assertTrue(effects.any { it is SessionEffect.CloseControlChannel })
            assertTrue(effects.any { it is SessionEffect.ScheduleAttemptDeadline })
            assertTrue(effects.any { it is SessionEffect.SelectMediaChannel })
            assertEquals(ConnectionAttemptId(ATTEMPT_A), converged.attempt.id)
            assertEquals(ConnectionTrigger.RECOVERY, converged.attempt.trigger)
            assertEquals(localRecovery.attempt.targetLock, converged.attempt.targetLock)
            assertEquals(
                localRecovery.attempt.deadlineElapsedRealtimeMs,
                converged.attempt.deadlineElapsedRealtimeMs
            )
            assertEquals(localRecovery.consecutiveFinalFailures, converged.consecutiveFinalFailures)
            assertEquals(
                ConnectionAttemptTerminalOutcome.GLARE_LOST,
                harness.orchestrator.terminalOutcome(localRecovery.attempt.id)
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        localRecovery.attempt.id,
                        localRecovery.attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertEquals(converged.attempt, harness.orchestrator.currentAttempt)
        }
    }

    @Test
    fun localRecoveryGlareWinnerRejectsRemoteAttemptWithoutLeavingRecovery() = runBlocking {
        val recoveryIds = ArrayDeque(listOf(ConnectionAttemptId(ATTEMPT_A)))
        harness(attemptIdFactory = recoveryIds::removeFirst).use { harness ->
            val localRecovery = enterRecovery(harness)
            val localChannel = requesterChannel(CHANNEL_A, localRecovery.attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, localChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)

            val remoteLoser = responderChannel(
                channelId = CHANNEL_B,
                requesterDeviceId = DEVICE_B,
                requesterRuntime = RUNTIME_B,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_B,
                originatingAttempt = localRecovery.attempt
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, remoteLoser)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_A,
                        remoteLoser.channelId,
                        remoteLoser.wireRequestKey,
                        RequestTrigger.RECOVERY,
                        Transport.LAN,
                        100L
                    )
                )
            )

            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.GLARE_LOST, reject.reason)
            assertEquals(remoteLoser.channelId, reject.channelId)
            assertEquals(localRecovery, harness.orchestrator.state.value)
            assertEquals(localRecovery.attempt, harness.orchestrator.currentAttempt)
            assertEquals(SignalingAttemptPhase.WAITING_REMOTE_DECISION, activePhase(harness))
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun activeRecoverySignalingDisconnectClearsContextAndRearmsSchedules() = runBlocking {
        val recoveryIds = ArrayDeque(
            listOf(ConnectionAttemptId("30000000-0000-4000-8000-000000000011"))
        )
        harness(attemptIdFactory = recoveryIds::removeFirst).use { harness ->
            val connectedAttempt = outboundAttempt()
            harness.start(connectedAttempt)
            val connectedChannel = requesterChannel(CHANNEL_A, connectedAttempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, connectedChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        connectedAttempt.id,
                        connectedChannel.channelId,
                        connectedChannel.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        connectedAttempt.id,
                        WebRtcConnectionState.CONNECTED,
                        500L
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(RUNTIME_A, connectedAttempt.id)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.RestartDiscovery)
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val recovering = harness.orchestrator.state.value as IntercomState.Recovering
            val recoveryChannel = requesterChannel(CHANNEL_B, recovering.attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, recoveryChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertEquals(recovering.attempt, harness.orchestrator.activeControlAttempt?.attempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(RUNTIME_A, recovering.attempt.id)
                )
            )

            assertNull(harness.orchestrator.activeControlAttempt)
            assertEquals(
                SessionEffect.RestartDiscovery(RUNTIME_A, recovering.attempt),
                harness.nextEffect()
            )
            assertEquals(
                SessionEffect.ScheduleAttemptDeadline(recovering.attempt),
                harness.nextEffect()
            )
            assertEquals(
                recovering.attempt.deadlineElapsedRealtimeMs,
                (harness.orchestrator.state.value as IntercomState.Recovering)
                    .attempt.deadlineElapsedRealtimeMs
            )
        }
    }

    @Test
    fun nonOwnerDisconnectCannotEndAcceptedAttemptButOwnerDisconnectCan() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val first = requesterChannel(CHANNEL_A, attempt)
            val owner = requesterChannel(CHANNEL_B, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, first)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    attempt.id,
                    owner.channelId,
                    owner.wireRequestKey
                )
            )
            harness.nextEffect()
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteDisconnect(
                        RUNTIME_A,
                        attempt.id,
                        first.channelId,
                        first.wireRequestKey,
                        DisconnectReason.parse("REMOTE_CANCELED")
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteDisconnect(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey,
                        DisconnectReason.parse("REMOTE_CANCELED")
                    )
                )
            )
            val effects = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(
                effects.any { it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt) }
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun decodedOwnerDisconnectBeforeEofCleanupConvergesExactlyOnce() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val owner = requesterChannel(CHANNEL_A, attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        500L
                    )
                )
            )

            assertTrue(
                canDeliverDecodedControlEnvelope(
                    sessionCurrent = true,
                    sessionClosed = true,
                    registeredSessionMatches = true
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteDisconnect(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey,
                        DisconnectReason.parse("REMOTE_CANCELED")
                    )
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(
                cleanup.any {
                    it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt)
                }
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ChannelClosed(
                        RUNTIME_A,
                        owner.channelId,
                        owner.wireRequestKey,
                        "EOF after decoded DISCONNECT"
                    )
                )
            )
            assertFalse(harness.hasPendingEffect())

            val replacement = outboundAttempt(ATTEMPT_B)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ConnectRequested(replacement)
                )
            )
            val replacementOwner = requesterChannel(CHANNEL_A, replacement)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, replacementOwner)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)

            val oldDecodedFrameDelivered = canDeliverDecodedControlEnvelope(
                sessionCurrent = true,
                sessionClosed = true,
                registeredSessionMatches = false
            )
            assertFalse(oldDecodedFrameDelivered)
            if (oldDecodedFrameDelivered) {
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteDisconnect(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey,
                        DisconnectReason.parse("STALE_REMOTE_CANCELED")
                    )
                )
            }
            assertEquals(replacement, harness.orchestrator.currentAttempt)
            assertEquals(
                setOf(replacementOwner.channelId),
                harness.orchestrator.activeControlAttempt?.channelIds
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun protocolViolationClosesOnlyANonOwnerButEndsTheOwnerAttempt() = runBlocking {
        harness().use { harness ->
            val owner = responderChannel(CHANNEL_A)
            val nonOwner = responderChannel(CHANNEL_B)
            harness.startRuntime()
            registerIncomingRequest(harness, owner)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, nonOwner)
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.IncomingConnectRequest(
                    RUNTIME_B,
                    nonOwner.channelId,
                    nonOwner.wireRequestKey,
                    RequestTrigger.USER,
                    Transport.LAN,
                    101L
                )
            )
            acceptIncoming(harness)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.MediaChannelSelected(
                    RUNTIME_B,
                    attempt.id,
                    owner.wireRequestKey,
                    owner.channelId
                )
            )
            harness.nextEffect()
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ProtocolViolation(
                        RUNTIME_B,
                        nonOwner.channelId,
                        nonOwner.wireRequestKey,
                        "invalid duplicate channel frame"
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertEquals(setOf(owner.channelId), harness.orchestrator.activeControlAttempt?.channelIds)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ProtocolViolation(
                        RUNTIME_B,
                        owner.channelId,
                        owner.wireRequestKey,
                        "invalid owner frame"
                    )
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(cleanup.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun localDisconnectSendsOnTheOwnerBeforeCleanup() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val owner = requesterChannel(CHANNEL_A, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    attempt.id,
                    owner.channelId,
                    owner.wireRequestKey
                )
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    RUNTIME_A,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    500L
                )
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Connected)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.DisconnectRequested(RUNTIME_A, attempt.id)
                )
            )
            val disconnect = harness.nextEffect() as SessionEffect.SendDisconnect
            assertEquals(owner.channelId, disconnect.channelId)
            assertEquals(SignalingAttemptPhase.TERMINATING, activePhase(harness))
            assertTrue(harness.orchestrator.state.value is IntercomState.Connected)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        SignalingMessageTypeV2.DISCONNECT
                    )
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(
                cleanup.any { it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt) }
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun localDisconnectBlocksQueuedWebRtcRecovery() = runBlocking {
        var createdRecoveryIds = 0
        harness(
            attemptIdFactory = {
                createdRecoveryIds++
                ConnectionAttemptId("unexpected-recovery-$createdRecoveryIds")
            }
        ).use { harness ->
            val (attempt, _) = beginConnectedLocalDisconnect(harness)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        attempt.id,
                        WebRtcConnectionState.DISCONNECTED,
                        501L
                    )
                )
            )

            assertLocalDisconnectCleanup(harness, attempt)
            assertEquals(0, createdRecoveryIds)
        }
    }

    @Test
    fun localDisconnectBlocksQueuedSignalingRecovery() = runBlocking {
        var createdRecoveryIds = 0
        harness(
            attemptIdFactory = {
                createdRecoveryIds++
                ConnectionAttemptId("unexpected-recovery-$createdRecoveryIds")
            }
        ).use { harness ->
            val (attempt, _) = beginConnectedLocalDisconnect(harness)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(
                        RUNTIME_A,
                        attempt.id
                    )
                )
            )

            assertLocalDisconnectCleanup(harness, attempt)
            assertEquals(0, createdRecoveryIds)
        }
    }

    @Test
    fun localDisconnectBlocksQueuedOwnerChannelRecovery() = runBlocking {
        var createdRecoveryIds = 0
        harness(
            attemptIdFactory = {
                createdRecoveryIds++
                ConnectionAttemptId("unexpected-recovery-$createdRecoveryIds")
            }
        ).use { harness ->
            val (attempt, owner) = beginConnectedLocalDisconnect(harness)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ChannelClosed(
                        RUNTIME_A,
                        owner.channelId,
                        owner.wireRequestKey,
                        "queued owner close"
                    )
                )
            )

            assertLocalDisconnectCleanup(harness, attempt)
            assertEquals(0, createdRecoveryIds)
        }
    }

    @Test
    fun localDisconnectSendFailureStillUsesNarrowCleanup() = runBlocking {
        harness().use { harness ->
            val (attempt, owner) = beginConnectedLocalDisconnect(harness)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingSendFailed(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        SignalingMessageTypeV2.DISCONNECT,
                        "owner socket closed"
                    )
                )
            )

            assertLocalDisconnectCleanup(harness, attempt)
        }
    }

    @Test
    fun connectedOwnerSendFailureRecoversTheSameTarget() = runBlocking {
        val recoveryId = ConnectionAttemptId(RECOVERY_ATTEMPT)
        harness(attemptIdFactory = { recoveryId }).use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val owner = requesterChannel(CHANNEL_A, attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        500L
                    )
                )
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingSendFailed(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        SignalingMessageTypeV2.CANDIDATE,
                        "network unavailable"
                    )
                )
            )

            val recovering = harness.orchestrator.state.value as IntercomState.Recovering
            assertEquals(recoveryId, recovering.attempt.id)
            assertEquals(attempt.targetLock, recovering.attempt.targetLock)
            assertEquals(attempt.channelPlan, recovering.attempt.channelPlan)
            assertEquals(ConnectionTrigger.RECOVERY, recovering.attempt.trigger)
            assertEquals(
                ConnectionAttemptTerminalOutcome.SUCCESS,
                harness.orchestrator.terminalOutcome(attempt.id)
            )
            val effects = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(
                effects.any {
                    it == SessionEffect.RestartDiscovery(RUNTIME_A, recovering.attempt)
                }
            )
            assertTrue(
                effects.any {
                    it == SessionEffect.ScheduleAttemptDeadline(recovering.attempt)
                }
            )
            assertFalse(effects.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertNull(harness.orchestrator.activeControlAttempt)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ChannelClosed(
                        RUNTIME_A,
                        owner.channelId,
                        owner.wireRequestKey,
                        "late close"
                    )
                )
            )
        }
    }

    @Test
    fun remoteExplicitDisconnectDuringRecoveryEndsWithoutRetryOrFailureIncrement() = runBlocking {
        val recoveryIds = ArrayDeque(
            listOf(ConnectionAttemptId("30000000-0000-4000-8000-000000000021"))
        )
        harness(attemptIdFactory = recoveryIds::removeFirst).use { harness ->
            val connectedAttempt = outboundAttempt()
            harness.start(connectedAttempt)
            val connectedChannel = requesterChannel(CHANNEL_A, connectedAttempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, connectedChannel)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        connectedAttempt.id,
                        connectedChannel.channelId,
                        connectedChannel.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        connectedAttempt.id,
                        WebRtcConnectionState.CONNECTED,
                        500L
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(RUNTIME_A, connectedAttempt.id)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.RestartDiscovery)
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)

            val recovering = harness.orchestrator.state.value as IntercomState.Recovering
            val owner = requesterChannel(CHANNEL_B, recovering.attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        recovering.attempt.id,
                        owner.channelId,
                        owner.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteDisconnect(
                        RUNTIME_A,
                        recovering.attempt.id,
                        owner.channelId,
                        owner.wireRequestKey,
                        DisconnectReason.parse("REMOTE_CANCELED")
                    )
                )
            )

            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(
                cleanup.any {
                    it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(recovering.attempt)
                }
            )
            assertFalse(cleanup.any { it is SessionEffect.RestartDiscovery })
            assertFalse(cleanup.any { it is SessionEffect.ResetWirelessEnvironment })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertNull(harness.orchestrator.currentAttempt)
            assertEquals(
                ConnectionAttemptTerminalOutcome.CANCELED,
                harness.orchestrator.terminalOutcome(recovering.attempt.id)
            )
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun selectorIsIndependentOfCollectionOrder() {
        val low = MediaChannelCandidate(ControlChannelId.parse(CHANNEL_A), Transport.LAN)
        val high = MediaChannelCandidate(ControlChannelId.parse(CHANNEL_B), Transport.LAN)

        assertEquals(
            low.channelId,
            selectMediaChannel(listOf(high, low), preferredTransport = Transport.LAN)
        )
        assertEquals(
            low.channelId,
            selectMediaChannel(listOf(low, high), preferredTransport = Transport.LAN)
        )
    }

    @Test
    fun singleAndDualPlansEmitExpectedPreferenceHint() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val channel = requesterChannel(CHANNEL_A, attempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, channel)
                )
            )
            val request = harness.nextEffect() as SessionEffect.SendConnectRequest
            assertNull(request.preferredTransportHint)
        }

        harness().use { harness ->
            val attempt = outboundAttempt(
                channelPlan = ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT)
            )
            harness.start(attempt)
            val preferred = requesterChannel(CHANNEL_A, attempt, Transport.LAN)
            val fallback = requesterChannel(CHANNEL_B, attempt, Transport.WIFI_DIRECT)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, preferred)
                )
            )
            assertEquals(
                Transport.LAN,
                (harness.nextEffect() as SessionEffect.SendConnectRequest)
                    .preferredTransportHint
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, fallback)
                )
            )
            assertEquals(
                Transport.LAN,
                (harness.nextEffect() as SessionEffect.SendConnectRequest)
                    .preferredTransportHint
            )
        }
    }

    @Test
    fun preferredWinnerSuppressesTheQueuedFallbackMilestone() = runBlocking {
        harness(attemptIdFactory = { ConnectionAttemptId(ATTEMPT_A) }).use { harness ->
            val attempt = harness.startPresence(
                setOf(Transport.LAN, Transport.WIFI_DIRECT)
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val open = harness.nextEffect() as SessionEffect.OpenTargetedTransport
            assertEquals(Transport.LAN, open.transport)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.FallbackTransport

            val preferred = requesterChannel(CHANNEL_A, attempt, Transport.LAN)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, preferred)
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        preferred.channelId,
                        preferred.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)

            harness.advanceBy(5_000L)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun preferredArrivalDuringOptimizationWinsAndCleansFallback() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            val fallback = responderChannel(CHANNEL_A, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        fallback.channelId,
                        fallback.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.MediaOptimization
            assertTrue(harness.orchestrator.state.value is IntercomState.Optimizing)
            assertEquals(SignalingAttemptPhase.OPTIMIZING_MEDIA, activePhase(harness))

            harness.advanceBy(999L)
            val preferred = responderChannel(CHANNEL_B, transport = Transport.LAN)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, preferred)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        preferred.channelId,
                        preferred.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        1_099L
                    )
                )
            )
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(Transport.LAN, select.preferredTransport)
            assertEquals(setOf(fallback.channelId, preferred.channelId), select.cohort.channelIds)

            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        preferred.wireRequestKey,
                        preferred.channelId
                    )
                )
            )
            val accept = harness.nextEffect() as SessionEffect.SendConnectAccept
            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(preferred.channelId, accept.channelId)
            assertEquals(fallback.channelId, reject.channelId)
            assertEquals(preferred.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        fallback.wireRequestKey,
                        fallback.channelId
                    )
                )
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        fallback.channelId,
                        SignalingMessageTypeV2.CONNECT_REJECT
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        preferred.channelId,
                        SignalingMessageTypeV2.CONNECT_ACCEPT
                    )
                )
            )
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(preferred.channelId, start.channelId)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_B,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        1_099L
                    )
                )
            )
            val connected = harness.orchestrator.state.value as IntercomState.Connected
            assertEquals(Transport.LAN, connected.transport)

            harness.advanceBy(1L)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun preferredAtOptimizationExpiryCannotJoinTheFrozenFallbackCohort() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            val fallback = responderChannel(CHANNEL_A, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        fallback.channelId,
                        fallback.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.MediaOptimization
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            harness.advanceBy(1_000L)
            val preferred = responderChannel(CHANNEL_B, transport = Transport.LAN)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, preferred)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        preferred.channelId,
                        preferred.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        1_100L
                    )
                )
            )
            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(preferred.channelId, reject.channelId)
            assertTrue(harness.orchestrator.state.value is IntercomState.Optimizing)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(setOf(fallback.channelId), select.cohort.channelIds)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        fallback.wireRequestKey,
                        fallback.channelId
                    )
                )
            )
            assertEquals(
                fallback.channelId,
                (harness.nextEffect() as SessionEffect.SendConnectAccept).channelId
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        preferred.wireRequestKey,
                        preferred.channelId
                    )
                )
            )
            assertEquals(
                fallback.channelId,
                harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId
            )
        }
    }

    @Test
    fun mediaSelectionAtTheTotalDeadlineCannotClaimAnOwner() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            val fallback = responderChannel(CHANNEL_A, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        fallback.channelId,
                        fallback.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.MediaOptimization
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            harness.advanceBy(1_000L)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            harness.advanceBy(
                attempt.deadlineElapsedRealtimeMs - milestone.scheduledAt.elapsedRealtimeMs
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        fallback.wireRequestKey,
                        select.cohort.channelIds.single()
                    )
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(cleanup.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertFalse(cleanup.any { it is SessionEffect.SendConnectAccept })
            assertEquals(
                ConnectionAttemptTerminalOutcome.TIMED_OUT,
                harness.orchestrator.terminalOutcome(attempt.id)
            )
        }
    }

    @Test
    fun fallbackWinsExactlyAtOptimizationExpiryAndRecordsItsTransport() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            val fallback = responderChannel(CHANNEL_A, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        fallback.channelId,
                        fallback.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.MediaOptimization
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            harness.advanceBy(1_000L)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(setOf(fallback.channelId), select.cohort.channelIds)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        fallback.wireRequestKey,
                        fallback.channelId
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.SendConnectAccept)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        fallback.channelId,
                        SignalingMessageTypeV2.CONNECT_ACCEPT
                    )
                )
            )
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(fallback.channelId, start.channelId)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_B,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        1_100L
                    )
                )
            )
            val connected = harness.orchestrator.state.value as IntercomState.Connected
            assertEquals(Transport.WIFI_DIRECT, connected.transport)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun totalDeadlineBeatsAnOptimizationDecisionAtTheSameTimestamp() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            harness.advanceBy(9_400L)
            val fallback = responderChannel(CHANNEL_A, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        fallback.channelId,
                        fallback.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        0L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.MediaOptimization
            assertEquals(10_000L, milestone.scheduledAt.elapsedRealtimeMs)

            harness.advanceBy(500L)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(cleanup.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertFalse(cleanup.any { it is SessionEffect.SelectMediaChannel })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertEquals(
                ConnectionAttemptTerminalOutcome.TIMED_OUT,
                harness.orchestrator.terminalOutcome(milestone.attempt.id)
            )
        }
    }

    @Test
    fun cancelDuringOptimizationInvalidatesTheQueuedWinnerDecision() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            val fallback = responderChannel(CHANNEL_A, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        fallback.channelId,
                        fallback.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val milestone = (
                harness.nextEffect() as SessionEffect.ScheduleAttemptMilestone
                ).milestone as AttemptMilestone.MediaOptimization
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.DisconnectRequested(RUNTIME_B, attempt.id)
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(
                cleanup.any { it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt) }
            )
            harness.advanceBy(1_000L)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptMilestoneElapsed(milestone)
                )
            )
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun cancelBeforeWinnerClaimClosesEveryCandidateAndRejectsLateCallbacks() = runBlocking {
        harness(pairedDeviceIds = setOf(DEVICE_A)).use { harness ->
            harness.startRuntime()
            val preferred = responderChannel(CHANNEL_A, transport = Transport.LAN)
            val fallback = responderChannel(CHANNEL_B, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, preferred)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        preferred.channelId,
                        preferred.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(setOf(preferred.channelId, fallback.channelId), select.cohort.channelIds)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.DisconnectRequested(RUNTIME_B, attempt.id)
                )
            )
            val cleanup = List(3) { harness.nextEffect() }
            assertEquals(
                setOf(preferred.channelId, fallback.channelId),
                cleanup.filterIsInstance<SessionEffect.CloseControlChannel>()
                    .mapTo(linkedSetOf(), SessionEffect.CloseControlChannel::channelId)
            )
            assertEquals(
                1,
                cleanup.count {
                    it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt)
                }
            )
            assertFalse(cleanup.any { it is SessionEffect.StartWebRtc })
            assertEquals(
                ConnectionAttemptTerminalOutcome.CANCELED,
                harness.orchestrator.terminalOutcome(attempt.id)
            )
            assertNull(harness.orchestrator.currentAttempt)
            assertNull(harness.orchestrator.activeControlAttempt)
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.MediaChannelSelected(
                        RUNTIME_B,
                        attempt.id,
                        preferred.wireRequestKey,
                        preferred.channelId
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_B,
                        attempt.id,
                        preferred.channelId,
                        preferred.wireRequestKey
                    )
                )
            )
            val staleClose = harness.nextEffect() as SessionEffect.CloseControlChannel
            assertEquals(preferred.channelId, staleClose.channelId)
            assertEquals(attempt.targetLock, staleClose.targetLock)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        preferred.channelId,
                        SignalingMessageTypeV2.CONNECT_ACCEPT
                    )
                )
            )
            assertFalse(harness.hasPendingEffect())
            assertNull(harness.orchestrator.currentAttempt)
            assertNull(harness.orchestrator.activeControlAttempt)
        }
    }

    @Test
    fun concurrentCallbacksCommitOneMediaOwnerAndLeaveNoAttemptResources() = runBlocking {
        harness(
            pairedDeviceIds = setOf(DEVICE_A),
            dispatcher = Dispatchers.Default
        ).use { harness ->
            harness.startRuntime()
            val preferred = responderChannel(CHANNEL_A, transport = Transport.LAN)
            val fallback = responderChannel(CHANNEL_B, transport = Transport.WIFI_DIRECT)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, preferred)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, fallback)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        preferred.channelId,
                        preferred.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            val claimResults = harness.dispatchConcurrently { index ->
                SessionEvent.MediaChannelSelected(
                    RUNTIME_B,
                    attempt.id,
                    select.wireRequestKey,
                    if (index % 2 == 0) preferred.channelId else fallback.channelId
                )
            }
            assertEquals(1, claimResults.count { it })
            val ownerEffects = List(2) { harness.nextEffect() }
            val accept = ownerEffects.filterIsInstance<SessionEffect.SendConnectAccept>().single()
            val reject = ownerEffects.filterIsInstance<SessionEffect.SendConnectReject>().single()
            assertTrue(accept.channelId != reject.channelId)
            assertEquals(accept.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)

            val duplicateRequests = harness.dispatchConcurrently { index ->
                val channel = if (index % 2 == 0) preferred else fallback
                SessionEvent.IncomingConnectRequest(
                    RUNTIME_B,
                    channel.channelId,
                    channel.wireRequestKey,
                    RequestTrigger.USER,
                    Transport.LAN,
                    101L
                )
            }
            assertTrue(duplicateRequests.all { it })
            assertFalse(harness.hasPendingEffect())

            harness.dispatchConcurrently { index ->
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    attempt.id,
                    if (index % 2 == 0) accept.channelId else reject.channelId,
                    SignalingMessageTypeV2.CONNECT_ACCEPT
                )
            }
            val start = harness.nextEffect() as SessionEffect.StartWebRtc
            assertEquals(accept.channelId, start.channelId)
            assertFalse(harness.hasPendingEffect())

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        reject.channelId,
                        SignalingMessageTypeV2.CONNECT_REJECT
                    )
                )
            )
            val loserClose = harness.nextEffect() as SessionEffect.CloseControlChannel
            assertEquals(reject.channelId, loserClose.channelId)

            val cancelResults = harness.dispatchConcurrently {
                SessionEvent.DisconnectRequested(RUNTIME_B, attempt.id)
            }
            assertTrue(cancelResults.all { it })
            val disconnect = harness.nextEffect() as SessionEffect.SendDisconnect
            assertEquals(accept.channelId, disconnect.channelId)
            assertFalse(harness.hasPendingEffect())

            val terminalResults = harness.dispatchConcurrently {
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    attempt.id,
                    accept.channelId,
                    SignalingMessageTypeV2.DISCONNECT
                )
            }
            assertEquals(1, terminalResults.count { it })
            val terminalEffects = List(2) { harness.nextEffect() }
            assertEquals(1, terminalEffects.count { it is SessionEffect.CloseControlChannel })
            assertEquals(
                1,
                terminalEffects.count {
                    it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt)
                }
            )
            assertFalse(terminalEffects.any { it is SessionEffect.StartWebRtc })
            assertFalse(harness.hasPendingEffect())
            assertNull(harness.orchestrator.currentAttempt)
            assertNull(harness.orchestrator.activeControlAttempt)
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertEquals(
                ConnectionAttemptTerminalOutcome.CANCELED,
                harness.orchestrator.terminalOutcome(attempt.id)
            )
        }
    }

    @Test
    fun sameTargetGlareWinnerIsSelectedBeforeBusy() = runBlocking {
        harness().use { harness ->
            val localAttempt = outboundAttempt(ATTEMPT_B)
            harness.start(localAttempt)
            val localChannel = requesterChannel(CHANNEL_B, localAttempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, localChannel)
            )
            harness.nextEffect()

            val remoteWinner = responderChannel(
                channelId = CHANNEL_A,
                requesterDeviceId = DEVICE_B,
                requesterRuntime = RUNTIME_B,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_A,
                originatingAttempt = localAttempt
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, remoteWinner)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_A,
                        remoteWinner.channelId,
                        remoteWinner.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )

            val effects = listOf(
                harness.nextEffect(),
                harness.nextEffect(),
                harness.nextEffect()
            )
            assertTrue(effects.any { it is SessionEffect.CloseControlChannel })
            assertTrue(effects.any { it is SessionEffect.ScheduleAttemptDeadline })
            assertTrue(effects.any { it is SessionEffect.SelectMediaChannel })
            assertFalse(effects.any { it is SessionEffect.SendBusy })
            assertEquals(ConnectionAttemptId(ATTEMPT_A), harness.orchestrator.currentAttempt?.id)
            assertEquals(DEVICE_B, harness.orchestrator.currentAttempt?.targetDeviceId)
            assertEquals(
                localAttempt.deadlineElapsedRealtimeMs,
                harness.orchestrator.currentAttempt?.deadlineElapsedRealtimeMs
            )
        }
    }

    @Test
    fun sameTargetGlareAtDeadlineCannotReplaceExpiredAttempt() = runBlocking {
        harness().use { harness ->
            val localAttempt = outboundAttempt(ATTEMPT_B)
            harness.start(localAttempt)
            val localChannel = requesterChannel(CHANNEL_B, localAttempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, localChannel)
                )
            )
            harness.nextEffect()

            val remoteWinner = responderChannel(
                channelId = CHANNEL_A,
                requesterDeviceId = DEVICE_B,
                requesterRuntime = RUNTIME_B,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_A,
                originatingAttempt = localAttempt
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, remoteWinner)
                )
            )
            harness.advanceBy(localAttempt.deadlineElapsedRealtimeMs - 100L)

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_A,
                        remoteWinner.channelId,
                        remoteWinner.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        localAttempt.deadlineElapsedRealtimeMs
                    )
                )
            )

            assertEquals(localAttempt, harness.orchestrator.currentAttempt)
            assertFalse(harness.hasPendingEffect())
        }
    }

    @Test
    fun glareLostIsBroadcastAcrossEveryVerifiedLosingRequestChannel() = runBlocking {
        harness().use { harness ->
            val localWinner = outboundAttempt(ATTEMPT_A)
            harness.start(localWinner)
            val localChannel = requesterChannel(CHANNEL_A, localWinner)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, localChannel)
            )
            harness.nextEffect()

            val losingFirst = responderChannel(
                channelId = CHANNEL_B,
                requesterDeviceId = DEVICE_B,
                requesterRuntime = RUNTIME_B,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_B,
                originatingAttempt = localWinner
            )
            val losingSecond = responderChannel(
                channelId = CHANNEL_C,
                requesterDeviceId = DEVICE_B,
                requesterRuntime = RUNTIME_B,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_B,
                originatingAttempt = localWinner
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, losingFirst)
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, losingSecond)
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_A,
                        losingFirst.channelId,
                        losingFirst.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )

            val rejects = listOf(harness.nextEffect(), harness.nextEffect())
                .map { it as SessionEffect.SendConnectReject }
            assertEquals(
                setOf(losingFirst.channelId, losingSecond.channelId),
                rejects.mapTo(linkedSetOf()) { it.channelId }
            )
            assertTrue(rejects.all { it.reason == RejectReason.GLARE_LOST })
            assertEquals(localWinner, harness.orchestrator.currentAttempt)
            assertEquals(SignalingAttemptPhase.WAITING_REMOTE_DECISION, activePhase(harness))
        }
    }

    @Test
    fun thirdPartyRequestReceivesBusyWithoutReplacingCurrentAttempt() = runBlocking {
        harness().use { harness ->
            val currentAttempt = outboundAttempt()
            harness.start(currentAttempt)
            val currentChannel = requesterChannel(CHANNEL_A, currentAttempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, currentChannel)
            )
            harness.nextEffect()

            val thirdParty = responderChannel(
                channelId = CHANNEL_C,
                requesterDeviceId = DEVICE_C,
                requesterRuntime = RUNTIME_C,
                responderDeviceId = DEVICE_A,
                attemptId = ATTEMPT_C
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, thirdParty)
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_A,
                        thirdParty.channelId,
                        thirdParty.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )

            val busy = harness.nextEffect() as SessionEffect.SendBusy
            assertEquals(thirdParty.channelId, busy.channelId)
            assertEquals(currentAttempt, harness.orchestrator.currentAttempt)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)
        }
    }

    @Test
    fun attemptRejectIsBroadcastBeforeCleanup() = runBlocking {
        harness().use { harness ->
            val first = responderChannel(CHANNEL_A)
            val second = responderChannel(CHANNEL_B)
            harness.startRuntime()
            registerIncomingRequest(harness, first)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, second)
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.IncomingConnectRequest(
                    RUNTIME_B,
                    second.channelId,
                    second.wireRequestKey,
                    RequestTrigger.USER,
                    Transport.LAN,
                    101L
                )
            )
            assertTrue(rejectIncoming(harness))
            val sends = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(sends.all { it is SessionEffect.SendConnectReject })
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
            assertEquals(
                setOf(first.channelId, second.channelId),
                harness.orchestrator.pendingInboundRequest?.pendingTerminalChannels
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        first.wireRequestKey.attemptId,
                        first.channelId,
                        SignalingMessageTypeV2.CONNECT_REJECT
                    )
                )
            )
            assertEquals(
                setOf(second.channelId),
                harness.orchestrator.pendingInboundRequest?.pendingTerminalChannels
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)

            harness.orchestrator.dispatchAndAwait(
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    first.wireRequestKey.attemptId,
                    second.channelId,
                    SignalingMessageTypeV2.CONNECT_REJECT
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertFalse(harness.hasPendingEffect())
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertNull(harness.orchestrator.currentAttempt)
            assertNull(harness.orchestrator.pendingInboundRequest)
        }
    }

    @Test
    fun confirmationOwnershipMovesWithoutCreatingAnotherRequest() = runBlocking {
        harness().use { harness ->
            val first = responderChannel(CHANNEL_A)
            val second = responderChannel(CHANNEL_B)
            harness.startRuntime()
            val originalPrompt = registerIncomingRequest(harness, first)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, second)
            )
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.IncomingConnectRequest(
                    RUNTIME_B,
                    second.channelId,
                    second.wireRequestKey,
                    RequestTrigger.USER,
                    Transport.LAN,
                    101L
                )
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ChannelClosed(
                        RUNTIME_B,
                        first.channelId,
                        first.wireRequestKey,
                        "LAN closed"
                    )
                )
            )
            assertEquals(
                second.channelId,
                harness.orchestrator.pendingInboundRequest?.confirmationChannelId
            )
            val cancel = harness.nextEffect() as SessionEffect.CancelIncomingConfirmation
            val publish = harness.nextEffect() as SessionEffect.PublishIncomingConfirmation
            val migratedPrompt = publish.prompt
            assertEquals(originalPrompt.actionNonce, cancel.actionNonce)
            assertEquals(second.channelId, migratedPrompt.channelId)
            assertEquals(
                originalPrompt.decisionDeadlineElapsedMs,
                migratedPrompt.decisionDeadlineElapsedMs
            )
            assertFalse(originalPrompt.actionNonce == migratedPrompt.actionNonce)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingAccepted(
                        originalPrompt.runtimeSessionId,
                        originalPrompt.attemptId,
                        originalPrompt.channelId,
                        originalPrompt.actionNonce,
                        100L
                    )
                )
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
        }
    }

    @Test
    fun finalPendingConfirmationChannelLossCreatesNoAttemptOrAttemptCleanup() = runBlocking {
        harness().use { harness ->
            val channel = responderChannel(CHANNEL_A)
            harness.startRuntime()
            val prompt = registerIncomingRequest(harness, channel)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ChannelClosed(
                        RUNTIME_B,
                        channel.channelId,
                        channel.wireRequestKey,
                        "channel lost"
                    )
                )
            )

            val cancel = harness.nextEffect() as SessionEffect.CancelIncomingConfirmation
            assertEquals(prompt.actionNonce, cancel.actionNonce)
            assertFalse(harness.hasPendingEffect())
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertNull(harness.orchestrator.currentAttempt)
            assertNull(harness.orchestrator.pendingInboundRequest)
        }
    }

    @Test
    fun lateRequesterChannelAfterAcceptIsRejectedBeforeRegistration() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val owner = requesterChannel(CHANNEL_A, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    attempt.id,
                    owner.channelId,
                    owner.wireRequestKey
                )
            )
            harness.nextEffect()

            val late = requesterChannel(CHANNEL_C, attempt)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, late)
                )
            )
            assertEquals(owner.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
        }
    }

    @Test
    fun mismatchedResponseContextCannotRemoveTheCurrentOwner() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            val owner = requesterChannel(CHANNEL_A, attempt)
            harness.start(attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
                )
            )
            harness.nextEffect()

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_C,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey
                    )
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        ConnectionAttemptId(ATTEMPT_B),
                        owner.channelId,
                        owner.wireRequestKey.copy(
                            attemptId = ConnectionAttemptId(ATTEMPT_B)
                        )
                    )
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey.copy(
                            requesterDeviceId = DeviceId.parse(DEVICE_C)
                        )
                    )
                )
            )

            assertFalse(harness.hasPendingEffect())
            assertEquals(setOf(owner.channelId), harness.orchestrator.activeControlAttempt?.channelIds)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
        }
    }

    @Test
    fun staleCallbacksCannotRemoveAReplacementUsingTheSameChannelId() = runBlocking {
        harness().use { harness ->
            val previous = outboundAttempt(ATTEMPT_A)
            val previousChannel = requesterChannel(CHANNEL_A, previous)
            harness.start(previous)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, previousChannel)
            )
            harness.nextEffect()

            val replacement = outboundAttempt(ATTEMPT_B)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(SessionEvent.AttemptReplaced(replacement))
            )
            val replacementChannel = requesterChannel(CHANNEL_A, replacement)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_A, replacementChannel)
                )
            )
            harness.nextEffect()

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ChannelClosed(
                        RUNTIME_A,
                        previousChannel.channelId,
                        previousChannel.wireRequestKey,
                        "stale reader"
                    )
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingSendFailed(
                        RUNTIME_A,
                        previous.id,
                        previousChannel.channelId,
                        SignalingMessageTypeV2.CONNECT_REQUEST,
                        "stale send completion"
                    )
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ProtocolViolation(
                        RUNTIME_A,
                        previousChannel.channelId,
                        previousChannel.wireRequestKey,
                        "stale protocol callback"
                    )
                )
            )

            assertFalse(harness.hasPendingEffect())
            assertEquals(replacement, harness.orchestrator.currentAttempt)
            assertEquals(
                setOf(replacementChannel.channelId),
                harness.orchestrator.activeControlAttempt?.channelIds
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        replacement.id,
                        replacementChannel.channelId,
                        replacementChannel.wireRequestKey
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
        }
    }

    @Test
    fun duplicateOwnerAcceptIsIdempotent() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            val owner = requesterChannel(CHANNEL_A, attempt)
            harness.start(attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    attempt.id,
                    owner.channelId,
                    owner.wireRequestKey
                )
            )
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        owner.channelId,
                        owner.wireRequestKey
                    )
                )
            )
            assertFalse(harness.hasPendingEffect())
            assertEquals(owner.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)
        }
    }

    @Test
    fun firstAttemptTerminalOutcomeWinsMailboxOrder() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val channel = requesterChannel(CHANNEL_A, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, channel)
            )
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectRejected(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey,
                        RejectReason.USER_REJECTED,
                        retryable = false
                    )
                )
            )
            harness.nextEffect()
            harness.nextEffect()
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectAccepted(
                        RUNTIME_A,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey
                    )
                )
            )
            val close = harness.nextEffect() as SessionEffect.CloseControlChannel
            assertEquals(attempt.targetLock, close.targetLock)
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun acceptedOutcomeIsNotOverwrittenByALateNonOwnerReject() = runBlocking {
        harness().use { harness ->
            val attempt = outboundAttempt()
            harness.start(attempt)
            val nonOwner = requesterChannel(CHANNEL_A, attempt)
            val owner = requesterChannel(CHANNEL_B, attempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, nonOwner)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    attempt.id,
                    owner.channelId,
                    owner.wireRequestKey
                )
            )
            harness.nextEffect()
            harness.nextEffect()

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteConnectRejected(
                        RUNTIME_A,
                        attempt.id,
                        nonOwner.channelId,
                        nonOwner.wireRequestKey,
                        RejectReason.USER_REJECTED,
                        retryable = false
                    )
                )
            )

            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)
            assertEquals(owner.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
        }
    }

    @Test
    fun activeConnectedRequestSupersedesADuplicateChannelWithoutAnotherConfirmation() = runBlocking {
        harness().use { harness ->
            val owner = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, owner)
            acceptIncoming(harness)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.MediaChannelSelected(
                    RUNTIME_B,
                    attempt.id,
                    owner.wireRequestKey,
                    owner.channelId
                )
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    attempt.id,
                    owner.channelId,
                    SignalingMessageTypeV2.CONNECT_ACCEPT
                )
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    RUNTIME_B,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    500L
                )
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Connected)

            val duplicate = responderChannel(CHANNEL_B)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, duplicate)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        duplicate.channelId,
                        duplicate.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        600L
                    )
                )
            )

            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.SUPERSEDED_CHANNEL, reject.reason)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connected)
            assertEquals(owner.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)
        }
    }

    @Test
    fun connectedThirdPartyRequestReceivesBusyWithoutReplacingMediaOwner() = runBlocking {
        harness().use { harness ->
            val owner = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, owner)
            acceptIncoming(harness)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.MediaChannelSelected(
                    RUNTIME_B,
                    attempt.id,
                    owner.wireRequestKey,
                    owner.channelId
                )
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    attempt.id,
                    owner.channelId,
                    SignalingMessageTypeV2.CONNECT_ACCEPT
                )
            )
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    RUNTIME_B,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    500L
                )
            )
            val connectedAttempt = requireNotNull(harness.orchestrator.currentAttempt)

            val thirdParty = responderChannel(
                channelId = CHANNEL_C,
                requesterDeviceId = DEVICE_C,
                requesterRuntime = RUNTIME_C,
                responderDeviceId = DEVICE_B,
                attemptId = ATTEMPT_C
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, thirdParty)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        thirdParty.channelId,
                        thirdParty.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        600L
                    )
                )
            )

            val busy = harness.nextEffect() as SessionEffect.SendBusy
            assertEquals(thirdParty.channelId, busy.channelId)
            assertEquals(ConnectionAttemptId(ATTEMPT_C), busy.attemptId)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        busy.attemptId,
                        busy.channelId,
                        SignalingMessageTypeV2.BUSY
                    )
                )
            )

            val close = harness.nextEffect() as SessionEffect.CloseControlChannel
            assertEquals(thirdParty.channelId, close.channelId)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connected)
            assertEquals(connectedAttempt, harness.orchestrator.currentAttempt)
            assertEquals(owner.wireRequestKey, harness.orchestrator.activeControlAttempt?.wireRequestKey)
            assertEquals(owner.channelId, harness.orchestrator.activeControlAttempt?.mediaOwnerChannelId)
        }
    }

    @Test
    fun rejectedRequestReplayUsesTombstoneWithoutAnotherConfirmation() = runBlocking {
        harness().use { harness ->
            val first = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, first)
            rejectIncoming(harness)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        first.wireRequestKey.attemptId,
                    first.channelId,
                    SignalingMessageTypeV2.CONNECT_REJECT
                )
            )
            harness.nextEffect()
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)

            val replay = responderChannel(CHANNEL_B)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, replay)
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        replay.channelId,
                        replay.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        200L
                    )
                )
            )
            val replayEffect = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.USER_REJECTED, replayEffect.reason)
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }
    }

    @Test
    fun pairedInboundRequestAutoAcceptsWithoutConfirmationSurface() = runBlocking {
        harness(setOf(DEVICE_A)).use { harness ->
            val channel = responderChannel(CHANNEL_A)
            harness.startRuntime()
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ControlChannelVerified(RUNTIME_B, channel)
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        channel.channelId,
                        channel.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )

            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            val select = harness.nextEffect() as SessionEffect.SelectMediaChannel
            assertEquals(setOf(channel.channelId), select.cohort.channelIds)
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)
            assertEquals(10_100L, harness.orchestrator.currentAttempt?.deadlineElapsedRealtimeMs)
        }
    }

    @Test
    fun backgroundRequestUsesNotificationWhenAvailable() = runBlocking {
        harness().use { harness ->
            val channel = responderChannel(CHANNEL_A)
            harness.startRuntime(
                ConfirmationAvailability(
                    appForeground = false,
                    notificationAvailable = true
                )
            )

            val prompt = registerIncomingRequest(
                harness,
                channel,
                expectedSurface = ConfirmationSurface.NOTIFICATION
            )

            assertEquals(channel.channelId, prompt.channelId)
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
        }
    }

    @Test
    fun backgroundRequestWithoutNotificationPermissionFailsClosed() = runBlocking {
        harness().use { harness ->
            val channel = responderChannel(CHANNEL_A)
            harness.startRuntime(ConfirmationAvailability.UNAVAILABLE)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, channel)
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingConnectRequest(
                        RUNTIME_B,
                        channel.channelId,
                        channel.wireRequestKey,
                        RequestTrigger.USER,
                        Transport.LAN,
                        100L
                    )
                )
            )

            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.CONFIRMATION_UNAVAILABLE, reject.reason)
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertNull(harness.orchestrator.currentAttempt)
            assertNull(harness.orchestrator.pendingInboundRequest)
            assertNull(harness.orchestrator.activeControlAttempt)
        }
    }

    @Test
    fun acceptAtDecisionDeadlineExpiresWithoutCreatingAttempt() = runBlocking {
        harness().use { harness ->
            harness.startRuntime()
            val prompt = registerIncomingRequest(harness, responderChannel(CHANNEL_A))

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingAccepted(
                        prompt.runtimeSessionId,
                        prompt.attemptId,
                        prompt.channelId,
                        prompt.actionNonce,
                        prompt.decisionDeadlineElapsedMs
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CancelIncomingConfirmation)
            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.TIMEOUT, reject.reason)
            assertNull(harness.orchestrator.currentAttempt)
            assertEquals(
                AttemptOutcome.TIMED_OUT,
                harness.orchestrator.pendingInboundRequest?.terminalOutcome
            )

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingDecisionTimedOut(
                        prompt.runtimeSessionId,
                        prompt.attemptId,
                        prompt.channelId,
                        prompt.actionNonce,
                        prompt.decisionDeadlineElapsedMs
                    )
                )
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
        }
    }

    @Test
    fun timeoutQueuedBeforeAcceptWinsAndLateAcceptCannotReplaceIt() = runBlocking {
        harness().use { harness ->
            harness.startRuntime()
            val prompt = registerIncomingRequest(harness, responderChannel(CHANNEL_A))

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingDecisionTimedOut(
                        prompt.runtimeSessionId,
                        prompt.attemptId,
                        prompt.channelId,
                        prompt.actionNonce,
                        prompt.decisionDeadlineElapsedMs
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CancelIncomingConfirmation)
            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.TIMEOUT, reject.reason)

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingAccepted(
                        prompt.runtimeSessionId,
                        prompt.attemptId,
                        prompt.channelId,
                        prompt.actionNonce,
                        prompt.decisionDeadlineElapsedMs - 1L
                    )
                )
            )
            assertEquals(
                AttemptOutcome.TIMED_OUT,
                harness.orchestrator.pendingInboundRequest?.terminalOutcome
            )
            assertNull(harness.orchestrator.currentAttempt)
        }
    }

    @Test
    fun acceptCreatedAfterDeadlineFinalizesTimeout() = runBlocking {
        harness().use { harness ->
            harness.startRuntime()
            val prompt = registerIncomingRequest(harness, responderChannel(CHANNEL_A))

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingAccepted(
                        prompt.runtimeSessionId,
                        prompt.attemptId,
                        prompt.channelId,
                        prompt.actionNonce,
                        prompt.decisionDeadlineElapsedMs + 1L
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CancelIncomingConfirmation)
            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.TIMEOUT, reject.reason)
            assertEquals(
                AttemptOutcome.TIMED_OUT,
                harness.orchestrator.pendingInboundRequest?.terminalOutcome
            )
            assertNull(harness.orchestrator.currentAttempt)
        }
    }

    @Test
    fun foregroundToBackgroundMigrationRotatesNonceWithoutExtendingDeadline() = runBlocking {
        harness().use { harness ->
            harness.startRuntime()
            val original = registerIncomingRequest(harness, responderChannel(CHANNEL_A))

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ConfirmationAvailabilityChanged(
                        RUNTIME_B,
                        ConfirmationAvailability(
                            appForeground = false,
                            notificationAvailable = true
                        )
                    )
                )
            )
            val cancel = harness.nextEffect() as SessionEffect.CancelIncomingConfirmation
            val publish = harness.nextEffect() as SessionEffect.PublishIncomingConfirmation
            val migrated = publish.prompt
            assertEquals(original.actionNonce, cancel.actionNonce)
            assertEquals(ConfirmationSurface.NOTIFICATION, migrated.surface)
            assertEquals(original.decisionDeadlineElapsedMs, migrated.decisionDeadlineElapsedMs)
            assertFalse(original.actionNonce == migrated.actionNonce)

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingRejected(
                        original.runtimeSessionId,
                        original.attemptId,
                        original.channelId,
                        original.actionNonce,
                        100L
                    )
                )
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ConfirmationAvailabilityChanged(
                        RUNTIME_B,
                        ConfirmationAvailability.UNAVAILABLE
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.CancelIncomingConfirmation)
            val reject = harness.nextEffect() as SessionEffect.SendConnectReject
            assertEquals(RejectReason.CONFIRMATION_UNAVAILABLE, reject.reason)
        }
    }

    private suspend fun registerIncomingRequest(
        harness: Harness,
        channel: VerifiedControlChannel,
        expectedSurface: ConfirmationSurface = ConfirmationSurface.IN_APP,
        preferredTransportHint: Transport? = null,
        occurredAtElapsedMs: Long = 100L
    ): IncomingConfirmationPrompt {
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_B, channel)
            )
        )
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.IncomingConnectRequest(
                    RUNTIME_B,
                    channel.channelId,
                    channel.wireRequestKey,
                    RequestTrigger.USER,
                    preferredTransportHint,
                    occurredAtElapsedMs
                )
            )
        )
        assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
        val prompt = (harness.nextEffect() as SessionEffect.PublishIncomingConfirmation).prompt
        assertEquals(expectedSurface, prompt.surface)
        assertEquals(occurredAtElapsedMs + 15_000L, prompt.decisionDeadlineElapsedMs)
        harness.currentPrompt = prompt
        return prompt
    }

    private suspend fun acceptIncoming(harness: Harness): Boolean {
        val prompt = requireNotNull(harness.currentPrompt)
        val accepted = harness.orchestrator.dispatchAndAwait(
            SessionEvent.IncomingAccepted(
                prompt.runtimeSessionId,
                prompt.attemptId,
                prompt.channelId,
                prompt.actionNonce,
                occurredAtElapsedMs = 100L
            )
        )
        val cancel = harness.nextEffect() as SessionEffect.CancelIncomingConfirmation
        assertEquals(prompt.actionNonce, cancel.actionNonce)
        assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
        harness.currentPrompt = null
        return accepted
    }

    private suspend fun rejectIncoming(harness: Harness): Boolean {
        val prompt = requireNotNull(harness.currentPrompt)
        val accepted = harness.orchestrator.dispatchAndAwait(
            SessionEvent.IncomingRejected(
                prompt.runtimeSessionId,
                prompt.attemptId,
                prompt.channelId,
                prompt.actionNonce,
                occurredAtElapsedMs = 100L
            )
        )
        val cancel = harness.nextEffect() as SessionEffect.CancelIncomingConfirmation
        assertEquals(prompt.actionNonce, cancel.actionNonce)
        harness.currentPrompt = null
        return accepted
    }

    private suspend fun beginConnectedLocalDisconnect(
        harness: Harness
    ): Pair<ConnectionAttempt, VerifiedControlChannel> {
        val attempt = outboundAttempt()
        harness.start(attempt)
        val owner = requesterChannel(CHANNEL_A, attempt)
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, owner)
            )
        )
        assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    attempt.id,
                    owner.channelId,
                    owner.wireRequestKey
                )
            )
        )
        assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    RUNTIME_A,
                    attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    500L
                )
            )
        )
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.DisconnectRequested(RUNTIME_A, attempt.id)
            )
        )
        assertTrue(harness.nextEffect() is SessionEffect.SendDisconnect)
        assertEquals(SignalingAttemptPhase.TERMINATING, activePhase(harness))
        assertTrue(harness.orchestrator.state.value is IntercomState.Connected)
        return attempt to owner
    }

    private suspend fun assertLocalDisconnectCleanup(
        harness: Harness,
        attempt: ConnectionAttempt
    ) {
        val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
        assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
        assertTrue(
            cleanup.any { it == SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt) }
        )
        assertFalse(cleanup.any { it is SessionEffect.RestartDiscovery })
        assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        assertNull(harness.orchestrator.currentAttempt)
        assertEquals(
            ConnectionAttemptTerminalOutcome.SUCCESS,
            harness.orchestrator.terminalOutcome(attempt.id)
        )
    }

    private fun activePhase(harness: Harness): SignalingAttemptPhase =
        requireNotNull(harness.orchestrator.activeControlAttempt).phase

    private fun outboundAttempt(
        attemptId: String = ATTEMPT_A,
        channelPlan: ChannelPlan = ChannelPlan.single(Transport.LAN)
    ) = ConnectionAttempt(
        id = ConnectionAttemptId(attemptId),
        runtimeSessionId = RUNTIME_A,
        targetLock = TargetLock(DEVICE_B, RUNTIME_B),
        trigger = ConnectionTrigger.USER,
        channelPlan = channelPlan,
        deadlineElapsedRealtimeMs = 10_000L
    )

    private fun requesterChannel(
        channelId: String,
        attempt: ConnectionAttempt,
        transport: Transport = Transport.LAN
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(channelId),
        transport = transport,
        requestRole = RequestRole.REQUESTER,
        wireRequestKey = WireRequestKey(
            DeviceId.parse(DEVICE_A),
            RUNTIME_A,
            attempt.id,
            DeviceId.parse(DEVICE_B)
        ),
        targetLock = attempt.targetLock,
        peer = verifiedPeer(DEVICE_B, RUNTIME_B),
        originatingAttempt = attempt
    )

    private fun responderChannel(
        channelId: String,
        requesterDeviceId: String = DEVICE_A,
        requesterRuntime: RuntimeSessionId = RUNTIME_A,
        responderDeviceId: String = DEVICE_B,
        attemptId: String = ATTEMPT_A,
        originatingAttempt: ConnectionAttempt? = null,
        transport: Transport = Transport.LAN
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(channelId),
        transport = transport,
        requestRole = RequestRole.RESPONDER,
        wireRequestKey = WireRequestKey(
            DeviceId.parse(requesterDeviceId),
            requesterRuntime,
            ConnectionAttemptId(attemptId),
            DeviceId.parse(responderDeviceId)
        ),
        targetLock = TargetLock(requesterDeviceId, requesterRuntime),
        peer = verifiedPeer(requesterDeviceId, requesterRuntime),
        originatingAttempt = originatingAttempt
    )

    private fun verifiedPeer(deviceId: String, runtimeSessionId: RuntimeSessionId) = PeerIdentity(
        deviceId = deviceId,
        nickname = "Rider",
        deviceName = "Phone",
        runtimeSessionId = runtimeSessionId,
        isDeviceIdVerified = true
    )

    private suspend fun enterRecovery(harness: Harness): IntercomState.Recovering {
        val connectedAttempt = outboundAttempt(ATTEMPT_C)
        harness.start(connectedAttempt)
        val connectedChannel = requesterChannel(CHANNEL_C, connectedAttempt)
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.ControlChannelVerified(RUNTIME_A, connectedChannel)
            )
        )
        assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.RemoteConnectAccepted(
                    RUNTIME_A,
                    connectedAttempt.id,
                    connectedChannel.channelId,
                    connectedChannel.wireRequestKey
                )
            )
        )
        assertTrue(harness.nextEffect() is SessionEffect.StartWebRtc)
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    RUNTIME_A,
                    connectedAttempt.id,
                    WebRtcConnectionState.CONNECTED,
                    500L
                )
            )
        )
        assertTrue(
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.SignalingDisconnected(RUNTIME_A, connectedAttempt.id)
            )
        )
        assertTrue(harness.nextEffect() is SessionEffect.RestartDiscovery)
        assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
        return harness.orchestrator.state.value as IntercomState.Recovering
    }

    private fun harness(
        pairedDeviceIds: Set<String> = emptySet(),
        attemptIdFactory: () -> ConnectionAttemptId = ConnectionAttemptId::create,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ) = Harness(pairedDeviceIds, attemptIdFactory, dispatcher)

    private class Harness(
        pairedDeviceIds: Set<String>,
        attemptIdFactory: () -> ConnectionAttemptId,
        dispatcher: CoroutineDispatcher
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val effectQueue = Channel<SessionEffect>(Channel.UNLIMITED)
        private val clock = FakeMonotonicClock(MonotonicTimestamp(100L))
        var currentPrompt: IncomingConfirmationPrompt? = null
        val orchestrator = SessionOrchestrator(
            pairingRepository = NoOpPairingRepository(pairedDeviceIds),
            dispatcher = dispatcher,
            elapsedRealtime = { clock.now().elapsedRealtimeMs },
            attemptIdFactory = attemptIdFactory
        )

        init {
            scope.launch {
                orchestrator.effects.collect(effectQueue::send)
            }
        }

        suspend fun startRuntime(
            availability: ConfirmationAvailability = ConfirmationAvailability(
                appForeground = true,
                notificationAvailable = true
            )
        ) {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(RUNTIME_B)))
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.ConfirmationAvailabilityChanged(
                        RUNTIME_B,
                        availability
                    )
                )
            )
        }

        suspend fun start(attempt: ConnectionAttempt) {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(attempt.runtimeSessionId)))
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attempt)))
        }

        suspend fun startPresence(availableTransports: Set<Transport>): ConnectionAttempt {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(RUNTIME_A)))
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.ConnectPresenceRequested(
                        runtimeSessionId = RUNTIME_A,
                        targetDeviceId = DEVICE_B,
                        targetSessionId = RUNTIME_B,
                        availableTransports = availableTransports
                    )
                )
            )
            return requireNotNull(orchestrator.currentAttempt)
        }

        suspend fun nextEffect(): SessionEffect = withTimeout(1_000L) { effectQueue.receive() }

        fun hasPendingEffect(): Boolean = effectQueue.tryReceive().isSuccess

        fun advanceBy(durationMs: Long) = clock.advanceBy(durationMs)

        suspend fun dispatchConcurrently(
            event: (Int) -> SessionEvent
        ): List<Boolean> = coroutineScope {
            List(CONCURRENT_EVENT_COUNT) { index ->
                async(Dispatchers.Default) {
                    orchestrator.dispatchAndAwait(event(index))
                }
            }.awaitAll()
        }

        override fun close() {
            orchestrator.close()
            scope.cancel()
        }
    }

    private class NoOpPairingRepository(
        private val pairedDeviceIds: Set<String> = emptySet()
    ) : PairingRepository {
        override fun observeAll(): Flow<List<PairingRecord>> = flowOf(emptyList())
        override suspend fun getAll(): List<PairingRecord> = emptyList()
        override suspend fun getByDeviceId(deviceId: String): PairingRecord? =
            deviceId.takeIf(pairedDeviceIds::contains)?.let {
                PairingRecord(
                    remoteDeviceId = it,
                    remoteNickname = "Paired Rider",
                    deviceName = "Phone",
                    localAlias = "",
                    shortCode = "0000",
                    pairedAt = 1L,
                    lastConnectedAt = 1L,
                    isPreferred = false,
                    lastTransport = Transport.LAN.name,
                    failureCount = 0
                )
            }
        override suspend fun saveConnectedPeer(record: PairingRecord) = Unit
        override suspend fun setPreferred(deviceId: String): Boolean = false
        override suspend fun clearPreferred() = Unit
        override suspend fun updateLastConnectedAt(
            deviceId: String,
            connectedAt: Long,
            transport: String?
        ): Boolean = false
        override suspend fun incrementFailureCount(deviceId: String): Boolean = false
        override suspend fun clearFailureCount(deviceId: String): Boolean = false
        override suspend fun forget(deviceId: String): Boolean = false
    }

    private companion object {
        const val CONCURRENT_EVENT_COUNT = 128
        val RUNTIME_A = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        val RUNTIME_B = RuntimeSessionId("10000000-0000-4000-8000-000000000002")
        val RUNTIME_C = RuntimeSessionId("10000000-0000-4000-8000-000000000003")
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val DEVICE_C = "c0000000-0000-4000-8000-000000000003"
        const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
        const val ATTEMPT_B = "20000000-0000-4000-8000-000000000002"
        const val ATTEMPT_C = "20000000-0000-4000-8000-000000000003"
        const val RECOVERY_ATTEMPT = "30000000-0000-4000-8000-000000000001"
        const val CHANNEL_A = "40000000-0000-4000-8000-000000000001"
        const val CHANNEL_B = "40000000-0000-4000-8000-000000000002"
        const val CHANNEL_C = "40000000-0000-4000-8000-000000000003"
    }

}
