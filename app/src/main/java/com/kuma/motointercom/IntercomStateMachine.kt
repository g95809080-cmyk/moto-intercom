package com.kuma.motointercom

sealed interface SessionEvent {
    data class RuntimeStarted(val runtimeSessionId: RuntimeSessionId) : SessionEvent

    data class IncomingRequest(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
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

    data class ConnectRequested(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val targetDeviceId: String?
    ) : SessionEvent

    data class TransportOptimizing(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
    ) : SessionEvent

    data class WebRtcConnected(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val peer: PeerIdentity,
        val connectedAt: Long,
        val transport: String?
    ) : SessionEvent

    data class PeerIdentified(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val peer: PeerIdentity
    ) : SessionEvent

    data class ConnectionLost(
        val runtimeSessionId: RuntimeSessionId,
        val connectionAttemptId: ConnectionAttemptId,
        val recoveryAttemptId: ConnectionAttemptId
    ) : SessionEvent

    data class AttemptFailed(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId
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

internal fun nextIntercomState(
    current: IntercomState,
    event: SessionEvent
): IntercomState? = when (event) {
    is SessionEvent.RuntimeStarted ->
        IntercomState.Discovering(event.runtimeSessionId)
            .takeIf { current == IntercomState.Offline }

    is SessionEvent.IncomingRequest ->
        IntercomState.IncomingConfirmation(event.runtimeSessionId, event.attemptId, event.peer)
            .takeIf { current is IntercomState.Discovering && current.matches(event.runtimeSessionId) }

    is SessionEvent.IncomingAccepted ->
        (current as? IntercomState.IncomingConfirmation)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                IntercomState.Connecting(
                    event.runtimeSessionId,
                    event.attemptId,
                    it.peer.deviceId
                )
            }

    is SessionEvent.IncomingRejected ->
        (current as? IntercomState.IncomingConfirmation)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { IntercomState.Discovering(event.runtimeSessionId) }

    is SessionEvent.ConnectRequested ->
        IntercomState.Connecting(
            event.runtimeSessionId,
            event.attemptId,
            event.targetDeviceId
        ).takeIf { current is IntercomState.Discovering && current.matches(event.runtimeSessionId) }

    is SessionEvent.TransportOptimizing ->
        (current as? IntercomState.Connecting)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let {
                IntercomState.Optimizing(
                    event.runtimeSessionId,
                    event.attemptId,
                    it.targetDeviceId
                )
            }

    is SessionEvent.WebRtcConnected -> when (current) {
        is IntercomState.Connecting -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { event.toConnected() }

        is IntercomState.Optimizing -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { event.toConnected() }

        is IntercomState.Recovering -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { event.toConnected() }

        else -> null
    }

    is SessionEvent.PeerIdentified ->
        (current as? IntercomState.Connected)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.copy(peer = event.peer)

    is SessionEvent.ConnectionLost ->
        (current as? IntercomState.Connected)
            ?.takeIf { it.matches(event.runtimeSessionId, event.connectionAttemptId) }
            ?.let {
                IntercomState.Recovering(
                    event.runtimeSessionId,
                    event.recoveryAttemptId,
                    it.peer.deviceId
                )
            }

    is SessionEvent.AttemptFailed -> when (current) {
        is IntercomState.Connecting -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { IntercomState.Discovering(event.runtimeSessionId) }

        is IntercomState.Optimizing -> current
            .takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { IntercomState.Discovering(event.runtimeSessionId) }

        else -> null
    }

    is SessionEvent.RecoveryExhausted ->
        (current as? IntercomState.Recovering)
            ?.takeIf { it.matches(event.runtimeSessionId, event.attemptId) }
            ?.let { IntercomState.Resetting(event.runtimeSessionId, it.targetDeviceId) }

    is SessionEvent.ResetCompleted ->
        (current as? IntercomState.Resetting)
            ?.takeIf { it.matches(event.runtimeSessionId) }
            ?.let { IntercomState.Discovering(event.runtimeSessionId) }

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
    }?.let { IntercomState.Discovering(event.runtimeSessionId) }

    is SessionEvent.StopRequested ->
        IntercomState.Stopping(event.runtimeSessionId).takeIf {
            current != IntercomState.Offline &&
                current !is IntercomState.Stopping &&
                current.matches(event.runtimeSessionId)
        }

    is SessionEvent.RuntimeStopped ->
        (current as? IntercomState.Stopping)
            ?.takeIf { it.matches(event.runtimeSessionId) }
            ?.let { IntercomState.Offline }
}

private fun SessionEvent.WebRtcConnected.toConnected(): IntercomState.Connected =
    IntercomState.Connected(
        runtimeSessionId,
        attemptId,
        peer,
        connectedAt,
        transport
    )

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
