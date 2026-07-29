package com.kuma.motointercom

import java.util.UUID

internal data class SignalingControlDecision(
    val accepted: Boolean,
    val state: IntercomState? = null,
    val effects: List<SessionEffect> = emptyList()
)

private data class TerminalAttemptRecord(
    val attempt: ConnectionAttempt,
    val outcome: ConnectionAttemptTerminalOutcome
)

private data class TargetedTransportRace(
    val attempt: ConnectionAttempt,
    val openedTransports: Set<Transport>,
    val readyTransports: Set<Transport>,
    val failedTransports: Set<Transport> = emptySet(),
    val retiredTransports: Set<Transport> = emptySet(),
    val fallbackMilestone: AttemptMilestone.FallbackTransport? = null,
    val fallbackDue: Boolean = false
)

internal class SignalingControlCoordinator(
    private val clock: MonotonicClock,
    private val attemptTimeoutMs: Long,
    private val fallbackDelayMs: Long = 5_000L,
    private val recoveryFallbackDelayMs: Long = 3_000L,
    private val recoveryRetryBackoffMs: Long = 1_500L,
    private val optimizationWindowMs: Long = 1_000L,
    private val confirmationTimeoutMs: Long = 15_000L,
    private val actionNonce: () -> String = { UUID.randomUUID().toString() },
    private val attemptIdFactory: () -> ConnectionAttemptId = ConnectionAttemptId::create
) {
    init {
        require(attemptTimeoutMs > 0L) { "Attempt timeout must be positive" }
        require(fallbackDelayMs in 1 until attemptTimeoutMs) {
            "Fallback delay must be inside the total attempt timeout"
        }
        require(recoveryFallbackDelayMs in 1 until attemptTimeoutMs) {
            "Recovery fallback delay must be inside the total attempt timeout"
        }
        require(recoveryRetryBackoffMs in 0 until attemptTimeoutMs) {
            "Recovery retry backoff must fit inside the total attempt timeout"
        }
        require(optimizationWindowMs > 0L) { "Optimization window must be positive" }
        require(fallbackDelayMs + optimizationWindowMs <= attemptTimeoutMs) {
            "Fallback plus optimization must fit inside the total attempt timeout"
        }
        require(recoveryFallbackDelayMs + optimizationWindowMs <= attemptTimeoutMs) {
            "Recovery fallback plus optimization must fit inside the total attempt timeout"
        }
        require(confirmationTimeoutMs > 0L) { "Confirmation timeout must be positive" }
    }
    private val channels = linkedMapOf<ControlChannelId, VerifiedControlChannel>()
    private val completedAttempts = linkedMapOf<WireRequestKey, CompletedWireAttempt>()
    private val terminalAttempts =
        linkedMapOf<ConnectionAttemptId, TerminalAttemptRecord>()
    @Volatile
    private var ownedAttempt: ConnectionAttempt? = null
    @Volatile
    private var active: AttemptChannelSet? = null
    @Volatile
    private var pendingInbound: PendingInboundRequest? = null
    @Volatile
    private var targetedTransportRace: TargetedTransportRace? = null

    internal val currentAttempt: ConnectionAttempt?
        get() = ownedAttempt

    internal val activeAttempt: AttemptChannelSet?
        get() = active

    internal val pendingInboundRequest: PendingInboundRequest?
        get() = pendingInbound

    internal fun terminalOutcome(
        attemptId: ConnectionAttemptId
    ): ConnectionAttemptTerminalOutcome? = terminalAttempts[attemptId]?.outcome

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
            is SessionEvent.TargetedTransportOverlapUnavailable ->
                targetedTransportOverlapUnavailable(current, event)
            is SessionEvent.RecoveryTransportReady -> recoveryTransportReady(current, event)
            is SessionEvent.AttemptTimedOut -> attemptTimedOut(current, event)
            is SessionEvent.AttemptMilestoneElapsed -> attemptMilestoneElapsed(current, event)
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
            targetedTransportRace = null
            completedAttempts.clear()
            terminalAttempts.clear()
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
        val plan = when {
            Transport.LAN in event.availableTransports &&
                Transport.WIFI_DIRECT in event.availableTransports ->
                ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT)
            Transport.LAN in event.availableTransports -> ChannelPlan.single(Transport.LAN)
            Transport.WIFI_DIRECT in event.availableTransports ->
                ChannelPlan.single(Transport.WIFI_DIRECT)
            else -> return rejected()
        }
        val newAttemptId = attemptIdFactory()
        if (terminalOutcome(newAttemptId) != null) return rejected()
        val attempt = ConnectionAttempt(
            id = newAttemptId,
            runtimeSessionId = event.runtimeSessionId,
            targetLock = TargetLock(event.targetDeviceId, event.targetSessionId),
            trigger = ConnectionTrigger.USER,
            channelPlan = plan,
            deadlineElapsedRealtimeMs = newAttemptDeadline().elapsedRealtimeMs
        )
        ownedAttempt = attempt
        targetedTransportRace = createTargetedTransportRace(attempt)
        val fallbackMilestone = targetedTransportRace?.fallbackMilestone
        return accepted(
            state = IntercomState.Connecting(attempt),
            effects = listOfNotNull(
                SessionEffect.ScheduleAttemptDeadline(attempt),
                SessionEffect.OpenTargetedTransport(attempt, attempt.preferredTransport),
                fallbackMilestone?.let(SessionEffect::ScheduleAttemptMilestone)
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
        targetedTransportRace = null
        return accepted(transition.state, transition.effects)
    }

    private fun targetedTransportOpenFailed(
        current: IntercomState,
        event: SessionEvent.TargetedTransportOpenFailed
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(event.runtimeSessionId, event.attemptId)
            ?.takeIf {
                current.connectionAttemptOrNull() == it &&
                    event.transport in it.channelPlan
            }
            ?: return rejected()
        val race = targetedTransportRace?.takeIf { it.attempt == attempt }
            ?: return terminateOwnedAttempt(
                current,
                attempt,
                ConnectionAttemptTerminalOutcome.FAILED
            )
        if (event.transport !in race.openedTransports) return rejected()
        val updated = race.copy(failedTransports = race.failedTransports + event.transport)
        targetedTransportRace = updated
        val liveTransports = active
            ?.takeIf { it.attempt == attempt }
            ?.channelIds
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { channels[it]?.transport }
            .minus(updated.retiredTransports)
        val viableOpened = (
            updated.openedTransports - updated.failedTransports - updated.retiredTransports
            ) + liveTransports
        val pending = attempt.channelPlan.plannedTransports - updated.openedTransports
        return if (viableOpened.isNotEmpty() || pending.isNotEmpty()) {
            accepted(state = current)
        } else {
            terminateOwnedAttempt(current, attempt, ConnectionAttemptTerminalOutcome.FAILED)
        }
    }

    private fun targetedTransportOverlapUnavailable(
        current: IntercomState,
        event: SessionEvent.TargetedTransportOverlapUnavailable
    ): SignalingControlDecision {
        val attempt = ownedAttempt?.takeIf {
            it == event.attempt &&
                current.connectionAttemptOrNull() == it &&
                !it.isExpiredAt(clock.now()) &&
                event.transport == it.channelPlan.fallbackTransport
        } ?: return rejected()
        val race = targetedTransportRace?.takeIf {
            it.attempt == attempt &&
                event.transport in it.openedTransports &&
                event.transport !in it.failedTransports &&
                attempt.preferredTransport !in it.retiredTransports
        } ?: return rejected()
        if (
            terminalOutcome(attempt.id) != null ||
            active?.takeIf { it.attempt == attempt }?.mediaOwnerChannelId != null ||
            channels.values.any {
                it.requestRole == RequestRole.REQUESTER &&
                    it.originatingAttempt == attempt &&
                    it.transport == attempt.preferredTransport
            }
        ) {
            return rejected()
        }
        targetedTransportRace = race.copy(
            retiredTransports = race.retiredTransports + attempt.preferredTransport
        )
        return accepted(
            state = current,
            effects = listOf(
                SessionEffect.RetireTargetedTransport(
                    attempt,
                    attempt.preferredTransport
                ),
                SessionEffect.OpenTargetedTransport(attempt, event.transport)
            )
        )
    }

    private fun attemptMilestoneElapsed(
        current: IntercomState,
        event: SessionEvent.AttemptMilestoneElapsed
    ): SignalingControlDecision = when (val milestone = event.milestone) {
        is AttemptMilestone.FallbackTransport -> fallbackTransportDue(current, milestone)
        is AttemptMilestone.MediaOptimization -> mediaOptimizationDue(current, milestone)
    }

    private fun fallbackTransportDue(
        current: IntercomState,
        milestone: AttemptMilestone.FallbackTransport
    ): SignalingControlDecision {
        val attempt = matchingOwnedAttempt(
            milestone.attempt.runtimeSessionId,
            milestone.attempt.id
        )?.takeIf {
            it == milestone.attempt &&
                current.connectionAttemptOrNull() == it &&
                !it.isExpiredAt(clock.now())
        } ?: return rejected()
        val race = targetedTransportRace?.takeIf {
            it.attempt == attempt && it.fallbackMilestone == milestone
        } ?: return rejected()
        if (clock.now().elapsedRealtimeMs < milestone.scheduledAt.elapsedRealtimeMs) {
            return rejected()
        }
        if (
            race.fallbackDue ||
            milestone.transport in race.openedTransports ||
            active?.takeIf { it.attempt == attempt }?.mediaOwnerChannelId != null ||
            terminalOutcome(attempt.id) != null
        ) {
            return rejected()
        }
        if (milestone.transport !in race.readyTransports) {
            targetedTransportRace = race.copy(fallbackDue = true)
            return accepted(state = current)
        }
        targetedTransportRace = race.copy(
            openedTransports = race.openedTransports + milestone.transport,
            fallbackDue = true
        )
        return accepted(
            state = current,
            effects = listOf(SessionEffect.OpenTargetedTransport(attempt, milestone.transport))
        )
    }

    private fun recoveryTransportReady(
        current: IntercomState,
        event: SessionEvent.RecoveryTransportReady
    ): SignalingControlDecision {
        val recovering = current as? IntercomState.Recovering ?: return rejected()
        val now = clock.now()
        val attempt = ownedAttempt?.takeIf {
            it == event.attempt &&
                recovering.attempt == it &&
                it.trigger == ConnectionTrigger.RECOVERY &&
                event.transport in it.channelPlan &&
                !it.isExpiredAt(now) &&
                terminalOutcome(it.id) == null
        } ?: return rejected()
        val race = targetedTransportRace?.takeIf {
            it.attempt == attempt && event.transport !in it.readyTransports
        } ?: return rejected()
        val fallbackMilestone = race.fallbackMilestone
        val fallbackDue = race.fallbackDue || fallbackMilestone?.let {
            now.elapsedRealtimeMs >= it.scheduledAt.elapsedRealtimeMs
        } == true
        val readyTransports = race.readyTransports + event.transport
        val transportsToOpen = buildSet {
            if (
                attempt.preferredTransport in readyTransports &&
                attempt.preferredTransport !in race.openedTransports
            ) {
                add(attempt.preferredTransport)
            }
            fallbackMilestone?.transport?.takeIf {
                fallbackDue && it in readyTransports && it !in race.openedTransports
            }?.let(::add)
        }
        targetedTransportRace = race.copy(
            openedTransports = race.openedTransports + transportsToOpen,
            readyTransports = readyTransports,
            fallbackDue = fallbackDue
        )
        return accepted(
            state = current,
            effects = transportsToOpen.map {
                SessionEffect.OpenTargetedTransport(attempt, it)
            }
        )
    }

    private fun mediaOptimizationDue(
        current: IntercomState,
        milestone: AttemptMilestone.MediaOptimization
    ): SignalingControlDecision {
        val context = active?.takeIf {
            it.phase == SignalingAttemptPhase.OPTIMIZING_MEDIA &&
                it.attempt == milestone.attempt &&
                it.wireRequestKey == milestone.wireRequestKey &&
                it.optimizationMilestone == milestone &&
                current.connectionAttemptOrNull() == it.attempt
        } ?: return rejected()
        if (context.attempt.isExpiredAt(clock.now())) {
            return terminateOwnedAttempt(
                current,
                context.attempt,
                ConnectionAttemptTerminalOutcome.TIMED_OUT
            )
        }
        if (clock.now().elapsedRealtimeMs < milestone.scheduledAt.elapsedRealtimeMs) {
            return rejected()
        }
        val remaining = context.channelIds.filterTo(linkedSetOf()) { it in channels }
        if (remaining.isEmpty()) return finishAttemptImmediately(current, context)
        val cohort = SelectionCohort(
            context.wireRequestKey,
            remaining,
            nowElapsedRealtimeMs()
        )
        active = context.copy(
            channelIds = remaining,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            selectionCohort = cohort,
            optimizationMilestone = null
        )
        return accepted(
            state = current,
            effects = listOf(selectEffect(context.attempt, cohort))
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
        val winnerTransport = winnerTransport(attempt) ?: return rejected()
        if (!recordTerminal(attempt, ConnectionAttemptTerminalOutcome.SUCCESS)) return rejected()
        targetedTransportRace = null
        return accepted(IntercomState.Connected(attempt, peer, connectedAt, winnerTransport))
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
        is IntercomState.Recovering -> if (restartConnectedDiscovery) {
            restartRecoveringAttempt(current, attempt)
        } else {
            accepted(state = current)
        }
        else -> rejected()
    }

    private fun restartRecoveringAttempt(
        current: IntercomState.Recovering,
        attempt: ConnectionAttempt
    ): SignalingControlDecision {
        active?.takeIf { it.attempt == attempt }?.let {
            rememberDisconnectedIfAccepted(it)
            forgetActiveChannels(it)
        }
        targetedTransportRace = createTargetedTransportRace(
            attempt,
            preferredTransportOpened = false
        )
        return accepted(
            state = current,
            effects = listOfNotNull(
                SessionEffect.RestartDiscovery(attempt.runtimeSessionId, attempt),
                SessionEffect.ScheduleAttemptDeadline(attempt),
                targetedTransportRace?.fallbackMilestone?.let(
                    SessionEffect::ScheduleAttemptMilestone
                )
            )
        )
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
        val recoveryPlan = current.attempt.channelPlan.orderedForRecovery(current.transport)
        val recoveryAttempt = ConnectionAttempt(
            id = recoveryAttemptId,
            runtimeSessionId = current.attempt.runtimeSessionId,
            targetLock = current.attempt.targetLock,
            trigger = ConnectionTrigger.RECOVERY,
            channelPlan = recoveryPlan,
            deadlineElapsedRealtimeMs = newAttemptDeadline().elapsedRealtimeMs
        )
        if (existingOutcome == null) recordTerminal(current.attempt, outcome)
        ownedAttempt = recoveryAttempt
        targetedTransportRace = createTargetedTransportRace(
            recoveryAttempt,
            preferredTransportOpened = !restartConnectedDiscovery
        )
        val fallbackMilestone = targetedTransportRace?.fallbackMilestone
        return accepted(
            state = IntercomState.Recovering(
                recoveryAttempt,
                current.peer,
                consecutiveFinalFailures = 0
            ),
            effects = if (restartConnectedDiscovery) {
                listOfNotNull(
                    SessionEffect.RestartDiscovery(
                        recoveryAttempt.runtimeSessionId,
                        recoveryAttempt
                    ),
                    SessionEffect.ScheduleAttemptDeadline(recoveryAttempt),
                    fallbackMilestone?.let(SessionEffect::ScheduleAttemptMilestone)
                )
            } else {
                listOfNotNull(
                    SessionEffect.ScheduleAttemptDeadline(recoveryAttempt),
                    SessionEffect.OpenTargetedTransport(
                        recoveryAttempt,
                        recoveryAttempt.preferredTransport
                    ),
                    fallbackMilestone?.let(SessionEffect::ScheduleAttemptMilestone)
                )
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
        return finishRecoveryAttempt(
            current = recovering,
            attempt = attempt,
            outcome = ConnectionAttemptTerminalOutcome.FAILED,
            context = active?.takeIf { it.attempt == attempt }
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
                closeEffect(pending.runtimeSessionId, pending.attemptId, it, pending.targetLock)
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
        val retiredTransports = targetedTransportRace
            ?.takeIf { it.attempt == attempt }
            ?.retiredTransports
            .orEmpty()
        if (
            ownedAttempt != attempt ||
            current.connectionAttemptOrNull() != attempt ||
            channel.transport !in attempt.channelPlan ||
            channel.transport in retiredTransports ||
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
                    preferredTransportHint = attempt.channelPlan.fallbackTransport?.let {
                        attempt.preferredTransport
                    }
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
            return acceptGlareWinner(
                current,
                channel,
                event.preferredTransportHint,
                event.occurredAtElapsedMs
            )
        }
        if (current is IntercomState.Discovering) {
            return when {
                policy.paired -> beginPairedInboundConnection(
                    current,
                    channel,
                    event.preferredTransportHint,
                    event.occurredAtElapsedMs
                )
                policy.confirmationAvailability.preferredSurface() != null ->
                    beginInboundConfirmation(
                        current,
                        channel,
                        event.preferredTransportHint,
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
        if (
            channel.channelId in context.channelIds &&
            channel.targetLock == context.attempt.targetLock &&
            channel.peer == context.peer
        ) {
            return accepted(state = current)
        }
        val reject = {
            accepted(
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
        val existingTransports = context.channelIds.mapNotNullTo(linkedSetOf()) {
            channels[it]?.transport
        }
        val now = clock.now()
        val optimizationMilestone = context.optimizationMilestone
        if (
            channel.transport !in context.attempt.channelPlan ||
            channel.transport in existingTransports ||
            channel.transport != context.attempt.preferredTransport ||
            channel.targetLock != context.attempt.targetLock ||
            channel.peer != context.peer ||
            context.phase != SignalingAttemptPhase.OPTIMIZING_MEDIA ||
            optimizationMilestone == null ||
            now.elapsedRealtimeMs >= optimizationMilestone.scheduledAt.elapsedRealtimeMs ||
            context.attempt.isExpiredAt(now)
        ) {
            return reject()
        }
        val channelIds = context.channelIds + channel.channelId
        val cohort = SelectionCohort(
            context.wireRequestKey,
            channelIds,
            nowElapsedRealtimeMs()
        )
        active = context.copy(
            channelIds = channelIds,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            selectionCohort = cohort,
            optimizationMilestone = null
        )
        return accepted(state = current, effects = listOf(selectEffect(context.attempt, cohort)))
    }

    private fun beginInboundConfirmation(
        current: IntercomState.Discovering,
        channel: VerifiedControlChannel,
        preferredTransportHint: Transport?,
        surface: ConfirmationSurface
    ): SignalingControlDecision {
        val plan = inboundChannelPlan(channel.transport, preferredTransportHint)
        val channelIds = eligibleChannels(channel.wireRequestKey, plan)
        val pending = PendingInboundRequest(
            runtimeSessionId = current.runtimeSessionId,
            wireRequestKey = channel.wireRequestKey,
            targetLock = channel.targetLock,
            peer = channel.peer,
            transport = channel.transport,
            channelPlan = plan,
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
        preferredTransportHint: Transport?,
        occurredAtElapsedMs: Long
    ): SignalingControlDecision {
        val deadlineAt = deadlineAfter(occurredAtElapsedMs, attemptTimeoutMs)
        if (clock.now().elapsedRealtimeMs >= deadlineAt.elapsedRealtimeMs) {
            return rejectInboundWithoutConfirmation(current, channel, RejectReason.TIMEOUT)
        }
        val plan = inboundChannelPlan(channel.transport, preferredTransportHint)
        val eligible = eligibleChannels(channel.wireRequestKey, plan)
        if (eligible.isEmpty()) return rejected()
        val attempt = inboundAttempt(
            runtimeSessionId = current.runtimeSessionId,
            channel = channel,
            channelPlan = plan,
            deadlineAt = deadlineAt
        )
        ownedAttempt = attempt
        return beginMediaSelection(
            current = current,
            attempt = attempt,
            peer = channel.peer,
            wireRequestKey = channel.wireRequestKey,
            channelIds = eligible,
            prefixEffects = listOf(SessionEffect.ScheduleAttemptDeadline(attempt))
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
        preferredTransportHint: Transport?,
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

        val plan = inboundChannelPlan(channel.transport, preferredTransportHint)
        val eligible = eligibleChannels(channel.wireRequestKey, plan)
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
            channelPlan = plan,
            deadlineAt = current.attempt.deadlineAt
        )
        ownedAttempt = attempt
        val effects = losingChannelIds.map {
            SessionEffect.CloseControlChannel(
                runtimeSessionId = current.runtimeSessionId,
                attemptId = current.attempt.id,
                channelId = it,
                targetLock = current.attempt.targetLock
            )
        } + SessionEffect.ScheduleAttemptDeadline(attempt)
        return beginMediaSelection(
            current = current,
            attempt = attempt,
            peer = channel.peer,
            wireRequestKey = channel.wireRequestKey,
            channelIds = eligible,
            prefixEffects = effects
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
            channels[channelId]?.transport?.let { it in pending.channelPlan } == true
        }
        if (eligible.isEmpty()) return finishPendingImmediately(current, pending)
        val confirmationChannel = channels[pending.confirmationChannelId]
            ?: return finishPendingImmediately(current, pending)
        val acceptedAttempt = inboundAttempt(
            runtimeSessionId = pending.runtimeSessionId,
            channel = confirmationChannel,
            channelPlan = pending.channelPlan,
            deadlineAt = acceptedDeadline
        )
        ownedAttempt = acceptedAttempt
        pendingInbound = null
        return beginMediaSelection(
            current = current,
            attempt = acceptedAttempt,
            peer = pending.peer,
            wireRequestKey = pending.wireRequestKey,
            channelIds = eligible,
            prefixEffects = listOf(
                cancelConfirmationEffect(pending),
                SessionEffect.ScheduleAttemptDeadline(acceptedAttempt)
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
        if (context.attempt.isExpiredAt(clock.now())) {
            return terminateOwnedAttempt(
                current,
                context.attempt,
                ConnectionAttemptTerminalOutcome.TIMED_OUT
            )
        }
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
            mediaOwnerChannelId = selected,
            optimizationMilestone = null
        )
        targetedTransportRace = null
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
                event.channelId,
                event.wireRequestKey
            )
        if (context.mediaOwnerChannelId != null) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId,
                event.wireRequestKey
            )
        }
        if (current.connectionAttemptOrNull() != context.attempt) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId,
                event.wireRequestKey
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
        targetedTransportRace = null
        val effects = losing.map {
            SessionEffect.CloseControlChannel(
                runtimeSessionId = context.attempt.runtimeSessionId,
                attemptId = context.attempt.id,
                channelId = it,
                targetLock = context.attempt.targetLock
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
            event.channelId,
            event.wireRequestKey
        )
        if (event.reason.scope == ResponseScope.CHANNEL) {
            return removeChannelAndContinueOrTerminate(current, context, event.channelId)
        }
        if (context.terminalOutcome == AttemptOutcome.ACCEPTED) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId,
                event.wireRequestKey
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
            event.channelId,
            event.wireRequestKey
        )
        if (context.terminalOutcome == AttemptOutcome.ACCEPTED) {
            return closeConflictingChannel(
                current,
                event.runtimeSessionId,
                event.attemptId,
                event.channelId,
                event.wireRequestKey
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
            event.channelId,
            event.wireRequestKey
        )
        if (context.mediaOwnerChannelId != event.channelId) {
            return removeChannelAndContinueOrTerminate(current, context, event.channelId)
        }
        remember(context.wireRequestKey, AttemptOutcome.DISCONNECTED, null)
        return finishActiveSessionDisconnect(
            current,
            context,
            logicalOutcome = ConnectionAttemptTerminalOutcome.CANCELED
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
                    SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt)
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
            return finishActiveSessionDisconnect(
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
            closeEffect(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                it,
                context.attempt.targetLock
            )
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
        pendingInbound?.takeIf {
            it.runtimeSessionId == event.runtimeSessionId &&
                it.attemptId == event.attemptId &&
                event.channelId in it.channelIds
        }?.let {
            return removePendingChannel(current, it, event.channelId, closeRemoved = true)
        }
        val context = active?.takeIf {
            it.attempt.runtimeSessionId == event.runtimeSessionId &&
                it.attempt.id == event.attemptId &&
                event.channelId in it.channelIds
        }
        if (context == null) {
            val removedChannel = channels[event.channelId]
                ?.takeIf {
                    current.runtimeSessionId == event.runtimeSessionId &&
                        it.wireRequestKey.attemptId == event.attemptId
                }
                ?: return rejected()
            channels.remove(event.channelId)
            return accepted(
                effects = listOf(
                    closeEffect(
                        event.runtimeSessionId,
                        event.attemptId,
                        event.channelId,
                        removedChannel.targetLock
                    )
                )
            )
        }
        channels.remove(event.channelId)
        if (
            current is IntercomState.Connected &&
            context.phase == SignalingAttemptPhase.CONNECTED &&
            context.mediaOwnerChannelId == event.channelId
        ) {
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
            effects = listOf(
                closeEffect(
                    event.runtimeSessionId,
                    event.attemptId,
                    event.channelId,
                    context.attempt.targetLock
                )
            )
        )
    }

    private fun channelClosed(
        current: IntercomState,
        event: SessionEvent.ChannelClosed
    ): SignalingControlDecision {
        pendingInbound?.takeIf {
            it.runtimeSessionId == event.runtimeSessionId &&
                it.wireRequestKey == event.wireRequestKey &&
                event.channelId in it.channelIds
        }?.let {
            return removePendingChannel(current, it, event.channelId, closeRemoved = false)
        }
        val context = active
            ?.takeIf {
                it.attempt.runtimeSessionId == event.runtimeSessionId &&
                    it.wireRequestKey == event.wireRequestKey &&
                    event.channelId in it.channelIds
            }
        if (context == null) {
            val storedChannel = channels[event.channelId]
                ?.takeIf {
                    current.runtimeSessionId == event.runtimeSessionId &&
                        it.wireRequestKey == event.wireRequestKey
                }
                ?: return rejected()
            channels.remove(storedChannel.channelId)
            return accepted(state = current)
        }
        channels.remove(event.channelId)
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
        if (
            context.phase == SignalingAttemptPhase.OPTIMIZING_MEDIA &&
            cohort != null &&
            remaining.any { channels[it]?.transport == context.attempt.preferredTransport }
        ) {
            active = requireNotNull(active).copy(
                phase = SignalingAttemptPhase.SELECTING_MEDIA,
                optimizationMilestone = null
            )
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
            it.runtimeSessionId == event.runtimeSessionId &&
                it.wireRequestKey == event.wireRequestKey &&
                event.channelId in it.channelIds
        }?.let {
            return removePendingChannel(current, it, event.channelId, closeRemoved = true)
        }
        val context = active?.takeIf {
            it.attempt.runtimeSessionId == event.runtimeSessionId &&
                it.wireRequestKey == event.wireRequestKey &&
                event.channelId in it.channelIds
        }
        if (context == null) {
            val storedChannel = channels[event.channelId]
                ?.takeIf {
                    current.runtimeSessionId == event.runtimeSessionId &&
                        it.wireRequestKey == event.wireRequestKey
                }
                ?: return rejected()
            channels.remove(storedChannel.channelId)
            return accepted(
                state = current,
                effects = listOf(
                    closeEffect(
                        event.runtimeSessionId,
                        event.wireRequestKey.attemptId,
                        event.channelId,
                        storedChannel.targetLock
                    )
                )
            )
        }
        val isOwner = context.mediaOwnerChannelId == event.channelId
        return if (isOwner) {
            finishAttemptImmediately(current, context)
        } else {
            removeChannelAndContinueOrTerminate(current, context, event.channelId)
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
        val context = active?.takeIf {
            it.attempt.runtimeSessionId == event.runtimeSessionId &&
                it.attempt.id == event.attemptId &&
                channelId in it.channelIds
        }
        val storedChannel = channels[channelId]
        val targetLock = context?.attempt?.targetLock ?: storedChannel
            ?.takeIf {
                current.runtimeSessionId == event.runtimeSessionId &&
                    it.wireRequestKey.attemptId == event.attemptId &&
                    completedAttempts[it.wireRequestKey] != null
            }
            ?.targetLock
            ?: return rejected()
        channels.remove(channelId)
        val close = closeEffect(
            event.runtimeSessionId,
            event.attemptId,
            channelId,
            targetLock
        )
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
                closeEffect(
                    context.attempt.runtimeSessionId,
                    context.attempt.id,
                    channelId,
                    context.attempt.targetLock
                )
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
            listOf(
                closeEffect(
                    pending.runtimeSessionId,
                    pending.attemptId,
                    channelId,
                    pending.targetLock
                )
            )
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
                    closeEffect(
                        pending.runtimeSessionId,
                        pending.attemptId,
                        it,
                        pending.targetLock
                    )
                }
        )
    }

    private fun finishAttemptImmediately(
        current: IntercomState,
        context: AttemptChannelSet,
        prefixEffects: List<SessionEffect> = emptyList(),
        logicalOutcome: ConnectionAttemptTerminalOutcome? = null
    ): SignalingControlDecision {
        if (context.terminalOutcome == AttemptOutcome.DISCONNECTED) {
            return finishActiveSessionDisconnect(
                current,
                context,
                prefixEffects,
                ConnectionAttemptTerminalOutcome.CANCELED
            )
        }
        val terminalOutcome = logicalOutcome ?: context.terminalOutcome?.toLogicalTerminalOutcome()
            ?: ConnectionAttemptTerminalOutcome.FAILED
        if (
            current is IntercomState.Recovering &&
            current.attempt == context.attempt &&
            terminalOutcome.countsAsRecoveryFinalFailure()
        ) {
            return finishRecoveryAttempt(
                current = current,
                attempt = context.attempt,
                outcome = terminalOutcome,
                prefixEffects = prefixEffects,
                context = context
            )
        }
        recordTerminal(
            context.attempt,
            terminalOutcome
        )
        rememberDisconnectedIfAccepted(context)
        val channelIds = context.channelIds
        channelIds.forEach(channels::remove)
        active = null
        clearOwnedAttempt(context.attempt)
        val closeEffects = channelIds.map {
            closeEffect(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                it,
                context.attempt.targetLock
            )
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
        if (
            channel.transport !in pending.channelPlan ||
            channel.targetLock != pending.targetLock ||
            channel.peer != pending.peer
        ) {
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
            val targetLock = channels[channelId]?.targetLock ?: return accepted()
            accepted(
                effects = listOf(
                    closeEffect(
                        runtimeSessionId,
                        completed.key.attemptId,
                        channelId,
                        targetLock
                    )
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
        channelId: ControlChannelId,
        wireRequestKey: WireRequestKey
    ): SignalingControlDecision {
        val context = active?.takeIf {
            it.attempt.runtimeSessionId == runtimeSessionId &&
                it.attempt.id == attemptId &&
                it.wireRequestKey == wireRequestKey
        }
        if (context != null && channelId in context.channelIds) {
            return accepted(state = current)
        }
        val targetLock = context?.attempt?.targetLock ?: terminalAttempts[attemptId]
            ?.takeIf {
                it.attempt.runtimeSessionId == runtimeSessionId &&
                    completedAttempts[wireRequestKey]?.key?.attemptId == attemptId
            }
            ?.attempt
            ?.targetLock
            ?: return rejected()
        return accepted(
            state = current,
            effects = listOf(
                closeEffect(
                    runtimeSessionId,
                    attemptId,
                    channelId,
                    targetLock
                )
            )
        )
    }

    private fun beginMediaSelection(
        current: IntercomState,
        attempt: ConnectionAttempt,
        peer: PeerIdentity,
        wireRequestKey: WireRequestKey,
        channelIds: Set<ControlChannelId>,
        prefixEffects: List<SessionEffect> = emptyList()
    ): SignalingControlDecision {
        if (attempt.isExpiredAt(clock.now())) {
            return terminateOwnedAttempt(
                current,
                attempt,
                ConnectionAttemptTerminalOutcome.TIMED_OUT
            )
        }
        val cohort = SelectionCohort(wireRequestKey, channelIds, nowElapsedRealtimeMs())
        val preferredReady = channelIds.any {
            channels[it]?.transport == attempt.preferredTransport
        }
        if (attempt.channelPlan.fallbackTransport != null && !preferredReady) {
            val milestone = AttemptMilestone.MediaOptimization(
                attempt = attempt,
                wireRequestKey = wireRequestKey,
                scheduledAt = optimizationAt(attempt)
            )
            active = AttemptChannelSet(
                wireRequestKey = wireRequestKey,
                attempt = attempt,
                peer = peer,
                channelIds = channelIds,
                phase = SignalingAttemptPhase.OPTIMIZING_MEDIA,
                selectionCohort = cohort,
                optimizationMilestone = milestone
            )
            return accepted(
                state = IntercomState.Optimizing(attempt, peer),
                effects = prefixEffects + SessionEffect.ScheduleAttemptMilestone(milestone)
            )
        }
        active = AttemptChannelSet(
            wireRequestKey = wireRequestKey,
            attempt = attempt,
            peer = peer,
            channelIds = channelIds,
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            selectionCohort = cohort
        )
        return accepted(
            state = IntercomState.Connecting(attempt, peer),
            effects = prefixEffects + selectEffect(attempt, cohort)
        )
    }

    private fun inboundAttempt(
        runtimeSessionId: RuntimeSessionId,
        channel: VerifiedControlChannel,
        channelPlan: ChannelPlan,
        deadlineAt: MonotonicTimestamp
    ): ConnectionAttempt = ConnectionAttempt(
        id = channel.wireRequestKey.attemptId,
        runtimeSessionId = runtimeSessionId,
        targetLock = channel.targetLock,
        trigger = ConnectionTrigger.INBOUND,
        channelPlan = channelPlan,
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

    private fun optimizationAt(attempt: ConnectionAttempt): MonotonicTimestamp =
        MonotonicTimestamp(
            minOf(
                Math.addExact(nowElapsedRealtimeMs(), optimizationWindowMs),
                attempt.deadlineElapsedRealtimeMs
            )
        )

    private fun inboundChannelPlan(
        channelTransport: Transport,
        preferredTransportHint: Transport?
    ): ChannelPlan {
        if (preferredTransportHint == null) return ChannelPlan.single(channelTransport)
        val fallback = Transport.entries.single { it != preferredTransportHint }
        return ChannelPlan.race(preferredTransportHint, fallback).also {
            require(channelTransport in it) { "Inbound channel must belong to the hinted plan" }
        }
    }

    private fun eligibleChannels(
        wireRequestKey: WireRequestKey,
        transport: Transport
    ): Set<ControlChannelId> = channels.values
        .filter { it.wireRequestKey == wireRequestKey && it.transport == transport }
        .mapTo(linkedSetOf()) { it.channelId }

    private fun eligibleChannels(
        wireRequestKey: WireRequestKey,
        channelPlan: ChannelPlan
    ): Set<ControlChannelId> = channels.values
        .filter { it.wireRequestKey == wireRequestKey && it.transport in channelPlan }
        .mapTo(linkedSetOf()) { it.channelId }

    private fun winnerTransport(attempt: ConnectionAttempt): Transport? {
        val context = active?.takeIf {
            it.attempt == attempt &&
                it.mediaOwnerChannelId != null &&
                it.terminalOutcome == AttemptOutcome.ACCEPTED
        }
        val owner = context?.mediaOwnerChannelId
        val channel = owner?.let(channels::get)?.takeIf {
            it.wireRequestKey == context.wireRequestKey &&
                it.targetLock == attempt.targetLock &&
                it.peer == context.peer &&
                it.transport in attempt.channelPlan
        }
        if (channel != null) return channel.transport
        return attempt.preferredTransport.takeIf {
            attempt.channelPlan.fallbackTransport == null
        }
    }

    private fun selectEffect(
        attempt: ConnectionAttempt,
        cohort: SelectionCohort
    ): SessionEffect.SelectMediaChannel = SessionEffect.SelectMediaChannel(
        runtimeSessionId = attempt.runtimeSessionId,
        attemptId = attempt.id,
        wireRequestKey = cohort.wireRequestKey,
        cohort = cohort,
        preferredTransport = attempt.preferredTransport
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
        channelId: ControlChannelId,
        targetLock: TargetLock
    ): SessionEffect.CloseControlChannel = SessionEffect.CloseControlChannel(
        runtimeSessionId,
        attemptId,
        channelId,
        targetLock
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
        if (
            current is IntercomState.Recovering &&
            current.attempt == attempt &&
            outcome.countsAsRecoveryFinalFailure()
        ) {
            return finishRecoveryAttempt(
                current = current,
                attempt = attempt,
                outcome = outcome,
                context = context
            )
        }
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
        if (targetedTransportRace?.attempt?.id == attempt.id) targetedTransportRace = null
    }

    private fun createTargetedTransportRace(
        attempt: ConnectionAttempt,
        preferredTransportOpened: Boolean = true
    ): TargetedTransportRace {
        val fallbackMilestone = attempt.channelPlan.fallbackTransport?.let {
            AttemptMilestone.FallbackTransport(
                attempt = attempt,
                transport = it,
                scheduledAt = fallbackAt(attempt)
            )
        }
        return TargetedTransportRace(
            attempt = attempt,
            openedTransports = if (preferredTransportOpened) {
                setOf(attempt.preferredTransport)
            } else {
                emptySet()
            },
            readyTransports = if (preferredTransportOpened) {
                attempt.channelPlan.plannedTransports
            } else {
                emptySet()
            },
            fallbackMilestone = fallbackMilestone
        )
    }

    private fun finishActiveSessionDisconnect(
        current: IntercomState,
        context: AttemptChannelSet,
        prefixEffects: List<SessionEffect> = emptyList(),
        logicalOutcome: ConnectionAttemptTerminalOutcome =
            ConnectionAttemptTerminalOutcome.CANCELED
    ): SignalingControlDecision {
        if (current.connectionAttemptOrNull() != context.attempt) return rejected()
        if (terminalOutcome(context.attempt.id) == null) {
            recordTerminal(context.attempt, logicalOutcome)
        }
        rememberDisconnectedIfAccepted(context)
        val channelIds = context.channelIds
        channelIds.forEach(channels::remove)
        if (active?.attempt == context.attempt) active = null
        clearOwnedAttempt(context.attempt)
        val closeEffects = channelIds.map {
            closeEffect(
                context.attempt.runtimeSessionId,
                context.attempt.id,
                it,
                context.attempt.targetLock
            )
        }
        return accepted(
            state = IntercomState.Discovering(context.attempt.runtimeSessionId),
            effects = prefixEffects + closeEffects +
                SessionEffect.ReleaseActiveSessionAndContinueDiscovery(context.attempt)
        )
    }

    private fun finishRecoveryAttempt(
        current: IntercomState.Recovering,
        attempt: ConnectionAttempt,
        outcome: ConnectionAttemptTerminalOutcome,
        prefixEffects: List<SessionEffect> = emptyList(),
        context: AttemptChannelSet? = null
    ): SignalingControlDecision {
        if (
            current.attempt != attempt ||
            !outcome.countsAsRecoveryFinalFailure() ||
            terminalOutcome(attempt.id) != null
        ) {
            return rejected()
        }
        val nextFailureCount = current.consecutiveFinalFailures + 1
        val retryAttempt = if (nextFailureCount < RECOVERY_RESET_FAILURE_THRESHOLD) {
            val retryAttemptId = attemptIdFactory()
            if (retryAttemptId == attempt.id || terminalOutcome(retryAttemptId) != null) {
                return rejected()
            }
            ConnectionAttempt(
                id = retryAttemptId,
                runtimeSessionId = attempt.runtimeSessionId,
                targetLock = attempt.targetLock,
                trigger = ConnectionTrigger.RECOVERY,
                channelPlan = attempt.channelPlan,
                deadlineElapsedRealtimeMs = newAttemptDeadline().elapsedRealtimeMs
            )
        } else {
            null
        }
        if (!recordTerminal(attempt, outcome)) return rejected()

        val channelIds = context?.channelIds.orEmpty()
        if (context != null) {
            rememberDisconnectedIfAccepted(context)
            channelIds.forEach(channels::remove)
            if (active?.attempt == attempt) active = null
        }
        clearOwnedAttempt(attempt)
        val closeEffects = channelIds.map {
            closeEffect(
                attempt.runtimeSessionId,
                attempt.id,
                it,
                attempt.targetLock
            )
        }

        if (retryAttempt == null) {
            return accepted(
                state = IntercomState.Resetting(
                    runtimeSessionId = attempt.runtimeSessionId,
                    targetDeviceId = attempt.targetDeviceId,
                    failedAttemptId = attempt.id,
                    consecutiveFinalFailures = nextFailureCount
                ),
                effects = prefixEffects + closeEffects +
                    SessionEffect.ResetWirelessEnvironment(
                        runtimeSessionId = attempt.runtimeSessionId,
                        targetDeviceId = attempt.targetDeviceId,
                        failedAttemptId = attempt.id,
                        consecutiveFinalFailures = nextFailureCount
                    )
            )
        }

        ownedAttempt = retryAttempt
        targetedTransportRace = createTargetedTransportRace(
            retryAttempt,
            preferredTransportOpened = false
        )
        val fallbackMilestone = targetedTransportRace?.fallbackMilestone
        return accepted(
            state = IntercomState.Recovering(
                attempt = retryAttempt,
                peer = current.peer,
                consecutiveFinalFailures = nextFailureCount
            ),
            effects = prefixEffects + closeEffects + listOfNotNull(
                SessionEffect.RestartDiscovery(
                    retryAttempt.runtimeSessionId,
                    retryAttempt,
                    restartDelayMillis = recoveryRetryBackoffMs
                ),
                SessionEffect.ScheduleAttemptDeadline(retryAttempt),
                fallbackMilestone?.let(SessionEffect::ScheduleAttemptMilestone)
            )
        )
    }

    private fun fallbackAt(attempt: ConnectionAttempt): MonotonicTimestamp {
        val startedAt = Math.subtractExact(
            attempt.deadlineElapsedRealtimeMs,
            attemptTimeoutMs
        )
        val delayMs = if (attempt.trigger == ConnectionTrigger.RECOVERY) {
            recoveryFallbackDelayMs
        } else {
            fallbackDelayMs
        }
        return MonotonicTimestamp(Math.addExact(startedAt, delayMs))
    }

    private fun recordTerminal(
        attempt: ConnectionAttempt,
        outcome: ConnectionAttemptTerminalOutcome
    ): Boolean {
        if (attempt.id in terminalAttempts) return false
        terminalAttempts[attempt.id] = TerminalAttemptRecord(attempt, outcome)
        while (terminalAttempts.size > MAX_TERMINAL_OUTCOMES) {
            terminalAttempts.remove(terminalAttempts.keys.first())
        }
        return true
    }

    private fun ConnectionAttemptTerminalOutcome.countsAsRecoveryFinalFailure(): Boolean =
        when (this) {
            ConnectionAttemptTerminalOutcome.TIMED_OUT,
            ConnectionAttemptTerminalOutcome.FAILED,
            ConnectionAttemptTerminalOutcome.REJECTED,
            ConnectionAttemptTerminalOutcome.BUSY,
            ConnectionAttemptTerminalOutcome.DISCONNECTED -> true
            ConnectionAttemptTerminalOutcome.SUCCESS,
            ConnectionAttemptTerminalOutcome.CANCELED,
            ConnectionAttemptTerminalOutcome.GLARE_LOST -> false
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
