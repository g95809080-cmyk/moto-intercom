package com.kuma.motointercom

import java.util.UUID

internal data class SignalingControlDecision(
    val accepted: Boolean,
    val state: IntercomState? = null,
    val effects: List<SessionEffect> = emptyList()
)

internal class SignalingControlCoordinator(
    private val elapsedRealtime: () -> Long,
    private val attemptTimeoutMs: Long,
    private val confirmationTimeoutMs: Long = 15_000L,
    private val actionNonce: () -> String = { UUID.randomUUID().toString() }
) {
    init {
        require(attemptTimeoutMs > 0L) { "Attempt timeout must be positive" }
        require(confirmationTimeoutMs > 0L) { "Confirmation timeout must be positive" }
    }
    private val channels = linkedMapOf<ControlChannelId, VerifiedControlChannel>()
    private val completedAttempts = linkedMapOf<WireRequestKey, CompletedWireAttempt>()
    @Volatile
    private var active: AttemptChannelSet? = null

    internal val activeAttempt: AttemptChannelSet?
        get() = active

    fun handle(
        current: IntercomState,
        event: SessionEvent,
        incomingPolicy: IncomingRequestPolicy? = null
    ): SignalingControlDecision? {
        pruneCompleted()
        return when (event) {
            is SessionEvent.ControlChannelVerified -> controlChannelVerified(current, event)
            is SessionEvent.IncomingConnectRequest -> incomingConnectRequest(
                current,
                event,
                requireNotNull(incomingPolicy) { "Incoming request policy is required" }
            )
            is SessionEvent.RemoteConnectAccepted -> remoteConnectAccepted(current, event)
            is SessionEvent.RemoteConnectRejected -> remoteConnectRejected(current, event)
            is SessionEvent.RemoteBusy -> remoteBusy(current, event)
            is SessionEvent.RemoteDisconnect -> remoteDisconnect(current, event)
            is SessionEvent.MediaChannelSelected -> mediaChannelSelected(current, event)
            is SessionEvent.SignalingMessageSent -> signalingMessageSent(current, event)
            is SessionEvent.SignalingSendFailed -> signalingSendFailed(current, event)
            is SessionEvent.ChannelClosed -> channelClosed(current, event)
            is SessionEvent.ProtocolViolation -> protocolViolation(current, event)
            is SessionEvent.IncomingAccepted -> incomingAccepted(current, event)
            is SessionEvent.IncomingRejected -> incomingRejected(current, event)
            is SessionEvent.ConfirmationAvailabilityChanged -> confirmationAvailabilityChanged(
                current,
                event
            )
            is SessionEvent.IncomingDecisionTimedOut -> incomingDecisionTimedOut(current, event)
            is SessionEvent.ConfirmationSurfaceUnavailable -> confirmationSurfaceUnavailable(
                current,
                event
            )
            is SessionEvent.DisconnectRequested -> disconnectRequested(current, event)
            else -> null
        }
    }

    fun onProductTransition(
        previous: IntercomState,
        next: IntercomState,
        event: SessionEvent
    ) {
        val context = active
        if (context != null) {
            when (event) {
                is SessionEvent.WebRtcStateChanged -> when (event.state) {
                    WebRtcConnectionState.CONNECTED -> {
                        if (next is IntercomState.Connected && next.attempt == context.attempt) {
                            active = context.copy(phase = SignalingAttemptPhase.CONNECTED)
                        }
                    }
                    WebRtcConnectionState.DISCONNECTED,
                    WebRtcConnectionState.FAILED,
                    WebRtcConnectionState.CLOSED -> if (next.connectionAttemptOrNull() != context.attempt) {
                        rememberDisconnectedIfAccepted(context)
                        forgetActiveChannels(context)
                    }
                    WebRtcConnectionState.OTHER -> Unit
                }
                is SessionEvent.AttemptTimedOut -> if (
                    context.attempt.runtimeSessionId == event.runtimeSessionId &&
                    context.attempt.id == event.attemptId
                ) {
                    remember(
                        context.wireRequestKey,
                        AttemptOutcome.TIMED_OUT,
                        null
                    )
                    forgetActiveChannels(context)
                }
                else -> if (
                    previous.connectionAttemptOrNull() == context.attempt &&
                    next.connectionAttemptOrNull() != context.attempt
                ) {
                    rememberDisconnectedIfAccepted(context)
                    forgetActiveChannels(context)
                }
            }
        }
        if (next == IntercomState.Offline) {
            channels.clear()
            active = null
            completedAttempts.clear()
        }
    }

    private fun controlChannelVerified(
        current: IntercomState,
        event: SessionEvent.ControlChannelVerified
    ): SignalingControlDecision {
        val channel = event.channel
        if (
            current.runtimeSessionId != event.runtimeSessionId ||
            channel.channelId in channels
        ) {
            return rejected()
        }
        if (channel.requestRole == RequestRole.RESPONDER) {
            channels[channel.channelId] = channel
            return accepted()
        }

        val attempt = channel.originatingAttempt ?: return rejected()
        if (
            current.connectionAttemptOrNull() != attempt ||
            attempt.channelPlan.transport != channel.transport ||
            attempt.targetLock != channel.targetLock
        ) {
            return rejected()
        }

        val context = active
        if (context != null && context.wireRequestKey != channel.wireRequestKey) {
            return rejected()
        }
        if (context != null && context.phase != SignalingAttemptPhase.WAITING_REMOTE_DECISION) {
            return rejected()
        }
        channels[channel.channelId] = channel
        active = if (context == null) {
            AttemptChannelSet(
                wireRequestKey = channel.wireRequestKey,
                attempt = attempt,
                peer = channel.peer,
                channelIds = setOf(channel.channelId),
                phase = SignalingAttemptPhase.WAITING_REMOTE_DECISION
            )
        } else {
            context.copy(channelIds = context.channelIds + channel.channelId)
        }
        val nextState = when (current) {
            is IntercomState.Connecting -> current.copy(peer = channel.peer)
            is IntercomState.Recovering -> current.copy(peer = channel.peer)
            else -> current
        }
        return accepted(
            state = nextState,
            effects = listOf(
                SessionEffect.SendConnectRequest(
                    runtimeSessionId = attempt.runtimeSessionId,
                    attemptId = attempt.id,
                    channelId = channel.channelId,
                    trigger = attempt.trigger.toRequestTrigger(),
                    preferredTransportHint = attempt.channelPlan.transport
                )
            )
        )
    }

    private fun incomingConnectRequest(
        current: IntercomState,
        event: SessionEvent.IncomingConnectRequest,
        policy: IncomingRequestPolicy
    ): SignalingControlDecision {
        val channel = channels[event.channelId]
            ?.takeIf {
                it.requestRole == RequestRole.RESPONDER &&
                    it.wireRequestKey == event.wireRequestKey &&
                    current.runtimeSessionId == event.runtimeSessionId
            }
            ?: return rejected()

        val activeContext = active
        if (activeContext?.wireRequestKey == event.wireRequestKey) {
            return handleDuplicateActiveRequest(current, channel, activeContext)
        }
        completedAttempts[event.wireRequestKey]?.let { completed ->
            return replayCompleted(event.runtimeSessionId, channel.channelId, completed)
        }

        val currentAttempt = current.connectionAttemptOrNull()
        if (
            current is IntercomState.Connecting &&
            currentAttempt != null &&
            currentAttempt.targetLock == channel.targetLock
        ) {
            return acceptGlareWinner(current, channel, event.occurredAtElapsedMs)
        }
        if (current is IntercomState.Discovering) {
            return when {
                policy.paired -> beginPairedInboundConnection(current, channel)
                policy.confirmationAvailability.preferredSurface() != null ->
                    beginInboundConfirmation(
                        current,
                        channel,
                        requireNotNull(policy.confirmationAvailability.preferredSurface())
                    )
                else -> rejectInboundWithoutConfirmation(
                    current,
                    channel,
                    RejectReason.CONFIRMATION_UNAVAILABLE
                )
            }
        }
        return busyRequest(event.runtimeSessionId, event.wireRequestKey)
    }

    private fun handleDuplicateActiveRequest(
        current: IntercomState,
        channel: VerifiedControlChannel,
        context: AttemptChannelSet
    ): SignalingControlDecision {
        if (channel.transport != context.attempt.channelPlan.transport) {
            return accepted(
                effects = listOf(
                    rejectEffect(
                        context.attempt.runtimeSessionId,
                        context.attempt.id,
                        channel.channelId,
                        RejectReason.SUPERSEDED_CHANNEL,
                        retryable = false
                    )
                )
            )
        }
        if (context.phase == SignalingAttemptPhase.WAITING_LOCAL_DECISION) {
            active = context.copy(channelIds = context.channelIds + channel.channelId)
            return accepted(state = current)
        }
        return accepted(
            effects = listOf(
                rejectEffect(
                    context.attempt.runtimeSessionId,
                    context.attempt.id,
                    channel.channelId,
                    RejectReason.SUPERSEDED_CHANNEL,
                    retryable = false
                )
            )
        )
    }

    private fun beginInboundConfirmation(
        current: IntercomState.Discovering,
        channel: VerifiedControlChannel,
        surface: ConfirmationSurface
    ): SignalingControlDecision {
        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            deadlineElapsedRealtimeMs = Long.MAX_VALUE
        )
        val channelIds = eligibleChannels(channel.wireRequestKey, channel.transport)
        val context = AttemptChannelSet(
            wireRequestKey = channel.wireRequestKey,
            attempt = attempt,
            peer = channel.peer,
            channelIds = channelIds,
            phase = SignalingAttemptPhase.WAITING_LOCAL_DECISION,
            confirmationChannelId = channel.channelId,
            confirmationSurface = surface,
            confirmationActionNonce = actionNonce(),
            decisionDeadlineElapsedMs = elapsedRealtime() + confirmationTimeoutMs
        )
        active = context
        return accepted(
            state = IntercomState.IncomingConfirmation(attempt, channel.peer),
            effects = listOf(SessionEffect.PublishIncomingConfirmation(context.confirmationPrompt()))
        )
    }

    private fun beginPairedInboundConnection(
        current: IntercomState.Discovering,
        channel: VerifiedControlChannel
    ): SignalingControlDecision {
        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            deadlineElapsedRealtimeMs = elapsedRealtime() + attemptTimeoutMs
        )
        val eligible = eligibleChannels(channel.wireRequestKey, channel.transport)
        if (eligible.isEmpty()) return terminateAttempt(attempt)
        val cohort = SelectionCohort(channel.wireRequestKey, eligible, elapsedRealtime())
        active = AttemptChannelSet(
            wireRequestKey = channel.wireRequestKey,
            attempt = attempt,
            peer = channel.peer,
            channelIds = eligible,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            selectionCohort = cohort
        )
        return accepted(
            state = IntercomState.Connecting(attempt, channel.peer),
            effects = listOf(selectEffect(attempt, cohort))
        )
    }

    private fun rejectInboundWithoutConfirmation(
        current: IntercomState.Discovering,
        channel: VerifiedControlChannel,
        reason: RejectReason
    ): SignalingControlDecision {
        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            deadlineElapsedRealtimeMs = Long.MAX_VALUE
        )
        val context = AttemptChannelSet(
            wireRequestKey = channel.wireRequestKey,
            attempt = attempt,
            peer = channel.peer,
            channelIds = eligibleChannels(channel.wireRequestKey, channel.transport),
            phase = SignalingAttemptPhase.WAITING_LOCAL_DECISION
        )
        active = context
        return beginTerminalBroadcast(
            current = current,
            context = context,
            outcome = AttemptOutcome.REJECTED,
            response = SignalingMessageV2.ConnectReject(reason, retryable = false)
        )
    }

    private fun acceptGlareWinner(
        current: IntercomState.Connecting,
        channel: VerifiedControlChannel,
        occurredAtElapsedMs: Long
    ): SignalingControlDecision {
        val losingContext = active
        if (
            losingContext != null &&
            channel.wireRequestKey >= losingContext.wireRequestKey
        ) {
            val response = SignalingMessageV2.ConnectReject(
                RejectReason.GLARE_LOST,
                retryable = false
            )
            remember(channel.wireRequestKey, AttemptOutcome.GLARE_LOST, response)
            return accepted(
                effects = channels.values
                    .filter { it.wireRequestKey == channel.wireRequestKey }
                    .map {
                        rejectEffect(
                            current.runtimeSessionId,
                            it.wireRequestKey.attemptId,
                            it.channelId,
                            RejectReason.GLARE_LOST,
                            retryable = false
                        )
                    }
            )
        }

        val losingChannelIds = losingContext?.channelIds.orEmpty() + channels.values
            .filter {
                it.originatingAttempt == current.attempt &&
                    it.wireRequestKey != channel.wireRequestKey
            }
            .map { it.channelId }
        losingContext?.let {
            remember(it.wireRequestKey, AttemptOutcome.GLARE_LOST, null)
        }
        losingChannelIds.forEach(channels::remove)

        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            deadlineElapsedRealtimeMs = current.attempt.deadlineElapsedRealtimeMs
        )
        val eligible = eligibleChannels(channel.wireRequestKey, channel.transport)
        if (eligible.isEmpty()) {
            active = null
            return terminateAttempt(attempt)
        }
        val cohort = SelectionCohort(channel.wireRequestKey, eligible, occurredAtElapsedMs)
        active = AttemptChannelSet(
            wireRequestKey = channel.wireRequestKey,
            attempt = attempt,
            peer = channel.peer,
            channelIds = eligible,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            selectionCohort = cohort
        )
        val effects = losingChannelIds.map {
            SessionEffect.CloseControlChannel(
                runtimeSessionId = current.runtimeSessionId,
                attemptId = current.attempt.id,
                channelId = it
            )
        } + selectEffect(attempt, cohort)
        return accepted(
            state = IntercomState.Connecting(attempt, channel.peer),
            effects = effects
        )
    }

    private fun incomingAccepted(
        current: IntercomState,
        event: SessionEvent.IncomingAccepted
    ): SignalingControlDecision {
        val context = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        val decisionDeadline = requireNotNull(context.decisionDeadlineElapsedMs)
        if (event.occurredAtElapsedMs > decisionDeadline) {
            return timeoutIncomingConfirmation(current, context)
        }
        val acceptedAttempt = if (context.attempt.deadlineElapsedRealtimeMs == Long.MAX_VALUE) {
            context.attempt.copy(
                deadlineElapsedRealtimeMs = elapsedRealtime() + attemptTimeoutMs
            )
        } else {
            context.attempt
        }
        val eligible = context.channelIds.filterTo(linkedSetOf()) { channelId ->
            channels[channelId]?.transport == context.attempt.channelPlan.transport
        }
        if (eligible.isEmpty()) return finishAttemptImmediately(current, context)
        val cohort = SelectionCohort(context.wireRequestKey, eligible, elapsedRealtime())
        active = context.copy(
            attempt = acceptedAttempt,
            channelIds = eligible,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            confirmationChannelId = null,
            confirmationSurface = null,
            confirmationActionNonce = null,
            decisionDeadlineElapsedMs = null,
            selectionCohort = cohort
        )
        return accepted(
            state = IntercomState.Connecting(acceptedAttempt, context.peer),
            effects = listOf(
                cancelConfirmationEffect(context),
                selectEffect(acceptedAttempt, cohort)
            )
        )
    }

    private fun incomingRejected(
        current: IntercomState,
        event: SessionEvent.IncomingRejected
    ): SignalingControlDecision {
        val context = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        if (event.occurredAtElapsedMs > requireNotNull(context.decisionDeadlineElapsedMs)) {
            return timeoutIncomingConfirmation(current, context)
        }
        return beginTerminalBroadcast(
            current = current,
            context = context,
            outcome = AttemptOutcome.REJECTED,
            response = SignalingMessageV2.ConnectReject(
                RejectReason.USER_REJECTED,
                retryable = false
            )
        )
    }

    private fun incomingDecisionTimedOut(
        current: IntercomState,
        event: SessionEvent.IncomingDecisionTimedOut
    ): SignalingControlDecision {
        val context = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        if (event.occurredAtElapsedMs < requireNotNull(context.decisionDeadlineElapsedMs)) {
            return rejected()
        }
        return timeoutIncomingConfirmation(current, context)
    }

    private fun confirmationSurfaceUnavailable(
        current: IntercomState,
        event: SessionEvent.ConfirmationSurfaceUnavailable
    ): SignalingControlDecision {
        val context = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        return beginTerminalBroadcast(
            current = current,
            context = context,
            outcome = AttemptOutcome.REJECTED,
            response = SignalingMessageV2.ConnectReject(
                RejectReason.CONFIRMATION_UNAVAILABLE,
                retryable = false
            )
        )
    }

    private fun confirmationAvailabilityChanged(
        current: IntercomState,
        event: SessionEvent.ConfirmationAvailabilityChanged
    ): SignalingControlDecision {
        val context = active
            ?.takeIf {
                it.phase == SignalingAttemptPhase.WAITING_LOCAL_DECISION &&
                    it.attempt.runtimeSessionId == event.runtimeSessionId
            }
            ?: return accepted(state = current)
        if (current !is IntercomState.IncomingConfirmation || current.attempt != context.attempt) {
            return accepted(state = current)
        }
        val desiredSurface = event.availability.preferredSurface()
            ?: return beginTerminalBroadcast(
                current = current,
                context = context,
                outcome = AttemptOutcome.REJECTED,
                response = SignalingMessageV2.ConnectReject(
                    RejectReason.CONFIRMATION_UNAVAILABLE,
                    retryable = false
                )
            )
        if (context.confirmationSurface == desiredSurface) {
            return accepted(state = current)
        }

        val previousNonce = requireNotNull(context.confirmationActionNonce)
        val migrated = context.copy(
            confirmationSurface = desiredSurface,
            confirmationActionNonce = actionNonce()
        )
        active = migrated
        return accepted(
            state = current,
            effects = listOf(
                SessionEffect.CancelIncomingConfirmation(
                    context.attempt.runtimeSessionId,
                    context.attempt.id,
                    previousNonce
                ),
                SessionEffect.PublishIncomingConfirmation(migrated.confirmationPrompt())
            )
        )
    }

    private fun timeoutIncomingConfirmation(
        current: IntercomState,
        context: AttemptChannelSet
    ): SignalingControlDecision = beginTerminalBroadcast(
        current = current,
        context = context,
        outcome = AttemptOutcome.TIMED_OUT,
        response = SignalingMessageV2.ConnectReject(
            RejectReason.TIMEOUT,
            retryable = false
        )
    )

    private fun mediaChannelSelected(
        current: IntercomState,
        event: SessionEvent.MediaChannelSelected
    ): SignalingControlDecision {
        val context = active
            ?.takeIf {
                it.phase == SignalingAttemptPhase.SELECTING_MEDIA &&
                    it.attempt.runtimeSessionId == event.runtimeSessionId &&
                    it.attempt.id == event.attemptId &&
                    it.wireRequestKey == event.wireRequestKey
            }
            ?: return rejected()
        val cohort = context.selectionCohort ?: return rejected()
        val selected = event.channelId
        if (selected == null || selected !in cohort.channelIds || selected !in channels) {
            val remaining = cohort.channelIds.filterTo(linkedSetOf()) { it in channels }
            if (remaining.isEmpty()) return finishAttemptImmediately(current, context)
            val reducedCohort = cohort.copy(channelIds = remaining)
            active = context.copy(channelIds = remaining, selectionCohort = reducedCohort)
            return accepted(effects = listOf(selectEffect(context.attempt, reducedCohort)))
        }
        if (context.mediaOwnerChannelId != null) return rejected()

        active = context.copy(
            phase = SignalingAttemptPhase.ACCEPTING,
            mediaOwnerChannelId = selected
        )
        val effects = mutableListOf<SessionEffect>(
            SessionEffect.SendConnectAccept(
                runtimeSessionId = context.attempt.runtimeSessionId,
                attemptId = context.attempt.id,
                channelId = selected
            )
        )
        (cohort.channelIds - selected).forEach { channelId ->
            effects += rejectEffect(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                channelId,
                RejectReason.SUPERSEDED_CHANNEL,
                retryable = false
            )
        }
        return accepted(state = current, effects = effects)
    }

    private fun remoteConnectAccepted(
        current: IntercomState,
        event: SessionEvent.RemoteConnectAccepted
    ): SignalingControlDecision {
        val context = active
            ?.takeIf {
                it.phase == SignalingAttemptPhase.WAITING_REMOTE_DECISION &&
                    it.attempt.runtimeSessionId == event.runtimeSessionId &&
                    it.attempt.id == event.attemptId &&
                    it.wireRequestKey == event.wireRequestKey &&
                    event.channelId in it.channelIds
            }
            ?: return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId
            )
        if (context.mediaOwnerChannelId != null) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId
            )
        }
        val losing = context.channelIds - event.channelId
        losing.forEach(channels::remove)
        active = context.copy(
            channelIds = setOf(event.channelId),
            phase = SignalingAttemptPhase.ACCEPTED,
            mediaOwnerChannelId = event.channelId,
            terminalOutcome = AttemptOutcome.ACCEPTED
        )
        val effects = losing.map {
            SessionEffect.CloseControlChannel(
                runtimeSessionId = context.attempt.runtimeSessionId,
                attemptId = context.attempt.id,
                channelId = it
            )
        } + SessionEffect.StartWebRtc(
            runtimeSessionId = context.attempt.runtimeSessionId,
            attempt = context.attempt,
            channelId = event.channelId,
            role = WebRtcRole.OFFERER,
            peer = context.peer
        )
        return accepted(state = current, effects = effects)
    }

    private fun remoteConnectRejected(
        current: IntercomState,
        event: SessionEvent.RemoteConnectRejected
    ): SignalingControlDecision {
        val context = matchingActive(
            event.runtimeSessionId,
            event.attemptId,
            event.wireRequestKey,
            event.channelId
        ) ?: return closeConflictingChannel(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId
        )
        if (event.reason.scope == ResponseScope.CHANNEL) {
            return removeChannelAndContinueOrTerminate(current, context, event.channelId)
        }
        if (context.terminalOutcome == AttemptOutcome.ACCEPTED) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId
            )
        }
        remember(
            context.wireRequestKey,
            AttemptOutcome.REJECTED,
            SignalingMessageV2.ConnectReject(event.reason, event.retryable)
        )
        return finishAttemptImmediately(current, context)
    }

    private fun remoteBusy(
        current: IntercomState,
        event: SessionEvent.RemoteBusy
    ): SignalingControlDecision {
        val context = matchingActive(
            event.runtimeSessionId,
            event.attemptId,
            event.wireRequestKey,
            event.channelId
        ) ?: return closeConflictingChannel(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId
        )
        if (context.terminalOutcome == AttemptOutcome.ACCEPTED) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId
            )
        }
        remember(
            context.wireRequestKey,
            AttemptOutcome.BUSY,
            SignalingMessageV2.Busy(event.reason, event.retryAfterMs)
        )
        return finishAttemptImmediately(current, context)
    }

    private fun remoteDisconnect(
        current: IntercomState,
        event: SessionEvent.RemoteDisconnect
    ): SignalingControlDecision {
        val context = matchingActive(
            event.runtimeSessionId,
            event.attemptId,
            event.wireRequestKey,
            event.channelId
        ) ?: return closeConflictingChannel(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId
        )
        if (context.mediaOwnerChannelId != event.channelId) {
            return removeChannelAndContinueOrTerminate(current, context, event.channelId)
        }
        remember(context.wireRequestKey, AttemptOutcome.DISCONNECTED, null)
        return finishAttemptImmediately(current, context)
    }

    private fun disconnectRequested(
        current: IntercomState,
        event: SessionEvent.DisconnectRequested
    ): SignalingControlDecision? {
        val context = active ?: return null
        if (
            context.attempt.runtimeSessionId != event.runtimeSessionId ||
            context.attempt.id != event.attemptId ||
            current.connectionAttemptOrNull() != context.attempt
        ) {
            return rejected()
        }
        if (context.phase == SignalingAttemptPhase.TERMINATING) {
            return accepted(state = current)
        }

        val owner = context.mediaOwnerChannelId
            ?.takeIf { it in context.channelIds && it in channels }
        if (owner == null) {
            remember(context.wireRequestKey, AttemptOutcome.CANCELED, null)
            return finishAttemptImmediately(current, context)
        }

        val nonOwners = context.channelIds - owner
        nonOwners.forEach(channels::remove)
        remember(context.wireRequestKey, AttemptOutcome.DISCONNECTED, null)
        active = context.copy(
            channelIds = setOf(owner),
            phase = SignalingAttemptPhase.TERMINATING,
            confirmationChannelId = null,
            selectionCohort = null,
            terminalOutcome = AttemptOutcome.DISCONNECTED,
            pendingTerminalChannels = setOf(owner)
        )
        val effects = nonOwners.map {
            closeEffect(context.attempt.runtimeSessionId, context.attempt.id, it)
        } + SessionEffect.SendDisconnect(
            runtimeSessionId = context.attempt.runtimeSessionId,
            attemptId = context.attempt.id,
            channelId = owner,
            reason = DisconnectReason.parse(LOCAL_DISCONNECT_REASON)
        )
        return accepted(state = current, effects = effects)
    }

    private fun signalingMessageSent(
        current: IntercomState,
        event: SessionEvent.SignalingMessageSent
    ): SignalingControlDecision {
        val context = active
        if (
            event.type == SignalingMessageTypeV2.CONNECT_ACCEPT &&
            context?.phase == SignalingAttemptPhase.ACCEPTING &&
            context.attempt.runtimeSessionId == event.runtimeSessionId &&
            context.attempt.id == event.attemptId &&
            context.mediaOwnerChannelId == event.channelId
        ) {
            active = context.copy(
                phase = SignalingAttemptPhase.ACCEPTED,
                terminalOutcome = AttemptOutcome.ACCEPTED
            )
            return accepted(
                state = current,
                effects = listOf(
                    SessionEffect.StartWebRtc(
                        runtimeSessionId = context.attempt.runtimeSessionId,
                        attempt = context.attempt,
                        channelId = event.channelId,
                        role = WebRtcRole.ANSWERER,
                        peer = context.peer
                    )
                )
            )
        }
        if (
            event.type != SignalingMessageTypeV2.CONNECT_REJECT &&
            event.type != SignalingMessageTypeV2.BUSY &&
            event.type != SignalingMessageTypeV2.DISCONNECT
        ) {
            return accepted(state = current)
        }
        return finishSentTerminalChannel(current, event)
    }

    private fun signalingSendFailed(
        current: IntercomState,
        event: SessionEvent.SignalingSendFailed
    ): SignalingControlDecision {
        val context = active
        channels.remove(event.channelId)
        if (context == null || event.channelId !in context.channelIds) {
            return accepted(
                effects = listOf(closeEffect(event.runtimeSessionId, event.attemptId, event.channelId))
            )
        }
        if (
            context.mediaOwnerChannelId == event.channelId ||
            context.channelIds.size == 1
        ) {
            return finishAttemptImmediately(current, context)
        }
        val remaining = context.channelIds - event.channelId
        active = context.copy(
            channelIds = remaining,
            pendingTerminalChannels = context.pendingTerminalChannels - event.channelId
        )
        return accepted(
            state = current,
            effects = listOf(closeEffect(event.runtimeSessionId, event.attemptId, event.channelId))
        )
    }

    private fun channelClosed(
        current: IntercomState,
        event: SessionEvent.ChannelClosed
    ): SignalingControlDecision {
        channels.remove(event.channelId)
        val context = active
            ?.takeIf { it.wireRequestKey == event.wireRequestKey && event.channelId in it.channelIds }
            ?: return accepted(state = current)
        if (context.mediaOwnerChannelId == event.channelId) {
            if (current is IntercomState.Connected) {
                rememberDisconnectedIfAccepted(context)
                val transition = reduceIntercomState(
                    current,
                    SessionEvent.SignalingDisconnected(
                        runtimeSessionId = event.runtimeSessionId,
                        attemptId = context.attempt.id,
                        recovery = event.recovery
                    )
                ) ?: return rejected()
                forgetActiveChannels(context)
                return accepted(transition.state, transition.effects)
            }
            return finishAttemptImmediately(current, context)
        }

        val remaining = context.channelIds - event.channelId
        if (remaining.isEmpty()) return finishAttemptImmediately(current, context)
        val (withoutChannel, confirmationEffects) = removeConfirmationChannel(
            context,
            event.channelId,
            remaining
        )
        val cohort = context.selectionCohort?.let {
            val cohortRemaining = it.channelIds - event.channelId
            if (cohortRemaining.isEmpty()) null else it.copy(channelIds = cohortRemaining)
        }
        active = withoutChannel.copy(
            selectionCohort = cohort,
            pendingTerminalChannels = context.pendingTerminalChannels - event.channelId
        )
        if (context.phase == SignalingAttemptPhase.SELECTING_MEDIA && cohort != null) {
            return accepted(
                state = current,
                effects = confirmationEffects + selectEffect(context.attempt, cohort)
            )
        }
        return accepted(state = current, effects = confirmationEffects)
    }

    private fun protocolViolation(
        current: IntercomState,
        event: SessionEvent.ProtocolViolation
    ): SignalingControlDecision {
        val context = active
        val isOwner = context?.wireRequestKey == event.wireRequestKey &&
            context.mediaOwnerChannelId == event.channelId
        return if (isOwner) {
            finishAttemptImmediately(current, requireNotNull(context))
        } else {
            channels.remove(event.channelId)
            if (context != null && event.channelId in context.channelIds) {
                removeChannelAndContinueOrTerminate(current, context, event.channelId)
            } else {
                accepted(
                    state = current,
                    effects = listOf(
                        closeEffect(
                            event.runtimeSessionId,
                            event.wireRequestKey.attemptId,
                            event.channelId
                        )
                    )
                )
            }
        }
    }

    private fun beginTerminalBroadcast(
        current: IntercomState,
        context: AttemptChannelSet,
        outcome: AttemptOutcome,
        response: SignalingMessageV2
    ): SignalingControlDecision {
        val pending = context.channelIds.filterTo(linkedSetOf()) { it in channels }
        remember(context.wireRequestKey, outcome, response)
        if (pending.isEmpty()) return finishAttemptImmediately(current, context)
        val cancelConfirmation = context.confirmationActionNonce?.let {
            SessionEffect.CancelIncomingConfirmation(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                it
            )
        }
        active = context.copy(
            phase = SignalingAttemptPhase.TERMINATING,
            confirmationChannelId = null,
            confirmationSurface = null,
            confirmationActionNonce = null,
            decisionDeadlineElapsedMs = null,
            terminalOutcome = outcome,
            pendingTerminalChannels = pending
        )
        val effects = listOfNotNull(cancelConfirmation) + pending.map { channelId ->
            responseEffect(
                runtimeSessionId = context.attempt.runtimeSessionId,
                attemptId = context.attempt.id,
                channelId = channelId,
                response = response
            )
        }
        return accepted(state = current, effects = effects)
    }

    private fun finishSentTerminalChannel(
        current: IntercomState,
        event: SessionEvent.SignalingMessageSent
    ): SignalingControlDecision {
        val channelId = event.channelId
        val context = active
        channels.remove(channelId)
        val close = closeEffect(event.runtimeSessionId, event.attemptId, channelId)
        if (
            context != null &&
            context.phase == SignalingAttemptPhase.TERMINATING &&
            channelId in context.pendingTerminalChannels
        ) {
            val remainingPending = context.pendingTerminalChannels - channelId
            val remainingChannels = context.channelIds - channelId
            val updated = context.copy(
                channelIds = remainingChannels,
                confirmationChannelId = null,
                mediaOwnerChannelId = context.mediaOwnerChannelId?.takeIf {
                    it in remainingChannels
                },
                pendingTerminalChannels = remainingPending
            )
            if (remainingPending.isNotEmpty()) {
                active = updated
                return accepted(state = current, effects = listOf(close))
            }
            return finishAttemptImmediately(current, updated, listOf(close))
        }

        if (context == null || channelId !in context.channelIds) {
            return accepted(state = current, effects = listOf(close))
        }
        if (context.mediaOwnerChannelId == channelId) {
            return finishAttemptImmediately(current, context, listOf(close))
        }

        val remaining = context.channelIds - channelId
        if (remaining.isEmpty()) {
            return finishAttemptImmediately(current, context, listOf(close))
        }
        active = context.copy(
            channelIds = remaining,
            confirmationChannelId = context.confirmationChannelId?.takeUnless { it == channelId },
            selectionCohort = context.selectionCohort?.let { cohort ->
                val remainingCohort = cohort.channelIds - channelId
                if (remainingCohort.isEmpty()) null else cohort.copy(channelIds = remainingCohort)
            },
            pendingTerminalChannels = context.pendingTerminalChannels - channelId
        )
        return accepted(state = current, effects = listOf(close))
    }

    private fun removeChannelAndContinueOrTerminate(
        current: IntercomState,
        context: AttemptChannelSet,
        channelId: ControlChannelId
    ): SignalingControlDecision {
        channels.remove(channelId)
        val remaining = context.channelIds - channelId
        if (remaining.isEmpty() || context.mediaOwnerChannelId == channelId) {
            return finishAttemptImmediately(current, context)
        }
        val (withoutChannel, confirmationEffects) = removeConfirmationChannel(
            context,
            channelId,
            remaining
        )
        active = withoutChannel.copy(
            pendingTerminalChannels = context.pendingTerminalChannels - channelId
        )
        return accepted(
            state = current,
            effects = listOf(
                closeEffect(context.attempt.runtimeSessionId, context.attempt.id, channelId)
            ) + confirmationEffects
        )
    }

    private fun removeConfirmationChannel(
        context: AttemptChannelSet,
        removedChannelId: ControlChannelId,
        remainingChannelIds: Set<ControlChannelId>
    ): Pair<AttemptChannelSet, List<SessionEffect>> {
        if (context.confirmationChannelId != removedChannelId) {
            return context.copy(channelIds = remainingChannelIds) to emptyList()
        }

        val previousNonce = requireNotNull(context.confirmationActionNonce)
        val migrated = context.copy(
            channelIds = remainingChannelIds,
            confirmationChannelId = requireNotNull(remainingChannelIds.minOrNull()),
            confirmationActionNonce = actionNonce()
        )
        return migrated to listOf(
            SessionEffect.CancelIncomingConfirmation(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                previousNonce
            ),
            SessionEffect.PublishIncomingConfirmation(migrated.confirmationPrompt())
        )
    }

    private fun finishAttemptImmediately(
        current: IntercomState,
        context: AttemptChannelSet,
        prefixEffects: List<SessionEffect> = emptyList()
    ): SignalingControlDecision {
        rememberDisconnectedIfAccepted(context)
        val cancelConfirmation = context.confirmationActionNonce?.let {
            SessionEffect.CancelIncomingConfirmation(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                it
            )
        }
        val channelIds = context.channelIds
        channelIds.forEach(channels::remove)
        active = null
        val closeEffects = channelIds.map {
            closeEffect(context.attempt.runtimeSessionId, context.attempt.id, it)
        }
        return accepted(
            state = IntercomState.Discovering(context.attempt.runtimeSessionId),
            effects = listOfNotNull(cancelConfirmation) + prefixEffects + closeEffects +
                SessionEffect.AbortAttemptAndResumeDiscovery(
                context.attempt.runtimeSessionId,
                context.attempt.id
            )
        )
    }

    private fun terminateAttempt(attempt: ConnectionAttempt): SignalingControlDecision = accepted(
        state = IntercomState.Discovering(attempt.runtimeSessionId),
        effects = listOf(
            SessionEffect.AbortAttemptAndResumeDiscovery(attempt.runtimeSessionId, attempt.id)
        )
    )

    private fun busyRequest(
        runtimeSessionId: RuntimeSessionId,
        wireRequestKey: WireRequestKey
    ): SignalingControlDecision {
        val busy = SignalingMessageV2.Busy(BusyReason.parse("BUSY"), retryAfterMs = null)
        remember(wireRequestKey, AttemptOutcome.BUSY, busy)
        val effects = channels.values
            .filter { it.wireRequestKey == wireRequestKey }
            .map {
                responseEffect(
                    runtimeSessionId,
                    wireRequestKey.attemptId,
                    it.channelId,
                    busy
                )
            }
        return accepted(effects = effects)
    }

    private fun replayCompleted(
        runtimeSessionId: RuntimeSessionId,
        channelId: ControlChannelId,
        completed: CompletedWireAttempt
    ): SignalingControlDecision {
        val response = completed.response
        return if (response == null) {
            accepted(
                effects = listOf(
                    closeEffect(runtimeSessionId, completed.key.attemptId, channelId)
                )
            )
        } else {
            accepted(
                effects = listOf(
                    responseEffect(runtimeSessionId, completed.key.attemptId, channelId, response)
                )
            )
        }
    }

    private fun matchingConfirmation(
        current: IntercomState,
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId,
        actionNonce: String
    ): AttemptChannelSet? {
        val confirmation = current as? IntercomState.IncomingConfirmation ?: return null
        return active?.takeIf {
            it.phase == SignalingAttemptPhase.WAITING_LOCAL_DECISION &&
                it.attempt == confirmation.attempt &&
                it.attempt.runtimeSessionId == runtimeSessionId &&
                it.attempt.id == attemptId &&
                it.confirmationChannelId == channelId &&
                it.confirmationActionNonce == actionNonce
        }
    }

    private fun AttemptChannelSet.confirmationPrompt(): IncomingConfirmationPrompt =
        IncomingConfirmationPrompt(
            runtimeSessionId = attempt.runtimeSessionId,
            attemptId = attempt.id,
            channelId = requireNotNull(confirmationChannelId),
            actionNonce = requireNotNull(confirmationActionNonce),
            peer = peer,
            decisionDeadlineElapsedMs = requireNotNull(decisionDeadlineElapsedMs),
            surface = requireNotNull(confirmationSurface)
        )

    private fun cancelConfirmationEffect(
        context: AttemptChannelSet
    ): SessionEffect.CancelIncomingConfirmation = SessionEffect.CancelIncomingConfirmation(
        context.attempt.runtimeSessionId,
        context.attempt.id,
        requireNotNull(context.confirmationActionNonce)
    )

    private fun matchingActive(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        wireRequestKey: WireRequestKey,
        channelId: ControlChannelId
    ): AttemptChannelSet? = active?.takeIf {
        it.attempt.runtimeSessionId == runtimeSessionId &&
            it.attempt.id == attemptId &&
            it.wireRequestKey == wireRequestKey &&
            channelId in it.channelIds
    }

    private fun closeConflictingChannel(
        current: IntercomState,
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId
    ): SignalingControlDecision {
        val context = active
        if (context != null && channelId in context.channelIds) {
            if (context.mediaOwnerChannelId == channelId) {
                return finishAttemptImmediately(current, context)
            }
            return removeChannelAndContinueOrTerminate(current, context, channelId)
        }
        channels.remove(channelId)
        return accepted(
            state = current,
            effects = listOf(closeEffect(runtimeSessionId, attemptId, channelId))
        )
    }

    private fun inboundAttempt(
        runtimeSessionId: RuntimeSessionId,
        channel: VerifiedControlChannel,
        deadlineElapsedRealtimeMs: Long
    ): ConnectionAttempt = ConnectionAttempt(
        id = channel.wireRequestKey.attemptId,
        runtimeSessionId = runtimeSessionId,
        targetLock = channel.targetLock,
        trigger = ConnectionTrigger.INBOUND,
        channelPlan = ChannelPlan.single(channel.transport),
        deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs
    )

    private fun eligibleChannels(
        wireRequestKey: WireRequestKey,
        transport: Transport
    ): Set<ControlChannelId> = channels.values
        .filter { it.wireRequestKey == wireRequestKey && it.transport == transport }
        .mapTo(linkedSetOf()) { it.channelId }

    private fun selectEffect(
        attempt: ConnectionAttempt,
        cohort: SelectionCohort
    ): SessionEffect.SelectMediaChannel = SessionEffect.SelectMediaChannel(
        runtimeSessionId = attempt.runtimeSessionId,
        attemptId = attempt.id,
        wireRequestKey = cohort.wireRequestKey,
        cohort = cohort,
        preferredTransport = attempt.channelPlan.transport
    )

    private fun responseEffect(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId,
        response: SignalingMessageV2
    ): SessionEffect = when (response) {
        is SignalingMessageV2.ConnectReject -> rejectEffect(
            runtimeSessionId,
            attemptId,
            channelId,
            response.reason,
            response.retryable
        )
        is SignalingMessageV2.Busy -> SessionEffect.SendBusy(
            runtimeSessionId,
            attemptId,
            channelId,
            response.reason,
            response.retryAfterMs
        )
        is SignalingMessageV2.Disconnect -> SessionEffect.SendDisconnect(
            runtimeSessionId,
            attemptId,
            channelId,
            response.reason
        )
        else -> error("Unsupported terminal signaling response: ${response.type}")
    }

    private fun rejectEffect(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId,
        reason: RejectReason,
        retryable: Boolean
    ): SessionEffect.SendConnectReject = SessionEffect.SendConnectReject(
        runtimeSessionId,
        attemptId,
        channelId,
        reason,
        retryable
    )

    private fun closeEffect(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId
    ): SessionEffect.CloseControlChannel = SessionEffect.CloseControlChannel(
        runtimeSessionId,
        attemptId,
        channelId
    )

    private fun remember(
        key: WireRequestKey,
        outcome: AttemptOutcome,
        response: SignalingMessageV2?
    ) {
        pruneCompleted()
        completedAttempts.remove(key)
        completedAttempts[key] = CompletedWireAttempt(
            key = key,
            outcome = outcome,
            response = response,
            expiresAtElapsedMs = elapsedRealtime() + TOMBSTONE_TTL_MS
        )
        while (completedAttempts.size > MAX_TOMBSTONES) {
            completedAttempts.remove(completedAttempts.keys.first())
        }
    }

    private fun pruneCompleted() {
        val now = elapsedRealtime()
        completedAttempts.entries.removeAll { it.value.expiresAtElapsedMs <= now }
    }

    private fun forgetActiveChannels(context: AttemptChannelSet) {
        context.channelIds.forEach(channels::remove)
        active = null
    }

    private fun rememberDisconnectedIfAccepted(context: AttemptChannelSet) {
        if (context.terminalOutcome == AttemptOutcome.ACCEPTED) {
            remember(context.wireRequestKey, AttemptOutcome.DISCONNECTED, null)
        }
    }

    private fun accepted(
        state: IntercomState? = null,
        effects: List<SessionEffect> = emptyList()
    ): SignalingControlDecision = SignalingControlDecision(true, state, effects)

    private fun rejected(): SignalingControlDecision = SignalingControlDecision(false)

    private companion object {
        const val MAX_TOMBSTONES = 128
        const val TOMBSTONE_TTL_MS = 60_000L
        const val LOCAL_DISCONNECT_REASON = "LOCAL_DISCONNECT"
    }
}
