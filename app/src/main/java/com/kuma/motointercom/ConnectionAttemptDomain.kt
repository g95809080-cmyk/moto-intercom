package com.kuma.motointercom

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
