package com.kuma.motointercom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertEquals(10_100L, start.attempt.deadlineElapsedRealtimeMs)
            assertEquals(start.attempt, harness.orchestrator.currentAttempt)
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
        }
    }

    @Test
    fun requesterDecisionWindowDoesNotConsumeMediaDeadline() = runBlocking {
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

            val waiting = harness.nextEffect() as SessionEffect.RescheduleAttemptDeadline
            assertEquals(25_100L, waiting.attempt.deadlineElapsedRealtimeMs)
            assertEquals(waiting.attempt, harness.orchestrator.currentAttempt)
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
            assertEquals(waiting.attempt, harness.orchestrator.currentAttempt)
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
            assertEquals(10_100L, start.attempt.deadlineElapsedRealtimeMs)
            assertEquals(start.attempt, harness.orchestrator.currentAttempt)
        }
    }

    @Test
    fun queuedTimeoutCannotTerminateAttemptAfterDeadlineRebase() = runBlocking {
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
            val waiting = harness.nextEffect() as SessionEffect.RescheduleAttemptDeadline

            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertEquals(waiting.attempt, harness.orchestrator.currentAttempt)

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
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        waiting.attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertEquals(start.attempt, harness.orchestrator.currentAttempt)

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

            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            assertEquals(Long.MAX_VALUE, attempt.deadlineElapsedRealtimeMs)
            assertTrue(acceptIncoming(harness))
            assertEquals(10_100L, harness.orchestrator.currentAttempt?.deadlineElapsedRealtimeMs)
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            acceptIncoming(harness)
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            acceptIncoming(harness)
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
                        DisconnectReason.parse("REMOTE_CANCELED"),
                        recovery()
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
                        DisconnectReason.parse("REMOTE_CANCELED"),
                        recovery()
                    )
                )
            )
            val effects = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(effects.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            acceptIncoming(harness)
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
                        recovery(),
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
                        recovery(),
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
                    500L,
                    recovery()
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
            assertTrue(cleanup.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
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

            val effects = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(effects.any { it is SessionEffect.CloseControlChannel })
            assertTrue(effects.any { it is SessionEffect.SelectMediaChannel })
            assertFalse(effects.any { it is SessionEffect.SendBusy })
            assertEquals(ConnectionAttemptId(ATTEMPT_A), harness.orchestrator.currentAttempt?.id)
            assertEquals(DEVICE_B, harness.orchestrator.currentAttempt?.targetDeviceId)
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)

            assertTrue(rejectIncoming(harness))
            val sends = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(sends.all { it is SessionEffect.SendConnectReject })
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
            assertEquals(
                setOf(first.channelId, second.channelId),
                harness.orchestrator.activeControlAttempt?.pendingTerminalChannels
            )

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingMessageSent(
                        RUNTIME_B,
                        attempt.id,
                        first.channelId,
                        SignalingMessageTypeV2.CONNECT_REJECT
                    )
                )
            )
            assertEquals(
                setOf(second.channelId),
                harness.orchestrator.activeControlAttempt?.pendingTerminalChannels
            )
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
            assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)

            harness.orchestrator.dispatchAndAwait(
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    attempt.id,
                    second.channelId,
                    SignalingMessageTypeV2.CONNECT_REJECT
                )
            )
            val cleanup = listOf(harness.nextEffect(), harness.nextEffect())
            assertTrue(cleanup.any { it is SessionEffect.CloseControlChannel })
            assertTrue(cleanup.any { it is SessionEffect.AbortAttemptAndResumeDiscovery })
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
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
                        recovery(),
                        "LAN closed"
                    )
                )
            )
            assertEquals(
                second.channelId,
                harness.orchestrator.activeControlAttempt?.confirmationChannelId
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
            assertTrue(harness.nextEffect() is SessionEffect.CloseControlChannel)
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            acceptIncoming(harness)
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
                    500L,
                    recovery()
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            acceptIncoming(harness)
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
                    500L,
                    recovery()
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
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            rejectIncoming(harness)
            harness.nextEffect()
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.SignalingMessageSent(
                    RUNTIME_B,
                    attempt.id,
                    first.channelId,
                    SignalingMessageTypeV2.CONNECT_REJECT
                )
            )
            harness.nextEffect()
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
            assertEquals(SignalingAttemptPhase.TERMINATING, activePhase(harness))
        }
    }

    @Test
    fun acceptQueuedBeforeTimeoutWinsWhenItsLocalTimestampMeetsDeadline() = runBlocking {
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
            assertTrue(harness.nextEffect() is SessionEffect.SelectMediaChannel)

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
            assertTrue(harness.orchestrator.state.value is IntercomState.Connecting)
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
            assertEquals(AttemptOutcome.TIMED_OUT, harness.orchestrator.activeControlAttempt?.terminalOutcome)
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
            assertEquals(AttemptOutcome.TIMED_OUT, harness.orchestrator.activeControlAttempt?.terminalOutcome)
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
        expectedSurface: ConfirmationSurface = ConfirmationSurface.IN_APP
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
                    Transport.LAN,
                    100L
                )
            )
        )
        assertTrue(harness.orchestrator.state.value is IntercomState.IncomingConfirmation)
        val prompt = (harness.nextEffect() as SessionEffect.PublishIncomingConfirmation).prompt
        assertEquals(expectedSurface, prompt.surface)
        assertEquals(15_100L, prompt.decisionDeadlineElapsedMs)
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

    private fun activePhase(harness: Harness): SignalingAttemptPhase =
        requireNotNull(harness.orchestrator.activeControlAttempt).phase

    private fun outboundAttempt(attemptId: String = ATTEMPT_A) = ConnectionAttempt(
        id = ConnectionAttemptId(attemptId),
        runtimeSessionId = RUNTIME_A,
        targetLock = TargetLock(DEVICE_B, RUNTIME_B),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 10_000L
    )

    private fun requesterChannel(
        channelId: String,
        attempt: ConnectionAttempt
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(channelId),
        transport = Transport.LAN,
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
        originatingAttempt: ConnectionAttempt? = null
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(channelId),
        transport = Transport.LAN,
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

    private fun recovery(): Long = 20_000L

    private fun harness(pairedDeviceIds: Set<String> = emptySet()) = Harness(pairedDeviceIds)

    private class Harness(pairedDeviceIds: Set<String>) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val effectQueue = Channel<SessionEffect>(Channel.UNLIMITED)
        var currentPrompt: IncomingConfirmationPrompt? = null
        val orchestrator = SessionOrchestrator(
            pairingRepository = NoOpPairingRepository(pairedDeviceIds),
            dispatcher = Dispatchers.Unconfined,
            elapsedRealtime = { 100L }
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

        suspend fun nextEffect(): SessionEffect = withTimeout(1_000L) { effectQueue.receive() }

        fun hasPendingEffect(): Boolean = effectQueue.tryReceive().isSuccess

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
