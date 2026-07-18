package com.kuma.motointercom

import java.util.UUID

internal data class SignalingControlDecision(
    val accepted: Boolean,
    val state: IntercomState? = null,
    val effects: List<SessionEffect> = emptyList()
)

internal class SignalingControlCoordinator(
    private val clock: MonotonicClock,
    private val attemptTimeoutMs: Long,
    private val confirmationTimeoutMs: Long = 15_000L,
    private val actionNonce: () -> String = { UUID.randomUUID().toString() },
    private val attemptIdFactory: () -> ConnectionAttemptId = ConnectionAttemptId::create
) {
    init {
        require(attemptTimeoutMs > 0L) { "Attempt timeout must be positive" }
        require(confirmationTimeoutMs > 0L) { "Confirmation timeout must be positive" }
    }
    private val channels = linkedMapOf<ControlChannelId, VerifiedControlChannel>()
    private val completedAttempts = linkedMapOf<WireRequestKey, CompletedWireAttempt>()
    private val terminalAttemptOutcomes =
        linkedMapOf<ConnectionAttemptId, ConnectionAttemptTerminalOutcome>()
    @Volatile
    private var ownedAttempt: ConnectionAttempt? = null
    @Volatile
    private var active: AttemptChannelSet? = null
    @Volatile
    private var pendingInbound: PendingInboundRequest? = null

    internal val currentAttempt: ConnectionAttempt?
        get() = ownedAttempt

    internal val activeAttempt: AttemptChannelSet?
        get() = active

    internal val pendingInboundRequest: PendingInboundRequest?
        get() = pendingInbound

    internal fun terminalOutcome(
        attemptId: ConnectionAttemptId
    ): ConnectionAttemptTerminalOutcome? = terminalAttemptOutcomes[attemptId]

    fun handle(
        current: IntercomState,
        event: SessionEvent,
        incomingPolicy: IncomingRequestPolicy? = null
    ): SignalingControlDecision? {
        pruneCompleted()
        return when (event) {
            is SessionEvent.ConnectRequested -> adoptOutboundAttempt(current, event)
            is SessionEvent.ConnectPresenceRequested -> connectPresenceRequested(current, event)
            is SessionEvent.AttemptReplaced -> replaceOwnedAttempt(current, event)
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
            is SessionEvent.TargetedTransportOpenFailed -> targetedTransportOpenFailed(
                current,
                event
            )
            is SessionEvent.AttemptTimedOut -> attemptTimedOut(current, event)
            is SessionEvent.WebRtcStateChanged -> webRtcStateChanged(current, event)
            is SessionEvent.SignalingDisconnected -> signalingDisconnected(current, event)
            is SessionEvent.RecoveryExhausted -> recoveryExhausted(current, event)
            is SessionEvent.DisconnectRequested -> disconnectRequested(current, event)
            is SessionEvent.StopRequested -> stopRequested(current, event)
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
                    context.attempt.id == event.attemptId &&
                    context.attempt.deadlineElapsedRealtimeMs ==
                    event.scheduledDeadlineElapsedRealtimeMs
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
            pendingInbound = null
            ownedAttempt = null
            completedAttempts.clear()
            terminalAttemptOutcomes.clear()
        }
    }

    private fun adoptOutboundAttempt(
        current: IntercomState,
        event: SessionEvent.ConnectRequested
    ): SignalingControlDecision {
        if (ownedAttempt != null || terminalOutcome(event.attempt.id) != null) return rejected()
        val transition = reduceIntercomState(current, event) ?: return rejected()
        ownedAttempt = event.attempt
        return accepted(transition.state, transition.effects)
    }

    private fun connectPresenceRequested(
        current: IntercomState,
        event: SessionEvent.ConnectPresenceRequested
    ): SignalingControlDecision {
        if (
            current !is IntercomState.Discovering ||
            current.runtimeSessionId != event.runtimeSessionId ||
            ownedAttempt != null ||
            pendingInbound != null ||
            event.targetDeviceId.isBlank()
        ) {
            return rejected()
        }
        val transport = when {
            Transport.LAN in event.availableTransports -> Transport.LAN
            Transport.WIFI_DIRECT in event.availableTransports -> Transport.WIFI_DIRECT
            else -> return rejected()
        }
        val newAttemptId = attemptIdFactory()
        if (terminalOutcome(newAttemptId) != null) return rejected()
        val attempt = ConnectionAttempt(
            id = newAttemptId,
            runtimeSessionId = event.runtimeSessionId,
            targetLock = TargetLock(event.targetDeviceId, event.targetSessionId),
            trigger = ConnectionTrigger.USER,
            channelPlan = ChannelPlan.single(transport),
            deadlineElapsedRealtimeMs = newAttemptDeadline().elapsedRealtimeMs
        )
        ownedAttempt = attempt
        return accepted(
            state = IntercomState.Connecting(attempt),
            effects = listOf(
                SessionEffect.ScheduleAttemptDeadline(attempt),
                SessionEffect.OpenTargetedTransport(attempt)
            )
        )
    }

    private fun replaceOwnedAttempt(
        current: IntercomState,
        event: SessionEvent.AttemptReplaced
    ): SignalingControlDecision {
        val previous = ownedAttempt ?: return rejected()
        if (event.attempt.id == previous.id) return rejected()
        if (terminalOutcome(event.attempt.id) != null) return rejected()
        val transition = reduceIntercomState(current, event) ?: return rejected()
        recordTerminal(previous, ConnectionAttemptTerminalOutcome.CANCELED)
        active?.takeIf { it.attempt == previous }?.let(::forgetActiveChannels)
        ownedAttempt = event.attempt
        return accepted(transition.state, transition.effects)
    }

    private fun targetedTransportOpenFailed(
        current: IntercomState,
        event: SessionEvent.TargetedTransportOpenFailed
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf {
                current.connectionAttemptOrNull() == it &&
                    it.channelPlan.transport == event.transport
            }
            ?: return rejected()
        return terminateOwnedAttempt(
            current,
            attempt,
            ConnectionAttemptTerminalOutcome.FAILED
        )
    }

    private fun attemptTimedOut(
        current: IntercomState,
        event: SessionEvent.AttemptTimedOut
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf {
                current.connectionAttemptOrNull() == it &&
                    it.deadlineElapsedRealtimeMs == event.scheduledDeadlineElapsedRealtimeMs &&
                    it.isExpiredAt(clock.now())
            }
            ?: return rejected()
        if (terminalOutcome(attempt.id) != null) return rejected()
        active?.takeIf { it.attempt == attempt }?.let {
            remember(it.wireRequestKey, AttemptOutcome.TIMED_OUT, null)
        }
        return terminateOwnedAttempt(
            current,
            attempt,
            ConnectionAttemptTerminalOutcome.TIMED_OUT
        )
    }

    private fun webRtcStateChanged(
        current: IntercomState,
        event: SessionEvent.WebRtcStateChanged
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf { current.connectionAttemptOrNull() == it }
            ?: return rejected()
        if (
            event.state == WebRtcConnectionState.CONNECTED &&
            attempt.isExpiredAt(clock.now())
        ) {
            return rejected()
        }
        return when (event.state) {
            WebRtcConnectionState.CONNECTED -> connectWebRtc(current, attempt, event.occurredAt)
            WebRtcConnectionState.DISCONNECTED -> connectionLost(
                current,
                attempt,
                ConnectionAttemptTerminalOutcome.DISCONNECTED,
                restartConnectedDiscovery = false
            )
            WebRtcConnectionState.FAILED,
            WebRtcConnectionState.CLOSED -> connectionLost(
                current,
                attempt,
                ConnectionAttemptTerminalOutcome.FAILED,
                restartConnectedDiscovery = false
            )
            WebRtcConnectionState.OTHER -> rejected()
        }
    }

    private fun connectWebRtc(
        current: IntercomState,
        attempt: ConnectionAttempt,
        connectedAt: Long
    ): SignalingControlDecision {
        if (terminalOutcome(attempt.id) != null) return rejected()
        val peer = when (current) {
            is IntercomState.Connecting -> current.peer
                ?.takeIf { it.isVerifiedFor(attempt.targetLock) }
            is IntercomState.Optimizing -> current.peer
                ?.takeIf { it.isVerifiedFor(attempt.targetLock) }
            is IntercomState.Recovering -> current.peer
                .takeIf { it.isVerifiedFor(attempt.targetLock) }
            else -> null
        } ?: return rejected()
        if (!recordTerminal(attempt, ConnectionAttemptTerminalOutcome.SUCCESS)) return rejected()
        return accepted(IntercomState.Connected(attempt, peer, connectedAt))
    }

    private fun signalingDisconnected(
        current: IntercomState,
        event: SessionEvent.SignalingDisconnected
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf { current.connectionAttemptOrNull() == it }
            ?: return rejected()
        return connectionLost(
            current,
            attempt,
            ConnectionAttemptTerminalOutcome.DISCONNECTED,
            restartConnectedDiscovery = true
        )
    }

    private fun connectionLost(
        current: IntercomState,
        attempt: ConnectionAttempt,
        outcome: ConnectionAttemptTerminalOutcome,
        restartConnectedDiscovery: Boolean
    ): SignalingControlDecision = when (current) {
        is IntercomState.Connected -> recoverConnectedAttempt(
            current,
            outcome,
            restartConnectedDiscovery
        )
        is IntercomState.Connecting,
        is IntercomState.Optimizing -> terminateOwnedAttempt(current, attempt, outcome)
        is IntercomState.Recovering -> accepted(
            state = current,
            effects = if (restartConnectedDiscovery) {
                listOf(SessionEffect.RestartDiscovery(attempt.runtimeSessionId, attempt))
            } else {
                emptyList()
            }
        )
        else -> rejected()
    }

    private fun recoverConnectedAttempt(
        current: IntercomState.Connected,
        outcome: ConnectionAttemptTerminalOutcome,
        restartConnectedDiscovery: Boolean
    ): SignalingControlDecision {
        active?.takeIf {
            it.attempt.id == current.attempt.id &&
                it.phase == SignalingAttemptPhase.TERMINATING
        }?.let { return finishAttemptImmediately(current, it) }
        val existingOutcome = terminalOutcome(current.attempt.id)
        if (
            existingOutcome != null &&
            existingOutcome != ConnectionAttemptTerminalOutcome.SUCCESS
        ) {
            return rejected()
        }
        val recoveryAttemptId = attemptIdFactory()
        if (terminalOutcome(recoveryAttemptId) != null) return rejected()
        val recoveryAttempt = ConnectionAttempt(
            id = recoveryAttemptId,
            runtimeSessionId = current.attempt.runtimeSessionId,
            targetLock = current.attempt.targetLock,
            trigger = ConnectionTrigger.RECOVERY,
            channelPlan = current.attempt.channelPlan,
            deadlineElapsedRealtimeMs = newAttemptDeadline().elapsedRealtimeMs
        )
        if (existingOutcome == null) recordTerminal(current.attempt, outcome)
        ownedAttempt = recoveryAttempt
        return accepted(
            state = IntercomState.Recovering(recoveryAttempt, current.peer),
            effects = if (restartConnectedDiscovery) {
                listOf(
                    SessionEffect.RestartDiscovery(
                        recoveryAttempt.runtimeSessionId,
                        recoveryAttempt
                    ),
                    SessionEffect.ScheduleAttemptDeadline(recoveryAttempt)
                )
            } else {
                listOf(SessionEffect.ScheduleAttemptDeadline(recoveryAttempt))
            }
        )
    }

    private fun recoveryExhausted(
        current: IntercomState,
        event: SessionEvent.RecoveryExhausted
    ): SignalingControlDecision {
        val recovering = current as? IntercomState.Recovering ?: return rejected()
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf { it == recovering.attempt }
            ?: return rejected()
        if (!recordTerminal(attempt, ConnectionAttemptTerminalOutcome.FAILED)) {
            return rejected()
        }
        clearOwnedAttempt(attempt)
        return accepted(
            IntercomState.Resetting(event.runtimeSessionId, attempt.targetDeviceId)
        )
    }

    private fun stopRequested(
        current: IntercomState,
        event: SessionEvent.StopRequested
    ): SignalingControlDecision? {
        pendingInbound?.let { pending ->
            if (
                pending.runtimeSessionId != event.runtimeSessionId ||
                current.runtimeSessionId != event.runtimeSessionId
            ) {
                return rejected()
            }
            val effects = listOfNotNull(
                pending.confirmationActionNonce?.let {
                    SessionEffect.CancelIncomingConfirmation(
                        pending.runtimeSessionId,
                        pending.attemptId,
                        it
                    )
                }
            ) + pending.channelIds.map {
                closeEffect(pending.runtimeSessionId, pending.attemptId, it)
            }
            pending.channelIds.forEach(channels::remove)
            pendingInbound = null
            return accepted(IntercomState.Stopping(event.runtimeSessionId), effects)
        }
        val attempt = ownedAttempt ?: return null
        if (attempt.runtimeSessionId != event.runtimeSessionId) return rejected()
        if (current.runtimeSessionId != event.runtimeSessionId) return rejected()
        if (terminalOutcome(attempt.id) == null) {
            recordTerminal(attempt, ConnectionAttemptTerminalOutcome.CANCELED)
        }
        clearOwnedAttempt(attempt)
        return accepted(IntercomState.Stopping(event.runtimeSessionId))
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
            ownedAttempt != attempt ||
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
        val pendingContext = pendingInbound
        if (pendingContext?.wireRequestKey == event.wireRequestKey) {
            return handleDuplicatePendingRequest(current, channel, pendingContext)
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
                policy.paired -> beginPairedInboundConnection(
                    current,
                    channel,
                    event.occurredAtElapsedMs
                )
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
        val channelIds = eligibleChannels(channel.wireRequestKey, channel.transport)
        val pending = PendingInboundRequest(
            runtimeSessionId = current.runtimeSessionId,
            wireRequestKey = channel.wireRequestKey,
            targetLock = channel.targetLock,
            peer = channel.peer,
            transport = channel.transport,
            channelIds = channelIds,
            phase = PendingInboundPhase.WAITING_LOCAL_DECISION,
            confirmationChannelId = channel.channelId,
            confirmationSurface = surface,
            confirmationActionNonce = actionNonce(),
            decisionDeadlineAt = deadlineAfter(clock.now(), confirmationTimeoutMs)
        )
        pendingInbound = pending
        return accepted(
            state = IntercomState.IncomingConfirmation(
                current.runtimeSessionId,
                pending.attemptId,
                channel.peer
            ),
            effects = listOf(SessionEffect.PublishIncomingConfirmation(pending.confirmationPrompt()))
        )
    }

    private fun beginPairedInboundConnection(
        current: IntercomState.Discovering,
        channel: VerifiedControlChannel,
        occurredAtElapsedMs: Long
    ): SignalingControlDecision {
        val deadlineAt = deadlineAfter(occurredAtElapsedMs, attemptTimeoutMs)
        if (clock.now().elapsedRealtimeMs >= deadlineAt.elapsedRealtimeMs) {
            return rejectInboundWithoutConfirmation(current, channel, RejectReason.TIMEOUT)
        }
        val eligible = eligibleChannels(channel.wireRequestKey, channel.transport)
        if (eligible.isEmpty()) return rejected()
        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            deadlineAt = deadlineAt
        )
        val cohort = SelectionCohort(channel.wireRequestKey, eligible, nowElapsedRealtimeMs())
        ownedAttempt = attempt
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
            effects = listOf(
                SessionEffect.ScheduleAttemptDeadline(attempt),
                selectEffect(attempt, cohort)
            )
        )
    }

    private fun rejectInboundWithoutConfirmation(
        current: IntercomState.Discovering,
        channel: VerifiedControlChannel,
        reason: RejectReason
    ): SignalingControlDecision {
        val response = SignalingMessageV2.ConnectReject(reason, retryable = false)
        remember(channel.wireRequestKey, AttemptOutcome.REJECTED, response)
        return accepted(
            state = current,
            effects = eligibleChannels(channel.wireRequestKey, channel.transport).map {
                responseEffect(
                    current.runtimeSessionId,
                    channel.wireRequestKey.attemptId,
                    it,
                    response
                )
            }
        )
    }

    private fun acceptGlareWinner(
        current: IntercomState.Connecting,
        channel: VerifiedControlChannel,
        occurredAtElapsedMs: Long
    ): SignalingControlDecision {
        if (current.attempt.isExpiredAt(clock.now())) return rejected()
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
        recordTerminal(current.attempt, ConnectionAttemptTerminalOutcome.GLARE_LOST)
        losingChannelIds.forEach(channels::remove)

        val eligible = eligibleChannels(channel.wireRequestKey, channel.transport)
        if (eligible.isEmpty()) {
            active = null
            clearOwnedAttempt(current.attempt)
            return accepted(
                state = IntercomState.Discovering(current.runtimeSessionId),
                effects = listOf(
                    SessionEffect.AbortAttemptAndResumeDiscovery(
                        current.runtimeSessionId,
                        current.attempt.id
                    )
                )
            )
        }
        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            deadlineAt = current.attempt.deadlineAt
        )
        val cohort = SelectionCohort(channel.wireRequestKey, eligible, occurredAtElapsedMs)
        ownedAttempt = attempt
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
        } + SessionEffect.ScheduleAttemptDeadline(attempt) + selectEffect(attempt, cohort)
        return accepted(
            state = IntercomState.Connecting(attempt, channel.peer),
            effects = effects
        )
    }

    private fun incomingAccepted(
        current: IntercomState,
        event: SessionEvent.IncomingAccepted
    ): SignalingControlDecision {
        val pending = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        if (event.occurredAtElapsedMs >= pending.decisionDeadlineAt.elapsedRealtimeMs) {
            return timeoutIncomingConfirmation(current, pending)
        }
        val acceptedDeadline = deadlineAfter(event.occurredAtElapsedMs, attemptTimeoutMs)
        if (clock.now().elapsedRealtimeMs >= acceptedDeadline.elapsedRealtimeMs) {
            return timeoutIncomingConfirmation(current, pending)
        }
        val eligible = pending.channelIds.filterTo(linkedSetOf()) { channelId ->
            channels[channelId]?.transport == pending.transport
        }
        if (eligible.isEmpty()) return finishPendingImmediately(current, pending)
        val confirmationChannel = channels[pending.confirmationChannelId]
            ?: return finishPendingImmediately(current, pending)
        val acceptedAttempt = inboundAttempt(
            runtimeSessionId = pending.runtimeSessionId,
            channel = confirmationChannel,
            deadlineAt = acceptedDeadline
        )
        val cohort = SelectionCohort(pending.wireRequestKey, eligible, nowElapsedRealtimeMs())
        ownedAttempt = acceptedAttempt
        active = AttemptChannelSet(
            wireRequestKey = pending.wireRequestKey,
            attempt = acceptedAttempt,
            peer = pending.peer,
            channelIds = eligible,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            selectionCohort = cohort
        )
        pendingInbound = null
        return accepted(
            state = IntercomState.Connecting(acceptedAttempt, pending.peer),
            effects = listOf(
                cancelConfirmationEffect(pending),
                SessionEffect.ScheduleAttemptDeadline(acceptedAttempt),
                selectEffect(acceptedAttempt, cohort)
            )
        )
    }

    private fun incomingRejected(
        current: IntercomState,
        event: SessionEvent.IncomingRejected
    ): SignalingControlDecision {
        val pending = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        if (event.occurredAtElapsedMs >= pending.decisionDeadlineAt.elapsedRealtimeMs) {
            return timeoutIncomingConfirmation(current, pending)
        }
        return beginPendingTerminalBroadcast(
            current = current,
            pending = pending,
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
        val pending = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        if (event.occurredAtElapsedMs < pending.decisionDeadlineAt.elapsedRealtimeMs) {
            return rejected()
        }
        return timeoutIncomingConfirmation(current, pending)
    }

    private fun confirmationSurfaceUnavailable(
        current: IntercomState,
        event: SessionEvent.ConfirmationSurfaceUnavailable
    ): SignalingControlDecision {
        val pending = matchingConfirmation(
            current,
            event.runtimeSessionId,
            event.attemptId,
            event.channelId,
            event.actionNonce
        ) ?: return rejected()
        return beginPendingTerminalBroadcast(
            current = current,
            pending = pending,
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
        val pending = pendingInbound
            ?.takeIf {
                it.phase == PendingInboundPhase.WAITING_LOCAL_DECISION &&
                    it.runtimeSessionId == event.runtimeSessionId
            }
            ?: return accepted(state = current)
        if (current !is IntercomState.IncomingConfirmation || current.attemptId != pending.attemptId) {
            return accepted(state = current)
        }
        val desiredSurface = event.availability.preferredSurface()
            ?: return beginPendingTerminalBroadcast(
                current = current,
                pending = pending,
                outcome = AttemptOutcome.REJECTED,
                response = SignalingMessageV2.ConnectReject(
                    RejectReason.CONFIRMATION_UNAVAILABLE,
                    retryable = false
                )
            )
        if (pending.confirmationSurface == desiredSurface) {
            return accepted(state = current)
        }

        val previousNonce = requireNotNull(pending.confirmationActionNonce)
        val migrated = pending.copy(
            confirmationSurface = desiredSurface,
            confirmationActionNonce = actionNonce()
        )
        pendingInbound = migrated
        return accepted(
            state = current,
            effects = listOf(
                SessionEffect.CancelIncomingConfirmation(
                    pending.runtimeSessionId,
                    pending.attemptId,
                    previousNonce
                ),
                SessionEffect.PublishIncomingConfirmation(migrated.confirmationPrompt())
            )
        )
    }

    private fun timeoutIncomingConfirmation(
        current: IntercomState,
        pending: PendingInboundRequest
    ): SignalingControlDecision = beginPendingTerminalBroadcast(
        current = current,
        pending = pending,
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
        if (current.connectionAttemptOrNull() != context.attempt) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId
            )
        }
        if (context.attempt.isExpiredAt(clock.now())) {
            return terminateOwnedAttempt(
                current,
                context.attempt,
                ConnectionAttemptTerminalOutcome.TIMED_OUT
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
        return finishAttemptImmediately(
            current,
            context,
            logicalOutcome = ConnectionAttemptTerminalOutcome.REJECTED
        )
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
        return finishAttemptImmediately(
            current,
            context,
            logicalOutcome = ConnectionAttemptTerminalOutcome.BUSY
        )
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
        return finishAttemptImmediately(
            current,
            context,
            logicalOutcome = ConnectionAttemptTerminalOutcome.DISCONNECTED
        )
    }

    private fun disconnectRequested(
        current: IntercomState,
        event: SessionEvent.DisconnectRequested
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf { current.connectionAttemptOrNull() == it }
            ?: return rejected()
        val context = active?.takeIf { it.attempt == attempt }
        if (context == null) {
            if (terminalOutcome(attempt.id) == null) {
                recordTerminal(attempt, ConnectionAttemptTerminalOutcome.CANCELED)
            }
            clearOwnedAttempt(attempt)
            return accepted(
                state = IntercomState.Discovering(attempt.runtimeSessionId),
                effects = listOf(
                    SessionEffect.AbortAttemptAndResumeDiscovery(
                        attempt.runtimeSessionId,
                        attempt.id
                    )
                )
            )
        }
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
            return finishAttemptImmediately(
                current,
                context,
                logicalOutcome = ConnectionAttemptTerminalOutcome.CANCELED
            )
        }

        val nonOwners = context.channelIds - owner
        nonOwners.forEach(channels::remove)
        if (terminalOutcome(context.attempt.id) == null) {
            recordTerminal(context.attempt, ConnectionAttemptTerminalOutcome.CANCELED)
        }
        remember(context.wireRequestKey, AttemptOutcome.DISCONNECTED, null)
        active = context.copy(
            channelIds = setOf(owner),
            phase = SignalingAttemptPhase.TERMINATING,
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
        pendingInbound?.takeIf {
            it.phase == PendingInboundPhase.TERMINATING &&
                it.runtimeSessionId == event.runtimeSessionId &&
                it.attemptId == event.attemptId &&
                event.channelId in it.pendingTerminalChannels
        }?.let { return finishSentPendingTerminalChannel(current, it, event.channelId) }
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
        pendingInbound?.takeIf { event.channelId in it.channelIds }?.let {
            return removePendingChannel(current, it, event.channelId, closeRemoved = true)
        }
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
        pendingInbound?.takeIf {
            it.wireRequestKey == event.wireRequestKey && event.channelId in it.channelIds
        }?.let {
            return removePendingChannel(current, it, event.channelId, closeRemoved = false)
        }
        channels.remove(event.channelId)
        val context = active
            ?.takeIf { it.wireRequestKey == event.wireRequestKey && event.channelId in it.channelIds }
            ?: return accepted(state = current)
        if (context.mediaOwnerChannelId == event.channelId) {
            if (current is IntercomState.Connected) {
                rememberDisconnectedIfAccepted(context)
                val decision = recoverConnectedAttempt(
                    current,
                    ConnectionAttemptTerminalOutcome.DISCONNECTED,
                    restartConnectedDiscovery = true
                )
                if (!decision.accepted) return decision
                forgetActiveChannels(context)
                return decision
            }
            return finishAttemptImmediately(current, context)
        }

        val remaining = context.channelIds - event.channelId
        if (remaining.isEmpty()) return finishAttemptImmediately(current, context)
        val cohort = context.selectionCohort?.let {
            val cohortRemaining = it.channelIds - event.channelId
            if (cohortRemaining.isEmpty()) null else it.copy(channelIds = cohortRemaining)
        }
        active = context.copy(
            channelIds = remaining,
            selectionCohort = cohort,
            pendingTerminalChannels = context.pendingTerminalChannels - event.channelId
        )
        if (context.phase == SignalingAttemptPhase.SELECTING_MEDIA && cohort != null) {
            return accepted(
                state = current,
                effects = listOf(selectEffect(context.attempt, cohort))
            )
        }
        return accepted(state = current)
    }

    private fun protocolViolation(
        current: IntercomState,
        event: SessionEvent.ProtocolViolation
    ): SignalingControlDecision {
        pendingInbound?.takeIf {
            it.wireRequestKey == event.wireRequestKey && event.channelId in it.channelIds
        }?.let {
            return removePendingChannel(current, it, event.channelId, closeRemoved = true)
        }
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

    private fun beginPendingTerminalBroadcast(
        current: IntercomState,
        pending: PendingInboundRequest,
        outcome: AttemptOutcome,
        response: SignalingMessageV2
    ): SignalingControlDecision {
        val terminalChannels = pending.channelIds.filterTo(linkedSetOf()) { it in channels }
        remember(pending.wireRequestKey, outcome, response)
        if (terminalChannels.isEmpty()) return finishPendingImmediately(current, pending)
        val cancelConfirmation = cancelConfirmationEffect(pending)
        pendingInbound = pending.copy(
            phase = PendingInboundPhase.TERMINATING,
            confirmationChannelId = null,
            confirmationSurface = null,
            confirmationActionNonce = null,
            terminalOutcome = outcome,
            pendingTerminalChannels = terminalChannels
        )
        val effects = listOf(cancelConfirmation) + terminalChannels.map { channelId ->
            responseEffect(
                runtimeSessionId = pending.runtimeSessionId,
                attemptId = pending.attemptId,
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
        active = context.copy(
            channelIds = remaining,
            pendingTerminalChannels = context.pendingTerminalChannels - channelId
        )
        return accepted(
            state = current,
            effects = listOf(
                closeEffect(context.attempt.runtimeSessionId, context.attempt.id, channelId)
            )
        )
    }

    private fun finishSentPendingTerminalChannel(
        current: IntercomState,
        pending: PendingInboundRequest,
        channelId: ControlChannelId
    ): SignalingControlDecision =
        removePendingChannel(current, pending, channelId, closeRemoved = true)

    private fun removePendingChannel(
        current: IntercomState,
        pending: PendingInboundRequest,
        channelId: ControlChannelId,
        closeRemoved: Boolean
    ): SignalingControlDecision {
        channels.remove(channelId)
        val remaining = pending.channelIds - channelId
        val prefixEffects = if (closeRemoved) {
            listOf(closeEffect(pending.runtimeSessionId, pending.attemptId, channelId))
        } else {
            emptyList()
        }
        if (remaining.isEmpty()) {
            return finishPendingImmediately(
                current,
                pending,
                prefixEffects,
                remainingChannelIds = emptySet()
            )
        }
        if (pending.phase == PendingInboundPhase.TERMINATING) {
            val remainingTerminal = pending.pendingTerminalChannels - channelId
            if (remainingTerminal.isEmpty()) {
                return finishPendingImmediately(
                    current,
                    pending,
                    prefixEffects,
                    remainingChannelIds = remaining
                )
            }
            pendingInbound = pending.copy(
                channelIds = remaining,
                pendingTerminalChannels = remainingTerminal
            )
            return accepted(state = current, effects = prefixEffects)
        }
        if (pending.confirmationChannelId != channelId) {
            pendingInbound = pending.copy(channelIds = remaining)
            return accepted(state = current, effects = prefixEffects)
        }

        val previousNonce = requireNotNull(pending.confirmationActionNonce)
        val migrated = pending.copy(
            channelIds = remaining,
            confirmationChannelId = requireNotNull(remaining.minOrNull()),
            confirmationActionNonce = actionNonce()
        )
        pendingInbound = migrated
        return accepted(
            state = current,
            effects = prefixEffects + listOf(
                SessionEffect.CancelIncomingConfirmation(
                    pending.runtimeSessionId,
                    pending.attemptId,
                    previousNonce
                ),
                SessionEffect.PublishIncomingConfirmation(migrated.confirmationPrompt())
            )
        )
    }

    private fun finishPendingImmediately(
        current: IntercomState,
        pending: PendingInboundRequest,
        prefixEffects: List<SessionEffect> = emptyList(),
        remainingChannelIds: Set<ControlChannelId> = pending.channelIds
    ): SignalingControlDecision {
        val cancelConfirmation = pending.confirmationActionNonce?.let {
            SessionEffect.CancelIncomingConfirmation(
                pending.runtimeSessionId,
                pending.attemptId,
                it
            )
        }
        remainingChannelIds.forEach(channels::remove)
        pendingInbound = null
        return accepted(
            state = IntercomState.Discovering(pending.runtimeSessionId),
            effects = listOfNotNull(cancelConfirmation) + prefixEffects +
                remainingChannelIds.map {
                    closeEffect(pending.runtimeSessionId, pending.attemptId, it)
                }
        )
    }

    private fun finishAttemptImmediately(
        current: IntercomState,
        context: AttemptChannelSet,
        prefixEffects: List<SessionEffect> = emptyList(),
        logicalOutcome: ConnectionAttemptTerminalOutcome? = null
    ): SignalingControlDecision {
        recordTerminal(
            context.attempt,
            logicalOutcome ?: context.terminalOutcome?.toLogicalTerminalOutcome()
                ?: ConnectionAttemptTerminalOutcome.FAILED
        )
        rememberDisconnectedIfAccepted(context)
        val channelIds = context.channelIds
        channelIds.forEach(channels::remove)
        active = null
        clearOwnedAttempt(context.attempt)
        val closeEffects = channelIds.map {
            closeEffect(context.attempt.runtimeSessionId, context.attempt.id, it)
        }
        return accepted(
            state = IntercomState.Discovering(context.attempt.runtimeSessionId),
            effects = prefixEffects + closeEffects +
                SessionEffect.AbortAttemptAndResumeDiscovery(
                context.attempt.runtimeSessionId,
                context.attempt.id
            )
        )
    }

    private fun handleDuplicatePendingRequest(
        current: IntercomState,
        channel: VerifiedControlChannel,
        pending: PendingInboundRequest
    ): SignalingControlDecision {
        if (channel.transport != pending.transport) {
            return accepted(
                effects = listOf(
                    rejectEffect(
                        pending.runtimeSessionId,
                        pending.attemptId,
                        channel.channelId,
                        RejectReason.SUPERSEDED_CHANNEL,
                        retryable = false
                    )
                )
            )
        }
        if (pending.phase != PendingInboundPhase.WAITING_LOCAL_DECISION) {
            return replayCompleted(
                pending.runtimeSessionId,
                channel.channelId,
                requireNotNull(completedAttempts[pending.wireRequestKey])
            )
        }
        pendingInbound = pending.copy(channelIds = pending.channelIds + channel.channelId)
        return accepted(state = current)
    }

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
    ): PendingInboundRequest? {
        val confirmation = current as? IntercomState.IncomingConfirmation ?: return null
        return pendingInbound?.takeIf {
            it.phase == PendingInboundPhase.WAITING_LOCAL_DECISION &&
                confirmation.runtimeSessionId == runtimeSessionId &&
                confirmation.attemptId == attemptId &&
                it.runtimeSessionId == runtimeSessionId &&
                it.attemptId == attemptId &&
                it.confirmationChannelId == channelId &&
                it.confirmationActionNonce == actionNonce
        }
    }

    private fun PendingInboundRequest.confirmationPrompt(): IncomingConfirmationPrompt =
        IncomingConfirmationPrompt(
            runtimeSessionId = runtimeSessionId,
            attemptId = attemptId,
            channelId = requireNotNull(confirmationChannelId),
            actionNonce = requireNotNull(confirmationActionNonce),
            peer = peer,
            decisionDeadlineElapsedMs = decisionDeadlineAt.elapsedRealtimeMs,
            surface = requireNotNull(confirmationSurface)
        )

    private fun cancelConfirmationEffect(
        pending: PendingInboundRequest
    ): SessionEffect.CancelIncomingConfirmation = SessionEffect.CancelIncomingConfirmation(
        pending.runtimeSessionId,
        pending.attemptId,
        requireNotNull(pending.confirmationActionNonce)
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
        pendingInbound?.takeIf { channelId in it.channelIds }?.let {
            return removePendingChannel(current, it, channelId, closeRemoved = true)
        }
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
        deadlineAt: MonotonicTimestamp
    ): ConnectionAttempt = ConnectionAttempt(
        id = channel.wireRequestKey.attemptId,
        runtimeSessionId = runtimeSessionId,
        targetLock = channel.targetLock,
        trigger = ConnectionTrigger.INBOUND,
        channelPlan = ChannelPlan.single(channel.transport),
        deadlineElapsedRealtimeMs = deadlineAt.elapsedRealtimeMs
    )

    private fun newAttemptDeadline(): MonotonicTimestamp =
        deadlineAfter(clock.now(), attemptTimeoutMs)

    private fun deadlineAfter(
        startedAt: MonotonicTimestamp,
        durationMs: Long
    ): MonotonicTimestamp = deadlineAfter(startedAt.elapsedRealtimeMs, durationMs)

    private fun deadlineAfter(startedAtElapsedMs: Long, durationMs: Long): MonotonicTimestamp {
        require(startedAtElapsedMs >= 0L) { "Monotonic start must not be negative" }
        require(durationMs > 0L) { "Deadline duration must be positive" }
        return MonotonicTimestamp(Math.addExact(startedAtElapsedMs, durationMs))
    }

    private fun nowElapsedRealtimeMs(): Long = clock.now().elapsedRealtimeMs

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
            expiresAtElapsedMs = Math.addExact(nowElapsedRealtimeMs(), TOMBSTONE_TTL_MS)
        )
        while (completedAttempts.size > MAX_TOMBSTONES) {
            completedAttempts.remove(completedAttempts.keys.first())
        }
    }

    private fun pruneCompleted() {
        val now = nowElapsedRealtimeMs()
        completedAttempts.entries.removeAll { it.value.expiresAtElapsedMs <= now }
    }

    private fun forgetActiveChannels(context: AttemptChannelSet) {
        context.channelIds.forEach(channels::remove)
        active = null
    }

    private fun matchingOwnedAttempt(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId
    ): ConnectionAttempt? = ownedAttempt?.takeIf {
        it.runtimeSessionId == runtimeSessionId && it.id == attemptId
    }

    private fun terminateOwnedAttempt(
        current: IntercomState,
        attempt: ConnectionAttempt,
        outcome: ConnectionAttemptTerminalOutcome
    ): SignalingControlDecision {
        if (terminalOutcome(attempt.id) != null) return rejected()
        val context = active?.takeIf { it.attempt.id == attempt.id }
        if (context != null) {
            return finishAttemptImmediately(
                current,
                context,
                logicalOutcome = outcome
            )
        }
        if (!recordTerminal(attempt, outcome)) return rejected()
        clearOwnedAttempt(attempt)
        return accepted(
            state = IntercomState.Discovering(attempt.runtimeSessionId),
            effects = listOf(
                SessionEffect.AbortAttemptAndResumeDiscovery(
                    attempt.runtimeSessionId,
                    attempt.id
                )
            )
        )
    }

    private fun replaceOwnedAttempt(
        previous: ConnectionAttempt,
        replacement: ConnectionAttempt
    ) {
        if (ownedAttempt?.id == previous.id) ownedAttempt = replacement
    }

    private fun clearOwnedAttempt(attempt: ConnectionAttempt) {
        if (ownedAttempt?.id == attempt.id) ownedAttempt = null
    }

    private fun recordTerminal(
        attempt: ConnectionAttempt,
        outcome: ConnectionAttemptTerminalOutcome
    ): Boolean {
        if (attempt.id in terminalAttemptOutcomes) return false
        terminalAttemptOutcomes[attempt.id] = outcome
        while (terminalAttemptOutcomes.size > MAX_TERMINAL_OUTCOMES) {
            terminalAttemptOutcomes.remove(terminalAttemptOutcomes.keys.first())
        }
        return true
    }

    private fun AttemptOutcome.toLogicalTerminalOutcome():
        ConnectionAttemptTerminalOutcome = when (this) {
        AttemptOutcome.ACCEPTED -> ConnectionAttemptTerminalOutcome.FAILED
        AttemptOutcome.REJECTED -> ConnectionAttemptTerminalOutcome.REJECTED
        AttemptOutcome.TIMED_OUT -> ConnectionAttemptTerminalOutcome.TIMED_OUT
        AttemptOutcome.BUSY -> ConnectionAttemptTerminalOutcome.BUSY
        AttemptOutcome.CANCELED -> ConnectionAttemptTerminalOutcome.CANCELED
        AttemptOutcome.GLARE_LOST -> ConnectionAttemptTerminalOutcome.GLARE_LOST
        AttemptOutcome.DISCONNECTED -> ConnectionAttemptTerminalOutcome.DISCONNECTED
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
        const val MAX_TERMINAL_OUTCOMES = 128
        const val MAX_TOMBSTONES = 128
        const val TOMBSTONE_TTL_MS = 60_000L
        const val LOCAL_DISCONNECT_REASON = "LOCAL_DISCONNECT"
    }
}
