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
    val deviceName: String = ""
)

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
        override val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val targetDeviceId: String?
    ) : IntercomState {
        override val kind = SessionState.CONNECTING
    }

    data class Optimizing(
        override val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val targetDeviceId: String?
    ) : IntercomState {
        override val kind = SessionState.OPTIMIZING
    }

    data class Connected(
        override val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val peer: PeerIdentity,
        val connectedAt: Long,
        val transport: String?
    ) : IntercomState {
        override val kind = SessionState.CONNECTED
    }

    data class Recovering(
        override val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val targetDeviceId: String?
    ) : IntercomState {
        override val kind = SessionState.RECOVERING
    }

    data class Resetting(
        override val runtimeSessionId: RuntimeSessionId,
        val targetDeviceId: String?
    ) : IntercomState {
        override val kind = SessionState.RESETTING
    }

    data class Stopping(
        override val runtimeSessionId: RuntimeSessionId
    ) : IntercomState {
        override val kind = SessionState.STOPPING
    }
}
