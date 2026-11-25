package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.group.*
import java.util.UUID

internal sealed interface GroupCloudInternalEvent {
    data class Start(val request: GroupCloudStartRequest) : GroupCloudInternalEvent
    data class Created(val operation: UUID, val session: CloudAdmittedSession) : GroupCloudInternalEvent
    data class JoinSubmitted(val operation: UUID, val submission: CloudJoinSubmission) : GroupCloudInternalEvent
    data class JoinCancelled(val operation: UUID) : GroupCloudInternalEvent
    data class JoinPolled(val operation: UUID, val result: CloudJoinPollResult) : GroupCloudInternalEvent
    data class BatchReceived(val operation: UUID, val batch: CloudEventBatch) : GroupCloudInternalEvent
    data class SnapshotApplied(val operation: UUID, val snapshot: GroupCloudSnapshotPayload, val message: String? = null) : GroupCloudInternalEvent
    data class TransferCompleted(val operation: UUID, val result: CloudTransferResult) : GroupCloudInternalEvent
    data class EndCompleted(val operation: UUID) : GroupCloudInternalEvent
    data class Resumed(val operation: UUID, val result: CloudResumeResult) : GroupCloudInternalEvent
    data class RtcTokenRefreshed(val operation: UUID, val rtc: CloudRtcToken) : GroupCloudInternalEvent
    data class Rtc(val event: GroupRtcEvent, val attemptId: Long = 0L) : GroupCloudInternalEvent
    data class RouteChanged(val route: AudioRouteSelection) : GroupCloudInternalEvent
    data class PollFailed(val operation: UUID, val error: GroupCloudException) : GroupCloudInternalEvent
    data class ActionFailed(val operation: UUID, val action: String, val error: GroupCloudException) : GroupCloudInternalEvent
    data class Fatal(val operation: UUID, val message: String, val reason: GroupTerminalReason = GroupTerminalReason.FAILED) : GroupCloudInternalEvent
}

internal sealed interface GroupCloudEffect {
    data class CreateRoom(val operation: UUID, val requireApproval: Boolean) : GroupCloudEffect
    data class SubmitJoin(val operation: UUID, val inviteCode: GroupCloudInviteCode, val receipt: GroupJoinReceipt) : GroupCloudEffect
    data class PollJoinRequest(val operation: UUID, val requestId: String, val requestSecret: String) : GroupCloudEffect
    data class CancelJoinRequest(val operation: UUID, val retraction: GroupJoinRetraction) : GroupCloudEffect
    data class PollEvents(val operation: UUID, val token: String, val roomId: String, val afterSeq: Long) : GroupCloudEffect
    data class AckEvents(val operation: UUID, val token: String, val roomId: String, val sequence: Long) : GroupCloudEffect
    data class ResumeRoom(val operation: UUID, val token: String, val roomId: String) : GroupCloudEffect
    data class RefreshRtcToken(val operation: UUID, val token: String, val roomId: String) : GroupCloudEffect
    data class ApproveJoin(val operation: UUID, val token: String, val roomId: String, val requestId: String, val expectedRevision: Long) : GroupCloudEffect
    data class RejectJoin(val operation: UUID, val token: String, val roomId: String, val requestId: String, val expectedRevision: Long) : GroupCloudEffect
    data class RotateInvite(val operation: UUID, val token: String, val roomId: String, val expectedRevision: Long) : GroupCloudEffect
    data class SetRequireApproval(val operation: UUID, val token: String, val roomId: String, val required: Boolean, val expectedRevision: Long) : GroupCloudEffect
    data class TransferOwner(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val alsoLeave: Boolean, val expectedRevision: Long) : GroupCloudEffect
    data class RemoveMember(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val expectedRevision: Long) : GroupCloudEffect
    data class UnblockMember(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val expectedRevision: Long) : GroupCloudEffect
    data class LeaveRoom(val token: String, val roomId: String) : GroupCloudEffect
    data class EndRoom(val operation: UUID, val token: String, val roomId: String, val expectedRevision: Long) : GroupCloudEffect
    data class ReportAudio(val operation: UUID, val token: String, val roomId: String, val available: Boolean) : GroupCloudEffect
    data class JoinRtc(
        val token: CloudRtcToken,
        val muted: Boolean,
        val route: AudioRouteSelection,
        val attemptId: Long,
        val allowedUsers: Set<String>
    ) : GroupCloudEffect
    data object LeaveRtc : GroupCloudEffect
    data class ApplyRtcToken(val token: CloudRtcToken, val attemptId: Long) : GroupCloudEffect
    data class SetRtcMuted(val muted: Boolean) : GroupCloudEffect
    data class SetRtcAllowedUsers(val userIds: Set<String>) : GroupCloudEffect
    data class SetRtcRoute(val route: AudioRouteSelection) : GroupCloudEffect
    data class Stop(val flushTerminal: Boolean) : GroupCloudEffect
    data class Publish(val snapshot: GroupSessionSnapshot) : GroupCloudEffect
}

/** Single product-state writer for NETWORK mode. RTC media is an effect boundary; no SDP or TURN state lives here. */
internal class GroupCloudSessionOrchestrator(
    private val endpoint: GroupAuthEndpoint,
    private val nickname: String,
    private val nowMs: () -> Long,
    private val wallNowMs: () -> Long = System::currentTimeMillis,
    private val assertWriter: () -> Unit,
    private val effects: (GroupCloudEffect) -> Unit,
    private val diagnostics: GroupDiagnostics = GroupDiagnostics()
) {
    private sealed interface QueueItem {
        data class User(val event: GroupSessionEvent) : QueueItem
        data class Internal(val event: GroupCloudInternalEvent) : QueueItem
    }

    private var phase = GroupPhase.IDLE
    private var operation: UUID? = null
    private var participation = GroupParticipation()
    private var roomId: String? = null
    private var roomKey: GroupRoomKey? = null
    private var sessionToken: String? = null
    private var tokenExpiresAtMs = 0L
    private var latestSeq = 0L
    private var currentPayload: GroupCloudSnapshotPayload? = null
    private var local: GroupMemberLease? = null
    private var rtcToken: CloudRtcToken? = null
    private var networkMediaState = GroupNetworkMediaState.IDLE
    private var activeSpeakerIds = emptySet<String>()
    private var route = AudioRouteSelection.EARPIECE
    private var joinCancellation: GroupJoinCancellation? = null
    private var pendingRequestId: String? = null
    private var pendingRequestSecret: String? = null
    private var pendingRequestExpiresAtMs = 0L
    private var pollInFlight = false
    private var resumeInFlight = false
    private var rtcRefreshInFlight = false
    private var rtcRefreshRetryAtMs = 0L
    private var rtcJoining = false
    private var rtcJoined = false
    private var nextRtcAttemptId = 0L
    private var activeRtcAttemptId: Long? = null
    private var rtcRetryAtMs = 0L
    private var recoveryDeadline: Long? = null
    private var recoveryStartedAtMs: Long? = null
    private var nextRetryAtMs = 0L
    private var roomStartedAtMs = 0L
    private var voiceReadyReported = false
    private var audioAvailable = false
    private var terminalReason: GroupTerminalReason? = null
    private var message = ""
    private var allowedRtcUsers = emptySet<String>()

    private val queue = ArrayDeque<QueueItem>()
    private var dispatching = false
    private var output = mutableListOf<GroupCloudEffect>()

    @Volatile
    var snapshot: GroupSessionSnapshot = buildSnapshot()
        private set

    fun dispatch(event: GroupSessionEvent) = enqueue(QueueItem.User(event))
    fun dispatchInternal(event: GroupCloudInternalEvent) = enqueue(QueueItem.Internal(event))

    private fun enqueue(item: QueueItem) {
        assertWriter()
        check(queue.size < 256) { "Group cloud writer queue overflow" }
        queue.addLast(item)
        if (dispatching) return
        dispatching = true
        try {
            while (queue.isNotEmpty()) {
                output = mutableListOf()
                when (val next = queue.removeFirst()) {
                    is QueueItem.User -> handleUser(next.event)
                    is QueueItem.Internal -> handleInternal(next.event)
                }
                val op = operation
                if (op != null) joinCancellation?.next(nowMs())?.let { output += GroupCloudEffect.CancelJoinRequest(op, it) }
                syncRtc()
                snapshot = buildSnapshot()
                observeVoiceReady(snapshot)
                output.add(0, GroupCloudEffect.Publish(snapshot))
                for (effect in output.toList()) {
                    try {
                        effects(effect)
                    } catch (_: Exception) {
                        if (effect is GroupCloudEffect.JoinRtc) {
                            rtcJoining = false
                            activeRtcAttemptId = null
                            rtcRetryAtMs = nowMs() + RTC_RETRY_INTERVAL_MS
                            networkMediaState = GroupNetworkMediaState.FAILED
                            message = "启动火山引擎 RTC 失败，请重试"
                        }
                    }
                }
            }
        } finally {
            dispatching = false
        }
    }

    private fun handleInternal(event: GroupCloudInternalEvent) {
        when (event) {
            is GroupCloudInternalEvent.Start -> if (phase == GroupPhase.IDLE) {
                terminalReason = null
                operation = UUID.randomUUID()
                roomStartedAtMs = nowMs()
                when (val request = event.request) {
                    is GroupCloudStartRequest.Create -> {
                        phase = GroupPhase.CREATING
                        message = "正在创建火山引擎 RTC 网络房间"
                        output += GroupCloudEffect.CreateRoom(operation!!, request.requireApproval)
                    }
                    is GroupCloudStartRequest.Join -> {
                        phase = GroupPhase.JOINING
                        message = "正在通过邀请码连接云端房间"
                        val cancellation = GroupJoinCancellation()
                        joinCancellation = cancellation
                        output += GroupCloudEffect.SubmitJoin(operation!!, request.inviteCode, cancellation.initial)
                    }
                }
            }

            is GroupCloudInternalEvent.Created -> {
                if (event.operation != operation || phase != GroupPhase.CREATING) return
                enterAdmittedSession(event.session, created = true)
            }

            is GroupCloudInternalEvent.JoinCancelled -> {
                if (event.operation != operation || joinCancellation?.requested != true) return
                finish("已取消加入申请", flush = false, terminal = GroupTerminalReason.USER_LEFT)
            }
            is GroupCloudInternalEvent.JoinSubmitted -> {
                if (event.operation != operation || phase != GroupPhase.JOINING) return
                joinCancellation?.submitted(event.submission)
                if (joinCancellation?.requested == true) return
                when (val submission = event.submission) {
                    is CloudJoinSubmission.Admitted -> {
                        diagnostics.cloudJoinRequest("admitted_direct")
                        enterAdmittedSession(submission.session, created = false)
                    }
                    is CloudJoinSubmission.Pending -> {
                        diagnostics.cloudJoinRequest("pending")
                        roomId = submission.roomId
                        pendingRequestId = submission.requestId
                        pendingRequestSecret = submission.requestSecret
                        pendingRequestExpiresAtMs = submission.expiresAtMs
                        phase = GroupPhase.WAITING
                        message = "已提交加入申请，等待群主审批"
                        scheduleJoinPoll()
                    }
                }
            }

            is GroupCloudInternalEvent.JoinPolled -> {
                if (event.operation != operation || phase != GroupPhase.WAITING || joinCancellation?.requested == true) return
                pollInFlight = false
                when (val result = event.result) {
                    is CloudJoinPollResult.Pending -> {
                        pendingRequestExpiresAtMs = result.expiresAtMs
                        scheduleJoinPoll()
                    }
                    is CloudJoinPollResult.Admitted -> {
                        diagnostics.cloudJoinRequest("approved")
                        pendingRequestId = null
                        pendingRequestSecret = null
                        enterAdmittedSession(result.session, created = false)
                    }
                    is CloudJoinPollResult.Rejected -> {
                        diagnostics.cloudJoinRequest("rejected")
                        finish(result.message, flush = false, terminal = GroupTerminalReason.REFUSED)
                    }
                    is CloudJoinPollResult.Ended -> {
                        val terminal = if (result.code == "CANCELLED") GroupTerminalReason.USER_LEFT else GroupTerminalReason.HOST_ENDED
                        finish(result.message, flush = false, terminal = terminal)
                    }
                    is CloudJoinPollResult.Expired -> {
                        finish(result.message, flush = false, terminal = GroupTerminalReason.FAILED)
                    }
                }
            }

            is GroupCloudInternalEvent.BatchReceived -> {
                if (event.operation != operation || (phase != GroupPhase.IN_ROOM && phase != GroupPhase.RECONNECTING)) return
                pollInFlight = false
                val batch = event.batch
                if (batch.latestSeq > latestSeq) latestSeq = batch.latestSeq
                if (batch.resync && batch.snapshot != null) applySnapshot(batch.snapshot)
                var maxAck = 0L
                for (cloudEvent in batch.events) {
                    if (phase == GroupPhase.IDLE) break
                    if (cloudEvent.seq > maxAck) maxAck = cloudEvent.seq
                    when (cloudEvent) {
                        is GroupCloudEvent.Snapshot -> applySnapshot(cloudEvent.payload)
                        is GroupCloudEvent.Terminal -> {
                            finish(cloudEvent.message, flush = false, terminal = mapTerminalReason(cloudEvent.reason))
                            return
                        }
                    }
                }
                if (phase == GroupPhase.IN_ROOM) {
                    val op = operation
                    val token = sessionToken
                    val room = roomId
                    if (maxAck > 0L && op != null && token != null && room != null) {
                        output += GroupCloudEffect.AckEvents(op, token, room, maxAck)
                    }
                    scheduleEventPoll()
                }
            }

            is GroupCloudInternalEvent.SnapshotApplied -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                applySnapshot(event.snapshot)
                if (phase != GroupPhase.IDLE && !event.message.isNullOrBlank()) message = event.message
            }

            is GroupCloudInternalEvent.TransferCompleted -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                roomId?.let { diagnostics.cloudOwnerTransfer(it, event.result.left) }
                if (event.result.left) {
                    finish("已转让群主并离开网络房间", flush = false, terminal = GroupTerminalReason.USER_LEFT)
                } else {
                    event.result.snapshot?.let(::applySnapshot)
                    if (phase != GroupPhase.IDLE) message = "已转让群主，当前身份为普通成员"
                }
            }

            is GroupCloudInternalEvent.EndCompleted -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                finish("已解散网络房间", flush = false, terminal = GroupTerminalReason.USER_LEFT)
            }

            is GroupCloudInternalEvent.Resumed -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                resumeInFlight = false
                val result = event.result
                val resumedRtc = result.rtc
                if (resumedRtc == null || !resumedRtc.isUsable(wallNowMs())) {
                    finish("RTC 语音凭证不可用，无法恢复网络对讲", flush = false, terminal = GroupTerminalReason.FAILED)
                    return
                }
                rtcToken = resumedRtc
                latestSeq = maxOf(latestSeq, result.latestSeq)
                val elapsed = recoveryStartedAtMs?.let { nowMs() - it }
                recoveryDeadline = null
                recoveryStartedAtMs = null
                phase = GroupPhase.IN_ROOM
                networkMediaState = GroupNetworkMediaState.IDLE
                applySnapshot(result.snapshot)
                if (phase == GroupPhase.IN_ROOM) {
                    message = "已重新连接网络房间"
                    diagnostics.recovery(roomKey, "member", "recovered", elapsed)
                    scheduleEventPoll()
                }
            }

            is GroupCloudInternalEvent.RtcTokenRefreshed -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                val attemptId = activeRtcAttemptId
                if (attemptId == null) {
                    recordRtcToken(event.rtc)
                } else {
                    rtcRefreshInFlight = true
                    output += GroupCloudEffect.ApplyRtcToken(event.rtc, attemptId)
                }
            }

            is GroupCloudInternalEvent.Rtc -> {
                if (phase != GroupPhase.IN_ROOM) return
                if (event.attemptId != 0L && event.attemptId != activeRtcAttemptId) return
                when (val rtcEvent = event.event) {
                    GroupRtcEvent.Joined -> {
                        if (!rtcJoining && !rtcJoined) return
                        rtcJoining = false
                        rtcJoined = true
                        rtcRetryAtMs = 0L
                        networkMediaState = GroupNetworkMediaState.JOINED
                        message = "火山引擎 RTC 公共频道语音已连接"
                    }
                    is GroupRtcEvent.JoinFailed -> {
                        if (!rtcJoining && !rtcJoined) return
                        rtcJoining = false
                        rtcJoined = false
                        activeRtcAttemptId = null
                        activeSpeakerIds = emptySet()
                        if (rtcEvent.code == -1000) {
                            rtcToken = null
                            requestRtcTokenRefresh()
                        }
                        rtcRetryAtMs = nowMs() + RTC_RETRY_INTERVAL_MS
                        networkMediaState = GroupNetworkMediaState.FAILED
                        message = rtcEvent.message
                    }
                    is GroupRtcEvent.TokenUpdated -> {
                        if (rtcEvent.accepted) recordRtcToken(rtcEvent.token) else retryRtcTokenRefresh()
                    }
                    GroupRtcEvent.TokenWillExpire -> {
                        if (rtcJoining || rtcJoined) requestRtcTokenRefresh()
                    }
                    is GroupRtcEvent.ConnectionLost -> {
                        if (!rtcJoining && !rtcJoined) return
                        rtcJoined = false
                        rtcJoining = false
                        activeRtcAttemptId = null
                        activeSpeakerIds = emptySet()
                        rtcRetryAtMs = nowMs() + RTC_RETRY_INTERVAL_MS
                        networkMediaState = GroupNetworkMediaState.JOINING
                        message = rtcEvent.message
                    }
                    is GroupRtcEvent.ActiveSpeakers -> {
                        if (!rtcJoined) return
                        activeSpeakerIds = currentPayload?.view?.members.orEmpty().speakingDevices(rtcEvent.userIds)
                    }
                    is GroupRtcEvent.Error -> {
                        if (rtcJoining || rtcJoined) message = rtcEvent.message
                    }
                }
            }

            is GroupCloudInternalEvent.RouteChanged -> {
                route = event.route
                if (rtcJoined) output += GroupCloudEffect.SetRtcRoute(event.route)
            }

            is GroupCloudInternalEvent.PollFailed -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                pollInFlight = false
                if (joinCancellation?.requested == true) return
                handleCloudError(event.error, fromPoll = true)
            }

            is GroupCloudInternalEvent.ActionFailed -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                if (joinCancellation?.requested == true) {
                    if (event.action == "join") joinCancellation?.submitted(null)
                    if (event.action == "cancel_join") {
                        joinCancellation?.retryAt(nowMs() + RETRY_INTERVAL_MS)
                        message = "撤销尚未确认，正在重试"
                    }
                    return
                }
                when (event.action) {
                    "resume" -> resumeInFlight = false
                    "rtc_token" -> retryRtcTokenRefresh()
                }
                handleCloudError(event.error, fromPoll = false, action = event.action)
            }

            is GroupCloudInternalEvent.Fatal -> {
                if (event.operation != operation || phase == GroupPhase.IDLE) return
                val token = sessionToken
                val room = roomId
                if (token != null && room != null && !snapshot.isOwner) {
                    output += GroupCloudEffect.LeaveRoom(token, room)
                }
                finish(event.message, flush = true, terminal = event.reason)
            }
        }
    }

    private fun handleUser(event: GroupSessionEvent) {
        when (event) {
            is GroupSessionEvent.Mute -> {
                val token = participation.token ?: return
                participation = participation.muteSelf(token, event.value)
                if (rtcJoining || rtcJoined) output += GroupCloudEffect.SetRtcMuted(event.value)
            }
            is GroupSessionEvent.Block -> {
                val token = participation.token ?: return
                if (event.peerId == endpoint.deviceId) return
                participation = participation.block(token, event.peerId, event.value)
            }
            is GroupSessionEvent.AudioAvailable -> {
                if (phase != GroupPhase.IN_ROOM || audioAvailable == event.available) return
                audioAvailable = event.available
                val op = operation ?: return
                val token = sessionToken ?: return
                val room = roomId ?: return
                output += GroupCloudEffect.ReportAudio(op, token, room, event.available)
            }
            is GroupSessionEvent.ApproveJoin -> {
                val context = ownerContext() ?: return
                diagnostics.cloudApproval("approve")
                output += GroupCloudEffect.ApproveJoin(
                    context.op, context.token, context.roomId, event.requestId, context.revision
                )
            }
            is GroupSessionEvent.RejectJoin -> {
                val context = ownerContext() ?: return
                diagnostics.cloudApproval("reject")
                output += GroupCloudEffect.RejectJoin(
                    context.op, context.token, context.roomId, event.requestId, context.revision
                )
            }
            is GroupSessionEvent.RotateInvite -> {
                val context = ownerContext() ?: return
                output += GroupCloudEffect.RotateInvite(context.op, context.token, context.roomId, context.revision)
            }
            is GroupSessionEvent.SetRequireApproval -> {
                val context = ownerContext() ?: return
                output += GroupCloudEffect.SetRequireApproval(
                    context.op, context.token, context.roomId, event.required, context.revision
                )
            }
            is GroupSessionEvent.TransferOwner -> {
                val context = ownerContext() ?: return
                val target = currentPayload?.view?.members?.singleOrNull {
                    it.lease.deviceId == event.targetDeviceId && it.status == GroupMemberStatus.ADMITTED
                }
                if (target == null || target.lease.deviceId == endpoint.deviceId) {
                    message = "只能转让给当前在线的已准入成员"
                    return
                }
                output += GroupCloudEffect.TransferOwner(
                    context.op, context.token, context.roomId, event.targetDeviceId, event.alsoLeave, context.revision
                )
            }
            is GroupSessionEvent.Remove -> {
                val context = ownerContext() ?: return
                if (event.peerId == endpoint.deviceId) return
                output += GroupCloudEffect.RemoveMember(
                    context.op, context.token, context.roomId, event.peerId, context.revision
                )
            }
            is GroupSessionEvent.Unblock -> {
                val context = ownerContext() ?: return
                output += GroupCloudEffect.UnblockMember(
                    context.op, context.token, context.roomId, event.peerId, context.revision
                )
            }
            GroupSessionEvent.EndRoom -> {
                val context = ownerContext() ?: return
                output += GroupCloudEffect.EndRoom(context.op, context.token, context.roomId, context.revision)
            }
            GroupSessionEvent.Leave -> handleLeave()
            GroupSessionEvent.Tick -> handleTick()
            else -> Unit
        }
    }

    private data class OwnerActionContext(val op: UUID, val token: String, val roomId: String, val revision: Long)

    private fun ownerContext(): OwnerActionContext? {
        if (phase != GroupPhase.IN_ROOM) return null
        val payload = currentPayload ?: return null
        if (payload.view.ownerId != endpoint.deviceId) return null
        return OwnerActionContext(
            operation ?: return null,
            sessionToken ?: return null,
            roomId ?: return null,
            payload.view.revision
        )
    }

    private fun handleLeave() {
        when (phase) {
            GroupPhase.IDLE -> Unit
            GroupPhase.CREATING -> finish("已取消连接", flush = false, terminal = GroupTerminalReason.USER_LEFT)
            GroupPhase.JOINING, GroupPhase.WAITING -> {
                joinCancellation?.requested = true
                message = "正在撤销加入申请"
            }
            GroupPhase.IN_ROOM, GroupPhase.RECONNECTING -> {
                val payload = currentPayload
                val isOwner = payload?.view?.ownerId == endpoint.deviceId
                val occupied = payload?.view?.members?.size ?: 0
                if (isOwner && occupied > 1) {
                    message = "群主离开前必须先转让群主或解散全队"
                    return
                }
                val token = sessionToken
                val room = roomId
                if (token != null && room != null) output += GroupCloudEffect.LeaveRoom(token, room)
                finish(
                    if (isOwner) "网络房间已结束" else "已离开网络房间",
                    flush = true,
                    terminal = GroupTerminalReason.USER_LEFT
                )
            }
            else -> finish("已离开网络房间", flush = false, terminal = GroupTerminalReason.USER_LEFT)
        }
    }

    private fun handleTick() {
        if (joinCancellation?.requested == true) return
        val now = nowMs()
        if (phase == GroupPhase.WAITING && pendingRequestExpiresAtMs > 0L && wallNowMs() >= pendingRequestExpiresAtMs) {
            finish("加入申请已超时，请重新申请", flush = false, terminal = GroupTerminalReason.FAILED)
            return
        }
        if (phase == GroupPhase.RECONNECTING) {
            val deadline = recoveryDeadline
            if (deadline != null && now >= deadline) {
                finish("断线超过 60 秒，网络房间连接已结束", flush = false, terminal = GroupTerminalReason.RECOVERY_EXPIRED)
                return
            }
            if (!resumeInFlight && now >= nextRetryAtMs) {
                val op = operation ?: return
                val token = sessionToken ?: return
                val room = roomId ?: return
                resumeInFlight = true
                nextRetryAtMs = now + RETRY_INTERVAL_MS
                output += GroupCloudEffect.ResumeRoom(op, token, room)
            }
            return
        }
        if (phase == GroupPhase.IN_ROOM) {
            if (rtcRefreshRetryAtMs != 0L || rtcToken?.needsRefresh(wallNowMs()) == true) requestRtcTokenRefresh()
            if (networkMediaState == GroupNetworkMediaState.FAILED && now >= rtcRetryAtMs) {
                rtcRetryAtMs = 0L
            }
            if (!pollInFlight) scheduleEventPoll()
        }
    }

    private fun enterAdmittedSession(session: CloudAdmittedSession, created: Boolean) {
        joinCancellation = null
        if (session.snapshot.view.mode != GroupMode.NETWORK) {
            output += GroupCloudEffect.LeaveRoom(session.sessionToken, session.roomId)
            finish("该邀请码属于双模 4 人房间，请切换到「双模 4 人」加入", flush = true, terminal = GroupTerminalReason.REFUSED)
            return
        }
        val initialRtc = session.rtc
        if (initialRtc == null || !initialRtc.isUsable(wallNowMs())) {
            output += GroupCloudEffect.LeaveRoom(session.sessionToken, session.roomId)
            finish("服务端签发的 RTC 凭证已过期，请重试", flush = true, terminal = GroupTerminalReason.FAILED)
            return
        }
        val selfMember = session.snapshot.view.members.singleOrNull {
            it.lease.deviceId == endpoint.deviceId &&
                it.lease.runtimeId == endpoint.runtimeId &&
                it.status == GroupMemberStatus.ADMITTED
        }
        if (selfMember == null) {
            finish("云端成员校验失败，请重新加入", flush = false, terminal = GroupTerminalReason.FAILED)
            return
        }
        roomId = session.roomId
        roomKey = session.snapshot.view.key
        sessionToken = session.sessionToken
        tokenExpiresAtMs = session.tokenExpiresAtMs
        latestSeq = session.latestSeq
        rtcToken = initialRtc
        local = selfMember.lease
        currentPayload = session.snapshot
        participation = participation.begin(session.snapshot.view.key)
        phase = GroupPhase.IN_ROOM
        voiceReadyReported = false
        audioAvailable = false
        networkMediaState = GroupNetworkMediaState.IDLE
        message = if (created) "网络房间已创建，分享邀请码给车友加入" else "已加入网络房间（公共频道）"
        if (created) diagnostics.cloudCreate(session.roomId, session.snapshot.requireApproval)
        else diagnostics.join(session.snapshot.view.key, selfMember.role.name, session.snapshot.view.members.size)
        scheduleEventPoll()
    }

    private fun applySnapshot(payload: GroupCloudSnapshotPayload) {
        val expected = roomKey ?: return
        if (payload.view.key != expected) return
        val previousRevision = currentPayload?.view?.revision ?: 0L
        if (payload.view.revision < previousRevision) return
        if (payload.view.ended) {
            val reason = mapTerminalReason(payload.endedReason ?: "OWNER_ENDED")
            finish(
                if (reason == GroupTerminalReason.RECOVERY_EXPIRED) "群主断线超过 60 秒，网络房间已结束" else "群主已解散网络房间",
                flush = false,
                terminal = reason
            )
            return
        }
        val selfMember = payload.view.members.singleOrNull {
            it.lease.deviceId == endpoint.deviceId && it.lease.runtimeId == endpoint.runtimeId
        }
        if (selfMember == null) {
            val terminal = if (endpoint.deviceId in payload.removedIds) GroupTerminalReason.REMOVED else GroupTerminalReason.FAILED
            finish(
                if (terminal == GroupTerminalReason.REMOVED) "你已被移出该网络房间" else "已不在房间成员名单中",
                flush = false,
                terminal = terminal
            )
            return
        }
        currentPayload = payload
        local = selfMember.lease
        activeSpeakerIds = activeSpeakerIds.intersect(payload.view.members.map { it.lease.deviceId }.toSet())
        if (selfMember.status == GroupMemberStatus.RESERVED) {
            beginRecovery("网络短暂中断，正在 60 秒内恢复席位")
        }
    }

    private fun handleCloudError(error: GroupCloudException, fromPoll: Boolean, action: String = "") {
        if (error.code == "CLIENT_CLOSED") return
        when (phase) {
            GroupPhase.CREATING, GroupPhase.JOINING -> {
                val terminal = when (error.code) {
                    "REMOVED" -> GroupTerminalReason.REMOVED
                    "ROOM_FULL", "INVALID_INVITE", "ALREADY_IN_ROOM" -> GroupTerminalReason.REFUSED
                    else -> GroupTerminalReason.FAILED
                }
                finish(error.userMessage, flush = false, terminal = terminal)
            }
            GroupPhase.WAITING -> {
                if (error.code in setOf("REQUEST_NOT_FOUND", "UNAUTHORIZED")) {
                    finish(error.userMessage, flush = false, terminal = GroupTerminalReason.FAILED)
                } else {
                    message = "网络波动，正在继续等待审批"
                    scheduleJoinPoll()
                }
            }
            GroupPhase.IN_ROOM, GroupPhase.RECONNECTING -> {
                when (error.code) {
                    "REMOVED" -> finish(error.userMessage, flush = false, terminal = GroupTerminalReason.REMOVED)
                    "ROOM_ENDED" -> finish(
                        error.userMessage,
                        flush = false,
                        terminal = mapTerminalReason(error.endedReason ?: "OWNER_ENDED")
                    )
                    "RECOVERY_EXPIRED" -> finish(error.userMessage, flush = false, terminal = GroupTerminalReason.RECOVERY_EXPIRED)
                    "UNAUTHORIZED" -> finish(error.userMessage, flush = false, terminal = GroupTerminalReason.FAILED)
                    "OWNER_TRANSFER_REQUIRED" -> message = error.userMessage
                    "REVISION_CONFLICT" -> {
                        message = "房间状态已更新，正在同步最新名单"
                        if (!pollInFlight) scheduleEventPoll()
                    }
                    "ROOM_FULL", "REQUEST_NOT_FOUND", "TARGET_NOT_ADMITTED", "FORBIDDEN",
                    "NOT_ADMITTED", "MEMBER_NOT_FOUND" -> message = error.userMessage
                    else -> {
                        if (action == "rtc_token") {
                            message = RTC_TOKEN_RETRY_MESSAGE
                        } else if (fromPoll || action == "resume") {
                            beginRecovery("网络连接中断，正在 60 秒内重连云端房间")
                        } else {
                            message = error.userMessage
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun recordRtcToken(token: CloudRtcToken) {
        if (rtcRefreshRetryAtMs != 0L && message == RTC_TOKEN_RETRY_MESSAGE) message = "语音凭证已续签"
        rtcToken = token
        rtcRefreshInFlight = false
        rtcRefreshRetryAtMs = 0L
        roomId?.let { diagnostics.cloudRtcTokenRefreshed(it, token.expiresAtMs) }
    }

    private fun retryRtcTokenRefresh() {
        rtcRefreshInFlight = false
        rtcRefreshRetryAtMs = nowMs() + RETRY_INTERVAL_MS
        message = RTC_TOKEN_RETRY_MESSAGE
    }

    private fun requestRtcTokenRefresh() {
        if (nowMs() < rtcRefreshRetryAtMs) return
        if (phase != GroupPhase.IN_ROOM || rtcRefreshInFlight) return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        rtcRefreshInFlight = true
        output += GroupCloudEffect.RefreshRtcToken(op, token, room)
    }

    private fun beginRecovery(statusMessage: String) {
        val now = nowMs()
        if (phase != GroupPhase.RECONNECTING) {
            phase = GroupPhase.RECONNECTING
            recoveryStartedAtMs = now
            recoveryDeadline = now + GroupRoom.RESERVATION_MS
            nextRetryAtMs = now + 1_000L
            diagnostics.recovery(roomKey, "member", "started")
        }
        message = statusMessage
    }

    private fun scheduleJoinPoll() {
        if (pollInFlight || phase != GroupPhase.WAITING) return
        val op = operation ?: return
        pollInFlight = true
        output += GroupCloudEffect.PollJoinRequest(
            op,
            pendingRequestId ?: return,
            pendingRequestSecret ?: return
        )
    }

    private fun scheduleEventPoll() {
        if (pollInFlight || phase != GroupPhase.IN_ROOM) return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        pollInFlight = true
        output += GroupCloudEffect.PollEvents(op, token, room, latestSeq)
    }

    private fun syncRtc() {
        val view = currentPayload?.view
        val self = local
        val selfMember = if (phase == GroupPhase.IN_ROOM && view != null && self != null) {
            view.members.singleOrNull { it.lease == self && it.status == GroupMemberStatus.ADMITTED }
        } else null
        val admittedCount = view?.members?.count { it.status == GroupMemberStatus.ADMITTED } ?: 0
        val eligible = selfMember != null && admittedCount >= 2
        if (!eligible) {
            if (rtcJoined || rtcJoining) output += GroupCloudEffect.LeaveRtc
            rtcJoined = false
            rtcJoining = false
            activeRtcAttemptId = null
            rtcRetryAtMs = 0L
            allowedRtcUsers = emptySet()
            activeSpeakerIds = emptySet()
            networkMediaState = when {
                phase != GroupPhase.IN_ROOM -> GroupNetworkMediaState.IDLE
                selfMember != null -> GroupNetworkMediaState.WAITING_FOR_PEER
                else -> GroupNetworkMediaState.IDLE
            }
            return
        }
        val allowed = view!!.members.allowedRtcUsers(participation.blockedDeviceIds)
        val token = rtcToken
        if (token != null && token.needsRefresh(wallNowMs())) requestRtcTokenRefresh()
        if (!rtcJoined && !rtcJoining && (rtcRetryAtMs == 0L || nowMs() >= rtcRetryAtMs)) {
            if (token == null || !token.isUsable(wallNowMs())) return
            rtcJoining = true
            allowedRtcUsers = allowed
            val attemptId = ++nextRtcAttemptId
            activeRtcAttemptId = attemptId
            rtcRetryAtMs = 0L
            networkMediaState = GroupNetworkMediaState.JOINING
            output += GroupCloudEffect.JoinRtc(token, participation.selfMuted, route, attemptId, allowed)
        } else if ((rtcJoining || rtcJoined) && allowed != allowedRtcUsers) {
            allowedRtcUsers = allowed
            output += GroupCloudEffect.SetRtcAllowedUsers(allowed)
        }
    }

    private fun observeVoiceReady(currentSnapshot: GroupSessionSnapshot) {
        if (currentSnapshot.phase == GroupPhase.IN_ROOM && currentSnapshot.voiceReady && !voiceReadyReported) {
            voiceReadyReported = true
            currentSnapshot.view?.key?.let {
                diagnostics.voiceReady(it, GroupChannel.Public, nowMs() - roomStartedAtMs)
            }
        } else if (!currentSnapshot.voiceReady) {
            voiceReadyReported = false
        }
    }

    private fun mapTerminalReason(reason: String): GroupTerminalReason = when (reason) {
        "REMOVED" -> GroupTerminalReason.REMOVED
        "OWNER_RECOVERY_EXPIRED", "RECOVERY_EXPIRED" -> GroupTerminalReason.RECOVERY_EXPIRED
        "OWNER_ENDED", "LAST_MEMBER_LEFT" -> GroupTerminalReason.HOST_ENDED
        else -> GroupTerminalReason.FAILED
    }

    private fun finish(reasonText: String, flush: Boolean, terminal: GroupTerminalReason) {
        diagnostics.end(roomKey, terminal)
        participation.token?.let { participation = participation.stop(it) }
        if (rtcJoined || rtcJoining) output += GroupCloudEffect.LeaveRtc
        phase = GroupPhase.IDLE
        operation = null
        roomId = null
        roomKey = null
        sessionToken = null
        tokenExpiresAtMs = 0L
        latestSeq = 0L
        currentPayload = null
        local = null
        rtcToken = null
        joinCancellation = null
        pendingRequestId = null
        pendingRequestSecret = null
        pendingRequestExpiresAtMs = 0L
        pollInFlight = false
        resumeInFlight = false
        rtcRefreshInFlight = false
        rtcRefreshRetryAtMs = 0L
        rtcJoining = false
        rtcJoined = false
        activeRtcAttemptId = null
        rtcRetryAtMs = 0L
        recoveryDeadline = null
        recoveryStartedAtMs = null
        audioAvailable = false
        voiceReadyReported = false
        networkMediaState = GroupNetworkMediaState.IDLE
        activeSpeakerIds = emptySet()
        allowedRtcUsers = emptySet()
        terminalReason = terminal
        message = reasonText
        output += GroupCloudEffect.Stop(flush)
    }

    private fun buildSnapshot(): GroupSessionSnapshot {
        val payload = currentPayload
        val view = payload?.view
        val owner = view != null && view.ownerId == endpoint.deviceId
        return GroupSessionSnapshot(
            phase = phase,
            operation = operation,
            networkAttempt = null,
            view = view,
            local = local,
            participation = participation,
            code = null,
            host = owner,
            message = message,
            matches = emptyList(),
            names = payload?.names ?: mapOf(endpoint.deviceId to nickname),
            removed = payload?.removedIds ?: emptySet(),
            channelSwitching = false,
            pendingSwitchTarget = null,
            terminalReason = terminalReason,
            mode = GroupMode.NETWORK,
            inviteCode = if (owner) payload?.inviteCode else null,
            requireApproval = payload?.requireApproval ?: false,
            pendingJoinRequests = if (owner) payload?.pendingRequests ?: emptyList() else emptyList(),
            networkMediaState = networkMediaState,
            activeSpeakerIds = activeSpeakerIds
        )
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 2_000L
        private const val RTC_RETRY_INTERVAL_MS = 5_000L
    }
}
