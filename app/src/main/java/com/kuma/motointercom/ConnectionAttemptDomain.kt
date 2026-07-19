package com.kuma.motointercom

import kotlin.math.min

@JvmInline
value class MonotonicTimestamp(val elapsedRealtimeMs: Long) {
    init {
        require(elapsedRealtimeMs >= 0L) {
            "Monotonic timestamp must not be negative"
        }
    }
}

fun interface MonotonicClock {
    fun now(): MonotonicTimestamp
}

internal fun ConnectionAttempt.remainingMillis(now: MonotonicTimestamp): Long =
    (deadlineAt.elapsedRealtimeMs - now.elapsedRealtimeMs).coerceAtLeast(0L)

internal fun ConnectionAttempt.remainingMillis(clock: MonotonicClock): Long =
    remainingMillis(clock.now())

internal fun ConnectionAttempt.boundedTimeoutMillis(
    clock: MonotonicClock,
    localCapMillis: Long
): Long {
    require(localCapMillis >= 0L) { "Local timeout cap must not be negative" }
    return min(localCapMillis, remainingMillis(clock))
}

internal fun ConnectionAttempt.hasSameImmutableIdentity(other: ConnectionAttempt?): Boolean =
    other != null &&
        id == other.id &&
        runtimeSessionId == other.runtimeSessionId &&
        targetLock == other.targetLock &&
        trigger == other.trigger &&
        channelPlan == other.channelPlan &&
        deadlineAt == other.deadlineAt

internal data class AttemptTaskContext(
    val attempt: ConnectionAttempt,
    val generation: Int
) {
    val attemptId: ConnectionAttemptId
        get() = attempt.id

    val targetDeviceId: String
        get() = attempt.targetDeviceId

    fun matchesAttempt(current: ConnectionAttempt?): Boolean =
        attempt.hasSameImmutableIdentity(current)

    fun isCurrent(
        current: ConnectionAttempt?,
        currentGeneration: Int,
        clock: MonotonicClock
    ): Boolean =
        generation == currentGeneration &&
            matchesAttempt(current) &&
            attempt.remainingMillis(clock) > 0L
}

data class ConnectionAttemptEventContext(
    val attemptId: ConnectionAttemptId,
    val targetDeviceId: String,
    val observedAt: MonotonicTimestamp
) {
    init {
        require(targetDeviceId.isNotBlank()) {
            "Event target device ID must not be blank"
        }
    }
}

enum class ConnectionAttemptTerminalOutcome {
    SUCCESS,
    CANCELED,
    TIMED_OUT,
    FAILED,
    REJECTED,
    BUSY,
    DISCONNECTED,
    GLARE_LOST
}
