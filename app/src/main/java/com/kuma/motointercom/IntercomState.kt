package com.kuma.motointercom

enum class SessionState {
    OFFLINE,
    DISCOVERING,
    INCOMING_CONFIRMATION,
    CONNECTING,
    OPTIMIZING,
    CONNECTED,
    RECOVERING,
    RESETTING,
    STOPPING
}

data class PeerIdentity(
    val deviceId: String?,
    val nickname: String,
    val deviceName: String = "",
    val runtimeSessionId: RuntimeSessionId? = null,
    val isDeviceIdVerified: Boolean = false
)

internal fun PeerIdentity.isVerifiedFor(targetLock: TargetLock): Boolean =
    isDeviceIdVerified &&
        deviceId == targetLock.targetDeviceId &&
        runtimeSessionId == targetLock.expectedRemoteSessionId

sealed interface IntercomState {
    val kind: SessionState
    val runtimeSessionId: RuntimeSessionId?

    data object Offline : IntercomState {
        override val kind = SessionState.OFFLINE
        override val runtimeSessionId: RuntimeSessionId? = null
    }

    data class Discovering(
        override val runtimeSessionId: RuntimeSessionId
    ) : IntercomState {
        override val kind = SessionState.DISCOVERING
    }

    data class IncomingConfirmation(
        override val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val peer: PeerIdentity
    ) : IntercomState {
        override val kind = SessionState.INCOMING_CONFIRMATION
    }

    data class Connecting(
        val attempt: ConnectionAttempt,
        val peer: PeerIdentity? = null
    ) : IntercomState {
        override val kind = SessionState.CONNECTING
        override val runtimeSessionId: RuntimeSessionId = attempt.runtimeSessionId
        val attemptId: ConnectionAttemptId = attempt.id
        val targetDeviceId: String = attempt.targetDeviceId
    }

    data class Optimizing(
        val attempt: ConnectionAttempt,
        val peer: PeerIdentity? = null
    ) : IntercomState {
        override val kind = SessionState.OPTIMIZING
        override val runtimeSessionId: RuntimeSessionId = attempt.runtimeSessionId
        val attemptId: ConnectionAttemptId = attempt.id
        val targetDeviceId: String = attempt.targetDeviceId
    }

    data class Connected(
        val attempt: ConnectionAttempt,
        val peer: PeerIdentity,
        val connectedAt: Long
    ) : IntercomState {
        override val kind = SessionState.CONNECTED
        override val runtimeSessionId: RuntimeSessionId = attempt.runtimeSessionId
        val attemptId: ConnectionAttemptId = attempt.id
        val transport: Transport = attempt.channelPlan.transport
    }

    data class Recovering(
        val attempt: ConnectionAttempt,
        val peer: PeerIdentity
    ) : IntercomState {
        override val kind = SessionState.RECOVERING
        override val runtimeSessionId: RuntimeSessionId = attempt.runtimeSessionId
        val attemptId: ConnectionAttemptId = attempt.id
        val targetDeviceId: String = attempt.targetDeviceId
    }

    data class Resetting(
        override val runtimeSessionId: RuntimeSessionId,
        val targetDeviceId: String
    ) : IntercomState {
        override val kind = SessionState.RESETTING
    }

    data class Stopping(
        override val runtimeSessionId: RuntimeSessionId
    ) : IntercomState {
        override val kind = SessionState.STOPPING
    }
}

internal fun IntercomState.connectionAttemptOrNull(): ConnectionAttempt? = when (this) {
    is IntercomState.IncomingConfirmation -> null
    is IntercomState.Connecting -> attempt
    is IntercomState.Optimizing -> attempt
    is IntercomState.Connected -> attempt
    is IntercomState.Recovering -> attempt
    else -> null
}
