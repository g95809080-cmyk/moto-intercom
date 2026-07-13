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
    data class AbortAttemptAndResumeDiscovery(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEffect

    data class RestartDiscovery(
        val runtimeSessionId: RuntimeSessionId,
        val attempt: ConnectionAttempt?
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
    val remoteDeviceId = event.remoteDeviceId?.trim()?.takeIf(String::isNotEmpty)
    val verifiedByTunnel = event.identityVerificationSource.verifiesStableDeviceId
    val expectedDeviceId = event.attempt.targetDeviceId
    if (
        verifiedByTunnel &&
        expectedDeviceId != null &&
        remoteDeviceId != null &&
        expectedDeviceId != remoteDeviceId
    ) {
        return null
    }
    val normalizedAttempt = event.attempt.copy(preferredTransport = event.transport).let {
        if (verifiedByTunnel) it.withVerifiedRemoteDeviceIdIfUnknown(remoteDeviceId) else it
    }
    val peer = remoteDeviceId?.let {
        PeerIdentity(deviceId = it, nickname = "", isDeviceIdVerified = verifiedByTunnel)
    } ?: normalizedAttempt.initialPeer()

    return when (current) {
        is IntercomState.Discovering ->
            transition(IntercomState.Connecting(normalizedAttempt, peer))
                .takeIf { current.matches(normalizedAttempt.runtimeSessionId) }

        is IntercomState.Connecting -> current
            .takeIf { it.matches(normalizedAttempt.runtimeSessionId, normalizedAttempt.id) }
            ?.let {
                transition(
                    IntercomState.Connecting(
                        normalizedAttempt.mergeVerifiedAttempt(it.attempt),
                        mergePeer(it.peer, peer)
                    )
                )
            }

        is IntercomState.Recovering -> current
            .takeIf { it.matches(normalizedAttempt.runtimeSessionId, normalizedAttempt.id) }
            ?.let {
                transition(
                    IntercomState.Recovering(
                        normalizedAttempt.mergeVerifiedAttempt(it.attempt),
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
    fun updatedAttempt(attempt: ConnectionAttempt): ConnectionAttempt? {
        val remoteDeviceId = event.peer.deviceId?.trim()?.takeIf(String::isNotEmpty)
            ?: return attempt
        val expected = attempt.targetDeviceId
        if (expected != null && expected != remoteDeviceId) return null
        return if (event.peer.isDeviceIdVerified) {
            attempt.withVerifiedTarget(remoteDeviceId)
        } else {
            attempt
        }
    }

    return when (current) {
        is IntercomState.IncomingConfirmation -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                val attempt = updatedAttempt(it.attempt) ?: return null
                transition(
                    IntercomState.IncomingConfirmation(
                        attempt,
                        mergePeer(it.peer, event.peer) ?: event.peer
                    )
                )
            }

        is IntercomState.Connecting -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                val attempt = updatedAttempt(it.attempt) ?: return null
                transition(IntercomState.Connecting(attempt, mergePeer(it.peer, event.peer)))
            }

        is IntercomState.Optimizing -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                val attempt = updatedAttempt(it.attempt) ?: return null
                transition(IntercomState.Optimizing(attempt, mergePeer(it.peer, event.peer)))
            }

        is IntercomState.Connected -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                val attempt = updatedAttempt(it.attempt) ?: return null
                transition(
                    it.copy(
                        attempt = attempt,
                        peer = mergePeer(it.peer, event.peer) ?: event.peer
                    )
                )
            }

        is IntercomState.Recovering -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                val attempt = updatedAttempt(it.attempt) ?: return null
                transition(
                    IntercomState.Recovering(
                        attempt,
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
        targetDeviceId = targetDeviceId,
        trigger = if (targetDeviceId == null) {
            ConnectionTrigger.LEGACY_PROVISIONAL_RECOVERY
        } else {
            ConnectionTrigger.RECOVERY
        },
        preferredTransport = preferredTransport,
        deadlineElapsedRealtimeMs = spec.deadlineElapsedRealtimeMs
    )

private fun IntercomState.Connecting.toConnected(connectedAt: Long): IntercomState.Connected =
    IntercomState.Connected(attempt, peer ?: attempt.initialPeer().orUnknown(), connectedAt)

private fun IntercomState.Optimizing.toConnected(connectedAt: Long): IntercomState.Connected =
    IntercomState.Connected(attempt, peer ?: attempt.initialPeer().orUnknown(), connectedAt)

private fun IntercomState.Recovering.toConnected(connectedAt: Long): IntercomState.Connected =
    IntercomState.Connected(attempt, peer, connectedAt)

private fun ConnectionAttempt.initialPeer(): PeerIdentity? = targetDeviceId?.let {
    PeerIdentity(deviceId = it, nickname = "")
}

private fun PeerIdentity?.orUnknown(): PeerIdentity = this ?: PeerIdentity(null, "")

private fun ConnectionAttempt.withVerifiedRemoteDeviceIdIfUnknown(
    deviceId: String?
): ConnectionAttempt {
    val normalized = deviceId?.trim()?.takeIf(String::isNotEmpty) ?: return this
    return if (targetDeviceId == null) withVerifiedTarget(normalized) else this
}

private fun ConnectionAttempt.mergeVerifiedAttempt(previous: ConnectionAttempt): ConnectionAttempt =
    copy(targetDeviceId = targetDeviceId ?: previous.targetDeviceId)

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

private fun restartEffect(
    runtimeSessionId: RuntimeSessionId,
    attempt: ConnectionAttempt?,
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
