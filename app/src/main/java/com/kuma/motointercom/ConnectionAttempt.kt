package com.kuma.motointercom

import java.util.Collections

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

class ChannelPlan private constructor(
    val preferredTransport: Transport,
    val fallbackTransport: Transport?
) {
    constructor(plannedTransports: Set<Transport>) : this(
        preferredTransport = requireSingleTransport(plannedTransports),
        fallbackTransport = null
    )

    init {
        require(fallbackTransport == null || fallbackTransport != preferredTransport) {
            "Fallback transport must differ from preferred transport"
        }
    }

    val plannedTransports: Set<Transport> = Collections.unmodifiableSet(
        fallbackTransport?.let {
            linkedSetOf(preferredTransport, it)
        } ?: linkedSetOf(preferredTransport)
    )

    operator fun contains(transport: Transport): Boolean = transport in plannedTransports

    override fun equals(other: Any?): Boolean = this === other ||
        other is ChannelPlan &&
        preferredTransport == other.preferredTransport &&
        fallbackTransport == other.fallbackTransport

    override fun hashCode(): Int = 31 * preferredTransport.hashCode() +
        (fallbackTransport?.hashCode() ?: 0)

    override fun toString(): String = "ChannelPlan(plannedTransports=$plannedTransports)"

    companion object {
        fun single(transport: Transport): ChannelPlan = ChannelPlan(transport, null)

        fun race(
            preferredTransport: Transport,
            fallbackTransport: Transport
        ): ChannelPlan = ChannelPlan(preferredTransport, fallbackTransport)

        private fun requireSingleTransport(plannedTransports: Set<Transport>): Transport {
            require(plannedTransports.size == 1) {
                "An unordered ChannelPlan constructor accepts exactly one transport"
            }
            return plannedTransports.first()
        }
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
        get() = channelPlan.preferredTransport

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

internal fun ChannelPlan.orderedForRecovery(
    lastSuccessfulTransport: Transport
): ChannelPlan {
    require(lastSuccessfulTransport in this) {
        "Last successful transport must belong to the connected attempt plan"
    }
    val alternate = plannedTransports.firstOrNull { it != lastSuccessfulTransport }
    return if (alternate == null) {
        ChannelPlan.single(lastSuccessfulTransport)
    } else {
        ChannelPlan.race(lastSuccessfulTransport, alternate)
    }
}

internal fun bindPlannedAdapterIngress(
    attempt: ConnectionAttempt,
    bindLan: (ConnectionAttempt) -> Unit,
    bindWifiDirect: (ConnectionAttempt) -> Unit
) {
    if (Transport.LAN in attempt.channelPlan) bindLan(attempt)
    if (Transport.WIFI_DIRECT in attempt.channelPlan) bindWifiDirect(attempt)
}

internal fun openPlannedTransport(
    attempt: ConnectionAttempt,
    transport: Transport,
    openLan: (ConnectionAttempt) -> Boolean,
    openWifiDirect: (ConnectionAttempt) -> Boolean
): Boolean {
    if (transport !in attempt.channelPlan) return false
    return when (transport) {
        Transport.LAN -> openLan(attempt)
        Transport.WIFI_DIRECT -> openWifiDirect(attempt)
    }
}

internal fun openPlannedTransport(
    attempt: ConnectionAttempt,
    openLan: (ConnectionAttempt) -> Boolean,
    openWifiDirect: (ConnectionAttempt) -> Boolean
): Boolean = openPlannedTransport(
    attempt,
    attempt.preferredTransport,
    openLan,
    openWifiDirect
)

internal fun retirePlannedTransport(
    attempt: ConnectionAttempt,
    transport: Transport,
    retireLan: (ConnectionAttempt) -> Unit,
    retireWifiDirect: (ConnectionAttempt) -> Unit
): Boolean {
    if (transport !in attempt.channelPlan) return false
    when (transport) {
        Transport.LAN -> retireLan(attempt)
        Transport.WIFI_DIRECT -> retireWifiDirect(attempt)
    }
    return true
}
