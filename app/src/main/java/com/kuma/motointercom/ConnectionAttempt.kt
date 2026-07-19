package com.kuma.motointercom

enum class ConnectionTrigger {
    USER,
    AUTO_PAIRED,
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

class ChannelPlan(plannedTransports: Set<Transport>) {
    init {
        require(plannedTransports.size == 1) {
            "Sprint 2 connection attempts must plan exactly one transport"
        }
    }

    val transport: Transport = plannedTransports.single()
    val plannedTransports: Set<Transport> = setOf(transport)

    override fun equals(other: Any?): Boolean =
        this === other || other is ChannelPlan && transport == other.transport

    override fun hashCode(): Int = transport.hashCode()

    override fun toString(): String = "ChannelPlan(plannedTransports=$plannedTransports)"

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

    val preferredTransport: Transport
        get() = channelPlan.transport

    val deadlineAt: MonotonicTimestamp
        get() = MonotonicTimestamp(deadlineElapsedRealtimeMs)

    fun isExpiredAt(now: MonotonicTimestamp): Boolean =
        now.elapsedRealtimeMs >= deadlineElapsedRealtimeMs

    fun accepts(event: ConnectionAttemptEventContext): Boolean =
        event.attemptId == id &&
            event.targetDeviceId == targetDeviceId &&
            !isExpiredAt(event.observedAt)

    fun isStale(event: ConnectionAttemptEventContext): Boolean = !accepts(event)
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
