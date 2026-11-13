package com.kuma.motointercom

internal enum class RideState {
    LOCAL_PENDING, LOCAL_ACTIVE, INVITE_PENDING, INVITE_ACCEPTED,
    CLOUD_PENDING, CLOUD_ACTIVE, SWITCHING_LOCAL, CLOUD_PAUSED, ENDED
}

internal enum class CloudEntryReason { INVITE_ACCEPTED, LOCAL_FAILURE }

internal enum class LocalSessionEndReason {
    TRANSPORT_FAILURE, USER_ENDED, INVITE_DECLINED, INVITE_TIMEOUT, RIDE_ENDED
}

internal data class LocalSwitch(val switchId: String,val probeId: String,val phase: String,
    val prepared: List<String>,val completed: List<String>,val expiresAt: Long)

internal data class RideSession(
    val rideId: String,
    val pairId: String,
    val members: List<String>,
    val state: RideState,
    val roomId: String,
    val revision: Long,
    val everLocalActive: Boolean,
    val cloudReason: CloudEntryReason?,
    val lastHeartbeatAt: Map<String, Long>,
    val cloudStartedAt: Long? = null,
    val cloudUsedMs: Long = 0,
    val cloudAllowanceMs: Long = 4 * 60 * 60 * 1_000L,
    val inviteId: String? = null,
    val inviteExpiresAt: Long? = null,
    val localSwitch: LocalSwitch? = null,
    val localSwitchAttempts: Int = 0
) {
    fun canManuallyResume(nowMs: Long): Boolean =
        state != RideState.ENDED && members.size == 2 && members.distinct().size == 2 &&
            lastHeartbeatAt.keys == members.toSet() &&
            lastHeartbeatAt.values.maxOrNull()?.let { nowMs >= it && nowMs - it < 10 * 60 * 1_000 } == true

    fun mayRequestFallback(reason: LocalSessionEndReason, privacyConsentAt: Long?, nowMs: Long): Boolean =
        reason == LocalSessionEndReason.TRANSPORT_FAILURE && state == RideState.LOCAL_ACTIVE &&
            everLocalActive && privacyConsentAt != null && privacyConsentAt in 1..nowMs && canManuallyResume(nowMs)
}

// The SDK adapter accepts a server-approved ride, never a pair of raw device IDs.
// The signed ticket endpoint rechecks authorization even if this snapshot is stale.
internal class ApprovedCloudRide private constructor(
    val rideId: String,
    val roomId: String,
    val revision: Long,
    val localDeviceId: String,
    val peerDeviceId: String,
    val peerName: String,
    val reason: CloudEntryReason,
    val cloudDeadlineAt: Long
) {
    companion object {
        fun from(
            ride: RideSession,
            localDeviceId: String,
            peerName: String,
            privacyConsentAt: Long?,
            nowMs: Long,
            serverNowMs: Long = nowMs
        ): ApprovedCloudRide? {
            if (privacyConsentAt == null || privacyConsentAt !in 1..nowMs || !ride.canManuallyResume(serverNowMs) ||
                localDeviceId !in ride.members || ride.state !in setOf(RideState.CLOUD_PENDING, RideState.CLOUD_ACTIVE) ||
                ride.cloudReason == null || ride.revision < 1 ||
                !ride.rideId.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                !ride.roomId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
                (ride.cloudReason == CloudEntryReason.LOCAL_FAILURE && !ride.everLocalActive)) return null
            val startedAt = ride.cloudStartedAt ?: return null
            if (startedAt > serverNowMs || ride.cloudUsedMs < 0 || ride.cloudAllowanceMs <= ride.cloudUsedMs) return null
            val serverDeadline = startedAt + (ride.cloudAllowanceMs - ride.cloudUsedMs)
            val deadline = nowMs + (serverDeadline - serverNowMs)
            if (deadline <= nowMs || serverDeadline < startedAt) return null
            return ApprovedCloudRide(ride.rideId, ride.roomId, ride.revision, localDeviceId,
                ride.members.single { it != localDeviceId }, peerName.trim().ifBlank { "车友" }, ride.cloudReason, deadline)
        }
    }
}
