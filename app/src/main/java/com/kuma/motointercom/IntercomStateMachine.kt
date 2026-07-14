package com.kuma.motointercom

enum class WebRtcConnectionState {
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    OTHER
}

sealed interface SessionEvent {
    data class RuntimeStarted(val runtimeSessionId: RuntimeSessionId) : SessionEvent

    data class IncomingRequest(
        val attempt: ConnectionAttempt,
        val peer: PeerIdentity
    ) : SessionEvent

    data class IncomingAccepted(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class IncomingRejected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class ConnectRequested(val attempt: ConnectionAttempt) : SessionEvent

    data class ConnectPresenceRequested(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val targetDeviceId: String,
        val targetSessionId: RuntimeSessionId,
        val availableTransports: Set<Transport>,
        val deadlineElapsedRealtimeMs: Long
    ) : SessionEvent

    data class AttemptReplaced(val attempt: ConnectionAttempt) : SessionEvent

    data class TunnelReady(
        val attempt: ConnectionAttempt,
        val remoteDeviceId: String?,
        val transport: Transport,
        val identityVerificationSource: IdentityVerificationSource
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
        val occurredAt: Long,
        val recovery: RecoveryAttemptSpec
    ) : SessionEvent

    data class SignalingDisconnected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val recovery: RecoveryAttemptSpec
    ) : SessionEvent

    data class RecoveryExhausted(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class ResetCompleted(val runtimeSessionId: RuntimeSessionId) : SessionEvent

    data class DisconnectRequested(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class StopRequested(val runtimeSessionId: RuntimeSessionId) : SessionEvent
    data class RuntimeStopped(val runtimeSessionId: RuntimeSessionId) : SessionEvent
}

sealed interface SessionEffect {
    data class OpenTargetedTransport(
        val attempt: ConnectionAttempt
    ) : SessionEffect

    data class AbortAttemptAndResumeDiscovery(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEffect

    data class RestartDiscovery(
        val runtimeSessionId: RuntimeSessionId,
        val attempt: ConnectionAttempt
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

    is SessionEvent.IncomingRequest ->
        transition(IntercomState.IncomingConfirmation(event.attempt, event.peer))
            .takeIf {
                current is IntercomState.Discovering &&
                    current.matches(event.attempt.runtimeSessionId)
            }

    is SessionEvent.IncomingAccepted ->
        (current as? IntercomState.IncomingConfirmation)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(IntercomState.Connecting(it.attempt, it.peer)) }

    is SessionEvent.IncomingRejected ->
        (current as? IntercomState.IncomingConfirmation)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(IntercomState.Discovering(event.runtimeSessionId)) }

    is SessionEvent.ConnectRequested ->
        transition(IntercomState.Connecting(event.attempt, event.attempt.initialPeer()))
            .takeIf {
                current is IntercomState.Discovering &&
                    current.matches(event.attempt.runtimeSessionId)
            }

    is SessionEvent.ConnectPresenceRequested ->
        event.toAttemptOrNull()?.let { attempt ->
            transition(
                IntercomState.Connecting(attempt, attempt.initialPeer()),
                listOf(SessionEffect.OpenTargetedTransport(attempt))
            ).takeIf {
                current is IntercomState.Discovering &&
                    current.matches(event.runtimeSessionId)
            }
        }

    is SessionEvent.AttemptReplaced -> replaceAttempt(current, event.attempt)

    is SessionEvent.TunnelReady -> reduceTunnelReady(current, event)

    is SessionEvent.TransportOptimizing ->
        (current as? IntercomState.Connecting)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(IntercomState.Optimizing(it.attempt, it.peer)) }

    is SessionEvent.RemoteIdentityReceived -> reduceRemoteIdentity(current, event)

    is SessionEvent.WebRtcStateChanged -> reduceWebRtcState(current, event)

    is SessionEvent.SignalingDisconnected -> reduceDisconnect(
        current = current,
        runtimeSessionId = event.runtimeSessionId,
        attemptId = event.attemptId,
        recovery = event.recovery,
        restartConnectedDiscovery = true
    )

    is SessionEvent.RecoveryExhausted ->
        (current as? IntercomState.Recovering)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                transition(
                    IntercomState.Resetting(event.runtimeSessionId, it.targetDeviceId)
                )
            }

    is SessionEvent.ResetCompleted ->
        (current as? IntercomState.Resetting)
            ?.takeIf { it.matches(event.runtimeSessionId) }
            ?.let { transition(IntercomState.Discovering(event.runtimeSessionId)) }

    is SessionEvent.DisconnectRequested -> when (current) {
        is IntercomState.Connecting -> current.takeIf {
            it.matches(event.runtimeSessionId, event.attemptId)
        }
        is IntercomState.Optimizing -> current.takeIf {
            it.matches(event.runtimeSessionId, event.attemptId)
        }
        is IntercomState.Connected -> current.takeIf {
            it.matches(event.runtimeSessionId, event.attemptId)
        }
        is IntercomState.Recovering -> current.takeIf {
            it.matches(event.runtimeSessionId, event.attemptId)
        }
        else -> null
    }?.let { transition(IntercomState.Discovering(event.runtimeSessionId)) }

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
        is IntercomState.IncomingConfirmation,
        is IntercomState.Connecting,
        is IntercomState.Optimizing ->
            transition(IntercomState.Connecting(attempt, attempt.initialPeer()))
        else -> null
    }
}

private fun reduceTunnelReady(
    current: IntercomState,
    event: SessionEvent.TunnelReady
): SessionTransition? {
    if (event.transport != event.attempt.channelPlan.transport) return null
    val remoteDeviceId = event.remoteDeviceId?.trim()?.takeIf(String::isNotEmpty)
    val verifiedByTunnel = event.identityVerificationSource.verifiesStableDeviceId
    if (verifiedByTunnel && remoteDeviceId != event.attempt.targetDeviceId) return null
    val peer = remoteDeviceId?.let {
        PeerIdentity(
            deviceId = it,
            nickname = "",
            runtimeSessionId = event.attempt.targetLock.expectedRemoteSessionId,
            isDeviceIdVerified = verifiedByTunnel
        )
    } ?: event.attempt.initialPeer()

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
                        mergePeer(it.peer, peer)
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
                    IntercomState.Recovering(
                        it.attempt,
                        mergePeer(it.peer, peer) ?: it.peer
                    )
                )
            }

        else -> null
    }
}

private fun reduceRemoteIdentity(
    current: IntercomState,
    event: SessionEvent.RemoteIdentityReceived
): SessionTransition? {
    fun matchesTarget(attempt: ConnectionAttempt): Boolean {
        val remoteDeviceId = event.peer.deviceId?.trim()?.takeIf(String::isNotEmpty)
        if (remoteDeviceId != null && remoteDeviceId != attempt.targetDeviceId) return false
        val remoteSessionId = event.peer.runtimeSessionId
        return remoteSessionId == null ||
            remoteSessionId == attempt.targetLock.expectedRemoteSessionId
    }

    return when (current) {
        is IntercomState.IncomingConfirmation -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                if (!matchesTarget(it.attempt)) return null
                transition(
                    IntercomState.IncomingConfirmation(
                        it.attempt,
                        mergePeer(it.peer, event.peer) ?: event.peer
                    )
                )
            }

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
                    IntercomState.Recovering(
                        it.attempt,
                        mergePeer(it.peer, event.peer) ?: event.peer
                    )
                )
            }

        else -> null
    }
}

private fun reduceWebRtcState(
    current: IntercomState,
    event: SessionEvent.WebRtcStateChanged
): SessionTransition? = when (event.state) {
    WebRtcConnectionState.CONNECTED -> when (current) {
        is IntercomState.Connecting -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(it.toConnected(event.occurredAt)) }

        is IntercomState.Optimizing -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(it.toConnected(event.occurredAt)) }

        is IntercomState.Recovering -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { transition(it.toConnected(event.occurredAt)) }

        else -> null
    }

    WebRtcConnectionState.DISCONNECTED,
    WebRtcConnectionState.FAILED,
    WebRtcConnectionState.CLOSED -> reduceDisconnect(
        current = current,
        runtimeSessionId = event.runtimeSessionId,
        attemptId = event.attemptId,
        recovery = event.recovery,
        restartConnectedDiscovery = false
    )

    WebRtcConnectionState.OTHER -> null
}

private fun reduceDisconnect(
    current: IntercomState,
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    recovery: RecoveryAttemptSpec,
    restartConnectedDiscovery: Boolean
): SessionTransition? = when (current) {
    is IntercomState.Connecting -> current
        .takeIf { it.matches(runtimeSessionId, attemptId) }
        ?.let {
            transition(
                IntercomState.Discovering(runtimeSessionId),
                listOf(
                    SessionEffect.AbortAttemptAndResumeDiscovery(runtimeSessionId, attemptId)
                )
            )
        }

    is IntercomState.Optimizing -> current
        .takeIf { it.matches(runtimeSessionId, attemptId) }
        ?.let {
            transition(
                IntercomState.Discovering(runtimeSessionId),
                listOf(
                    SessionEffect.AbortAttemptAndResumeDiscovery(runtimeSessionId, attemptId)
                )
            )
        }

    is IntercomState.Connected -> current
        .takeIf { it.matches(runtimeSessionId, attemptId) }
        ?.let {
            val recoveryAttempt = it.attempt.toRecovery(recovery)
            transition(
                IntercomState.Recovering(recoveryAttempt, it.peer),
                restartEffect(runtimeSessionId, recoveryAttempt, restartConnectedDiscovery)
            )
        }

    is IntercomState.Recovering -> current
        .takeIf {
            it.runtimeSessionId == runtimeSessionId &&
                (it.attemptId == attemptId || it.attemptId == recovery.id)
        }
        ?.let {
            transition(
                it,
                restartEffect(runtimeSessionId, it.attempt, restartConnectedDiscovery)
            )
        }

    else -> null
}

private fun ConnectionAttempt.toRecovery(spec: RecoveryAttemptSpec): ConnectionAttempt =
    ConnectionAttempt(
        id = spec.id,
        runtimeSessionId = runtimeSessionId,
        targetLock = targetLock,
        trigger = ConnectionTrigger.RECOVERY,
        channelPlan = channelPlan,
        deadlineElapsedRealtimeMs = spec.deadlineElapsedRealtimeMs
    )

private fun IntercomState.Connecting.toConnected(connectedAt: Long): IntercomState.Connected =
    IntercomState.Connected(attempt, peer ?: attempt.initialPeer(), connectedAt)

private fun IntercomState.Optimizing.toConnected(connectedAt: Long): IntercomState.Connected =
    IntercomState.Connected(attempt, peer ?: attempt.initialPeer(), connectedAt)

private fun IntercomState.Recovering.toConnected(connectedAt: Long): IntercomState.Connected =
    IntercomState.Connected(attempt, peer, connectedAt)

private fun ConnectionAttempt.initialPeer(): PeerIdentity = PeerIdentity(
    deviceId = targetDeviceId,
    nickname = "",
    runtimeSessionId = targetLock.expectedRemoteSessionId
)

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

private fun SessionEvent.ConnectPresenceRequested.toAttemptOrNull(): ConnectionAttempt? {
    if (targetDeviceId.isBlank() || deadlineElapsedRealtimeMs <= 0L) return null
    val transport = when {
        Transport.LAN in availableTransports -> Transport.LAN
        Transport.WIFI_DIRECT in availableTransports -> Transport.WIFI_DIRECT
        else -> return null
    }
    return ConnectionAttempt(
        id = attemptId,
        runtimeSessionId = runtimeSessionId,
        targetLock = TargetLock(targetDeviceId, targetSessionId),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(transport),
        deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs
    )
}

private fun restartEffect(
    runtimeSessionId: RuntimeSessionId,
    attempt: ConnectionAttempt,
    enabled: Boolean
): List<SessionEffect> = if (enabled) {
    listOf(SessionEffect.RestartDiscovery(runtimeSessionId, attempt))
} else {
    emptyList()
}

private fun transition(
    state: IntercomState,
    effects: List<SessionEffect> = emptyList()
): SessionTransition = SessionTransition(state, effects)

private fun IntercomState.matches(runtimeSessionId: RuntimeSessionId): Boolean =
    this.runtimeSessionId == runtimeSessionId

private fun IntercomState.IncomingConfirmation.matches(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId
): Boolean = this.runtimeSessionId == runtimeSessionId && this.attemptId == attemptId

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
