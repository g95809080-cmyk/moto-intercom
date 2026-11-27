package com.kuma.motointercom.group

import com.kuma.motointercom.DiagnosticLog

internal fun interface GroupDiagnosticSink {
    fun record(event: String)
}

/** Redacted structured lifecycle diagnostics for group radio (FR-20). Never logs join codes or credentials. */
internal class GroupDiagnostics(
    private val sink: GroupDiagnosticSink = GroupDiagnosticSink { DiagnosticLog.i(TAG, it) }
) {
    fun create(room: GroupRoomKey, stage: String) =
        emit("group_create", "room=${short(room.instanceId)}", "stage=$stage")

    fun join(room: GroupRoomKey, role: String, seats: Int) =
        emit("group_join", "room=${short(room.instanceId)}", "role=$role", "seats=$seats")

    fun voiceReady(room: GroupRoomKey, channel: GroupChannel, elapsedMs: Long) =
        emit("group_voice_ready", "room=${short(room.instanceId)}", "channel=${channelKind(channel)}", "elapsedMs=${elapsedMs.coerceAtLeast(0)}")

    fun channelSwitch(room: GroupRoomKey, from: GroupChannel?, to: GroupChannel, result: String) =
        emit("group_channel_switch", "room=${short(room.instanceId)}", "from=${from?.let(::channelKind) ?: "NONE"}",
            "to=${channelKind(to)}", "result=$result")

    fun privateCall(room: GroupRoomKey, inviteId: String, action: String) =
        emit("group_private_call", "room=${short(room.instanceId)}", "invite=${short(inviteId)}", "action=$action")

    fun roleChanged(room: GroupRoomKey, role: GroupRole? = null, includesFormation: Boolean? = null) =
        emit("group_role_changed", "room=${short(room.instanceId)}",
            *(listOfNotNull(role?.let { "role=${it.name}" }, includesFormation?.let { "includesFormation=$it" }).toTypedArray()))

    fun recovery(room: GroupRoomKey?, actor: String, state: String, elapsedMs: Long? = null) =
        emit("group_recovery", "room=${room?.let { short(it.instanceId) } ?: "none"}", "actor=$actor", "state=$state",
            *(listOfNotNull(elapsedMs?.let { "elapsedMs=${it.coerceAtLeast(0)}" }).toTypedArray()))

    fun end(room: GroupRoomKey?, reason: GroupTerminalReason) =
        emit("group_end", "room=${room?.let { short(it.instanceId) } ?: "none"}", "reason=${reason.name}")

    fun cloudCreate(roomId: String, requireApproval: Boolean) =
        emit("group_cloud_create", "room=${short(roomId)}", "requireApproval=$requireApproval")

    fun cloudJoinRequest(status: String) =
        emit("group_cloud_join_request", "status=$status")

    fun cloudApproval(decision: String) =
        emit("group_cloud_approval", "decision=$decision")

    fun cloudOwnerTransfer(roomId: String, alsoLeave: Boolean) =
        emit("group_cloud_owner_transfer", "room=${short(roomId)}", "alsoLeave=$alsoLeave")

    fun cloudRtcTokenRefreshed(roomId: String, expiresAtMs: Long) =
        emit("group_cloud_rtc_token_refreshed", "room=${short(roomId)}", "expiresAtMs=$expiresAtMs")

    fun cloudRtcJoinAttempt(roomId: String) =
        emit("group_cloud_rtc_join_attempt", "room=${short(roomId)}")

    fun cloudRtcJoinReturned(roomId: String, code: Int) =
        emit("group_cloud_rtc_join_returned", "room=${short(roomId)}", "code=$code")

    fun cloudRtcRoomState(roomId: String, source: String, state: Int, reason: Int?) =
        emit("group_cloud_rtc_room_state", "room=${short(roomId)}", "source=$source",
            "state=$state", "reason=${reason ?: "none"}")

    fun cloudRtcConnectionState(state: Int, reason: Int) =
        emit("group_cloud_rtc_connection_state", "state=$state", "reason=$reason")

    fun cloudRtcSdkError(code: Int) =
        emit("group_cloud_rtc_sdk_error", "code=$code")

    fun cloudRtcTokenUpdate(code: Int) =
        emit("group_cloud_rtc_token_update", "code=$code")

    fun hybridTransportSwitch(roomId: String, from: GroupTransport, to: GroupTransport, epoch: Long, reason: String) =
        emit("group_hybrid_transport_switch", "room=${short(roomId)}", "from=${from.name}", "to=${to.name}", "epoch=$epoch", "reason=$reason")

    fun hybridMediaBarrier(roomId: String, stage: String, epoch: Long) =
        emit("group_hybrid_media_barrier", "room=${short(roomId)}", "stage=$stage", "epoch=$epoch")

    fun hybridLocalAuthorityReject(roomId: String) =
        emit("group_hybrid_local_authority_reject", "room=${short(roomId)}")

    fun hybridProximity(roomId: String, stage: String, epoch: Long) =
        emit("group_hybrid_proximity", "room=${short(roomId)}", "stage=$stage", "epoch=$epoch")

    private fun emit(name: String, vararg fields: String) {
        sink.record(buildString {
            append(name)
            fields.forEach { field -> append(' '); append(field) }
        })
    }

    private fun channelKind(channel: GroupChannel): String = when (channel) {
        GroupChannel.Public -> "PUBLIC"
        GroupChannel.Rider -> "RIDER"
        is GroupChannel.Private -> "PRIVATE"
    }

    private fun short(id: String): String = id.replace("-", "").takeLast(8).ifEmpty { "none" }

    companion object {
        private const val TAG = "MotoComGroup"
    }
}
