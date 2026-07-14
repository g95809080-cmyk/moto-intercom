package com.kuma.motointercom

enum class ConnectionTrigger {
    USER,
    INBOUND,
    RECOVERY
}

enum class Transport {
    LAN,
    WIFI_DIRECT
}

data class TargetLock(
    val targetDeviceId: String,
    val expectedRemoteSessionId: RuntimeSessionId
) {
    init {
        require(targetDeviceId.isNotBlank()) { "Target device ID must not be blank" }
    }
}

data class ChannelPlan(
    val plannedTransports: Set<Transport>
) {
    init {
        require(plannedTransports.size == 1) {
            "Sprint 2 connection attempts must plan exactly one transport"
        }
    }

    val transport: Transport
        get() = plannedTransports.single()

    companion object {
        fun single(transport: Transport): ChannelPlan = ChannelPlan(setOf(transport))
    }
}

data class ConnectionAttempt(
    val id: ConnectionAttemptId,
    val runtimeSessionId: RuntimeSessionId,
    val targetLock: TargetLock,
    val trigger: ConnectionTrigger,
    val channelPlan: ChannelPlan,
    val deadlineElapsedRealtimeMs: Long
) {
    init {
        require(deadlineElapsedRealtimeMs > 0L) {
            "Connection attempt deadline must be positive"
        }
    }

    val targetDeviceId: String
        get() = targetLock.targetDeviceId
}

data class RecoveryAttemptSpec(
    val id: ConnectionAttemptId,
    val deadlineElapsedRealtimeMs: Long
) {
    init {
        require(deadlineElapsedRealtimeMs > 0L) {
            "Recovery attempt deadline must be positive"
        }
    }
}

internal fun plannedDiscoveryTransports(attempt: ConnectionAttempt?): Set<Transport> =
    attempt?.channelPlan?.plannedTransports ?: Transport.entries.toSet()

internal fun openPlannedTransport(
    attempt: ConnectionAttempt,
    openLan: (ConnectionAttempt) -> Boolean,
    openWifiDirect: (ConnectionAttempt) -> Boolean
): Boolean = when (attempt.channelPlan.transport) {
    Transport.LAN -> openLan(attempt)
    Transport.WIFI_DIRECT -> openWifiDirect(attempt)
}
