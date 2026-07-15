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
            assertEquals(AttemptOutcome.ACCEPTED, harness.orchestrator.activeControlAttempt?.terminalOutcome)
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
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingAccepted(RUNTIME_B, attempt.id)
                )
            )
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
            harness.orchestrator.dispatchAndAwait(SessionEvent.IncomingAccepted(RUNTIME_B, attempt.id))
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
            harness.orchestrator.dispatchAndAwait(SessionEvent.IncomingAccepted(RUNTIME_B, attempt.id))
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
            harness.orchestrator.dispatchAndAwait(SessionEvent.IncomingAccepted(RUNTIME_B, attempt.id))
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

            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.IncomingRejected(RUNTIME_B, attempt.id)
                )
            )
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
            harness.orchestrator.dispatchAndAwait(SessionEvent.IncomingAccepted(RUNTIME_B, attempt.id))
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
    fun rejectedRequestReplayUsesTombstoneWithoutAnotherConfirmation() = runBlocking {
        harness().use { harness ->
            val first = responderChannel(CHANNEL_A)
            harness.startRuntime()
            registerIncomingRequest(harness, first)
            val attempt = requireNotNull(harness.orchestrator.currentAttempt)
            harness.orchestrator.dispatchAndAwait(
                SessionEvent.IncomingRejected(RUNTIME_B, attempt.id)
            )
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

    private suspend fun registerIncomingRequest(
        harness: Harness,
        channel: VerifiedControlChannel
    ) {
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

    private fun recovery() = RecoveryAttemptSpec(
        ConnectionAttemptId(RECOVERY_ATTEMPT),
        20_000L
    )

    private fun harness() = Harness()

    private class Harness : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val effectQueue = Channel<SessionEffect>(Channel.UNLIMITED)
        val orchestrator = SessionOrchestrator(
            pairingRepository = NoOpPairingRepository(),
            dispatcher = Dispatchers.Unconfined,
            elapsedRealtime = { 100L }
        )

        init {
            scope.launch {
                orchestrator.effects.collect(effectQueue::send)
            }
        }

        suspend fun startRuntime() {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(RUNTIME_B)))
        }

        suspend fun start(attempt: ConnectionAttempt) {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(attempt.runtimeSessionId)))
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attempt)))
        }

        suspend fun nextEffect(): SessionEffect = withTimeout(1_000L) { effectQueue.receive() }

        override fun close() {
            orchestrator.close()
            scope.cancel()
        }
    }

    private class NoOpPairingRepository : PairingRepository {
        override fun observeAll(): Flow<List<PairingRecord>> = flowOf(emptyList())
        override suspend fun getAll(): List<PairingRecord> = emptyList()
        override suspend fun getByDeviceId(deviceId: String): PairingRecord? = null
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
