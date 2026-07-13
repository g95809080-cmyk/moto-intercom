package com.kuma.motointercom

enum class ConnectionTrigger {
    USER,
    AUTO_DISCOVERY,
    INBOUND,
    RECOVERY,
    LEGACY_PROVISIONAL,
    LEGACY_PROVISIONAL_RECOVERY;

    val allowsUnknownTarget: Boolean
        get() = this == LEGACY_PROVISIONAL || this == LEGACY_PROVISIONAL_RECOVERY
}

enum class Transport {
    LAN,
    WIFI_DIRECT
}

enum class IdentityVerificationSource {
    SOCKET_HANDSHAKE,
    DISCOVERY_UNVERIFIED,
    NONE;

    val verifiesStableDeviceId: Boolean
        get() = this == SOCKET_HANDSHAKE
}

data class ConnectionAttempt(
    val id: ConnectionAttemptId,
    val runtimeSessionId: RuntimeSessionId,
    val targetDeviceId: String?,
    val trigger: ConnectionTrigger,
    val preferredTransport: Transport?,
    val deadlineElapsedRealtimeMs: Long
) {
    init {
        require(targetDeviceId == null || targetDeviceId.isNotBlank()) {
            "Target device ID must not be blank"
        }
        require(targetDeviceId != null || trigger.allowsUnknownTarget) {
            "Only legacy/provisional attempts may have an unknown target"
        }
        require(deadlineElapsedRealtimeMs > 0L) {
            "Connection attempt deadline must be positive"
        }
    }

    val isProvisional: Boolean
        get() = targetDeviceId == null && trigger.allowsUnknownTarget

    fun withVerifiedTarget(deviceId: String): ConnectionAttempt {
        val normalized = deviceId.trim()
        require(normalized.isNotEmpty()) { "Verified target device ID must not be blank" }
        return copy(targetDeviceId = normalized)
    }
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
