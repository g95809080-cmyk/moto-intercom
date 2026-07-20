package com.kuma.motointercom

enum class WebRtcConnectionState {
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    OTHER
}

internal sealed interface SessionEvent {
    data class RuntimeStarted(val runtimeSessionId: RuntimeSessionId) : SessionEvent

    data class IncomingAccepted(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val actionNonce: String,
        val occurredAtElapsedMs: Long
    ) : SessionEvent

    data class IncomingRejected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val actionNonce: String,
        val occurredAtElapsedMs: Long
    ) : SessionEvent

    data class ConnectRequested(val attempt: ConnectionAttempt) : SessionEvent

    data class ConnectPresenceRequested(
        val runtimeSessionId: RuntimeSessionId,
        val targetDeviceId: String,
        val targetSessionId: RuntimeSessionId,
        val availableTransports: Set<Transport>
    ) : SessionEvent

    data class AttemptReplaced(val attempt: ConnectionAttempt) : SessionEvent

    data class TunnelReady(
        val attempt: ConnectionAttempt,
        val peer: PeerIdentity,
        val transport: Transport
    ) : SessionEvent

    data class ControlChannelVerified(
        val runtimeSessionId: RuntimeSessionId,
        val channel: VerifiedControlChannel
    ) : SessionEvent

    data class IncomingConnectRequest(
        val runtimeSessionId: RuntimeSessionId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey,
        val trigger: RequestTrigger,
        val preferredTransportHint: Transport?,
        val occurredAtElapsedMs: Long
    ) : SessionEvent

    data class RemoteConnectAccepted(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey
    ) : SessionEvent

    data class RemoteConnectRejected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey,
        val reason: RejectReason,
        val retryable: Boolean
    ) : SessionEvent

    data class RemoteBusy(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey,
        val reason: BusyReason,
        val retryAfterMs: Long?
    ) : SessionEvent

    data class RemoteDisconnect(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey,
        val reason: DisconnectReason
    ) : SessionEvent

    data class MediaChannelSelected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val wireRequestKey: WireRequestKey,
        val channelId: ControlChannelId?
    ) : SessionEvent

    data class SignalingMessageSent(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val type: SignalingMessageTypeV2
    ) : SessionEvent

    data class SignalingSendFailed(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val type: SignalingMessageTypeV2,
        val reason: String
    ) : SessionEvent

    data class ChannelClosed(
        val runtimeSessionId: RuntimeSessionId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey,
        val reason: String
    ) : SessionEvent

    data class ProtocolViolation(
        val runtimeSessionId: RuntimeSessionId,
        val channelId: ControlChannelId,
        val wireRequestKey: WireRequestKey,
        val reason: String
    ) : SessionEvent

    data class ConfirmationAvailabilityChanged(
        val runtimeSessionId: RuntimeSessionId,
        val availability: ConfirmationAvailability
    ) : SessionEvent

    data class IncomingDecisionTimedOut(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val actionNonce: String,
        val occurredAtElapsedMs: Long
    ) : SessionEvent

    data class ConfirmationSurfaceUnavailable(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val actionNonce: String
    ) : SessionEvent

    data class TargetedTransportOpenFailed(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val transport: Transport,
        val reason: String
    ) : SessionEvent

    data class TargetedTransportOverlapUnavailable(
        val attempt: ConnectionAttempt,
        val transport: Transport
    ) : SessionEvent

    data class RecoveryTransportReady(
        val attempt: ConnectionAttempt,
        val transport: Transport
    ) : SessionEvent

    data class AttemptTimedOut(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val scheduledDeadlineElapsedRealtimeMs: Long
    ) : SessionEvent

    data class AttemptMilestoneElapsed(
        val milestone: AttemptMilestone
    ) : SessionEvent

    data class TransportOptimizing(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class RemoteIdentityReceived(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val peer: PeerIdentity
    ) : SessionEvent

    data class WebRtcStateChanged(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val state: WebRtcConnectionState,
        val occurredAt: Long
    ) : SessionEvent

    data class SignalingDisconnected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class RecoveryExhausted(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class ResetCompleted(
        val runtimeSessionId: RuntimeSessionId,
        val failedAttemptId: ConnectionAttemptId
    ) : SessionEvent

    data class DisconnectRequested(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class StopRequested(val runtimeSessionId: RuntimeSessionId) : SessionEvent
    data class RuntimeStopped(val runtimeSessionId: RuntimeSessionId) : SessionEvent
}

internal sealed interface SessionEffect {
    data class OpenTargetedTransport(
        val attempt: ConnectionAttempt,
        val transport: Transport = attempt.preferredTransport
    ) : SessionEffect

    data class RetireTargetedTransport(
        val attempt: ConnectionAttempt,
        val transport: Transport
    ) : SessionEffect

    data class ScheduleAttemptMilestone(
        val milestone: AttemptMilestone
    ) : SessionEffect

    data class AbortAttemptAndResumeDiscovery(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEffect

    data class RestartDiscovery(
        val runtimeSessionId: RuntimeSessionId,
        val attempt: ConnectionAttempt,
        val restartDelayMillis: Long = 0L
    ) : SessionEffect {
        init {
            require(restartDelayMillis >= 0L) { "Restart delay must not be negative" }
        }
    }

    data class ResetWirelessEnvironment(
        val runtimeSessionId: RuntimeSessionId,
        val targetDeviceId: String,
        val failedAttemptId: ConnectionAttemptId,
        val consecutiveFinalFailures: Int
    ) : SessionEffect {
        init {
            require(targetDeviceId.isNotBlank()) { "Reset target device ID must not be blank" }
            require(consecutiveFinalFailures >= RECOVERY_RESET_FAILURE_THRESHOLD) {
                "Wireless reset requires the recovery failure threshold"
            }
        }
    }

    data class ScheduleAttemptDeadline(
        val attempt: ConnectionAttempt
    ) : SessionEffect

    data class SendConnectRequest(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val trigger: RequestTrigger,
        val preferredTransportHint: Transport?
    ) : SessionEffect

    data class SendConnectAccept(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId
    ) : SessionEffect

    data class SendConnectReject(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val reason: RejectReason,
        val retryable: Boolean
    ) : SessionEffect

    data class SendBusy(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val reason: BusyReason,
        val retryAfterMs: Long?
    ) : SessionEffect

    data class SendDisconnect(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val reason: DisconnectReason
    ) : SessionEffect

    data class SelectMediaChannel(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val wireRequestKey: WireRequestKey,
        val cohort: SelectionCohort,
        val preferredTransport: Transport?
    ) : SessionEffect

    data class StartWebRtc(
        val runtimeSessionId: RuntimeSessionId,
        val attempt: ConnectionAttempt,
        val channelId: ControlChannelId,
        val role: WebRtcRole,
        val peer: PeerIdentity
    ) : SessionEffect

    data class CloseControlChannel(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val targetLock: TargetLock
    ) : SessionEffect

    data class PublishIncomingConfirmation(
        val prompt: IncomingConfirmationPrompt
    ) : SessionEffect

    data class CancelIncomingConfirmation(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val actionNonce: String
    ) : SessionEffect
}

internal data class SessionTransition(
    val state: IntercomState,
    val effects: List<SessionEffect> = emptyList()
)

internal fun nextIntercomState(
    current: IntercomState,
    event: SessionEvent
): IntercomState? = reduceIntercomState(current, event)?.state

internal fun reduceIntercomState(
    current: IntercomState,
    event: SessionEvent
): SessionTransition? = when (event) {
    is SessionEvent.RuntimeStarted ->
        transition(IntercomState.Discovering(event.runtimeSessionId))
            .takeIf { current == IntercomState.Offline }

    is SessionEvent.ConnectRequested ->
        transition(IntercomState.Connecting(event.attempt))
            .takeIf {
                current is IntercomState.Discovering &&
                    current.matches(event.attempt.runtimeSessionId)
            }

    is SessionEvent.AttemptReplaced -> replaceAttempt(current, event.attempt)

    is SessionEvent.TunnelReady -> reduceTunnelReady(current, event)

    is SessionEvent.ControlChannelVerified,
    is SessionEvent.IncomingConnectRequest,
    is SessionEvent.RemoteConnectAccepted,
    is SessionEvent.RemoteConnectRejected,
    is SessionEvent.RemoteBusy,
    is SessionEvent.RemoteDisconnect,
    is SessionEvent.MediaChannelSelected,
    is SessionEvent.SignalingMessageSent,
    is SessionEvent.SignalingSendFailed,
    is SessionEvent.ChannelClosed,
    is SessionEvent.ProtocolViolation,
    is SessionEvent.IncomingAccepted,
    is SessionEvent.IncomingRejected,
    is SessionEvent.ConfirmationAvailabilityChanged,
    is SessionEvent.IncomingDecisionTimedOut,
    is SessionEvent.ConfirmationSurfaceUnavailable,
    is SessionEvent.ConnectPresenceRequested,
    is SessionEvent.TargetedTransportOpenFailed,
    is SessionEvent.TargetedTransportOverlapUnavailable,
    is SessionEvent.RecoveryTransportReady,
    is SessionEvent.AttemptTimedOut,
    is SessionEvent.AttemptMilestoneElapsed,
    is SessionEvent.WebRtcStateChanged,
    is SessionEvent.SignalingDisconnected,
    is SessionEvent.RecoveryExhausted,
    is SessionEvent.DisconnectRequested -> null

    is SessionEvent.TransportOptimizing ->
        (current as? IntercomState.Connecting)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(IntercomState.Optimizing(it.attempt, it.peer)) }

    is SessionEvent.RemoteIdentityReceived -> reduceRemoteIdentity(current, event)

    is SessionEvent.ResetCompleted ->
        (current as? IntercomState.Resetting)
            ?.takeIf {
                it.matches(event.runtimeSessionId) &&
                    it.failedAttemptId == event.failedAttemptId
            }
            ?.let { transition(IntercomState.Discovering(event.runtimeSessionId)) }

    is SessionEvent.StopRequested ->
        transition(IntercomState.Stopping(event.runtimeSessionId)).takeIf {
            current != IntercomState.Offline &&
                current !is IntercomState.Stopping &&
                current.matches(event.runtimeSessionId)
        }

    is SessionEvent.RuntimeStopped ->
        (current as? IntercomState.Stopping)
            ?.takeIf { it.matches(event.runtimeSessionId) }
            ?.let { transition(IntercomState.Offline) }
}

private fun replaceAttempt(
    current: IntercomState,
    attempt: ConnectionAttempt
): SessionTransition? {
    if (!current.matches(attempt.runtimeSessionId)) return null
    return when (current) {
        is IntercomState.Discovering,
        is IntercomState.Connecting,
        is IntercomState.Optimizing ->
            transition(IntercomState.Connecting(attempt))
        else -> null
    }
}

private fun reduceTunnelReady(
    current: IntercomState,
    event: SessionEvent.TunnelReady
): SessionTransition? {
    if (event.transport !in event.attempt.channelPlan) return null
    if (!event.peer.isVerifiedFor(event.attempt.targetLock)) return null

    return when (current) {
        is IntercomState.Connecting -> current
            .takeIf {
                it.matches(event.attempt.runtimeSessionId, event.attempt.id) &&
                    it.attempt == event.attempt
            }
            ?.let {
                transition(
                    IntercomState.Connecting(
                        it.attempt,
                        mergePeer(it.peer, event.peer)
                    )
                )
            }

        is IntercomState.Recovering -> current
            .takeIf {
                it.matches(event.attempt.runtimeSessionId, event.attempt.id) &&
                    it.attempt == event.attempt
            }
            ?.let {
                transition(
                    it.copy(peer = mergePeer(it.peer, event.peer) ?: it.peer)
                )
            }

        else -> null
    }
}

private fun reduceRemoteIdentity(
    current: IntercomState,
    event: SessionEvent.RemoteIdentityReceived
): SessionTransition? {
    fun matchesTarget(attempt: ConnectionAttempt): Boolean =
        event.peer.isVerifiedFor(attempt.targetLock)

    return when (current) {
        is IntercomState.Connecting -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                if (!matchesTarget(it.attempt)) return null
                transition(IntercomState.Connecting(it.attempt, mergePeer(it.peer, event.peer)))
            }

        is IntercomState.Optimizing -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                if (!matchesTarget(it.attempt)) return null
                transition(IntercomState.Optimizing(it.attempt, mergePeer(it.peer, event.peer)))
            }

        is IntercomState.Connected -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                if (!matchesTarget(it.attempt)) return null
                transition(
                    it.copy(
                        peer = mergePeer(it.peer, event.peer) ?: event.peer
                    )
                )
            }

        is IntercomState.Recovering -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                if (!matchesTarget(it.attempt)) return null
                transition(
                    it.copy(peer = mergePeer(it.peer, event.peer) ?: event.peer)
                )
            }

        else -> null
    }
}

private fun mergePeer(first: PeerIdentity?, second: PeerIdentity?): PeerIdentity? = when {
    first == null -> second
    second == null -> first
    else -> PeerIdentity(
        deviceId = second.deviceId ?: first.deviceId,
        nickname = second.nickname.ifBlank { first.nickname },
        deviceName = second.deviceName.ifBlank { first.deviceName },
        runtimeSessionId = second.runtimeSessionId ?: first.runtimeSessionId,
        isDeviceIdVerified = first.isDeviceIdVerified || second.isDeviceIdVerified
    )
}

private fun transition(
    state: IntercomState,
    effects: List<SessionEffect> = emptyList()
): SessionTransition = SessionTransition(state, effects)

private fun IntercomState.matches(runtimeSessionId: RuntimeSessionId): Boolean =
    this.runtimeSessionId == runtimeSessionId

private fun IntercomState.Connecting.matches(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId
): Boolean = this.runtimeSessionId == runtimeSessionId && this.attemptId == attemptId

private fun IntercomState.Optimizing.matches(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId
): Boolean = this.runtimeSessionId == runtimeSessionId && this.attemptId == attemptId

private fun IntercomState.Connected.matches(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId
): Boolean = this.runtimeSessionId == runtimeSessionId && this.attemptId == attemptId

private fun IntercomState.Recovering.matches(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId
): Boolean = this.runtimeSessionId == runtimeSessionId && this.attemptId == attemptId
