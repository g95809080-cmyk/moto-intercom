package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.group.GroupChannel
import com.kuma.motointercom.group.GroupRole
import com.kuma.motointercom.group.GroupTransport
import java.io.Closeable

internal class GroupCloudException(
    val httpStatus: Int,
    val code: String,
    val userMessage: String,
    val endedReason: String? = null
) : RuntimeException("$code($httpStatus)")

internal interface GroupCloudApi : Closeable {
    fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean): CloudAdmittedSession
    fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String): CloudJoinSubmission
    fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String, receipt: GroupJoinReceipt): CloudJoinSubmission =
        submitJoinRequest(inviteCode, deviceId, runtimeId, nickname)
    fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int): CloudJoinPollResult
    fun cancelJoinRequest(requestId: String, requestSecret: String)
    fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long): GroupCloudSnapshotPayload
    fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long): GroupCloudSnapshotPayload
    fun rotateInvite(token: String, roomId: String, expectedRevision: Long): GroupCloudSnapshotPayload
    fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long): GroupCloudSnapshotPayload
    fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long): CloudTransferResult
    fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long): GroupCloudSnapshotPayload
    fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long): GroupCloudSnapshotPayload
    fun leaveRoom(token: String, roomId: String): Boolean
    fun endRoom(token: String, roomId: String, expectedRevision: Long)
    fun resumeMember(token: String, roomId: String): CloudResumeResult
    fun refreshRtcToken(token: String, roomId: String): CloudRtcToken
    fun updateAudioAvailability(token: String, roomId: String, available: Boolean): GroupCloudSnapshotPayload
    fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch
    fun ackEvents(token: String, roomId: String, sequence: Long)
    fun createHybridRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean): CloudAdmittedSession =
        throw UnsupportedOperationException("HYBRID not supported")
    fun setRole(token: String, roomId: String, targetDeviceId: String, role: GroupRole, expectedRevision: Long): GroupCloudSnapshotPayload =
        throw UnsupportedOperationException("HYBRID not supported")
    fun setRiderIncludesFormation(token: String, roomId: String, included: Boolean, expectedRevision: Long): GroupCloudSnapshotPayload =
        throw UnsupportedOperationException("HYBRID not supported")
    fun switchChannel(token: String, roomId: String, channel: GroupChannel, expectedRevision: Long): CloudChannelActionResult =
        throw UnsupportedOperationException("HYBRID not supported")
    fun requestPrivate(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long): CloudPrivateRequestResult =
        throw UnsupportedOperationException("HYBRID not supported")
    fun acceptPrivate(token: String, roomId: String, inviteId: String, expectedRevision: Long): CloudChannelActionResult =
        throw UnsupportedOperationException("HYBRID not supported")
    fun declinePrivate(token: String, roomId: String, inviteId: String, expectedRevision: Long): GroupCloudSnapshotPayload =
        throw UnsupportedOperationException("HYBRID not supported")
    fun cancelPrivate(token: String, roomId: String, inviteId: String, expectedRevision: Long): GroupCloudSnapshotPayload =
        throw UnsupportedOperationException("HYBRID not supported")
    fun closePrivate(token: String, roomId: String, expectedRevision: Long): CloudChannelActionResult =
        throw UnsupportedOperationException("HYBRID not supported")
    fun switchTransport(
        token: String,
        roomId: String,
        target: GroupTransport,
        reason: String,
        expectedRevision: Long?,
        expectedTransportEpoch: Long
    ): CloudTransportSwitchResult = throw UnsupportedOperationException("HYBRID not supported")
    fun reportLocalStatus(
        token: String,
        roomId: String,
        transportEpoch: Long,
        localReady: Boolean,
        localReachable: Boolean,
        localProbeActive: Boolean,
        localRssiDbm: Int? = null,
        localControlReady: Boolean = false,
        localHostAddress: String? = null,
        localFrequencyMhz: Int? = null,
        preconnectFailed: Boolean = false
    ): CloudLocalStatusResult = throw UnsupportedOperationException("HYBRID not supported")
}
