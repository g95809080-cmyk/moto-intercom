package com.kuma.motointercom.group.hybrid

import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import com.kuma.motointercom.group.network.GroupWifiP2pPreset
import com.kuma.motointercom.group.network.supportsGroupWifiP2pPreset
import java.util.UUID

internal enum class HybridMediaPhase {
    NONE,
    LOCAL_ACTIVE,
    DRAINING_LOCAL,
    CLOUD_ACTIVE,
    DRAINING_CLOUD,
    BARRIER_FAILED
}

internal sealed interface GroupHybridInternalEvent {
    data class Start(val request: GroupCloudStartRequest) : GroupHybridInternalEvent
    data class Created(val operation: UUID, val session: CloudAdmittedSession) : GroupHybridInternalEvent
    data class JoinSubmitted(val operation: UUID, val submission: CloudJoinSubmission) : GroupHybridInternalEvent
    data class JoinCancelled(val operation: UUID) : GroupHybridInternalEvent
    data class JoinPolled(val operation: UUID, val result: CloudJoinPollResult) : GroupHybridInternalEvent
    data class BatchReceived(val operation: UUID, val batch: CloudEventBatch) : GroupHybridInternalEvent
    data class SnapshotApplied(
        val operation: UUID,
        val snapshot: GroupCloudSnapshotPayload,
        val rtc: CloudRtcToken? = null,
        val message: String? = null
    ) : GroupHybridInternalEvent
    data class TransferCompleted(val operation: UUID, val result: CloudTransferResult) : GroupHybridInternalEvent
    data class EndCompleted(val operation: UUID) : GroupHybridInternalEvent
    data class Resumed(val operation: UUID, val result: CloudResumeResult) : GroupHybridInternalEvent
    data class RtcTokenRefreshed(val operation: UUID, val rtc: CloudRtcToken) : GroupHybridInternalEvent
    data class TransportSwitched(val operation: UUID, val result: CloudTransportSwitchResult) : GroupHybridInternalEvent
    data class LocalStatusReported(val operation: UUID, val result: CloudLocalStatusResult) : GroupHybridInternalEvent
    data class PollFailed(val operation: UUID, val error: GroupCloudException) : GroupHybridInternalEvent
    data class ActionFailed(val operation: UUID, val action: String, val error: GroupCloudException) : GroupHybridInternalEvent
    data class Fatal(val operation: UUID, val message: String, val reason: GroupTerminalReason = GroupTerminalReason.FAILED) : GroupHybridInternalEvent

    data class LocalTransportDisposed(val epoch: Long) : GroupHybridInternalEvent
    data class CloudMediaDisposed(val attemptId: Long) : GroupHybridInternalEvent
    data class LocalMediaDisposeFailed(val epoch: Long, val message: String) : GroupHybridInternalEvent
    data class CloudMediaDisposeFailed(val attemptId: Long, val message: String) : GroupHybridInternalEvent
    data class Rtc(val event: GroupRtcEvent, val attemptId: Long) : GroupHybridInternalEvent
    data class RouteChanged(val route: AudioRouteSelection) : GroupHybridInternalEvent
    data class ProximityUpdated(
        val epoch: Long,
        val beaconActive: Boolean,
        val reachable: Boolean,
        val rssiDbm: Int? = null
    ) : GroupHybridInternalEvent

    data class PreconnectHostReady(
        val epoch: Long,
        val attempt: UUID,
        val hostAddress: String? = null,
        val frequencyMhz: Int? = null
    ) : GroupHybridInternalEvent
    data class PreconnectNetworkReady(
        val epoch: Long,
        val attempt: UUID,
        val hostAddress: String? = null
    ) : GroupHybridInternalEvent
    data class PreconnectFailed(val epoch: Long, val attempt: UUID, val message: String) : GroupHybridInternalEvent
    data class PreconnectAuthenticated(val epoch: Long, val channel: UUID, val context: GroupAuthContext) : GroupHybridInternalEvent
    data class PreconnectControl(val epoch: Long, val channel: UUID, val message: GroupControl) : GroupHybridInternalEvent
    data class PreconnectChannelClosed(val epoch: Long, val channel: UUID) : GroupHybridInternalEvent

    data class LocalHostReady(
        val epoch: Long,
        val attempt: UUID,
        val hostAddress: String? = null,
        val frequencyMhz: Int? = null
    ) : GroupHybridInternalEvent
    data class LocalFound(val epoch: Long, val attempt: UUID, val matches: List<GroupBootstrapMatch>) : GroupHybridInternalEvent
    data class LocalSearchFailed(val epoch: Long, val attempt: UUID, val message: String) : GroupHybridInternalEvent
    data class LocalNetworkReady(
        val epoch: Long,
        val attempt: UUID,
        val hostAddress: String? = null
    ) : GroupHybridInternalEvent
    data class LocalNetworkFailed(val epoch: Long, val attempt: UUID, val message: String) : GroupHybridInternalEvent
    data class LocalNetworkLost(val epoch: Long, val attempt: UUID) : GroupHybridInternalEvent
    data class LocalAuthenticated(val epoch: Long, val channel: UUID, val context: GroupAuthContext) : GroupHybridInternalEvent
    data class LocalControl(val epoch: Long, val channel: UUID, val message: GroupControl) : GroupHybridInternalEvent
    data class LocalChannelClosed(val epoch: Long, val channel: UUID) : GroupHybridInternalEvent
    data class LocalControlFailed(val epoch: Long, val attempt: UUID) : GroupHybridInternalEvent
    data class LocalAudioAvailable(val epoch: Long, val available: Boolean) : GroupHybridInternalEvent
    data class LocalVoiceEvidence(val epoch: Long, val evidence: GroupVoiceEvidence) : GroupHybridInternalEvent
    data class LocalOutgoing(val epoch: Long, val lease: GroupMediaLease, val message: GroupMessage) : GroupHybridInternalEvent
    data class LocalMediaFailed(val epoch: Long, val lease: GroupMediaLease) : GroupHybridInternalEvent
}

internal sealed interface GroupHybridEffect {
    data class CreateHybridRoom(val operation: UUID, val requireApproval: Boolean) : GroupHybridEffect
    data class SubmitJoin(val operation: UUID, val inviteCode: GroupCloudInviteCode, val receipt: GroupJoinReceipt) : GroupHybridEffect
    data class PollJoinRequest(val operation: UUID, val requestId: String, val requestSecret: String) : GroupHybridEffect
    data class CancelJoinRequest(val operation: UUID, val retraction: GroupJoinRetraction) : GroupHybridEffect
    data class PollEvents(val operation: UUID, val token: String, val roomId: String, val afterSeq: Long) : GroupHybridEffect
    data class AckEvents(val operation: UUID, val token: String, val roomId: String, val sequence: Long) : GroupHybridEffect
    data class ResumeRoom(val operation: UUID, val token: String, val roomId: String) : GroupHybridEffect
    data class RefreshRtcToken(val operation: UUID, val token: String, val roomId: String) : GroupHybridEffect
    data class ApproveJoin(val operation: UUID, val token: String, val roomId: String, val requestId: String, val expectedRevision: Long) : GroupHybridEffect
    data class RejectJoin(val operation: UUID, val token: String, val roomId: String, val requestId: String, val expectedRevision: Long) : GroupHybridEffect
    data class RotateInvite(val operation: UUID, val token: String, val roomId: String, val expectedRevision: Long) : GroupHybridEffect
    data class SetRequireApproval(val operation: UUID, val token: String, val roomId: String, val required: Boolean, val expectedRevision: Long) : GroupHybridEffect
    data class TransferOwner(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val alsoLeave: Boolean, val expectedRevision: Long) : GroupHybridEffect
    data class RemoveMember(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val expectedRevision: Long) : GroupHybridEffect
    data class UnblockMember(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val expectedRevision: Long) : GroupHybridEffect
    data class SetRole(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val role: GroupRole, val expectedRevision: Long) : GroupHybridEffect
    data class SetRiderIncludesFormation(val operation: UUID, val token: String, val roomId: String, val included: Boolean, val expectedRevision: Long) : GroupHybridEffect
    data class SwitchChannel(val operation: UUID, val token: String, val roomId: String, val channel: GroupChannel, val expectedRevision: Long) : GroupHybridEffect
    data class RequestPrivate(val operation: UUID, val token: String, val roomId: String, val targetDeviceId: String, val expectedRevision: Long) : GroupHybridEffect
    data class AcceptPrivate(val operation: UUID, val token: String, val roomId: String, val inviteId: String, val expectedRevision: Long) : GroupHybridEffect
    data class DeclinePrivate(val operation: UUID, val token: String, val roomId: String, val inviteId: String, val expectedRevision: Long) : GroupHybridEffect
    data class CancelPrivate(val operation: UUID, val token: String, val roomId: String, val inviteId: String, val expectedRevision: Long) : GroupHybridEffect
    data class ClosePrivate(val operation: UUID, val token: String, val roomId: String, val expectedRevision: Long) : GroupHybridEffect
    data class SwitchTransport(
        val operation: UUID,
        val token: String,
        val roomId: String,
        val target: GroupTransport,
        val reason: String,
        val expectedRevision: Long?,
        val expectedTransportEpoch: Long
    ) : GroupHybridEffect
    data class ReportLocalStatus(
        val operation: UUID,
        val token: String,
        val roomId: String,
        val transportEpoch: Long,
        val localReady: Boolean,
        val localReachable: Boolean,
        val localProbeActive: Boolean,
        val localRssiDbm: Int? = null,
        val localControlReady: Boolean = false,
        val localHostAddress: String? = null,
        val localFrequencyMhz: Int? = null,
        val preconnectFailed: Boolean = false
    ) : GroupHybridEffect
    data class LeaveRoom(val token: String, val roomId: String) : GroupHybridEffect
    data class EndRoom(val operation: UUID, val token: String, val roomId: String, val expectedRevision: Long) : GroupHybridEffect
    data class ReportAudio(val operation: UUID, val token: String, val roomId: String, val available: Boolean) : GroupHybridEffect

    data class StartLocalHost(val epoch: Long, val attempt: UUID, val descriptor: GroupDescriptor, val code: GroupJoinCode) : GroupHybridEffect
    data class StartLocalHostDirect(
        val epoch: Long,
        val attempt: UUID,
        val descriptor: GroupDescriptor,
        val code: GroupJoinCode,
        val preset: GroupWifiP2pPreset
    ) : GroupHybridEffect
    data class StartLocalSearch(val epoch: Long, val attempt: UUID, val endpoint: GroupAuthEndpoint, val code: GroupJoinCode) : GroupHybridEffect
    data class JoinLocalNetwork(val epoch: Long, val attempt: UUID, val match: GroupBootstrapMatch) : GroupHybridEffect
    data class JoinLocalDirect(
        val epoch: Long,
        val attempt: UUID,
        val preset: GroupWifiP2pPreset,
        val expectedHostAddress: String?
    ) : GroupHybridEffect
    data class ConnectLocalControl(
        val epoch: Long,
        val attempt: UUID,
        val context: GroupAuthContext,
        val code: GroupJoinCode,
        val match: GroupBootstrapMatch
    ) : GroupHybridEffect
    data class ConnectLocalDirect(
        val epoch: Long,
        val attempt: UUID,
        val context: GroupAuthContext,
        val code: GroupJoinCode,
        val hostAddress: String,
        val port: Int
    ) : GroupHybridEffect
    data class SendLocal(val epoch: Long, val channel: UUID, val build: (Long) -> GroupControl, val terminal: Boolean = false) : GroupHybridEffect
    data class AdmitLocalChannel(val epoch: Long, val channel: UUID) : GroupHybridEffect
    data class CloseLocalChannel(val epoch: Long, val channel: UUID) : GroupHybridEffect
    data class OpenLocalMedia(val epoch: Long, val lease: GroupMediaLease, val offerer: Boolean) : GroupHybridEffect
    data class CloseLocalMedia(val epoch: Long, val lease: GroupMediaLease) : GroupHybridEffect
    data class LocalMediaSignal(val epoch: Long, val lease: GroupMediaLease, val message: GroupMessage) : GroupHybridEffect
    data class SetLocalMuted(val muted: Boolean) : GroupHybridEffect
    data class SetLocalBlocked(val peerId: String, val blocked: Boolean) : GroupHybridEffect
    data class StopLocalTransportAndMedia(val epoch: Long) : GroupHybridEffect

    data class StartPreconnectHostBeacon(
        val epoch: Long,
        val attempt: UUID,
        val descriptor: GroupDescriptor,
        val preset: GroupWifiP2pPreset
    ) : GroupHybridEffect
    data class StartPreconnectControlServer(
        val epoch: Long,
        val attempt: UUID,
        val descriptor: GroupDescriptor,
        val code: GroupJoinCode,
        val preset: GroupWifiP2pPreset
    ) : GroupHybridEffect
    data class JoinPreconnectDirect(
        val epoch: Long,
        val attempt: UUID,
        val preset: GroupWifiP2pPreset,
        val expectedHostAddress: String?
    ) : GroupHybridEffect
    data class ConnectPreconnectDirect(
        val epoch: Long,
        val attempt: UUID,
        val context: GroupAuthContext,
        val code: GroupJoinCode,
        val hostAddress: String,
        val port: Int
    ) : GroupHybridEffect
    data class SendPreconnect(val epoch: Long, val channel: UUID, val build: (Long) -> GroupControl, val terminal: Boolean = false) : GroupHybridEffect
    data class AdmitPreconnectChannel(val epoch: Long, val channel: UUID) : GroupHybridEffect
    data class ClosePreconnectChannel(val epoch: Long, val channel: UUID) : GroupHybridEffect
    data class PromotePreconnectToLocal(val fromEpoch: Long, val toEpoch: Long) : GroupHybridEffect
    data object StopPreconnectNetwork : GroupHybridEffect

    data class JoinRtc(
        val token: CloudRtcToken,
        val muted: Boolean,
        val route: AudioRouteSelection,
        val attemptId: Long,
        val allowedUsers: Set<String>
    ) : GroupHybridEffect
    data class LeaveRtc(val attemptId: Long) : GroupHybridEffect
    data class ApplyRtcToken(val token: CloudRtcToken, val attemptId: Long) : GroupHybridEffect
    data class SetRtcMuted(val muted: Boolean) : GroupHybridEffect
    data class SetRtcAllowedUsers(val userIds: Set<String>) : GroupHybridEffect
    data class SetRtcRoute(val route: AudioRouteSelection) : GroupHybridEffect

    data class StartProximityBeacon(val epoch: Long, val roomInstanceId: String) : GroupHybridEffect
    data class StartProximityScan(
        val epoch: Long,
        val roomInstanceId: String,
        val targetWifiSsid: String? = null
    ) : GroupHybridEffect
    data object StopProximityProbe : GroupHybridEffect

    data class Publish(val snapshot: GroupSessionSnapshot) : GroupHybridEffect
    data class Stop(val flushTerminal: Boolean) : GroupHybridEffect
}

/**
 * Single-writer R5 HYBRID orchestrator.
 * Cloud is the sole authority for membership, roles, channels, private calls, and active transport epoch.
 * Local media (GroupAudio) and Cloud media (VolcGroupRtcMedia) are strictly separated by a quiescence barrier.
 */
internal class GroupHybridSessionOrchestrator(
    private val endpoint: GroupAuthEndpoint,
    private val nickname: String,
    private val nowMs: () -> Long,
    private val assertWriter: () -> Unit,
    private val effects: (GroupHybridEffect) -> Unit,
    private val diagnostics: GroupDiagnostics = GroupDiagnostics(),
    private val wallNowMs: () -> Long = nowMs,
    private val supportsFastP2p: () -> Boolean = ::supportsGroupWifiP2pPreset
) {
    private sealed interface QueueItem {
        data class User(val event: GroupSessionEvent) : QueueItem
        data class Internal(val event: GroupHybridInternalEvent) : QueueItem
    }

    private val queue = ArrayDeque<QueueItem>()
    private var dispatching = false
    private var output = mutableListOf<GroupHybridEffect>()

    private var phase = GroupPhase.IDLE
    private var operation: UUID? = null
    private var roomId: String? = null
    private var roomKey: GroupRoomKey? = null
    private var sessionToken: String? = null
    private var tokenExpiresAtMs = 0L
    private var latestSeq = 0L
    private var local: GroupMemberLease? = null
    private var currentPayload: GroupCloudSnapshotPayload? = null
    private var participation = GroupParticipation()
    private var message = ""
    private var terminalReason: GroupTerminalReason? = null
    private var joinCancellation: GroupJoinCancellation? = null
    private var pendingRequestId: String? = null
    private var pendingRequestSecret: String? = null
    private var pendingRequestExpiresAtMs = 0L
    private var pollInFlight = false
    private var resumeInFlight = false
    private var recoveryDeadline: Long? = null
    private var nextRetryAtMs = 0L
    private var cloudOffline = false

    private var mediaPhase = HybridMediaPhase.NONE
    private var activeLocalEpoch: Long? = null
    private var drainingLocalEpoch: Long? = null
    private var localAttempt: UUID? = null
    private var localHostReady = false
    private var localHostAddress: String? = null
    private var localHostFrequencyMhz: Int? = null
    private var localClientChannel: UUID? = null
    private var localClientConnected = false
    private var localClientNetworkReady = false
    private var localClientDirectHostAddress: String? = null
    private var localClientMatch: GroupBootstrapMatch? = null
    private var localConnectDeadlineMs = 0L
    private var localRescueStartedAtMs = 0L
    private var localRescueAttempts = 0
    private var localRescueDirectCount = 0
    private var retryingCachedNetwork = false
    private var localRescueNextRetryAtMs = 0L
    private var localRescueFallbackReason = "LOCAL_LINK_LOST"
    private var localRescueFallbackMessage = "本地直连已断开，正在整组切至云端兜底"
    private val localMediaRestartTimestamps = mutableMapOf<GroupPair, ArrayDeque<Long>>()
    private var localControlSequence = 0L
    private var localPublication = 0L
    private var nextLinkGeneration = 1L
    private val hostPeerChannels = linkedMapOf<String, UUID>()
    private val hostChannelToPeer = mutableMapOf<UUID, String>()
    private val hostUnadmittedChannels = mutableMapOf<UUID, GroupAuthContext>()
    private val hostLocalLinks = linkedMapOf<GroupPair, GroupLink>()
    private val hostLocalAudio = mutableMapOf<String, Boolean>()
    private val hostPeerDropDeadlines = mutableMapOf<String, Long>()
    private var clientLocalLinks = emptyList<GroupLink>()
    private var clientLocalAudio = emptyMap<String, Boolean>()
    private val openedLocalLeases = linkedMapOf<String, GroupMediaLease>()
    private val openedLocalChannelRevs = mutableMapOf<String, Pair<Long, Long>>()
    private var localSelfAudioAvailable = false
    private var lastReportedLocalReady = false

    private var rtcToken: CloudRtcToken? = null
    private var allowedRtcUsers = emptySet<String>()
    private var rtcRefreshInFlight = false
    private var rtcRefreshRetryAtMs = 0L
    private var nextRtcAttemptId = 1L
    private var activeRtcAttemptId: Long? = null
    private var drainingRtcAttemptId: Long? = null
    private var activeRtcRoomId: String? = null
    private var rtcJoined = false
    private var rtcRetryAtMs = 0L
    private var networkMediaState = GroupNetworkMediaState.IDLE
    private var activeSpeakerIds: Set<String> = emptySet()
    private var selectedRoute = AudioRouteSelection.EARPIECE

    private var proximityEpoch: Long? = null
    private var proximityIsHost = false
    private var proximityTargetWifiSsid: String? = null
    private var proximityBeaconActive = false
    private var proximityReachable = false
    private var proximityRssiDbm: Int? = null
    private var lastLocalStatusReportAtMs = 0L

    private var preconnectEpoch: Long? = null
    private var preconnectAttempt: UUID? = null
    private var preconnectIsHost = false
    private var preconnectPresetSsid: String? = null
    private var preconnectHostBeaconReady = false
    private var preconnectHostControlServerStarted = false
    private var preconnectHostControlServerReady = false
    private var preconnectHostAddress: String? = null
    private var preconnectHostFrequencyMhz: Int? = null
    private var preconnectClientStarted = false
    private var preconnectClientChannel: UUID? = null
    private var preconnectClientConnected = false
    private var preconnectControlReady = false
    private var preconnectLatestRoster: GroupRoster? = null
    private val preconnectHostPeerChannels = linkedMapOf<String, UUID>()
    private val preconnectHostChannelToPeer = mutableMapOf<UUID, String>()
    private val preconnectHostUnadmittedChannels = mutableMapOf<UUID, GroupAuthContext>()
    private data class BufferedPreconnectSignal(
        val fromEpoch: Long,
        val channel: UUID,
        val control: GroupControl.Signal
    )
    private val bufferedPreconnectSignals = ArrayDeque<BufferedPreconnectSignal>()
    private var lastReportedControlReady = false

    private var fallbackRequestedEpoch: Long? = null
    private var manualSwitchInFlight = false
    private var lastTransport: GroupTransport? = null
    private var lastTransportEpoch = 0L
    private var roomStartedAtMs = 0L
    private var voiceReadyReported = false

    var snapshot: GroupSessionSnapshot = buildSnapshot()
        private set

    internal val currentMediaPhase: HybridMediaPhase get() = mediaPhase

    fun dispatch(event: GroupSessionEvent) = enqueue(QueueItem.User(event))

    fun dispatchInternal(event: GroupHybridInternalEvent) = enqueue(QueueItem.Internal(event))

    private fun enqueue(item: QueueItem) {
        assertWriter()
        require(queue.size < MAX_QUEUE_SIZE) { "Hybrid group orchestrator queue overflow" }
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
                if (op != null) joinCancellation?.next(nowMs())?.let { output += GroupHybridEffect.CancelJoinRequest(op, it) }
                syncTransportAndMedia()
                syncRtcAllowedUsers()
                syncProximityProbe()
                syncCloudPreconnect()
                snapshot = buildSnapshot()
                observeVoiceReady(snapshot)
                output.add(0, GroupHybridEffect.Publish(snapshot))
                for (effect in output.toList()) {
                    try {
                        effects(effect)
                    } catch (_: Exception) {
                        if (effect is GroupHybridEffect.JoinRtc) {
                            rtcJoined = false
                            activeRtcAttemptId = null
                            activeRtcRoomId = null
                            mediaPhase = HybridMediaPhase.NONE
                            rtcRetryAtMs = nowMs() + RTC_RETRY_INTERVAL_MS
                            networkMediaState = GroupNetworkMediaState.FAILED
                            message = "启动云端兜底语音失败，正在重试"
                        }
                    }
                }
            }
        } finally {
            dispatching = false
        }
    }

    private fun handleInternal(event: GroupHybridInternalEvent) {
        when (event) {
            is GroupHybridInternalEvent.Start -> if (phase == GroupPhase.IDLE) {
                terminalReason = null
                operation = UUID.randomUUID()
                roomStartedAtMs = nowMs()
                when (val request = event.request) {
                    is GroupCloudStartRequest.Create -> {
                        phase = GroupPhase.CREATING
                        message = "正在创建双模 4 人房间（本地优先）"
                        output += GroupHybridEffect.CreateHybridRoom(operation!!, request.requireApproval)
                    }
                    is GroupCloudStartRequest.Join -> {
                        phase = GroupPhase.JOINING
                        message = "正在通过邀请码加入双模房间"
                        val cancellation = GroupJoinCancellation()
                        joinCancellation = cancellation
                        output += GroupHybridEffect.SubmitJoin(operation!!, request.inviteCode, cancellation.initial)
                    }
                }
            }

            is GroupHybridInternalEvent.Created -> {
                if (event.operation != operation || phase != GroupPhase.CREATING) return
                enterAdmittedSession(event.session, created = true)
            }

            is GroupHybridInternalEvent.JoinCancelled -> {
                if (event.operation != operation || joinCancellation?.requested != true) return
                finish("已取消加入申请", flush = false, terminal = GroupTerminalReason.USER_LEFT)
            }
            is GroupHybridInternalEvent.JoinSubmitted -> {
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

            is GroupHybridInternalEvent.JoinPolled -> {
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

            is GroupHybridInternalEvent.BatchReceived -> {
                if (event.operation != operation || phase != GroupPhase.IN_ROOM) return
                pollInFlight = false
                cloudOffline = false
                recoveryDeadline = null
                var ackSeq = latestSeq
                if (event.batch.resync && event.batch.snapshot != null) {
                    applyAuthoritativeSnapshot(event.batch.snapshot)
                    ackSeq = maxOf(ackSeq, event.batch.latestSeq)
                }
                for (item in event.batch.events) {
                    if (item.seq <= latestSeq) continue
                    ackSeq = maxOf(ackSeq, item.seq)
                    when (item) {
                        is GroupCloudEvent.Snapshot -> applyAuthoritativeSnapshot(item.payload)
                        is GroupCloudEvent.Terminal -> {
                            finish(item.message, flush = false, terminal = mapTerminalReason(item.reason))
                            return
                        }
                    }
                }
                if (ackSeq > latestSeq) {
                    latestSeq = ackSeq
                    val token = sessionToken
                    val room = roomId
                    if (token != null && room != null) {
                        output += GroupHybridEffect.AckEvents(event.operation, token, room, latestSeq)
                    }
                }
                if (phase == GroupPhase.IN_ROOM) scheduleEventPoll()
            }

            is GroupHybridInternalEvent.SnapshotApplied -> {
                if (event.operation != operation || phase != GroupPhase.IN_ROOM) return
                applyAuthoritativeSnapshot(event.snapshot)
                acceptRtcTokenIfMatching(event.rtc)
                event.message?.let { message = it }
            }

            is GroupHybridInternalEvent.TransferCompleted -> {
                if (event.operation != operation || phase != GroupPhase.IN_ROOM) return
                if (event.result.left) {
                    finish("已转让群主并离开双模房间", flush = false, terminal = GroupTerminalReason.USER_LEFT)
                } else {
                    event.result.snapshot?.let { applyAuthoritativeSnapshot(it) }
                    message = "群主权限已转让"
                }
            }

            is GroupHybridInternalEvent.EndCompleted -> {
                if (event.operation != operation) return
                finish("已解散双模房间", flush = false, terminal = GroupTerminalReason.HOST_ENDED)
            }

            is GroupHybridInternalEvent.Resumed -> {
                if (event.operation != operation) return
                if (phase != GroupPhase.RECONNECTING && !cloudOffline) return
                resumeInFlight = false
                cloudOffline = false
                recoveryDeadline = null
                latestSeq = maxOf(latestSeq, event.result.latestSeq)
                phase = GroupPhase.IN_ROOM
                applyAuthoritativeSnapshot(event.result.snapshot)
                acceptRtcTokenIfMatching(event.result.rtc)
                if (phase == GroupPhase.IN_ROOM) {
                    diagnostics.recovery(roomKey, "hybrid_member", "resumed")
                    message = "云端控制面已恢复同步"
                    scheduleEventPoll()
                }
            }

            is GroupHybridInternalEvent.RtcTokenRefreshed -> {
                if (event.operation != operation || phase != GroupPhase.IN_ROOM) return
                rtcRefreshInFlight = false
                acceptRtcTokenIfMatching(event.rtc)
            }

            is GroupHybridInternalEvent.TransportSwitched -> {
                if (event.operation != operation || phase != GroupPhase.IN_ROOM) return
                manualSwitchInFlight = false
                fallbackRequestedEpoch = null
                applyAuthoritativeSnapshot(event.result.snapshot)
                acceptRtcTokenIfMatching(event.result.rtc)
            }

            is GroupHybridInternalEvent.LocalStatusReported -> {
                if (event.operation != operation || phase != GroupPhase.IN_ROOM) return
                applyAuthoritativeSnapshot(event.result.snapshot)
            }

            is GroupHybridInternalEvent.LocalTransportDisposed -> {
                if (mediaPhase == HybridMediaPhase.DRAINING_LOCAL && event.epoch == drainingLocalEpoch) {
                    diagnostics.hybridMediaBarrier(roomId.orEmpty(), "local_disposed", event.epoch)
                    drainingLocalEpoch = null
                    mediaPhase = HybridMediaPhase.NONE
                }
            }

            is GroupHybridInternalEvent.CloudMediaDisposed -> {
                if (mediaPhase == HybridMediaPhase.DRAINING_CLOUD && event.attemptId == drainingRtcAttemptId) {
                    diagnostics.hybridMediaBarrier(roomId.orEmpty(), "cloud_disposed", lastTransportEpoch)
                    drainingRtcAttemptId = null
                    mediaPhase = HybridMediaPhase.NONE
                }
            }

            is GroupHybridInternalEvent.LocalMediaDisposeFailed -> {
                diagnostics.hybridMediaBarrier(roomId.orEmpty(), "local_dispose_failed", event.epoch)
                drainingLocalEpoch = null
                activeLocalEpoch = null
                mediaPhase = HybridMediaPhase.BARRIER_FAILED
                finish(event.message, flush = false, terminal = GroupTerminalReason.FAILED)
            }

            is GroupHybridInternalEvent.CloudMediaDisposeFailed -> {
                diagnostics.hybridMediaBarrier(roomId.orEmpty(), "cloud_dispose_failed", lastTransportEpoch)
                drainingRtcAttemptId = null
                activeRtcAttemptId = null
                activeRtcRoomId = null
                rtcJoined = false
                mediaPhase = HybridMediaPhase.BARRIER_FAILED
                finish(event.message, flush = false, terminal = GroupTerminalReason.FAILED)
            }

            is GroupHybridInternalEvent.Rtc -> handleRtcEvent(event.event, event.attemptId)

            is GroupHybridInternalEvent.RouteChanged -> {
                selectedRoute = event.route
                if (mediaPhase == HybridMediaPhase.CLOUD_ACTIVE) {
                    output += GroupHybridEffect.SetRtcRoute(event.route)
                }
            }

            is GroupHybridInternalEvent.ProximityUpdated -> {
                if (phase != GroupPhase.IN_ROOM || event.epoch != proximityEpoch) return
                val changed = proximityBeaconActive != event.beaconActive ||
                    proximityReachable != event.reachable ||
                    proximityRssiDbm != event.rssiDbm
                proximityBeaconActive = event.beaconActive
                proximityReachable = event.reachable
                proximityRssiDbm = event.rssiDbm
                if (changed) {
                    diagnostics.hybridProximity(
                        roomId.orEmpty(),
                        if (proximityIsHost) "beacon_${event.beaconActive}" else "reachable_${event.reachable}",
                        event.epoch
                    )
                    reportLocalStatusNow(force = true)
                }
            }

            is GroupHybridInternalEvent.PreconnectHostReady -> handlePreconnectHostReady(event)
            is GroupHybridInternalEvent.PreconnectNetworkReady -> handlePreconnectNetworkReady(event)
            is GroupHybridInternalEvent.PreconnectFailed -> handlePreconnectFailure(event.epoch, event.message)
            is GroupHybridInternalEvent.PreconnectAuthenticated -> handlePreconnectAuthenticated(event)
            is GroupHybridInternalEvent.PreconnectControl -> handlePreconnectControl(event)
            is GroupHybridInternalEvent.PreconnectChannelClosed -> handlePreconnectChannelClosed(event)

            is GroupHybridInternalEvent.LocalHostReady -> handleLocalHostReady(event)
            is GroupHybridInternalEvent.LocalFound -> handleLocalFound(event)
            is GroupHybridInternalEvent.LocalSearchFailed -> if (event.attempt == localAttempt) {
                handleLocalDisruption(event.epoch, networkStillUp = false, reason = "LOCAL_UNAVAILABLE", errorMessage = event.message)
            }
            is GroupHybridInternalEvent.LocalNetworkReady -> handleLocalNetworkReady(event)
            is GroupHybridInternalEvent.LocalNetworkFailed -> if (event.attempt == localAttempt) handleLocalDisruption(
                event.epoch,
                networkStillUp = false,
                reason = if (isCurrentLocalHost()) "LOCAL_HOST_LOST" else "LOCAL_UNAVAILABLE",
                errorMessage = event.message,
                refreshMatch = !localClientNetworkReady
            )
            is GroupHybridInternalEvent.LocalNetworkLost -> if (event.attempt == localAttempt) handleLocalDisruption(
                event.epoch,
                networkStillUp = false,
                reason = if (isCurrentLocalHost()) "LOCAL_HOST_LOST" else "LOCAL_LINK_LOST",
                errorMessage = "本地直连网络中断，正在整组切至云端兜底"
            )
            is GroupHybridInternalEvent.LocalAuthenticated -> handleLocalAuthenticated(event)
            is GroupHybridInternalEvent.LocalControl -> handleLocalControl(event)
            is GroupHybridInternalEvent.LocalChannelClosed -> handleLocalChannelClosed(event)
            is GroupHybridInternalEvent.LocalControlFailed -> if (event.attempt == localAttempt) handleLocalDisruption(
                event.epoch,
                networkStillUp = localClientNetworkReady,
                reason = "LOCAL_LINK_LOST",
                errorMessage = "本地控制通道建立失败，正在整组切至云端兜底"
            )
            is GroupHybridInternalEvent.LocalAudioAvailable -> handleLocalAudioAvailable(event)
            is GroupHybridInternalEvent.LocalVoiceEvidence -> handleLocalVoiceEvidence(event)
            is GroupHybridInternalEvent.LocalOutgoing -> handleLocalOutgoing(event)
            is GroupHybridInternalEvent.LocalMediaFailed -> handleLocalMediaFailed(event)

            is GroupHybridInternalEvent.PollFailed -> handlePollFailed(event)
            is GroupHybridInternalEvent.ActionFailed -> handleActionFailed(event)
            is GroupHybridInternalEvent.Fatal -> {
                if (event.operation != operation) return
                finish(event.message, flush = false, terminal = event.reason)
            }
        }
    }

    private fun handleUser(event: GroupSessionEvent) {
        when (event) {
            is GroupSessionEvent.Mute -> {
                val intent = participation.token ?: return
                participation = participation.muteSelf(intent, event.value)
                if (mediaPhase == HybridMediaPhase.CLOUD_ACTIVE) {
                    output += GroupHybridEffect.SetRtcMuted(event.value)
                } else if (mediaPhase == HybridMediaPhase.LOCAL_ACTIVE) {
                    output += GroupHybridEffect.SetLocalMuted(event.value)
                }
            }
            is GroupSessionEvent.Block -> {
                val intent = participation.token ?: return
                participation = participation.block(intent, event.peerId, event.value)
                if (mediaPhase == HybridMediaPhase.LOCAL_ACTIVE) {
                    output += GroupHybridEffect.SetLocalBlocked(event.peerId, event.value)
                }
            }
            is GroupSessionEvent.ApproveJoin -> {
                val context = ownerContext() ?: return
                diagnostics.cloudApproval("approve")
                output += GroupHybridEffect.ApproveJoin(context.op, context.token, context.roomId, event.requestId, context.revision)
            }
            is GroupSessionEvent.RejectJoin -> {
                val context = ownerContext() ?: return
                diagnostics.cloudApproval("reject")
                output += GroupHybridEffect.RejectJoin(context.op, context.token, context.roomId, event.requestId, context.revision)
            }
            GroupSessionEvent.RotateInvite -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.RotateInvite(context.op, context.token, context.roomId, context.revision)
            }
            is GroupSessionEvent.SetRequireApproval -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.SetRequireApproval(context.op, context.token, context.roomId, event.required, context.revision)
            }
            is GroupSessionEvent.TransferOwner -> {
                val context = ownerContext() ?: return
                diagnostics.cloudOwnerTransfer(context.roomId, event.alsoLeave)
                output += GroupHybridEffect.TransferOwner(
                    context.op, context.token, context.roomId, event.targetDeviceId, event.alsoLeave, context.revision
                )
            }
            is GroupSessionEvent.Remove -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.RemoveMember(context.op, context.token, context.roomId, event.peerId, context.revision)
            }
            is GroupSessionEvent.Unblock -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.UnblockMember(context.op, context.token, context.roomId, event.peerId, context.revision)
            }
            is GroupSessionEvent.SetRole -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.SetRole(context.op, context.token, context.roomId, event.peerId, event.role, context.revision)
            }
            is GroupSessionEvent.SetRiderIncludesFormation -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.SetRiderIncludesFormation(context.op, context.token, context.roomId, event.included, context.revision)
            }
            is GroupSessionEvent.SwitchChannel -> {
                val context = memberContext() ?: return
                if (isTransportSwitching()) {
                    message = "正在切换传输模式，请稍后再切频道"
                    return
                }
                if (event.channel is GroupChannel.Private) return
                if (event.channel == GroupChannel.Rider && currentPayload?.view?.canEnterRider(localRole()) != true) {
                    message = "当前角色无权进入领队频道"
                    return
                }
                output += GroupHybridEffect.SwitchChannel(context.op, context.token, context.roomId, event.channel, context.revision)
            }
            is GroupSessionEvent.RequestPrivate -> {
                val context = memberContext() ?: return
                output += GroupHybridEffect.RequestPrivate(context.op, context.token, context.roomId, event.peerId, context.revision)
            }
            is GroupSessionEvent.AcceptPrivate -> {
                val context = memberContext() ?: return
                output += GroupHybridEffect.AcceptPrivate(context.op, context.token, context.roomId, event.inviteId, context.revision)
            }
            is GroupSessionEvent.DeclinePrivate -> {
                val context = memberContext() ?: return
                output += GroupHybridEffect.DeclinePrivate(context.op, context.token, context.roomId, event.inviteId, context.revision)
            }
            is GroupSessionEvent.CancelPrivate -> {
                val context = memberContext() ?: return
                output += GroupHybridEffect.CancelPrivate(context.op, context.token, context.roomId, event.inviteId, context.revision)
            }
            GroupSessionEvent.EndPrivate -> {
                val context = memberContext() ?: return
                output += GroupHybridEffect.ClosePrivate(context.op, context.token, context.roomId, context.revision)
            }
            is GroupSessionEvent.SwitchTransport -> {
                val context = ownerContext() ?: return
                val transport = currentPayload?.hybridTransport ?: return
                if (isTransportSwitching() || manualSwitchInFlight || transport.active == event.target) return
                manualSwitchInFlight = true
                val reason = if (event.target == GroupTransport.CLOUD) "MANUAL_CLOUD" else "MANUAL_LOCAL"
                message = if (event.target == GroupTransport.CLOUD) "正在切换全组至云端兜底…" else "正在切换全组回本地直连…"
                output += GroupHybridEffect.SwitchTransport(
                    operation = context.op,
                    token = context.token,
                    roomId = context.roomId,
                    target = event.target,
                    reason = reason,
                    expectedRevision = context.revision,
                    expectedTransportEpoch = transport.epoch
                )
            }
            GroupSessionEvent.EndRoom -> {
                val context = ownerContext() ?: return
                output += GroupHybridEffect.EndRoom(context.op, context.token, context.roomId, context.revision)
            }
            GroupSessionEvent.Leave -> handleLeave()
            GroupSessionEvent.Tick -> handleTick()
            else -> Unit
        }
    }

    private data class ActionContext(val op: UUID, val token: String, val roomId: String, val revision: Long)

    private fun memberContext(): ActionContext? {
        if (phase != GroupPhase.IN_ROOM || cloudOffline) return null
        val payload = currentPayload ?: return null
        return ActionContext(
            operation ?: return null,
            sessionToken ?: return null,
            roomId ?: return null,
            payload.view.revision
        )
    }

    private fun ownerContext(): ActionContext? {
        val context = memberContext() ?: return null
        if (currentPayload?.view?.ownerId != endpoint.deviceId) return null
        return context
    }

    private fun localRole(): GroupRole =
        currentPayload?.view?.members?.singleOrNull { it.lease.deviceId == endpoint.deviceId }?.role ?: GroupRole.MEMBER

    private fun isCurrentLocalHost(): Boolean =
        currentPayload?.hybridTransport?.localHostDeviceId == endpoint.deviceId

    private fun isTransportSwitching(): Boolean {
        if (mediaPhase == HybridMediaPhase.DRAINING_LOCAL || mediaPhase == HybridMediaPhase.DRAINING_CLOUD) return true
        val desired = currentPayload?.hybridTransport?.active ?: return false
        return when (desired) {
            GroupTransport.LOCAL -> mediaPhase == HybridMediaPhase.CLOUD_ACTIVE
            GroupTransport.CLOUD -> mediaPhase == HybridMediaPhase.LOCAL_ACTIVE
        }
    }

    private fun enterAdmittedSession(session: CloudAdmittedSession, created: Boolean) {
        joinCancellation = null
        if (session.snapshot.view.mode != GroupMode.HYBRID || session.snapshot.hybridTransport == null) {
            output += GroupHybridEffect.LeaveRoom(session.sessionToken, session.roomId)
            finish("该邀请码属于网络 50 人大群，请切换到「网络 50 人」加入", flush = true, terminal = GroupTerminalReason.REFUSED)
            return
        }
        val transport = session.snapshot.hybridTransport
        val expectedRtcRoom = session.snapshot.rtcRoomId?.takeIf { it.isNotBlank() }
        val initialRtc = session.rtc
        val wallNow = wallNowMs()
        if (transport.active == GroupTransport.CLOUD && initialRtc != null && !initialRtc.isUsable(wallNow)) {
            output += GroupHybridEffect.LeaveRoom(session.sessionToken, session.roomId)
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
        rtcToken = if (transport.active == GroupTransport.CLOUD && expectedRtcRoom != null) {
            initialRtc?.takeIf { it.isUsable(wallNow) && it.rtcRoomId == expectedRtcRoom }
        } else {
            null
        }
        local = selfMember.lease
        currentPayload = session.snapshot
        participation = participation.begin(session.snapshot.view.key)
        phase = GroupPhase.IN_ROOM
        cloudOffline = false
        voiceReadyReported = false
        lastTransport = transport.active
        lastTransportEpoch = transport.epoch
        if (created) {
            diagnostics.cloudCreate(session.roomId, session.snapshot.requireApproval)
            message = "双模房间已创建（本地优先，最多 4 人）"
        } else {
            diagnostics.join(session.snapshot.view.key, selfMember.role.name, session.snapshot.view.members.size)
            message = if (transport.active == GroupTransport.LOCAL) {
                "已加入双模房间，正在建立本地直连"
            } else {
                "已加入双模房间（当前云端兜底模式）"
            }
        }
        scheduleEventPoll()
    }

    private fun applyAuthoritativeSnapshot(payload: GroupCloudSnapshotPayload) {
        if (payload.view.mode != GroupMode.HYBRID || payload.hybridTransport == null) {
            finish("房间模式不匹配", flush = false, terminal = GroupTerminalReason.FAILED)
            return
        }
        val previous = currentPayload
        if (previous != null && payload.view.revision < previous.view.revision) return
        currentPayload = payload
        roomKey = payload.view.key
        if (payload.view.ended) {
            finish(
                "双模房间已结束",
                flush = false,
                terminal = mapTerminalReason(payload.endedReason ?: "ROOM_ENDED")
            )
            return
        }
        val selfMember = payload.view.members.singleOrNull { it.lease.deviceId == endpoint.deviceId }
        if (selfMember == null) {
            val removed = endpoint.deviceId in payload.removedIds
            finish(
                if (removed) "你已被群主移出该双模房间" else "已离开双模房间",
                flush = false,
                terminal = if (removed) GroupTerminalReason.REMOVED else GroupTerminalReason.HOST_ENDED
            )
            return
        }
        local = selfMember.lease
        val transport = payload.hybridTransport
        if (lastTransport != null && (lastTransport != transport.active || lastTransportEpoch != transport.epoch)) {
            diagnostics.hybridTransportSwitch(
                roomId.orEmpty(),
                lastTransport!!,
                transport.active,
                transport.epoch,
                transport.reason
            )
            fallbackRequestedEpoch = null
            manualSwitchInFlight = false
            voiceReadyReported = false
            message = when (transport.active) {
                GroupTransport.CLOUD -> when (transport.reason) {
                    "MANUAL_CLOUD" -> "群主已切换全组至云端兜底语音"
                    "LOCAL_HOST_LOST" -> "本地房主离线，已整组切换至云端兜底"
                    "LOCAL_UNAVAILABLE" -> "本地直连暂不可用，已整组切换至云端兜底"
                    else -> "本地链路中断，已整组切换至云端兜底"
                }
                GroupTransport.LOCAL -> when (transport.reason) {
                    "LOCAL_RECOVERED" -> "检测到全员本地信号恢复，正在整组切回本地直连"
                    "MANUAL_LOCAL" -> "群主已切换全组回本地直连"
                    else -> "正在建立本地直连"
                }
            }
        }
        lastTransport = transport.active
        lastTransportEpoch = transport.epoch
        val expectedRtcRoom = payload.rtcRoomId?.takeIf { it.isNotBlank() }
        if (transport.active != GroupTransport.CLOUD || expectedRtcRoom == null) {
            rtcToken = null
            rtcRefreshInFlight = false
            rtcRefreshRetryAtMs = 0L
        } else {
            if (previous?.rtcRoomId != expectedRtcRoom) {
                rtcRefreshInFlight = false
                rtcRefreshRetryAtMs = 0L
            }
            val cached = rtcToken
            if (cached != null && cached.rtcRoomId != expectedRtcRoom) {
                rtcToken = null
                rtcRefreshInFlight = false
            }
        }

        if (mediaPhase == HybridMediaPhase.LOCAL_ACTIVE &&
            transport.active == GroupTransport.LOCAL &&
            transport.epoch == activeLocalEpoch
        ) {
            if (isCurrentLocalHost()) {
                reconcileHostLocalAuthority()
            }
            reconcileLocalMedia()
        } else if (
            transport.active == GroupTransport.CLOUD &&
            preconnectEpoch == transport.epoch &&
            preconnectIsHost
        ) {
            val admittedIds = payload.view.members
                .filter { it.status == GroupMemberStatus.ADMITTED }
                .map { it.lease.deviceId }
                .toSet()
            for ((peerId, channelId) in preconnectHostPeerChannels.toMap()) {
                if (peerId !in admittedIds) {
                    preconnectHostPeerChannels.remove(peerId)
                    preconnectHostChannelToPeer.remove(channelId)
                    output += GroupHybridEffect.ClosePreconnectChannel(transport.epoch, channelId)
                }
            }
            recomputePreconnectHostControlReady()
        }
    }

    private fun syncTransportAndMedia() {
        if (phase != GroupPhase.IN_ROOM) {
            drainAnyActiveMedia()
            return
        }
        val payload = currentPayload ?: return
        val transport = payload.hybridTransport ?: return
        val selfMember = payload.view.members.singleOrNull { it.lease.deviceId == endpoint.deviceId }
        if (selfMember?.status != GroupMemberStatus.ADMITTED) {
            drainAnyActiveMedia()
            return
        }

        when (mediaPhase) {
            HybridMediaPhase.DRAINING_LOCAL,
            HybridMediaPhase.DRAINING_CLOUD,
            HybridMediaPhase.BARRIER_FAILED -> return

            HybridMediaPhase.LOCAL_ACTIVE -> {
                if (transport.active != GroupTransport.LOCAL || transport.epoch != activeLocalEpoch) {
                    beginDrainLocal()
                }
            }

            HybridMediaPhase.CLOUD_ACTIVE -> {
                val admittedInChannel = admittedMembersInCurrentChannel(payload)
                val desiredRtcRoom = payload.rtcRoomId?.takeIf { it.isNotBlank() }
                val channelMismatch = desiredRtcRoom == null || activeRtcRoomId != desiredRtcRoom
                if (transport.active != GroupTransport.CLOUD || admittedInChannel.size < 2 || channelMismatch) {
                    beginDrainCloud()
                }
            }

            HybridMediaPhase.NONE -> {
                if (transport.active == GroupTransport.LOCAL) {
                    startLocalTransportEpoch(payload, transport)
                } else {
                    maybeStartCloudRtc(payload, transport)
                }
            }
        }
    }

    private fun drainAnyActiveMedia() {
        when (mediaPhase) {
            HybridMediaPhase.LOCAL_ACTIVE -> beginDrainLocal()
            HybridMediaPhase.CLOUD_ACTIVE -> beginDrainCloud()
            else -> Unit
        }
    }

    private fun beginDrainLocal() {
        val epoch = activeLocalEpoch ?: return
        diagnostics.hybridMediaBarrier(roomId.orEmpty(), "local_draining", epoch)
        mediaPhase = HybridMediaPhase.DRAINING_LOCAL
        drainingLocalEpoch = epoch
        activeLocalEpoch = null
        localAttempt = null
        localHostReady = false
        localHostAddress = null
        localHostFrequencyMhz = null
        localClientChannel = null
        localClientConnected = false
        localClientNetworkReady = false
        localClientDirectHostAddress = null
        localClientMatch = null
        localConnectDeadlineMs = 0L
        clearLocalRescueState()
        localMediaRestartTimestamps.clear()
        hostPeerChannels.clear()
        hostChannelToPeer.clear()
        hostUnadmittedChannels.clear()
        hostLocalLinks.clear()
        hostLocalAudio.clear()
        hostPeerDropDeadlines.clear()
        clientLocalLinks = emptyList()
        clientLocalAudio = emptyMap()
        openedLocalLeases.clear()
        openedLocalChannelRevs.clear()
        localSelfAudioAvailable = false
        lastReportedLocalReady = false
        output += GroupHybridEffect.StopLocalTransportAndMedia(epoch)
    }

    private fun beginDrainCloud() {
        val attempt = activeRtcAttemptId ?: return
        diagnostics.hybridMediaBarrier(roomId.orEmpty(), "cloud_draining", lastTransportEpoch)
        mediaPhase = HybridMediaPhase.DRAINING_CLOUD
        drainingRtcAttemptId = attempt
        activeRtcAttemptId = null
        activeRtcRoomId = null
        rtcJoined = false
        activeSpeakerIds = emptySet()
        networkMediaState = GroupNetworkMediaState.IDLE
        output += GroupHybridEffect.LeaveRtc(attempt)
    }

    private fun startLocalTransportEpoch(payload: GroupCloudSnapshotPayload, transport: GroupHybridTransportPayload) {
        val codeString = transport.localCode ?: return
        val joinCode = runCatching { GroupJoinCode(codeString) }.getOrNull() ?: return
        if (canPromotePreconnect(transport)) {
            promotePreconnectToLocal(payload, transport)
            return
        }
        stopPreconnectState(emitStop = true)
        val attempt = UUID.randomUUID()
        mediaPhase = HybridMediaPhase.LOCAL_ACTIVE
        activeLocalEpoch = transport.epoch
        localAttempt = attempt
        localHostReady = false
        localHostAddress = null
        localHostFrequencyMhz = null
        localClientChannel = null
        localClientConnected = false
        localClientNetworkReady = false
        localClientDirectHostAddress = null
        localClientMatch = null
        localSelfAudioAvailable = false
        lastReportedLocalReady = false
        clearLocalRescueState()
        localMediaRestartTimestamps.clear()
        hostPeerChannels.clear()
        hostChannelToPeer.clear()
        hostUnadmittedChannels.clear()
        hostLocalLinks.clear()
        hostLocalAudio.clear()
        hostPeerDropDeadlines.clear()
        clientLocalLinks = emptyList()
        clientLocalAudio = emptyMap()
        openedLocalLeases.clear()
        openedLocalChannelRevs.clear()
        networkMediaState = GroupNetworkMediaState.IDLE
        activeSpeakerIds = emptySet()

        val preset = routeAPresetFor(transport)
        if (endpoint.deviceId == transport.localHostDeviceId) {
            localConnectDeadlineMs = 0L
            val descriptor = GroupDescriptor(
                room = GroupRoomKey(transport.localRoomInstanceId, endpoint.runtimeId),
                host = endpoint,
                nickname = nickname
            )
            if (preset != null) {
                output += GroupHybridEffect.StartLocalHostDirect(transport.epoch, attempt, descriptor, joinCode, preset)
            } else {
                output += GroupHybridEffect.StartLocalHost(transport.epoch, attempt, descriptor, joinCode)
            }
        } else {
            localConnectDeadlineMs = nowMs() + LOCAL_CONNECT_TIMEOUT_MS
            if (preset != null) {
                val now = nowMs()
                localRescueStartedAtMs = now
                localRescueAttempts = 1
                localRescueDirectCount = 1
                localRescueNextRetryAtMs = now + LOCAL_SELF_RESCUE_RETRY_INTERVAL_MS
                localRescueFallbackReason = "LOCAL_UNAVAILABLE"
                localRescueFallbackMessage = "本地直连暂不可用，正在整组切至云端兜底"
                output += GroupHybridEffect.JoinLocalDirect(transport.epoch, attempt, preset, transport.localHostAddress)
            } else {
                output += GroupHybridEffect.StartLocalSearch(transport.epoch, attempt, endpoint, joinCode)
            }
        }
    }

    private fun maybeStartCloudRtc(payload: GroupCloudSnapshotPayload, transport: GroupHybridTransportPayload) {
        val admittedInChannel = admittedMembersInCurrentChannel(payload)
        if (admittedInChannel.size < 2) {
            networkMediaState = GroupNetworkMediaState.WAITING_FOR_PEER
            activeSpeakerIds = emptySet()
            return
        }
        val expectedRtcRoom = payload.rtcRoomId?.takeIf { it.isNotBlank() }
        if (expectedRtcRoom == null) {
            rtcToken = null
            rtcRefreshInFlight = false
            activeSpeakerIds = emptySet()
            networkMediaState = GroupNetworkMediaState.FAILED
            message = "云端语音房间标识缺失，等待控制面同步"
            return
        }
        val now = nowMs()
        val wallNow = wallNowMs()
        if (now < rtcRetryAtMs) return
        val token = rtcToken
        val tokenMatchesRoom = token != null && token.rtcRoomId == expectedRtcRoom
        if (token == null || !token.isUsable(wallNow) || !tokenMatchesRoom) {
            if (!tokenMatchesRoom) {
                rtcToken = null
            }
            networkMediaState = GroupNetworkMediaState.JOINING
            requestRtcTokenRefresh()
            return
        }
        val attemptId = nextRtcAttemptId++
        mediaPhase = HybridMediaPhase.CLOUD_ACTIVE
        activeRtcAttemptId = attemptId
        activeRtcRoomId = token.rtcRoomId
        rtcJoined = false
        networkMediaState = GroupNetworkMediaState.JOINING
        allowedRtcUsers = admittedInChannel.allowedRtcUsers(participation.blockedDeviceIds)
        output += GroupHybridEffect.JoinRtc(token, participation.selfMuted, selectedRoute, attemptId, allowedRtcUsers)
    }

    private fun syncRtcAllowedUsers() {
        if (mediaPhase != HybridMediaPhase.CLOUD_ACTIVE) return
        val members = currentPayload?.let(::admittedMembersInCurrentChannel).orEmpty()
        val allowed = members.allowedRtcUsers(participation.blockedDeviceIds)
        if (allowed != allowedRtcUsers) {
            allowedRtcUsers = allowed
            output += GroupHybridEffect.SetRtcAllowedUsers(allowed)
        }
    }

    private fun acceptRtcTokenIfMatching(candidate: CloudRtcToken?) {
        val token = candidate ?: return
        val payload = currentPayload ?: return
        if (phase != GroupPhase.IN_ROOM) return
        if (payload.hybridTransport?.active != GroupTransport.CLOUD) return
        val expectedRtcRoom = payload.rtcRoomId?.takeIf { it.isNotBlank() } ?: return
        if (!token.isUsable(wallNowMs()) || token.rtcRoomId != expectedRtcRoom) return
        val attemptId = activeRtcAttemptId
        if (mediaPhase == HybridMediaPhase.CLOUD_ACTIVE && activeRtcRoomId == expectedRtcRoom && attemptId != null) {
            rtcRefreshInFlight = true
            output += GroupHybridEffect.ApplyRtcToken(token, attemptId)
        } else {
            recordRtcToken(token)
        }
    }

    private fun syncProximityProbe() {
        val payload = currentPayload
        val transport = payload?.hybridTransport
        val shouldProbe = phase == GroupPhase.IN_ROOM &&
            !cloudOffline &&
            payload != null &&
            transport != null &&
            transport.active == GroupTransport.CLOUD &&
            transport.reason != "MANUAL_CLOUD" &&
            mediaPhase != HybridMediaPhase.DRAINING_LOCAL &&
            mediaPhase != HybridMediaPhase.LOCAL_ACTIVE &&
            wallNowMs() >= transport.cooldownUntilMs &&
            payload.view.members.count { it.status == GroupMemberStatus.ADMITTED } >= 2

        if (!shouldProbe) {
            if (proximityEpoch != null) {
                proximityEpoch = null
                proximityTargetWifiSsid = null
                proximityBeaconActive = false
                proximityReachable = false
                proximityRssiDbm = null
                output += GroupHybridEffect.StopProximityProbe
            }
            return
        }
        val currentTransport = transport!!
        val isHost = currentTransport.localHostDeviceId == endpoint.deviceId ||
            (currentTransport.localHostDeviceId.isBlank() && payload!!.view.ownerId == endpoint.deviceId)
        val targetSsid = currentTransport.localP2pSsid
        if (proximityEpoch == currentTransport.epoch && proximityIsHost == isHost && proximityTargetWifiSsid == targetSsid) return
        if (proximityEpoch != null) {
            output += GroupHybridEffect.StopProximityProbe
        }
        proximityEpoch = currentTransport.epoch
        proximityIsHost = isHost
        proximityTargetWifiSsid = targetSsid
        proximityBeaconActive = false
        proximityReachable = false
        proximityRssiDbm = null
        if (isHost) {
            output += GroupHybridEffect.StartProximityBeacon(currentTransport.epoch, currentTransport.localRoomInstanceId)
        } else {
            output += GroupHybridEffect.StartProximityScan(
                currentTransport.epoch,
                currentTransport.localRoomInstanceId,
                targetSsid
            )
        }
    }

    private fun syncCloudPreconnect() {
        val payload = currentPayload
        val transport = payload?.hybridTransport
        if (mediaPhase == HybridMediaPhase.BARRIER_FAILED || phase != GroupPhase.IN_ROOM || payload == null || transport == null) {
            stopPreconnectState(emitStop = true)
            return
        }
        if (transport.active == GroupTransport.LOCAL && canPromotePreconnect(transport)) {
            return
        }
        val admittedCount = payload.view.members.count { it.status == GroupMemberStatus.ADMITTED }
        val autoRecoverableCloud = !cloudOffline &&
            transport.active == GroupTransport.CLOUD &&
            transport.reason != "MANUAL_CLOUD" &&
            wallNowMs() >= transport.cooldownUntilMs &&
            admittedCount >= 2 &&
            mediaPhase != HybridMediaPhase.DRAINING_LOCAL &&
            mediaPhase != HybridMediaPhase.LOCAL_ACTIVE
        if (!autoRecoverableCloud) {
            stopPreconnectState(emitStop = true)
            return
        }
        val isHost = transport.localHostDeviceId == endpoint.deviceId ||
            (transport.localHostDeviceId.isBlank() && payload.view.ownerId == endpoint.deviceId)
        val preset = routeAPresetFor(transport)
        if (isHost) {
            if (preset == null) {
                stopPreconnectState(emitStop = true)
                return
            }
            if (preconnectEpoch != transport.epoch || !preconnectIsHost || preconnectPresetSsid != preset.networkName) {
                stopPreconnectState(emitStop = true)
                val attempt = UUID.randomUUID()
                preconnectEpoch = transport.epoch
                preconnectAttempt = attempt
                preconnectIsHost = true
                preconnectPresetSsid = preset.networkName
                val descriptor = GroupDescriptor(
                    room = GroupRoomKey(transport.localRoomInstanceId, endpoint.runtimeId),
                    host = endpoint,
                    nickname = nickname
                )
                output += GroupHybridEffect.StartPreconnectHostBeacon(transport.epoch, attempt, descriptor, preset)
            }
            if (transport.recoveryStage == GroupHybridRecoveryStage.PRECONNECTING) {
                val codeString = transport.localCode ?: return
                val joinCode = runCatching { GroupJoinCode(codeString) }.getOrNull() ?: return
                val attempt = preconnectAttempt ?: return
                if (!preconnectHostControlServerStarted && preconnectHostBeaconReady) {
                    preconnectHostControlServerStarted = true
                    val descriptor = GroupDescriptor(
                        room = GroupRoomKey(transport.localRoomInstanceId, endpoint.runtimeId),
                        host = endpoint,
                        nickname = nickname
                    )
                    output += GroupHybridEffect.StartPreconnectControlServer(transport.epoch, attempt, descriptor, joinCode, preset)
                }
            } else if (preconnectHostControlServerStarted) {
                stopPreconnectState(emitStop = true)
            }
        } else {
            if (transport.recoveryStage != GroupHybridRecoveryStage.PRECONNECTING || preset == null || transport.localCode == null) {
                stopPreconnectState(emitStop = true)
                return
            }
            if (preconnectEpoch != transport.epoch || preconnectIsHost || !preconnectClientStarted || preconnectPresetSsid != preset.networkName) {
                stopPreconnectState(emitStop = true)
                val attempt = UUID.randomUUID()
                preconnectEpoch = transport.epoch
                preconnectAttempt = attempt
                preconnectIsHost = false
                preconnectPresetSsid = preset.networkName
                preconnectClientStarted = true
                output += GroupHybridEffect.JoinPreconnectDirect(transport.epoch, attempt, preset, transport.localHostAddress)
            }
        }
    }

    private fun handleRtcEvent(event: GroupRtcEvent, attemptId: Long) {
        if (phase != GroupPhase.IN_ROOM) return
        if (mediaPhase != HybridMediaPhase.CLOUD_ACTIVE || attemptId != activeRtcAttemptId) return
        when (event) {
            GroupRtcEvent.Joined -> {
                rtcJoined = true
                rtcRetryAtMs = 0L
                networkMediaState = GroupNetworkMediaState.JOINED
                message = "双模云端兜底语音已连接"
                reportAudioAvailability(true)
            }
            is GroupRtcEvent.TokenUpdated -> {
                if (event.accepted) recordRtcToken(event.token) else retryRtcTokenRefresh()
            }
            GroupRtcEvent.TokenWillExpire -> requestRtcTokenRefresh()
            is GroupRtcEvent.ActiveSpeakers -> activeSpeakerIds = currentPayload?.view?.members.orEmpty().speakingDevices(event.userIds)
            is GroupRtcEvent.JoinFailed -> {
                if (event.code == -1000) {
                    rtcToken = null
                    requestRtcTokenRefresh()
                }
                rtcJoined = false
                activeSpeakerIds = emptySet()
                rtcRetryAtMs = nowMs() + RTC_RETRY_INTERVAL_MS
                networkMediaState = GroupNetworkMediaState.FAILED
                message = event.message
                beginDrainCloud()
                reportAudioAvailability(false)
            }
            is GroupRtcEvent.ConnectionLost -> {
                rtcJoined = false
                activeSpeakerIds = emptySet()
                rtcRetryAtMs = nowMs() + RTC_RETRY_INTERVAL_MS
                networkMediaState = GroupNetworkMediaState.FAILED
                message = event.message
                beginDrainCloud()
                reportAudioAvailability(false)
            }
            is GroupRtcEvent.Error -> message = event.message
        }
    }

    private fun handleLocalHostReady(event: GroupHybridInternalEvent.LocalHostReady) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch || event.attempt != localAttempt) return
        localHostReady = true
        if (!event.hostAddress.isNullOrBlank()) localHostAddress = event.hostAddress
        if (event.frequencyMhz != null) localHostFrequencyMhz = event.frequencyMhz
        message = "本地 Wi-Fi Direct 已就绪，等待队友直连"
        reconcileHostLocalAuthority()
        reportLocalStatusNow(force = true)
    }

    private fun handleLocalFound(event: GroupHybridInternalEvent.LocalFound) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        val attempt = localAttempt ?: return
        if (event.attempt != attempt) return
        val matched = event.matches.firstOrNull { matchesCurrentLocalHost(it.descriptor.room, it.descriptor.host) }
        if (matched == null) {
            handleLocalDisruption(event.epoch, networkStillUp = false, reason = "LOCAL_UNAVAILABLE", errorMessage = "未发现同房间本地主机，正在整组切至云端兜底")
            return
        }
        localClientMatch = matched
        message = "已发现本地主机，正在连接 Wi-Fi Direct"
        output += GroupHybridEffect.JoinLocalNetwork(event.epoch, attempt, matched)
    }

    private fun handleLocalNetworkReady(event: GroupHybridInternalEvent.LocalNetworkReady) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch || event.attempt != localAttempt) return
        val transport = currentPayload?.hybridTransport ?: return
        val codeString = transport.localCode ?: return
        val joinCode = runCatching { GroupJoinCode(codeString) }.getOrNull() ?: return
        localClientNetworkReady = true
        val match = localClientMatch
        if (match != null) {
            val context = buildDirectAuthContext(transport) ?: return
            output += GroupHybridEffect.ConnectLocalControl(event.epoch, event.attempt, context, joinCode, match)
            return
        }
        val hostAddress = event.hostAddress?.takeIf { it.isNotBlank() }
            ?: transport.localHostAddress?.takeIf { it.isNotBlank() }
            ?: DEFAULT_P2P_GO_ADDRESS
        localClientDirectHostAddress = hostAddress
        val authContext = buildDirectAuthContext(transport) ?: return
        output += GroupHybridEffect.ConnectLocalDirect(
            epoch = event.epoch,
            attempt = event.attempt,
            context = authContext,
            code = joinCode,
            hostAddress = hostAddress,
            port = transport.localControlPort
        )
    }

    private fun handleLocalAuthenticated(event: GroupHybridInternalEvent.LocalAuthenticated) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        if (isCurrentLocalHost()) {
            val peerEndpoint = event.context.client
            val authorized = currentPayload?.view?.members?.singleOrNull {
                it.lease.deviceId == peerEndpoint.deviceId &&
                    it.lease.runtimeId == peerEndpoint.runtimeId &&
                    it.status == GroupMemberStatus.ADMITTED
            }
            if (authorized == null) {
                diagnostics.hybridLocalAuthorityReject(roomId.orEmpty())
                sendLocalControl(event.epoch, event.channel, GroupControl.Refused(GroupResult.FORBIDDEN), terminal = true)
                output += GroupHybridEffect.CloseLocalChannel(event.epoch, event.channel)
                return
            }
            hostUnadmittedChannels[event.channel] = event.context
        } else {
            if (event.context.client != endpoint || !matchesCurrentLocalHost(event.context.room, event.context.host)) {
                output += GroupHybridEffect.CloseLocalChannel(event.epoch, event.channel)
                return
            }
            localClientChannel = event.channel
            sendLocalControl(event.epoch, event.channel, GroupControl.Join(nickname))
        }
    }

    private fun handleLocalControl(event: GroupHybridInternalEvent.LocalControl) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        if (isCurrentLocalHost()) {
            handleHostLocalControl(event.epoch, event.channel, event.message)
        } else {
            handleClientLocalControl(event.epoch, event.channel, event.message)
        }
    }

    private fun handleHostLocalControl(epoch: Long, channelId: UUID, control: GroupControl) {
        val existingPeerId = hostChannelToPeer[channelId]
        if (existingPeerId == null) {
            val context = hostUnadmittedChannels.remove(channelId) ?: run {
                output += GroupHybridEffect.CloseLocalChannel(epoch, channelId)
                return
            }
            if (control !is GroupControl.Join) {
                output += GroupHybridEffect.CloseLocalChannel(epoch, channelId)
                return
            }
            val peerEndpoint = context.client
            val authorized = currentPayload?.view?.members?.singleOrNull {
                it.lease.deviceId == peerEndpoint.deviceId &&
                    it.lease.runtimeId == peerEndpoint.runtimeId &&
                    it.status == GroupMemberStatus.ADMITTED
            }
            if (authorized == null) {
                diagnostics.hybridLocalAuthorityReject(roomId.orEmpty())
                sendLocalControl(epoch, channelId, GroupControl.Refused(GroupResult.FORBIDDEN), terminal = true)
                output += GroupHybridEffect.CloseLocalChannel(epoch, channelId)
                return
            }
            hostPeerChannels[peerEndpoint.deviceId]?.let { prior ->
                if (prior != channelId) {
                    hostChannelToPeer.remove(prior)
                    output += GroupHybridEffect.CloseLocalChannel(epoch, prior)
                }
            }
            hostPeerChannels[peerEndpoint.deviceId] = channelId
            hostChannelToPeer[channelId] = peerEndpoint.deviceId
            hostPeerDropDeadlines.remove(peerEndpoint.deviceId)
            output += GroupHybridEffect.AdmitLocalChannel(epoch, channelId)
            reconcileHostLocalAuthority()
            reconcileLocalMedia()
            reportLocalStatusNow(force = false)
            return
        }

        val frame = (control as? GroupControl.Signal)?.frame ?: return
        val senderMember = currentPayload?.view?.members?.singleOrNull {
            it.lease.deviceId == existingPeerId && it.status == GroupMemberStatus.ADMITTED
        } ?: run {
            output += GroupHybridEffect.CloseLocalChannel(epoch, channelId)
            return
        }
        if (frame.sender.deviceId != existingPeerId) return
        when (val body = frame.message) {
            is GroupMessage.AudioAvailable -> {
                hostLocalAudio[existingPeerId] = body.available
                if (!body.available) {
                    hostLocalLinks.entries.removeAll { it.key.first == existingPeerId || it.key.second == existingPeerId }
                }
                reconcileHostLocalAuthority()
                reconcileLocalMedia()
            }
            is GroupMessage.LinkConfirmed -> {
                val link = hostLocalLinks[body.link.pair]
                if (link != null && link.lease == body.link && existingPeerId in listOf(body.link.pair.first, body.link.pair.second)) {
                    hostLocalLinks[body.link.pair] = link.copy(confirmedBy = link.confirmedBy + existingPeerId)
                    broadcastLocalRoster(epoch)
                    reconcileLocalMedia()
                }
            }
            is GroupMessage.RestartLink -> {
                val pair = body.link.pair
                val current = hostLocalLinks[pair]
                if (current?.lease == body.link && existingPeerId in listOf(pair.first, pair.second)) {
                    if (canRestartLocalMediaPair(pair, nowMs())) {
                        hostLocalLinks.remove(pair)
                        reconcileHostLocalAuthority()
                        reconcileLocalMedia()
                    }
                }
            }
            is GroupMessage.Offer,
            is GroupMessage.Answer,
            is GroupMessage.Candidate -> {
                val linkLease = when (body) {
                    is GroupMessage.Offer -> body.link
                    is GroupMessage.Answer -> body.link
                    is GroupMessage.Candidate -> body.link
                    else -> return
                }
                val activeLink = hostLocalLinks[linkLease.pair] ?: return
                if (activeLink.lease != linkLease) return
                val otherId = when (existingPeerId) {
                    linkLease.pair.first -> linkLease.pair.second
                    linkLease.pair.second -> linkLease.pair.first
                    else -> return
                }
                if (otherId == endpoint.deviceId) {
                    val mediaLease = mediaLeaseFor(senderMember.lease, activeLink.lease) ?: return
                    output += GroupHybridEffect.LocalMediaSignal(epoch, mediaLease, body)
                } else {
                    val targetChannel = hostPeerChannels[otherId] ?: return
                    sendLocalControl(epoch, targetChannel, GroupControl.Signal(frame))
                }
            }
            else -> Unit
        }
    }

    private fun handleClientLocalControl(epoch: Long, channelId: UUID, control: GroupControl) {
        if (channelId != localClientChannel) return
        when (control) {
            is GroupControl.Welcome -> {
                applyLocalRosterFromHost(control.roster)
            }
            is GroupControl.Roster -> {
                applyLocalRosterFromHost(control.value)
            }
            is GroupControl.Signal -> {
                val body = control.frame.message
                val linkLease = when (body) {
                    is GroupMessage.Offer -> body.link
                    is GroupMessage.Answer -> body.link
                    is GroupMessage.Candidate -> body.link
                    else -> return
                }
                val otherId = when (endpoint.deviceId) {
                    linkLease.pair.first -> linkLease.pair.second
                    linkLease.pair.second -> linkLease.pair.first
                    else -> return
                }
                val peerMember = currentPayload?.view?.members?.singleOrNull { it.lease.deviceId == otherId } ?: return
                val mediaLease = mediaLeaseFor(peerMember.lease, linkLease) ?: return
                output += GroupHybridEffect.LocalMediaSignal(epoch, mediaLease, body)
            }
            is GroupControl.Refused -> {
                requestAutomaticFallback("LOCAL_UNAVAILABLE", "本地主机拒绝直连，正在整组切至云端兜底")
            }
            is GroupControl.Terminated -> {
                requestAutomaticFallback("LOCAL_HOST_LOST", "本地直连已结束，正在整组切至云端兜底")
            }
            else -> Unit
        }
    }

    private fun applyLocalRosterFromHost(roster: GroupRoster) {
                localClientConnected = true
                localConnectDeadlineMs = 0L
                clearLocalRescueState()
                clientLocalLinks = roster.links
                clientLocalAudio = roster.members.associate { it.lease.deviceId to it.audioAvailable }
                message = "双模本地直连已连接"
                reconcileLocalMedia()
                reportLocalStatusNow(force = false)
    }

    private fun handleLocalChannelClosed(event: GroupHybridInternalEvent.LocalChannelClosed) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        if (isCurrentLocalHost()) {
            hostUnadmittedChannels.remove(event.channel)
            val peerId = hostChannelToPeer.remove(event.channel) ?: return
            if (hostPeerChannels[peerId] == event.channel) {
                hostPeerChannels.remove(peerId)
            }
            hostLocalAudio.remove(peerId)
            hostLocalLinks.entries.removeAll { it.key.first == peerId || it.key.second == peerId }
            reconcileHostLocalAuthority()
            reconcileLocalMedia()
            val payload = currentPayload ?: return
            val peer = payload.view.members.find {
                it.lease.deviceId == peerId && it.status == GroupMemberStatus.ADMITTED
            }
            if (peer != null) {
                val fast = peer.supportsFastP2p && payload.hybridTransport?.let { routeAPresetFor(it) } != null
                val grace = if (fast) LOCAL_PEER_DROP_GRACE_MS else LEGACY_PEER_DROP_GRACE_MS
                hostPeerDropDeadlines[peerId] = nowMs() + grace
            }
        } else if (event.channel == localClientChannel) {
            localClientChannel = null
            localClientConnected = false
            clientLocalLinks = emptyList()
            reconcileLocalMedia()
            handleLocalDisruption(
                epoch = event.epoch,
                networkStillUp = localClientNetworkReady,
                reason = "LOCAL_LINK_LOST",
                errorMessage = "本地直连已断开，正在整组切至云端兜底"
            )
        }
    }

    private fun handleLocalDisruption(epoch: Long, networkStillUp: Boolean, reason: String, errorMessage: String, refreshMatch: Boolean = false) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || epoch != activeLocalEpoch) return
        if (isCurrentLocalHost()) {
            localHostReady = false
            if (cloudOffline) {
                val payload = currentPayload ?: return
                val transport = payload.hybridTransport ?: return
                for (lease in openedLocalLeases.values.toList()) {
                    output += GroupHybridEffect.CloseLocalMedia(epoch, lease)
                }
                openedLocalLeases.clear()
                openedLocalChannelRevs.clear()
                message = "无公网信号，正在原地重建本地直连主机…"
                startLocalTransportEpoch(payload, transport)
            } else {
                requestAutomaticFallback(reason, errorMessage)
            }
            return
        }
        if (!networkStillUp) {
            localClientNetworkReady = false
        }
        localClientConnected = false
        localConnectDeadlineMs = 0L
        val now = nowMs()
        val expiredMatch = refreshMatch && retryingCachedNetwork
        if (refreshMatch) {
            localClientMatch = null
            retryingCachedNetwork = false
        }
        if (localRescueNextRetryAtMs == Long.MAX_VALUE) localRescueNextRetryAtMs = now + LOCAL_SELF_RESCUE_RETRY_INTERVAL_MS
        if (localRescueStartedAtMs == 0L) {
            localRescueStartedAtMs = now
            localRescueAttempts = 0
            localRescueFallbackReason = reason
            localRescueFallbackMessage = errorMessage
            triggerMemberLocalRescueAttempt(epoch, preferTcpOnly = networkStillUp, now = now)
            return
        }
        if (expiredMatch) {
            triggerMemberLocalRescueAttempt(epoch, preferTcpOnly = false, now = now)
            return
        }
        val budgetExhausted = localRescueAttempts >= LOCAL_SELF_RESCUE_MAX_ATTEMPTS ||
            now - localRescueStartedAtMs >= LOCAL_SELF_RESCUE_WINDOW_MS
        if (budgetExhausted) {
            if (cloudOffline) {
                localRescueNextRetryAtMs = now + LOCAL_SELF_RESCUE_RETRY_INTERVAL_MS
                message = "无公网信号，持续在本地静默重连中…"
            } else {
                requestAutomaticFallback(localRescueFallbackReason, localRescueFallbackMessage)
            }
            return
        }
        if (now >= localRescueNextRetryAtMs) {
            triggerMemberLocalRescueAttempt(epoch, preferTcpOnly = networkStillUp, now = now)
        } else {
            message = "本地直连短暂中断，等待静默本地自救（${localRescueAttempts}/${LOCAL_SELF_RESCUE_MAX_ATTEMPTS}）…"
        }
    }

    private fun triggerMemberLocalRescueAttempt(epoch: Long, preferTcpOnly: Boolean, now: Long) {
        val transport = currentPayload?.hybridTransport ?: return
        val codeString = transport.localCode ?: return
        val joinCode = runCatching { GroupJoinCode(codeString) }.getOrNull() ?: return
        val attempt = localAttempt ?: return
        if (!cloudOffline) {
            localRescueAttempts += 1
        }
        localRescueNextRetryAtMs = now + LOCAL_SELF_RESCUE_RETRY_INTERVAL_MS
        val shownAttempt = localRescueAttempts.coerceIn(1, LOCAL_SELF_RESCUE_MAX_ATTEMPTS)
        message = if (cloudOffline) {
            "无公网信号，正在本地持续静默重连…"
        } else {
            "本地直连短暂中断，正在本地静默重连（${shownAttempt}/${LOCAL_SELF_RESCUE_MAX_ATTEMPTS}）…"
        }
        if (preferTcpOnly && localClientNetworkReady) {
            val match = localClientMatch
            if (match != null) {
                val context = buildDirectAuthContext(transport) ?: return
                output += GroupHybridEffect.ConnectLocalControl(epoch, attempt, context, joinCode, match)
                return
            }
            val hostAddress = localClientDirectHostAddress
                ?: transport.localHostAddress
                ?: DEFAULT_P2P_GO_ADDRESS
            val authContext = buildDirectAuthContext(transport)
            if (authContext != null) {
                output += GroupHybridEffect.ConnectLocalDirect(
                    epoch = epoch,
                    attempt = attempt,
                    context = authContext,
                    code = joinCode,
                    hostAddress = hostAddress,
                    port = transport.localControlPort
                )
                return
            }
        }
        localClientNetworkReady = false
        localClientChannel?.let { retired ->
            localClientChannel = null
            clientLocalLinks = emptyList()
            reconcileLocalMedia()
            output += GroupHybridEffect.CloseLocalChannel(epoch, retired)
        }
        val preset = routeAPresetFor(transport, includeFrequency = localRescueDirectCount == 0)
        if (preset != null) {
            localRescueDirectCount += 1
            output += GroupHybridEffect.JoinLocalDirect(epoch, attempt, preset, transport.localHostAddress)
        } else {
            // BLE discovery and the cached Wi-Fi request each need their own window.
            localRescueNextRetryAtMs = Long.MAX_VALUE
            localConnectDeadlineMs = now + LOCAL_CONNECT_TIMEOUT_MS
            val match = localClientMatch
            retryingCachedNetwork = match != null
            if (match == null) {
                val searchAttempt = UUID.randomUUID()
                localAttempt = searchAttempt
                output += GroupHybridEffect.StartLocalSearch(epoch, searchAttempt, endpoint, joinCode)
            } else {
                output += GroupHybridEffect.JoinLocalNetwork(epoch, attempt, match)
            }
        }
    }

    private fun handleLocalAudioAvailable(event: GroupHybridInternalEvent.LocalAudioAvailable) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        localSelfAudioAvailable = event.available
        if (isCurrentLocalHost()) {
            hostLocalAudio[endpoint.deviceId] = event.available
            if (!event.available) {
                hostLocalLinks.entries.removeAll { it.key.first == endpoint.deviceId || it.key.second == endpoint.deviceId }
            }
            reconcileHostLocalAuthority()
            reconcileLocalMedia()
        } else {
            val channelId = localClientChannel ?: return
            val hostMember = currentLocalHostMember() ?: return
            sendLocalSignal(event.epoch, channelId, hostMember.lease, GroupMessage.AudioAvailable(event.available))
        }
        reportAudioAvailability(event.available)
    }

    private fun handleLocalVoiceEvidence(event: GroupHybridInternalEvent.LocalVoiceEvidence) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        val currentSnapshot = buildSnapshot()
        if (!currentSnapshot.allows(event.evidence.lease) || !event.evidence.sufficient(nowMs())) return
        if (isCurrentLocalHost()) {
            val link = hostLocalLinks[event.evidence.lease.link.pair]
            if (link != null && link.lease == event.evidence.lease.link && endpoint.deviceId !in link.confirmedBy) {
                localMediaRestartTimestamps.remove(event.evidence.lease.link.pair)
                hostLocalLinks[event.evidence.lease.link.pair] = link.copy(confirmedBy = link.confirmedBy + endpoint.deviceId)
                broadcastLocalRoster(event.epoch)
            }
        } else {
            val channelId = localClientChannel ?: return
            val hostMember = currentLocalHostMember() ?: return
            localMediaRestartTimestamps.remove(event.evidence.lease.link.pair)
            sendLocalSignal(event.epoch, channelId, hostMember.lease, GroupMessage.LinkConfirmed(event.evidence.lease.link))
        }
    }

    private fun handleLocalOutgoing(event: GroupHybridInternalEvent.LocalOutgoing) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        if (!buildSnapshot().allows(event.lease)) return
        if (isCurrentLocalHost()) {
            val targetChannel = hostPeerChannels[event.lease.peer.deviceId] ?: return
            sendLocalSignal(event.epoch, targetChannel, event.lease.peer, event.message)
        } else {
            val channelId = localClientChannel ?: return
            sendLocalSignal(event.epoch, channelId, event.lease.peer, event.message)
        }
    }

    private fun handleLocalMediaFailed(event: GroupHybridInternalEvent.LocalMediaFailed) {
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE || event.epoch != activeLocalEpoch) return
        val pair = event.lease.link.pair
        if (!canRestartLocalMediaPair(pair, nowMs())) return
        if (isCurrentLocalHost()) {
            val current = hostLocalLinks[pair]
            if (current?.lease == event.lease.link) {
                hostLocalLinks.remove(pair)
                reconcileHostLocalAuthority()
                reconcileLocalMedia()
            }
        } else {
            val channelId = localClientChannel ?: return
            val hostMember = currentLocalHostMember() ?: return
            sendLocalSignal(event.epoch, channelId, hostMember.lease, GroupMessage.RestartLink(event.lease.link))
        }
    }

    private fun canRestartLocalMediaPair(pair: GroupPair, now: Long): Boolean {
        val history = localMediaRestartTimestamps.getOrPut(pair) { ArrayDeque() }
        while (history.isNotEmpty() && now - history.first() > LOCAL_MEDIA_RESTART_WINDOW_MS) {
            history.removeFirst()
        }
        if (history.size >= LOCAL_MEDIA_RESTART_MAX_COUNT) return false
        history.addLast(now)
        return true
    }

    private fun handlePreconnectHostReady(event: GroupHybridInternalEvent.PreconnectHostReady) {
        if (phase != GroupPhase.IN_ROOM || event.epoch != preconnectEpoch || event.attempt != preconnectAttempt || !preconnectIsHost) return
        preconnectHostBeaconReady = true
        if (!event.hostAddress.isNullOrBlank()) preconnectHostAddress = event.hostAddress
        if (event.frequencyMhz != null) preconnectHostFrequencyMhz = event.frequencyMhz
        val transport = currentPayload?.hybridTransport
        if (transport?.recoveryStage == GroupHybridRecoveryStage.PRECONNECTING && preconnectHostControlServerStarted) {
            preconnectHostControlServerReady = true
            recomputePreconnectHostControlReady()
        }
        reportLocalStatusNow(force = true)
    }

    private fun handlePreconnectNetworkReady(event: GroupHybridInternalEvent.PreconnectNetworkReady) {
        if (phase != GroupPhase.IN_ROOM || event.epoch != preconnectEpoch || event.attempt != preconnectAttempt || preconnectIsHost) return
        val transport = currentPayload?.hybridTransport ?: return
        if (transport.active != GroupTransport.CLOUD || transport.recoveryStage != GroupHybridRecoveryStage.PRECONNECTING) return
        val codeString = transport.localCode ?: return
        val joinCode = runCatching { GroupJoinCode(codeString) }.getOrNull() ?: return
        val hostAddress = event.hostAddress?.takeIf { it.isNotBlank() }
            ?: transport.localHostAddress?.takeIf { it.isNotBlank() }
            ?: DEFAULT_P2P_GO_ADDRESS
        preconnectHostAddress = hostAddress
        val authContext = buildDirectAuthContext(transport) ?: return
        output += GroupHybridEffect.ConnectPreconnectDirect(
            epoch = event.epoch,
            attempt = event.attempt,
            context = authContext,
            code = joinCode,
            hostAddress = hostAddress,
            port = transport.localControlPort
        )
    }

    private fun handlePreconnectAuthenticated(event: GroupHybridInternalEvent.PreconnectAuthenticated) {
        if (phase != GroupPhase.IN_ROOM || event.epoch != preconnectEpoch) return
        if (preconnectIsHost) {
            val peerEndpoint = event.context.client
            val authorized = currentPayload?.view?.members?.singleOrNull {
                it.lease.deviceId == peerEndpoint.deviceId &&
                    it.lease.runtimeId == peerEndpoint.runtimeId &&
                    it.status == GroupMemberStatus.ADMITTED
            }
            if (authorized == null) {
                diagnostics.hybridLocalAuthorityReject(roomId.orEmpty())
                sendPreconnectControl(event.epoch, event.channel, GroupControl.Refused(GroupResult.FORBIDDEN), terminal = true)
                output += GroupHybridEffect.ClosePreconnectChannel(event.epoch, event.channel)
                return
            }
            preconnectHostUnadmittedChannels[event.channel] = event.context
        } else {
            preconnectClientChannel = event.channel
            sendPreconnectControl(event.epoch, event.channel, GroupControl.Join(nickname))
        }
    }

    private fun handlePreconnectControl(event: GroupHybridInternalEvent.PreconnectControl) {
        if (phase != GroupPhase.IN_ROOM || event.epoch != preconnectEpoch) return
        if (event.message is GroupControl.Signal && bufferPreconnectSignal(event)) return
        if (preconnectIsHost) {
            val existingPeerId = preconnectHostChannelToPeer[event.channel]
            if (existingPeerId == null) {
                val context = preconnectHostUnadmittedChannels.remove(event.channel) ?: run {
                    output += GroupHybridEffect.ClosePreconnectChannel(event.epoch, event.channel)
                    return
                }
                if (event.message !is GroupControl.Join) {
                    output += GroupHybridEffect.ClosePreconnectChannel(event.epoch, event.channel)
                    return
                }
                val peerEndpoint = context.client
                val authorized = currentPayload?.view?.members?.singleOrNull {
                    it.lease.deviceId == peerEndpoint.deviceId &&
                        it.lease.runtimeId == peerEndpoint.runtimeId &&
                        it.status == GroupMemberStatus.ADMITTED
                }
                if (authorized == null) {
                    diagnostics.hybridLocalAuthorityReject(roomId.orEmpty())
                    sendPreconnectControl(event.epoch, event.channel, GroupControl.Refused(GroupResult.FORBIDDEN), terminal = true)
                    output += GroupHybridEffect.ClosePreconnectChannel(event.epoch, event.channel)
                    return
                }
                preconnectHostPeerChannels[peerEndpoint.deviceId]?.let { prior ->
                    if (prior != event.channel) {
                        preconnectHostChannelToPeer.remove(prior)
                        output += GroupHybridEffect.ClosePreconnectChannel(event.epoch, prior)
                    }
                }
                preconnectHostPeerChannels[peerEndpoint.deviceId] = event.channel
                preconnectHostChannelToPeer[event.channel] = peerEndpoint.deviceId
                output += GroupHybridEffect.AdmitPreconnectChannel(event.epoch, event.channel)
                sendPreconnectControlOnlyRoster(event.epoch, event.channel)
                recomputePreconnectHostControlReady()
                reportLocalStatusNow(force = true)
            }
        } else if (event.channel == preconnectClientChannel) {
            when (val control = event.message) {
                is GroupControl.Roster -> {
                    preconnectLatestRoster = control.value
                    preconnectClientConnected = true
                    preconnectControlReady = true
                    reportLocalStatusNow(force = true)
                }
                is GroupControl.Refused -> handlePreconnectFailure(event.epoch, "本地预连被主机拒绝")
                else -> Unit
            }
        }
    }

    private fun bufferPreconnectSignal(event: GroupHybridInternalEvent.PreconnectControl): Boolean {
        val control = event.message as? GroupControl.Signal ?: return false
        val transport = currentPayload?.hybridTransport ?: return false
        // The host can promote its socket before this member receives the LOCAL cloud snapshot.
        val awaitingHandover = transport.active == GroupTransport.CLOUD &&
            transport.epoch == event.epoch && transport.recoveryStage == GroupHybridRecoveryStage.PRECONNECTING
        val drainingCloud = transport.active == GroupTransport.LOCAL &&
            transport.handoverFromEpoch == event.epoch && mediaPhase == HybridMediaPhase.DRAINING_CLOUD
        if (!awaitingHandover && !drainingCloud) return false
        val body = control.frame.message
        val link = when (body) {
            is GroupMessage.Offer -> body.link
            is GroupMessage.Answer -> body.link
            is GroupMessage.Candidate -> body.link
            else -> return false
        }
        val validChannel = if (preconnectIsHost) {
            val peerId = preconnectHostChannelToPeer[event.channel] ?: return true
            control.frame.sender.deviceId == peerId && peerId in listOf(link.pair.first, link.pair.second)
        } else {
            event.channel == preconnectClientChannel && endpoint.deviceId in listOf(link.pair.first, link.pair.second)
        }
        if (!validChannel) return true
        if (bufferedPreconnectSignals.size == MAX_BUFFERED_PRECONNECT_SIGNALS) {
            val candidate = bufferedPreconnectSignals.indexOfFirst { it.control.frame.message is GroupMessage.Candidate }
            if (candidate >= 0) bufferedPreconnectSignals.removeAt(candidate)
            else if (body is GroupMessage.Candidate) return true
            else bufferedPreconnectSignals.removeFirst()
        }
        bufferedPreconnectSignals.addLast(
            BufferedPreconnectSignal(event.epoch, event.channel, control)
        )
        return true
    }

    private fun handlePreconnectChannelClosed(event: GroupHybridInternalEvent.PreconnectChannelClosed) {
        if (phase != GroupPhase.IN_ROOM || event.epoch != preconnectEpoch) return
        if (preconnectIsHost) {
            preconnectHostUnadmittedChannels.remove(event.channel)
            val peerId = preconnectHostChannelToPeer.remove(event.channel) ?: return
            if (preconnectHostPeerChannels[peerId] == event.channel) {
                preconnectHostPeerChannels.remove(peerId)
            }
            recomputePreconnectHostControlReady()
            reportLocalStatusNow(force = true)
        } else if (event.channel == preconnectClientChannel) {
            handlePreconnectFailure(event.epoch, "本地预连控制通道中断")
        }
    }

    private fun handlePreconnectFailure(epoch: Long, reasonMessage: String) {
        if (phase != GroupPhase.IN_ROOM || epoch != preconnectEpoch) return
        val transport = currentPayload?.hybridTransport
        val shouldNotifyServer = transport != null &&
            transport.active == GroupTransport.CLOUD &&
            transport.epoch == epoch &&
            transport.recoveryStage == GroupHybridRecoveryStage.PRECONNECTING &&
            !cloudOffline
        stopPreconnectState(emitStop = true)
        if (shouldNotifyServer) {
            val op = operation ?: return
            val token = sessionToken ?: return
            val room = roomId ?: return
            output += GroupHybridEffect.ReportLocalStatus(
                operation = op,
                token = token,
                roomId = room,
                transportEpoch = epoch,
                localReady = false,
                localReachable = false,
                localProbeActive = false,
                localControlReady = false,
                preconnectFailed = true
            )
        }
    }

    private fun sendPreconnectControlOnlyRoster(epoch: Long, channelId: UUID) {
        val payload = currentPayload ?: return
        val transport = payload.hybridTransport ?: return
        val hostMember = payload.view.members.singleOrNull {
            it.lease.deviceId == endpoint.deviceId &&
                it.status == GroupMemberStatus.ADMITTED &&
                it.role == GroupRole.OWNER
        } ?: return
        val localKey = GroupRoomKey(transport.localRoomInstanceId, hostMember.lease.runtimeId)
        val membersWithoutLocalAudio = payload.view.members.map { it.copy(audioAvailable = false) }
        val roster = runCatching {
            GroupRoster(
                key = localKey,
                hostId = endpoint.deviceId,
                publication = ++localPublication,
                ended = false,
                members = membersWithoutLocalAudio,
                links = emptyList(),
                names = payload.names.filterKeys { id -> membersWithoutLocalAudio.any { it.lease.deviceId == id } },
                riderIncludesFormation = payload.view.riderIncludesFormation,
                privateInvites = payload.view.privateInvites
            )
        }.getOrNull() ?: return
        sendPreconnectControl(epoch, channelId, GroupControl.Roster(roster))
    }

    private fun recomputePreconnectHostControlReady() {
        val payload = currentPayload ?: return
        if (!preconnectIsHost || !preconnectHostControlServerReady) {
            preconnectControlReady = false
            return
        }
        val otherAdmittedIds = payload.view.members
            .filter { it.status == GroupMemberStatus.ADMITTED && it.lease.deviceId != endpoint.deviceId }
            .map { it.lease.deviceId }
            .toSet()
        preconnectControlReady = otherAdmittedIds.isNotEmpty() && preconnectHostPeerChannels.keys.containsAll(otherAdmittedIds)
    }

    private fun canPromotePreconnect(transport: GroupHybridTransportPayload): Boolean {
        val fromEpoch = transport.handoverFromEpoch ?: return false
        if (preconnectEpoch != fromEpoch || preconnectAttempt == null) return false
        return if (endpoint.deviceId == transport.localHostDeviceId) {
            preconnectIsHost && preconnectHostBeaconReady && preconnectHostControlServerReady
        } else {
            !preconnectIsHost && preconnectClientChannel != null && preconnectClientConnected
        }
    }

    private fun promotePreconnectToLocal(payload: GroupCloudSnapshotPayload, transport: GroupHybridTransportPayload) {
        val fromEpoch = checkNotNull(transport.handoverFromEpoch)
        val attempt = checkNotNull(preconnectAttempt)
        val promotedHost = endpoint.deviceId == transport.localHostDeviceId
        val savedHostChannels = preconnectHostPeerChannels.toMap()
        val savedChannelToPeer = preconnectHostChannelToPeer.toMap()
        val savedClientChannel = preconnectClientChannel
        val savedLatestRoster = preconnectLatestRoster
        val savedHostAddress = preconnectHostAddress
        val savedHostFreq = preconnectHostFrequencyMhz
        val savedSignals = bufferedPreconnectSignals.filter {
            it.fromEpoch == fromEpoch
        }

        clearPreconnectFieldsOnly()
        mediaPhase = HybridMediaPhase.LOCAL_ACTIVE
        activeLocalEpoch = transport.epoch
        localAttempt = attempt
        localHostReady = promotedHost
        localHostAddress = if (promotedHost) savedHostAddress else null
        localHostFrequencyMhz = if (promotedHost) savedHostFreq else null
        localClientChannel = if (promotedHost) null else savedClientChannel
        localClientConnected = !promotedHost && savedClientChannel != null
        localClientNetworkReady = !promotedHost && savedClientChannel != null
        localClientDirectHostAddress = if (promotedHost) null else savedHostAddress
        localClientMatch = null
        localConnectDeadlineMs = 0L
        localSelfAudioAvailable = false
        lastReportedLocalReady = false
        clearLocalRescueState()
        localMediaRestartTimestamps.clear()
        hostPeerChannels.clear()
        hostChannelToPeer.clear()
        hostUnadmittedChannels.clear()
        hostLocalLinks.clear()
        hostLocalAudio.clear()
        hostPeerDropDeadlines.clear()
        clientLocalLinks = emptyList()
        clientLocalAudio = emptyMap()
        openedLocalLeases.clear()
        openedLocalChannelRevs.clear()
        networkMediaState = GroupNetworkMediaState.IDLE
        activeSpeakerIds = emptySet()

        output += GroupHybridEffect.PromotePreconnectToLocal(fromEpoch = fromEpoch, toEpoch = transport.epoch)
        if (promotedHost) {
            hostPeerChannels.putAll(savedHostChannels)
            hostChannelToPeer.putAll(savedChannelToPeer)
            message = "双模本地直连已静默切回"
            reconcileHostLocalAuthority()
            reconcileLocalMedia()
        } else {
            message = "双模本地直连已静默切回"
            if (savedLatestRoster != null && savedLatestRoster.links.isNotEmpty()) {
                applyLocalRosterFromHost(savedLatestRoster)
            } else {
                reconcileLocalMedia()
            }
        }
        savedSignals.forEach { signal ->
            if (promotedHost) {
                if (hostChannelToPeer.containsKey(signal.channel)) {
                    handleHostLocalControl(transport.epoch, signal.channel, signal.control)
                }
            } else if (signal.channel == localClientChannel) {
                handleClientLocalControl(transport.epoch, signal.channel, signal.control)
            }
        }
        reportLocalStatusNow(force = true)
    }

    private fun clearPreconnectFieldsOnly() {
        preconnectEpoch = null
        preconnectAttempt = null
        preconnectIsHost = false
        preconnectPresetSsid = null
        preconnectHostBeaconReady = false
        preconnectHostControlServerStarted = false
        preconnectHostControlServerReady = false
        preconnectHostAddress = null
        preconnectHostFrequencyMhz = null
        preconnectClientStarted = false
        preconnectClientChannel = null
        preconnectClientConnected = false
        preconnectControlReady = false
        preconnectLatestRoster = null
        preconnectHostPeerChannels.clear()
        preconnectHostChannelToPeer.clear()
        preconnectHostUnadmittedChannels.clear()
        bufferedPreconnectSignals.clear()
        lastReportedControlReady = false
    }

    private fun stopPreconnectState(emitStop: Boolean) {
        val hadActive = preconnectEpoch != null || preconnectAttempt != null
        clearPreconnectFieldsOnly()
        if (emitStop && hadActive) {
            output += GroupHybridEffect.StopPreconnectNetwork
        }
    }

    private fun clearLocalRescueState() {
        localRescueStartedAtMs = 0L
        localRescueAttempts = 0
        localRescueDirectCount = 0
        retryingCachedNetwork = false
        localRescueNextRetryAtMs = 0L
    }

    private fun routeAPresetFor(
        transport: GroupHybridTransportPayload,
        includeFrequency: Boolean = true
    ): GroupWifiP2pPreset? {
        if (!supportsFastP2p()) return null
        val ssid = transport.localP2pSsid?.takeIf { it.isNotBlank() } ?: return null
        val pass = transport.localP2pPassphrase?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            GroupWifiP2pPreset(
                networkName = ssid,
                passphrase = pass,
                frequencyMhz = if (includeFrequency) transport.localFrequencyMhz else null,
                prefer2GhzBand = true
            )
        }.getOrNull()
    }

    private fun matchesCurrentLocalHost(room: GroupRoomKey, host: GroupAuthEndpoint): Boolean {
        val transport = currentPayload?.hybridTransport ?: return false
        val member = currentLocalHostMember() ?: return false
        return member.status == GroupMemberStatus.ADMITTED &&
            host == GroupAuthEndpoint(member.lease.deviceId, member.lease.runtimeId) &&
            room == GroupRoomKey(transport.localRoomInstanceId, member.lease.runtimeId)
    }

    private fun buildDirectAuthContext(transport: GroupHybridTransportPayload): GroupAuthContext? {
        val hostMember = currentLocalHostMember() ?: return null
        val hostEndpoint = GroupAuthEndpoint(hostMember.lease.deviceId, hostMember.lease.runtimeId)
        return GroupAuthContext(
            room = GroupRoomKey(transport.localRoomInstanceId, hostMember.lease.runtimeId),
            host = hostEndpoint,
            client = endpoint,
            handshakeId = UUID.randomUUID().toString()
        )
    }

    private fun sendPreconnectControl(epoch: Long, channelId: UUID, control: GroupControl, terminal: Boolean = false) {
        output += GroupHybridEffect.SendPreconnect(
            epoch = epoch,
            channel = channelId,
            build = { control },
            terminal = terminal
        )
    }

    private fun reconcileHostLocalAuthority() {
        val epoch = activeLocalEpoch ?: return
        val payload = currentPayload ?: return
        if (!isCurrentLocalHost() || !localHostReady) return
        val admittedById = payload.view.members
            .filter { it.status == GroupMemberStatus.ADMITTED }
            .associateBy { it.lease.deviceId }

        for ((peerId, channelId) in hostPeerChannels.toMap()) {
            if (peerId !in admittedById) {
                hostPeerChannels.remove(peerId)
                hostChannelToPeer.remove(channelId)
                hostLocalAudio.remove(peerId)
                hostPeerDropDeadlines.remove(peerId)
                output += GroupHybridEffect.CloseLocalChannel(epoch, channelId)
            }
        }
        for (peerId in hostPeerDropDeadlines.keys.toList()) {
            if (peerId !in admittedById || peerId in hostPeerChannels) {
                hostPeerDropDeadlines.remove(peerId)
            }
        }

        val connectedIds = buildSet {
            if (endpoint.deviceId in admittedById) add(endpoint.deviceId)
            addAll(hostPeerChannels.keys.filter { it in admittedById })
        }
        val activePairs = admittedById.values
            .filter { it.lease.deviceId in connectedIds }
            .groupBy { it.channel }
            .values
            .flatMap { group ->
                val ids = group.map { it.lease.deviceId }.sorted()
                ids.flatMapIndexed { idx, firstId -> ids.drop(idx + 1).map { secondId -> GroupPair(firstId, secondId) } }
            }
            .toSet()

        hostLocalLinks.entries.removeAll { (pair, link) ->
            val first = admittedById[pair.first]
            val second = admittedById[pair.second]
            pair !in activePairs ||
                first == null ||
                second == null ||
                link.lease.firstIncarnation != first.lease.incarnation ||
                link.lease.secondIncarnation != second.lease.incarnation
        }
        for (pair in activePairs) {
            if (pair !in hostLocalLinks) {
                val first = admittedById.getValue(pair.first)
                val second = admittedById.getValue(pair.second)
                val lease = GroupLinkLease(
                    pair = pair,
                    firstIncarnation = first.lease.incarnation,
                    secondIncarnation = second.lease.incarnation,
                    generation = nextLinkGeneration++
                )
                hostLocalLinks[pair] = GroupLink(lease)
            }
        }
        broadcastLocalRoster(epoch)
    }

    private fun broadcastLocalRoster(epoch: Long) {
        val payload = currentPayload ?: return
        val transport = payload.hybridTransport ?: return
        if (hostPeerChannels.isEmpty()) return
        val hostMember = payload.view.members.singleOrNull {
            it.lease.deviceId == endpoint.deviceId &&
                it.status == GroupMemberStatus.ADMITTED &&
                it.role == GroupRole.OWNER
        } ?: return
        val localKey = GroupRoomKey(transport.localRoomInstanceId, hostMember.lease.runtimeId)
        val membersWithAudio = payload.view.members.map { member ->
            val available = if (member.status == GroupMemberStatus.ADMITTED) {
                hostLocalAudio[member.lease.deviceId] ?: false
            } else false
            member.copy(audioAvailable = available)
        }
        val roster = runCatching {
            GroupRoster(
                key = localKey,
                hostId = endpoint.deviceId,
                publication = ++localPublication,
                ended = false,
                members = membersWithAudio,
                links = hostLocalLinks.values.toList(),
                names = payload.names.filterKeys { id -> membersWithAudio.any { it.lease.deviceId == id } },
                riderIncludesFormation = payload.view.riderIncludesFormation,
                privateInvites = payload.view.privateInvites
            )
        }.getOrNull() ?: return
        hostPeerChannels.values.forEach { channelId ->
            sendLocalControl(epoch, channelId, GroupControl.Roster(roster))
        }
    }

    private fun reconcileLocalMedia() {
        val epoch = activeLocalEpoch ?: return
        if (mediaPhase != HybridMediaPhase.LOCAL_ACTIVE) return
        val view = effectiveLocalView() ?: return
        val localLease = local ?: return
        val intent = participation.token ?: return
        val selfMember = view.members.singleOrNull { it.lease == localLease && it.status == GroupMemberStatus.ADMITTED }
            ?: return
        val desired = linkedMapOf<String, GroupMediaLease>()
        val desiredRevs = mutableMapOf<String, Pair<Long, Long>>()
        for (link in view.links) {
            val peerId = when (endpoint.deviceId) {
                link.lease.pair.first -> link.lease.pair.second
                link.lease.pair.second -> link.lease.pair.first
                else -> continue
            }
            val peerMember = view.members.singleOrNull {
                it.lease.deviceId == peerId &&
                    it.status == GroupMemberStatus.ADMITTED &&
                    it.channel == selfMember.channel
            } ?: continue
            desired[peerId] = GroupMediaLease(
                intent = intent,
                local = localLease,
                peer = peerMember.lease,
                link = link.lease
            )
            desiredRevs[peerId] = selfMember.channelRevision to peerMember.channelRevision
        }
        for ((peerId, activeLease) in openedLocalLeases.toMap()) {
            if (desired[peerId] != activeLease || openedLocalChannelRevs[peerId] != desiredRevs[peerId]) {
                openedLocalLeases.remove(peerId)
                openedLocalChannelRevs.remove(peerId)
                output += GroupHybridEffect.CloseLocalMedia(epoch, activeLease)
            }
        }
        val openingFirst = openedLocalLeases.isEmpty() && desired.isNotEmpty()
        for ((peerId, desiredLease) in desired) {
            if (openedLocalLeases[peerId] != desiredLease || openedLocalChannelRevs[peerId] != desiredRevs[peerId]) {
                openedLocalLeases[peerId] = desiredLease
                openedLocalChannelRevs[peerId] = desiredRevs.getValue(peerId)
                val offerer = endpoint.deviceId == desiredLease.link.pair.first
                output += GroupHybridEffect.OpenLocalMedia(epoch, desiredLease, offerer)
            }
        }
        if (openingFirst) {
            output += GroupHybridEffect.SetLocalMuted(participation.selfMuted)
            participation.blockedDeviceIds.forEach { peerId ->
                output += GroupHybridEffect.SetLocalBlocked(peerId, true)
            }
        }
    }

    private fun mediaLeaseFor(peerLease: GroupMemberLease, linkLease: GroupLinkLease): GroupMediaLease? {
        val view = effectiveLocalView() ?: return null
        val localLease = local ?: return null
        val intent = participation.token ?: return null
        val selfMember = view.members.singleOrNull { it.lease == localLease } ?: return null
        val peerMember = view.members.singleOrNull { it.lease == peerLease } ?: return null
        if (selfMember.channel != peerMember.channel) return null
        return GroupMediaLease(
            intent = intent,
            local = localLease,
            peer = peerLease,
            link = linkLease
        )
    }

    private fun requestAutomaticFallback(reason: String, userMessage: String) {
        val payload = currentPayload ?: return
        val transport = payload.hybridTransport ?: return
        if (phase != GroupPhase.IN_ROOM || transport.active != GroupTransport.LOCAL) return
        if (fallbackRequestedEpoch == transport.epoch) return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        fallbackRequestedEpoch = transport.epoch
        message = userMessage
        output += GroupHybridEffect.SwitchTransport(
            operation = op,
            token = token,
            roomId = room,
            target = GroupTransport.CLOUD,
            reason = reason,
            expectedRevision = null,
            expectedTransportEpoch = transport.epoch
        )
    }

    private fun reportLocalStatusNow(force: Boolean) {
        if (phase != GroupPhase.IN_ROOM || cloudOffline) return
        val payload = currentPayload ?: return
        val transport = payload.hybridTransport ?: return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        val now = nowMs()
        val localReady = mediaPhase == HybridMediaPhase.LOCAL_ACTIVE &&
            transport.active == GroupTransport.LOCAL &&
            (if (isCurrentLocalHost()) localHostReady && hostPeerChannels.isNotEmpty() else localClientConnected)
        val localReachable = if (transport.active == GroupTransport.LOCAL) localReady else proximityReachable
        val hostBeaconUp = proximityBeaconActive || (preconnectIsHost && preconnectHostBeaconReady)
        val probeActive = transport.active == GroupTransport.CLOUD && proximityIsHost && hostBeaconUp
        val controlReady = transport.active == GroupTransport.CLOUD &&
            transport.recoveryStage == GroupHybridRecoveryStage.PRECONNECTING &&
            preconnectEpoch == transport.epoch &&
            preconnectControlReady
        val reportedAddress = when {
            transport.active == GroupTransport.LOCAL && isCurrentLocalHost() -> localHostAddress
            transport.active == GroupTransport.CLOUD && preconnectIsHost -> preconnectHostAddress
            else -> null
        }
        val reportedFreq = when {
            transport.active == GroupTransport.LOCAL && isCurrentLocalHost() -> localHostFrequencyMhz
            transport.active == GroupTransport.CLOUD && preconnectIsHost -> preconnectHostFrequencyMhz
            else -> null
        }
        if (
            !force &&
            localReady == lastReportedLocalReady &&
            controlReady == lastReportedControlReady &&
            now - lastLocalStatusReportAtMs < LOCAL_STATUS_HEARTBEAT_MS
        ) {
            return
        }
        lastReportedLocalReady = localReady
        lastReportedControlReady = controlReady
        lastLocalStatusReportAtMs = now
        output += GroupHybridEffect.ReportLocalStatus(
            operation = op,
            token = token,
            roomId = room,
            transportEpoch = transport.epoch,
            localReady = localReady,
            localReachable = localReachable,
            localProbeActive = probeActive,
            localRssiDbm = if (transport.active == GroupTransport.CLOUD && !proximityIsHost) proximityRssiDbm else null,
            localControlReady = controlReady,
            localHostAddress = reportedAddress,
            localFrequencyMhz = reportedFreq,
            preconnectFailed = false
        )
    }

    private fun handlePollFailed(event: GroupHybridInternalEvent.PollFailed) {
        if (event.operation != operation || joinCancellation?.requested == true) return
        pollInFlight = false
        when (event.error.code) {
            "REMOVED" -> finish(event.error.userMessage, flush = false, terminal = GroupTerminalReason.REMOVED)
            "ROOM_ENDED" -> finish(
                event.error.userMessage,
                flush = false,
                terminal = mapTerminalReason(event.error.endedReason ?: "ROOM_ENDED")
            )
            "RECOVERY_EXPIRED" -> finish(event.error.userMessage, flush = false, terminal = GroupTerminalReason.RECOVERY_EXPIRED)
            "UNAUTHORIZED" -> finish(event.error.userMessage, flush = false, terminal = GroupTerminalReason.FAILED)
            else -> {
                if (phase == GroupPhase.WAITING) {
                    scheduleJoinPoll()
                } else if (phase == GroupPhase.IN_ROOM) {
                    if (mediaPhase == HybridMediaPhase.LOCAL_ACTIVE && currentPayload?.hybridTransport?.active == GroupTransport.LOCAL) {
                        cloudOffline = true
                        if (recoveryDeadline == null) {
                            recoveryDeadline = nowMs() + RECOVERY_WINDOW_MS
                            diagnostics.recovery(roomKey, "hybrid_local_keepalive", "cloud_offline")
                        }
                        nextRetryAtMs = nowMs() + RETRY_INTERVAL_MS
                        message = "云端暂离线，当前保持本地直连通话，正在重连云端"
                    } else {
                        enterReconnecting("云端连接中断，正在 60 秒内恢复…")
                    }
                }
            }
        }
    }

    private fun handleActionFailed(event: GroupHybridInternalEvent.ActionFailed) {
        if (event.operation != operation) return
        if (joinCancellation?.requested == true) {
            if (event.action == "join") joinCancellation?.submitted(null)
            if (event.action == "cancel_join") {
                joinCancellation?.retryAt(nowMs() + RETRY_INTERVAL_MS)
                message = "撤销尚未确认，正在重试"
            }
            return
        }
        if (event.action == "resume") {
            resumeInFlight = false
            if (event.error.code in TERMINAL_CODES) {
                finish(event.error.userMessage, flush = false, terminal = mapErrorToTerminal(event.error))
                return
            }
            nextRetryAtMs = nowMs() + RETRY_INTERVAL_MS
            return
        }
        if (event.action == "switch_transport") {
            manualSwitchInFlight = false
            if (event.error.code == "TRANSPORT_CONFLICT") {
                fallbackRequestedEpoch = null
                if (!pollInFlight && phase == GroupPhase.IN_ROOM) scheduleEventPoll()
                return
            }
            fallbackRequestedEpoch = null
        }
        if (event.action == "create" || event.action == "join") {
            val terminal = if (event.error.code in setOf("INVALID_INVITE", "ROOM_FULL", "REMOVED", "ALREADY_IN_ROOM")) {
                GroupTerminalReason.REFUSED
            } else {
                GroupTerminalReason.FAILED
            }
            finish(event.error.userMessage, flush = false, terminal = terminal)
            return
        }
        if (event.error.code in TERMINAL_CODES) {
            finish(event.error.userMessage, flush = false, terminal = mapErrorToTerminal(event.error))
            return
        }
        if (event.action == "rtc_token") retryRtcTokenRefresh() else message = event.error.userMessage
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
                if (token != null && room != null) output += GroupHybridEffect.LeaveRoom(token, room)
                finish(
                    if (isOwner) "双模房间已结束" else "已离开双模房间",
                    flush = true,
                    terminal = GroupTerminalReason.USER_LEFT
                )
            }
            else -> finish("已离开双模房间", flush = false, terminal = GroupTerminalReason.USER_LEFT)
        }
    }

    private fun handleTick() {
        if (joinCancellation?.requested == true) return
        val now = nowMs()
        val wallNow = wallNowMs()
        if (phase == GroupPhase.WAITING && pendingRequestExpiresAtMs > 0L && wallNow >= pendingRequestExpiresAtMs) {
            finish("加入申请已超时，请重新申请", flush = false, terminal = GroupTerminalReason.FAILED)
            return
        }
        if (phase == GroupPhase.RECONNECTING) {
            val deadline = recoveryDeadline
            if (deadline != null && now >= deadline) {
                finish("断线超过 60 秒，双模房间连接已结束", flush = false, terminal = GroupTerminalReason.RECOVERY_EXPIRED)
                return
            }
            if (!resumeInFlight && now >= nextRetryAtMs) {
                triggerResume(now)
            }
            return
        }
        if (phase != GroupPhase.IN_ROOM) return

        if (cloudOffline) {
            val deadline = recoveryDeadline
            if (deadline != null && now >= deadline) {
                finish("云端断线超过 60 秒，双模房间已结束", flush = false, terminal = GroupTerminalReason.RECOVERY_EXPIRED)
                return
            }
            if (!resumeInFlight && now >= nextRetryAtMs) {
                triggerResume(now)
            }
        } else if (!pollInFlight) {
            scheduleEventPoll()
        }

        val payload = currentPayload ?: return
        val transport = payload.hybridTransport ?: return
        if (transport.active == GroupTransport.LOCAL && mediaPhase == HybridMediaPhase.LOCAL_ACTIVE) {
            if (!isCurrentLocalHost() && !localClientConnected) {
                val activeEpoch = activeLocalEpoch
                if (localRescueStartedAtMs > 0L && activeEpoch != null) {
                    val legacyTimeout = localRescueNextRetryAtMs == Long.MAX_VALUE && now >= localConnectDeadlineMs
                    if (legacyTimeout && (retryingCachedNetwork || cloudOffline)) {
                        localClientMatch = null
                        triggerMemberLocalRescueAttempt(activeEpoch, preferTcpOnly = false, now = now)
                    } else if (!cloudOffline && now >= maxOf(localRescueStartedAtMs + LOCAL_SELF_RESCUE_WINDOW_MS, localConnectDeadlineMs)) {
                        requestAutomaticFallback(localRescueFallbackReason, localRescueFallbackMessage)
                    } else if (now >= localRescueNextRetryAtMs) {
                        if (cloudOffline || localRescueAttempts < LOCAL_SELF_RESCUE_MAX_ATTEMPTS) {
                            triggerMemberLocalRescueAttempt(activeEpoch, preferTcpOnly = false, now = now)
                        } else {
                            requestAutomaticFallback(localRescueFallbackReason, localRescueFallbackMessage)
                        }
                    }
                } else if (localConnectDeadlineMs > 0L && now >= localConnectDeadlineMs) {
                    localConnectDeadlineMs = 0L
                    if (!cloudOffline) {
                        requestAutomaticFallback("LOCAL_UNAVAILABLE", "本地直连超时，正在整组切至云端兜底")
                    }
                }
            } else if (isCurrentLocalHost()) {
                val expiredPeer = hostPeerDropDeadlines.entries.firstOrNull { now >= it.value }?.key
                if (expiredPeer != null) {
                    val stillAdmitted = payload.view.members.any {
                        it.lease.deviceId == expiredPeer && it.status == GroupMemberStatus.ADMITTED
                    }
                    if (!stillAdmitted || expiredPeer in hostPeerChannels) {
                        hostPeerDropDeadlines.remove(expiredPeer)
                    } else if (!cloudOffline) {
                        hostPeerDropDeadlines.remove(expiredPeer)
                        requestAutomaticFallback("LOCAL_LINK_LOST", "有队友离开本地直连范围，正在整组切至云端兜底")
                    }
                }
            }
            reportLocalStatusNow(force = false)
        } else if (transport.active == GroupTransport.CLOUD) {
            if (rtcRefreshRetryAtMs != 0L || rtcToken?.needsRefresh(wallNow) == true) requestRtcTokenRefresh()
            if (networkMediaState == GroupNetworkMediaState.FAILED && now >= rtcRetryAtMs) {
                rtcRetryAtMs = 0L
            }
            if (
                (proximityEpoch == transport.epoch && (proximityBeaconActive || proximityReachable || proximityRssiDbm != null)) ||
                (preconnectEpoch == transport.epoch && (preconnectHostBeaconReady || preconnectControlReady))
            ) {
                reportLocalStatusNow(force = false)
            }
        }
    }

    private fun triggerResume(now: Long) {
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        resumeInFlight = true
        nextRetryAtMs = now + RETRY_INTERVAL_MS
        output += GroupHybridEffect.ResumeRoom(op, token, room)
    }

    private fun enterReconnecting(reasonMessage: String) {
        if (phase != GroupPhase.IN_ROOM) return
        phase = GroupPhase.RECONNECTING
        cloudOffline = false
        recoveryDeadline = nowMs() + RECOVERY_WINDOW_MS
        nextRetryAtMs = nowMs() + RETRY_INTERVAL_MS
        message = reasonMessage
        drainAnyActiveMedia()
        diagnostics.recovery(roomKey, "hybrid_member", "reconnecting")
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
        if (rtcRefreshInFlight || phase != GroupPhase.IN_ROOM || cloudOffline) return
        val payload = currentPayload ?: return
        if (payload.hybridTransport?.active != GroupTransport.CLOUD || payload.rtcRoomId.isNullOrBlank()) return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        rtcRefreshInFlight = true
        output += GroupHybridEffect.RefreshRtcToken(op, token, room)
    }

    private fun reportAudioAvailability(available: Boolean) {
        if (phase != GroupPhase.IN_ROOM || cloudOffline) return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        output += GroupHybridEffect.ReportAudio(op, token, room, available)
    }

    private fun scheduleJoinPoll() {
        if (pollInFlight || phase != GroupPhase.WAITING) return
        val op = operation ?: return
        val reqId = pendingRequestId ?: return
        val secret = pendingRequestSecret ?: return
        pollInFlight = true
        output += GroupHybridEffect.PollJoinRequest(op, reqId, secret)
    }

    private fun scheduleEventPoll() {
        if (pollInFlight || phase != GroupPhase.IN_ROOM || cloudOffline) return
        val op = operation ?: return
        val token = sessionToken ?: return
        val room = roomId ?: return
        pollInFlight = true
        output += GroupHybridEffect.PollEvents(op, token, room, latestSeq)
    }

    private fun currentLocalHostMember(): GroupMember? {
        val transport = currentPayload?.hybridTransport ?: return null
        return currentPayload?.view?.members?.singleOrNull { it.lease.deviceId == transport.localHostDeviceId }
    }

    private fun sendLocalControl(epoch: Long, channelId: UUID, control: GroupControl, terminal: Boolean = false) {
        output += GroupHybridEffect.SendLocal(
            epoch = epoch,
            channel = channelId,
            build = { control },
            terminal = terminal
        )
    }

    private fun sendLocalSignal(epoch: Long, channelId: UUID, recipient: GroupMemberLease, body: GroupMessage) {
        val transport = currentPayload?.hybridTransport ?: return
        val localLease = local ?: return
        val localKey = GroupRoomKey(
            transport.localRoomInstanceId,
            currentLocalHostMember()?.lease?.runtimeId ?: endpoint.runtimeId
        )
        val sequence = ++localControlSequence
        sendLocalControl(
            epoch = epoch,
            channelId = channelId,
            control = GroupControl.Signal(GroupFrame(localKey, localLease, recipient, sequence, body))
        )
    }

    private fun admittedMembersInCurrentChannel(payload: GroupCloudSnapshotPayload): List<GroupMember> {
        val selfMember = payload.view.members.singleOrNull { it.lease.deviceId == endpoint.deviceId } ?: return emptyList()
        return payload.view.members.filter {
            it.status == GroupMemberStatus.ADMITTED && it.channel == selfMember.channel
        }
    }

    private fun effectiveLocalView(): GroupCloudRoomView? {
        val payload = currentPayload ?: return null
        val transport = payload.hybridTransport ?: return payload.view
        if (transport.active != GroupTransport.LOCAL || mediaPhase != HybridMediaPhase.LOCAL_ACTIVE) {
            return payload.view
        }
        val links = if (isCurrentLocalHost()) hostLocalLinks.values.toList() else clientLocalLinks
        val audio = if (isCurrentLocalHost()) hostLocalAudio.toMap() else clientLocalAudio
        return payload.view.withLocalLinks(
            newLinks = links,
            localAudioAvailability = audio,
            activeHostDeviceId = transport.localHostDeviceId
        )
    }

    private fun finish(reasonMessage: String, flush: Boolean, terminal: GroupTerminalReason) {
        if (phase == GroupPhase.IDLE && operation == null) return
        diagnostics.end(roomKey, terminal)
        if (proximityEpoch != null) {
            proximityEpoch = null
            proximityTargetWifiSsid = null
            proximityBeaconActive = false
            proximityReachable = false
            proximityRssiDbm = null
            output += GroupHybridEffect.StopProximityProbe
        }
        stopPreconnectState(emitStop = true)
        drainAnyActiveMedia()
        phase = GroupPhase.IDLE
        operation = null
        roomId = null
        roomKey = null
        sessionToken = null
        tokenExpiresAtMs = 0L
        latestSeq = 0L
        local = null
        currentPayload = null
        participation = participation.token?.let(participation::stop) ?: GroupParticipation()
        joinCancellation = null
        pendingRequestId = null
        pendingRequestSecret = null
        pendingRequestExpiresAtMs = 0L
        pollInFlight = false
        resumeInFlight = false
        recoveryDeadline = null
        nextRetryAtMs = 0L
        cloudOffline = false
        rtcToken = null
        rtcRefreshInFlight = false
        rtcRefreshRetryAtMs = 0L
        rtcRetryAtMs = 0L
        networkMediaState = GroupNetworkMediaState.IDLE
        activeSpeakerIds = emptySet()
        fallbackRequestedEpoch = null
        manualSwitchInFlight = false
        lastTransport = null
        lastTransportEpoch = 0L
        voiceReadyReported = false
        terminalReason = terminal
        message = reasonMessage
        output += GroupHybridEffect.Stop(flush)
    }

    private fun observeVoiceReady(current: GroupSessionSnapshot) {
        if (current.voiceReady && !voiceReadyReported) {
            val key = current.view?.key ?: return
            voiceReadyReported = true
            diagnostics.voiceReady(key, current.currentChannel, nowMs() - roomStartedAtMs)
        } else if (!current.voiceReady) {
            voiceReadyReported = false
        }
    }

    private fun buildSnapshot(): GroupSessionSnapshot {
        val payload = currentPayload
        val transport = payload?.hybridTransport
        val effectiveView = effectiveLocalView()
        return GroupSessionSnapshot(
            phase = phase,
            operation = operation,
            networkAttempt = localAttempt ?: preconnectAttempt,
            view = effectiveView,
            local = local,
            participation = participation,
            code = null,
            host = payload?.view?.ownerId == endpoint.deviceId,
            message = message,
            matches = emptyList(),
            names = payload?.names ?: emptyMap(),
            removed = payload?.removedIds ?: emptySet(),
            channelSwitching = false,
            pendingSwitchTarget = null,
            terminalReason = terminalReason,
            mode = GroupMode.HYBRID,
            inviteCode = payload?.inviteCode,
            requireApproval = payload?.requireApproval ?: false,
            pendingJoinRequests = payload?.pendingRequests ?: emptyList(),
            networkMediaState = networkMediaState,
            activeSpeakerIds = activeSpeakerIds,
            activeTransport = transport?.active ?: GroupTransport.LOCAL,
            transportSwitching = isTransportSwitching(),
            transportEpoch = transport?.epoch ?: 0L,
            transportReason = transport?.reason,
            cloudOffline = cloudOffline
        )
    }

    private fun mapTerminalReason(reason: String): GroupTerminalReason = when (reason) {
        "REMOVED" -> GroupTerminalReason.REMOVED
        "RECOVERY_EXPIRED", "OWNER_RECOVERY_EXPIRED" -> GroupTerminalReason.RECOVERY_EXPIRED
        "OWNER_ENDED", "LAST_MEMBER_LEFT", "ROOM_ENDED" -> GroupTerminalReason.HOST_ENDED
        else -> GroupTerminalReason.FAILED
    }

    private fun mapErrorToTerminal(error: GroupCloudException): GroupTerminalReason = when (error.code) {
        "REMOVED" -> GroupTerminalReason.REMOVED
        "RECOVERY_EXPIRED" -> GroupTerminalReason.RECOVERY_EXPIRED
        "ROOM_ENDED" -> mapTerminalReason(error.endedReason ?: "ROOM_ENDED")
        else -> GroupTerminalReason.FAILED
    }

    companion object {
        private const val MAX_QUEUE_SIZE = 256
        private const val MAX_BUFFERED_PRECONNECT_SIGNALS = 64
        private const val RECOVERY_WINDOW_MS = 60_000L
        private const val RETRY_INTERVAL_MS = 2_000L
        private const val RTC_RETRY_INTERVAL_MS = 5_000L
        private const val LOCAL_CONNECT_TIMEOUT_MS = 20_000L
        internal const val LOCAL_PEER_DROP_GRACE_MS = 13_000L
        internal const val LOCAL_SELF_RESCUE_MAX_ATTEMPTS = 3
        internal const val LOCAL_SELF_RESCUE_WINDOW_MS = 10_000L
        internal const val LOCAL_SELF_RESCUE_RETRY_INTERVAL_MS = 3_000L
        // A cached network attempt may be followed by a fresh BLE bootstrap and Wi-Fi join.
        private const val LEGACY_PEER_DROP_GRACE_MS = 2 * LOCAL_CONNECT_TIMEOUT_MS + LOCAL_SELF_RESCUE_RETRY_INTERVAL_MS
        internal const val LOCAL_MEDIA_RESTART_MAX_COUNT = 2
        internal const val LOCAL_MEDIA_RESTART_WINDOW_MS = 10_000L
        private const val DEFAULT_P2P_GO_ADDRESS = "192.168.49.1"
        private const val LOCAL_STATUS_HEARTBEAT_MS = 10_000L
        private val TERMINAL_CODES = setOf("REMOVED", "ROOM_ENDED", "RECOVERY_EXPIRED", "UNAUTHORIZED")
    }
}
