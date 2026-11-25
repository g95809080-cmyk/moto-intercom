package com.kuma.motointercom.group

import java.util.UUID

internal enum class GroupPhase { IDLE, CREATING, SEARCHING, SELECTING, JOINING, IN_ROOM, RECONNECTING, WAITING }
internal enum class GroupTerminalReason { USER_LEFT, HOST_ENDED, REMOVED, RECOVERY_EXPIRED, REFUSED, FAILED }
internal enum class GroupNetworkMediaState { IDLE, WAITING_FOR_PEER, JOINING, JOINED, FAILED }
internal class GroupSessionSnapshot(
    val phase: GroupPhase,
    val operation: UUID?,
    val networkAttempt: UUID?,
    val view: GroupRoomView?,
    val local: GroupMemberLease?,
    val participation: GroupParticipation,
    val code: GroupJoinCode?,
    val host: Boolean,
    val message: String,
    val matches: List<GroupBootstrapMatch>,
    val names: Map<String, String>,
    val removed: Set<String>,
    val channelSwitching: Boolean = false,
    val pendingSwitchTarget: GroupChannel? = null,
    val terminalReason: GroupTerminalReason? = null,
    val mode: GroupMode = GroupMode.LOCAL,
    val inviteCode: String? = null,
    val requireApproval: Boolean = false,
    val pendingJoinRequests: List<GroupJoinRequest> = emptyList(),
    val networkMediaState: GroupNetworkMediaState = GroupNetworkMediaState.IDLE,
    val activeSpeakerIds: Set<String> = emptySet(),
    val activeTransport: GroupTransport = if (mode == GroupMode.NETWORK) GroupTransport.CLOUD else GroupTransport.LOCAL,
    val transportSwitching: Boolean = false,
    val transportEpoch: Long = 0L,
    val transportReason: String? = null,
    val cloudOffline: Boolean = false
) {
    fun allows(lease: GroupMediaLease) = local == lease.local && view?.let { lease.matches(it, participation) } == true
    val localMember: GroupMember? get() = view?.members?.singleOrNull { it.lease == local }
    val isOwner: Boolean get() = view?.let { it.ownerId == local?.deviceId } ?: host
    val maxMembers: Int get() = view?.maxMembers ?: GroupRoom.MAX_MEMBERS
    val localRole: GroupRole get() = localMember?.role ?: if (isOwner) GroupRole.OWNER else GroupRole.MEMBER
    val currentChannel: GroupChannel get() = localMember?.channel ?: GroupChannel.Public
    val canEnterRider: Boolean get() = view?.canEnterRider(localRole) == true
    val riderIncludesFormation: Boolean get() = view?.riderIncludesFormation ?: true
    val outgoingPrivateInvite: GroupPrivateInvite? get() = local?.deviceId?.let { id ->
        view?.privateInvites?.singleOrNull { it.callerId == id }
    }
    val incomingPrivateInvite: GroupPrivateInvite? get() = local?.deviceId?.let { id ->
        view?.privateInvites?.singleOrNull { it.calleeId == id }
    }
    val privatePartner: GroupMember? get() {
        val channel = currentChannel as? GroupChannel.Private ?: return null
        val selfId = local?.deviceId ?: return null
        return view?.members?.singleOrNull {
            it.status != GroupMemberStatus.WAITING && it.channel == channel && it.lease.deviceId != selfId
        }
    }
    val channelMembers: List<GroupMember> get() {
        val room = view ?: return emptyList()
        val channel = currentChannel
        return room.members.filter { it.status != GroupMemberStatus.WAITING && it.channel == channel }
    }
    val channelLinks: List<GroupLink> get() {
        val room = view ?: return emptyList()
        val ids = channelMembers.map { it.lease.deviceId }.toSet()
        return room.links.filter { it.lease.pair.first in ids && it.lease.pair.second in ids }
    }
    val voiceReady: Boolean get() {
        val room = view ?: return false
        if (room.ended || localMember?.status != GroupMemberStatus.ADMITTED) return false
        if (transportSwitching) return false
        if (mode == GroupMode.NETWORK) return networkMediaState == GroupNetworkMediaState.JOINED
        if (mode == GroupMode.HYBRID && activeTransport == GroupTransport.CLOUD) {
            val active = channelMembers.filter { it.status == GroupMemberStatus.ADMITTED }
            return active.size >= 2 && networkMediaState == GroupNetworkMediaState.JOINED
        }
        val membersInChannel = channelMembers
        val active = membersInChannel.filter { it.status == GroupMemberStatus.ADMITTED }
        if (active.size < 2 || membersInChannel.any { it.status == GroupMemberStatus.RESERVED } || active.any { !it.audioAvailable }) return false
        val expected = active.size * (active.size - 1) / 2
        val linksInChannel = channelLinks
        return linksInChannel.size == expected && linksInChannel.all {
            it.confirmedBy.containsAll(listOf(it.lease.pair.first, it.lease.pair.second))
        }
    }
    override fun toString() = "GroupSessionSnapshot(phase=$phase)"
}

internal data class GroupVoiceEvidence(
    val lease: GroupMediaLease,
    val connected: Boolean,
    val remoteTrack: Boolean,
    val audioIoEnabled: Boolean,
    val routeReady: Boolean,
    val previousSent: Long,
    val previousReceived: Long,
    val sent: Long,
    val received: Long,
    val observedAtMs: Long
) {
    fun sufficient(now: Long) = connected && remoteTrack && audioIoEnabled && routeReady &&
        previousSent >= 0 && previousReceived >= 0 && sent > previousSent && received > previousReceived &&
        observedAtMs <= now && now - observedAtMs <= 5_000
}

internal sealed interface GroupSessionEvent {
    data object Create : GroupSessionEvent
    class Search(val code: GroupJoinCode) : GroupSessionEvent
    class Found(val operation: UUID, val matches: List<GroupBootstrapMatch>) : GroupSessionEvent
    class Select(val room: GroupRoomKey) : GroupSessionEvent
    class HostReady(
        val operation: UUID,
        val hostAddress: String? = null,
        val frequencyMhz: Int? = null
    ) : GroupSessionEvent
    class NetworkReady(
        val operation: UUID,
        val hostAddress: String? = null
    ) : GroupSessionEvent
    class NetworkLost(val operation: UUID) : GroupSessionEvent
    class NetworkFailed(val attempt: UUID, val message: String) : GroupSessionEvent
    class NetworkRefreshed(val attempt: UUID, val match: GroupBootstrapMatch) : GroupSessionEvent
    class Authenticated(val operation: UUID, val channel: UUID, val context: GroupAuthContext, val atMs: Long) : GroupSessionEvent
    class Control(val channel: UUID, val message: GroupControl) : GroupSessionEvent
    class Closed(val channel: UUID) : GroupSessionEvent
    class ControlFailed(val attempt: UUID) : GroupSessionEvent
    class Failed(val operation: UUID, val message: String) : GroupSessionEvent
    class AudioAvailable(val available: Boolean) : GroupSessionEvent
    class Evidence(val evidence: GroupVoiceEvidence) : GroupSessionEvent
    class Outgoing(val lease: GroupMediaLease, val message: GroupMessage) : GroupSessionEvent
    class MediaFailed(val lease: GroupMediaLease) : GroupSessionEvent
    class Mute(val value: Boolean) : GroupSessionEvent
    class Block(val peerId: String, val value: Boolean) : GroupSessionEvent
    class Remove(val peerId: String) : GroupSessionEvent
    class Unblock(val peerId: String) : GroupSessionEvent
    class SetRole(val peerId: String, val role: GroupRole) : GroupSessionEvent
    class SetRiderIncludesFormation(val included: Boolean) : GroupSessionEvent
    class SwitchChannel(val channel: GroupChannel) : GroupSessionEvent
    class RequestPrivate(val peerId: String) : GroupSessionEvent
    class AcceptPrivate(val inviteId: String) : GroupSessionEvent
    class DeclinePrivate(val inviteId: String) : GroupSessionEvent
    class CancelPrivate(val inviteId: String) : GroupSessionEvent
    data object EndPrivate : GroupSessionEvent
    data object Tick : GroupSessionEvent
    data class SearchProgress(val operation: UUID, val index: Int, val total: Int) : GroupSessionEvent
    data object Leave : GroupSessionEvent
    data class ApproveJoin(val requestId: String) : GroupSessionEvent
    data class RejectJoin(val requestId: String) : GroupSessionEvent
    data class TransferOwner(val targetDeviceId: String, val alsoLeave: Boolean = false) : GroupSessionEvent
    data class SetRequireApproval(val required: Boolean) : GroupSessionEvent
    data object RotateInvite : GroupSessionEvent
    data object EndRoom : GroupSessionEvent
    data class SwitchTransport(val target: GroupTransport) : GroupSessionEvent
}

internal sealed interface GroupSessionEffect {
    class StartHost(val operation: UUID, val descriptor: GroupDescriptor, val code: GroupJoinCode) : GroupSessionEffect
    class RecoverHost(val operation: UUID, val descriptor: GroupDescriptor, val code: GroupJoinCode) : GroupSessionEffect
    class Search(val operation: UUID, val endpoint: GroupAuthEndpoint, val code: GroupJoinCode) : GroupSessionEffect
    class JoinNetwork(val operation: UUID, val match: GroupBootstrapMatch, val refreshCode: GroupJoinCode? = null) : GroupSessionEffect
    class ConnectControl(val operation: UUID, val attempt: UUID, val context: GroupAuthContext, val code: GroupJoinCode, val match: GroupBootstrapMatch) : GroupSessionEffect
    class Send(val channel: UUID, val build: (Long) -> GroupControl, val terminal: Boolean = false) : GroupSessionEffect
    class CloseChannel(val channel: UUID) : GroupSessionEffect
    class AdmitChannel(val channel: UUID) : GroupSessionEffect
    class OpenMedia(val lease: GroupMediaLease, val offerer: Boolean) : GroupSessionEffect
    class CloseMedia(val lease: GroupMediaLease) : GroupSessionEffect
    class MediaSignal(val lease: GroupMediaLease, val message: GroupMessage) : GroupSessionEffect
    class Mute(val value: Boolean) : GroupSessionEffect
    class Block(val peerId: String, val value: Boolean) : GroupSessionEffect
    class Stop(val flushTerminal: Boolean) : GroupSessionEffect
    class Publish(val snapshot: GroupSessionSnapshot) : GroupSessionEffect
}

/** Group-mode component of the product orchestrator. Service executes effects, never reduces room state. */
internal class GroupSessionOrchestrator(
    private val endpoint: GroupAuthEndpoint,
    private val nickname: String,
    private val nowMs: () -> Long,
    private val assertWriter: () -> Unit,
    private val effects: (GroupSessionEffect) -> Unit,
    private val diagnostics: GroupDiagnostics = GroupDiagnostics {}
) {
    private data class Pending(val context: GroupAuthContext, val at: Long)
    private data class PendingChannelSwitch(
        val target: GroupChannel,
        val baseRevision: Long,
        val deadlineAtMs: Long
    )
    private class MemberChannel(val id: UUID, val lease: GroupMemberLease, var ingress: GroupHostIngress)
    private var phase = GroupPhase.IDLE
    private var operation: UUID? = null
    private var controlAttempt: UUID? = null
    private var networkAttempt: UUID? = null
    private var networkInFlight = false
    private var participation = GroupParticipation()
    private var hostRoom: GroupRoom? = null
    private var roster: GroupRoster? = null
    private var local: GroupMemberLease? = null
    private var code: GroupJoinCode? = null
    private var selected: GroupBootstrapMatch? = null
    private var matches = emptyList<GroupBootstrapMatch>()
    private val names = mutableMapOf<String, String>()
    private val pending = mutableMapOf<UUID, Pending>()
    private val channels = mutableMapOf<String, MemberChannel>()
    private var clientChannel: UUID? = null
    private var clientIngress: GroupClientIngress? = null
    private var publication = 0L
    private var networkReady = false
    private var audioAvailable = false
    private var nextRetry = 0L
    private var recoveryDeadline: Long? = null
    private var recoveryStartedAtMs: Long? = null
    private var roomStartedAtMs = 0L
    private var lastVoiceReadyChannel: GroupChannel? = null
    private var pendingSwitch: PendingChannelSwitch? = null
    private var terminalReason: GroupTerminalReason? = null
    private var mediaCloseBlocked = false
    private var message = ""
    private val media = mutableMapOf<String, GroupMediaLease>()
    private val evidenceSent = mutableSetOf<GroupLinkLease>()
    private val restartAt = mutableMapOf<GroupLinkLease, Long>()
    private val queue = ArrayDeque<GroupSessionEvent>()
    private var dispatching = false
    private var output = mutableListOf<GroupSessionEffect>()
    @Volatile var snapshot = snapshot()
        private set

    fun dispatch(event: GroupSessionEvent) {
        assertWriter()
        check(queue.size < 256) { "Group writer queue overflow" }
        queue.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (queue.isNotEmpty()) {
                output = mutableListOf()
                handle(queue.removeFirst())
                syncMedia()
                snapshot = snapshot()
                observeVoiceReady(snapshot)
                output.add(0, GroupSessionEffect.Publish(snapshot))
                for (effect in output.toList()) {
                    if (mediaCloseBlocked && (effect is GroupSessionEffect.OpenMedia || effect is GroupSessionEffect.MediaSignal)) {
                        if (effect is GroupSessionEffect.OpenMedia && media[effect.lease.peer.deviceId] == effect.lease) {
                            media.remove(effect.lease.peer.deviceId)
                        }
                        continue
                    }
                    try { effects(effect) }
                    catch (_: Exception) {
                        effectFailed(effect)
                    }
                }
            }
        } finally { dispatching = false }
    }
    private fun effectFailed(effect: GroupSessionEffect) {
        if (effect is GroupSessionEffect.CloseMedia) {
            mediaCloseBlocked = true
            operation?.let { queue.addFirst(GroupSessionEvent.Failed(it, "关闭旧频道语音失败，已停止对讲以防止串台")) }
            return
        }
        val event = when (effect) {
            is GroupSessionEffect.OpenMedia -> GroupSessionEvent.MediaFailed(effect.lease)
            is GroupSessionEffect.MediaSignal -> GroupSessionEvent.MediaFailed(effect.lease)
            is GroupSessionEffect.Send -> {
                runCatching { effects(GroupSessionEffect.CloseChannel(effect.channel)) }
                GroupSessionEvent.Closed(effect.channel)
            }
            is GroupSessionEffect.AdmitChannel -> {
                runCatching { effects(GroupSessionEffect.CloseChannel(effect.channel)) }
                GroupSessionEvent.Closed(effect.channel)
            }
            is GroupSessionEffect.CloseChannel -> GroupSessionEvent.Closed(effect.channel)
            is GroupSessionEffect.ConnectControl -> GroupSessionEvent.ControlFailed(effect.attempt)
            is GroupSessionEffect.StartHost -> GroupSessionEvent.NetworkFailed(effect.operation, "创建网络失败")
            is GroupSessionEffect.RecoverHost -> GroupSessionEvent.NetworkFailed(effect.operation, "正在等待网络恢复")
            is GroupSessionEffect.JoinNetwork -> GroupSessionEvent.NetworkFailed(effect.operation, "正在等待网络恢复")
            is GroupSessionEffect.Publish, is GroupSessionEffect.Stop -> null
            else -> operation?.let { GroupSessionEvent.Failed(it, "连接失败，正在恢复") }
        }
        event?.let { queue.addLast(it) }
    }
    private fun reconnectHost() {
        beginRecovery()
        networkReady = false
        networkAttempt = null; networkInFlight = false
        nextRetry = nowMs() + 3_000
        if (phase == GroupPhase.RECONNECTING) return
        pending.keys.toList().forEach { output += GroupSessionEffect.CloseChannel(it) }
        pending.clear()
        channels.values.toList().forEach {
            reduce(GroupEvent.Lost(it.lease))
            output += GroupSessionEffect.CloseChannel(it.id)
        }
        channels.clear()
        evidenceSent.clear(); restartAt.clear()
        phase = GroupPhase.RECONNECTING; nextRetry = nowMs() + 3_000
        message = "离线网络已断开，正在恢复原房间"
        diagnostics.recovery(hostRoom?.key, "host", "started")
    }
    private fun snapshot() = GroupSessionSnapshot(phase, operation, networkAttempt, hostRoom ?: roster, local,
        participation, code, hostRoom != null, message, matches.toList(), names.toMap(), hostRoom?.removedDeviceIds.orEmpty(),
        pendingSwitch != null, pendingSwitch?.target, terminalReason)
    private fun observeVoiceReady(currentSnapshot: GroupSessionSnapshot) {
        val view = currentSnapshot.view
        if (view != null && currentSnapshot.voiceReady) {
            if (lastVoiceReadyChannel != currentSnapshot.currentChannel) {
                lastVoiceReadyChannel = currentSnapshot.currentChannel
                diagnostics.voiceReady(view.key, currentSnapshot.currentChannel, nowMs() - roomStartedAtMs)
            }
        } else {
            lastVoiceReadyChannel = null
        }
    }
    private fun handle(event: GroupSessionEvent) {
        if (recoveryDeadline?.let { nowMs() >= it } == true) {
            val elapsed = recoveryStartedAtMs?.let { nowMs() - it }
            diagnostics.recovery((hostRoom ?: roster)?.key ?: selected?.descriptor?.room,
                if (hostRoom != null) "host" else "member", "expired", elapsed)
            finish("恢复已超过 60 秒，房间已结束，请重新创建或加入", false, GroupTerminalReason.RECOVERY_EXPIRED)
            return
        }
        when (event) {
            GroupSessionEvent.Create -> if (phase == GroupPhase.IDLE) {
                terminalReason = null
                mediaCloseBlocked = false
                operation = UUID.randomUUID(); code = GroupJoinCode.generate()
                networkAttempt = operation; networkInFlight = true
                roomStartedAtMs = nowMs()
                val room = GroupRoom.create(endpoint.deviceId, endpoint.runtimeId, nowMs(), checkNotNull(code))
                hostRoom = room; local = room.members.single().lease
                participation = participation.begin(room.key); names[endpoint.deviceId] = nickname
                phase = GroupPhase.CREATING; message = "正在创建离线房间"
                diagnostics.create(room.key, "started")
                output += GroupSessionEffect.StartHost(operation!!, GroupDescriptor(room.key, endpoint, nickname), room.joinCode)
            }
            is GroupSessionEvent.Search -> if (phase == GroupPhase.IDLE) {
                terminalReason = null
                mediaCloseBlocked = false
                operation = UUID.randomUUID(); code = event.code; phase = GroupPhase.SEARCHING
                message = "正在查找并验证附近房间"
                output += GroupSessionEffect.Search(operation!!, endpoint, event.code)
            }
            is GroupSessionEvent.SearchProgress -> if (event.operation == operation && phase == GroupPhase.SEARCHING) {
                message = "正在验证附近房间 ${event.index}/${event.total}"
            }
            is GroupSessionEvent.Found -> if (event.operation == operation && phase == GroupPhase.SEARCHING) {
                matches = event.matches.distinctBy { it.descriptor.room }.take(16)
                if (matches.isEmpty()) finish("没有验证通过的房间，请检查口令后重试", false, GroupTerminalReason.FAILED)
                else if (matches.size == 1) select(matches.single())
                else { phase = GroupPhase.SELECTING; message = "多个房间使用此口令，请选择房间" }
            }
            is GroupSessionEvent.Select -> if (phase == GroupPhase.SELECTING) matches.singleOrNull { it.descriptor.room == event.room }?.let(::select)
            is GroupSessionEvent.HostReady -> if (event.operation == networkAttempt && networkInFlight && hostRoom != null) {
                val recovered = recoveryStartedAtMs != null
                val recoveryElapsed = recoveryStartedAtMs?.let { nowMs() - it }
                recoveryDeadline = null
                recoveryStartedAtMs = null
                networkInFlight = false
                networkReady = true; phase = GroupPhase.IN_ROOM; message = "房间已创建，等待成员加入"
                if (recovered) diagnostics.recovery(hostRoom?.key, "host", "recovered", recoveryElapsed)
                else diagnostics.create(hostRoom!!.key, "ready")
            }
            is GroupSessionEvent.NetworkReady -> if (event.operation == networkAttempt && networkInFlight && selected != null && phase != GroupPhase.IDLE) {
                networkInFlight = false
                networkReady = true; connectControl()
            }
            is GroupSessionEvent.NetworkLost -> if (event.operation == networkAttempt) {
                if (hostRoom != null) reconnectHost()
                else { networkAttempt = null; networkInFlight = false; networkReady = false; reconnect("Wi-Fi 已断开，正在重连") }
            }
            is GroupSessionEvent.NetworkFailed -> if (event.attempt == networkAttempt) {
                networkAttempt = null; networkInFlight = false; networkReady = false
                if (hostRoom != null && phase == GroupPhase.CREATING) finish(event.message, false, GroupTerminalReason.FAILED)
                else if (hostRoom != null) { reconnectHost(); message = event.message }
                else reconnect(event.message)
            }
            is GroupSessionEvent.NetworkRefreshed -> if (event.attempt == networkAttempt && networkInFlight &&
                event.match.descriptor.room == selected?.descriptor?.room && event.match.descriptor.host == selected?.descriptor?.host) {
                selected = event.match
            }
            is GroupSessionEvent.Authenticated -> authenticated(event)
            is GroupSessionEvent.Control -> control(event.channel, event.message)
            is GroupSessionEvent.Closed -> closed(event.channel)
            is GroupSessionEvent.ControlFailed -> if (event.attempt == controlAttempt && selected != null) reconnect("连接中断，正在重连原房间")
            is GroupSessionEvent.Failed -> if (event.operation == operation) {
                if (hostRoom != null && channels.isNotEmpty()) {
                    local?.let { reduce(GroupEvent.End(it)) }
                    channels.values.forEach { send(it.id, GroupControl.Terminated(GroupTermination.HOST_ENDED), true) }
                    finish(event.message, true, GroupTerminalReason.FAILED)
                } else {
                    finish(event.message, false, GroupTerminalReason.FAILED)
                }
            }
            is GroupSessionEvent.AudioAvailable -> if (audioAvailable != event.available) {
                audioAvailable = event.available
                if (!event.available) evidenceSent.clear()
                local?.let { lease ->
                    if (hostRoom != null) { reduce(GroupEvent.AudioAvailability(lease, event.available)); publishRoom() }
                    else sendToHost(GroupMessage.AudioAvailable(event.available))
                }
            }
            is GroupSessionEvent.Evidence -> {
                val evidence = event.evidence
                if (current(evidence.lease) && audioAvailable && evidence.sufficient(nowMs()) && evidenceSent.add(evidence.lease.link)) {
                    if (hostRoom != null) { reduce(GroupEvent.ConfirmLink(evidence.lease.local, evidence.lease.link)); publishRoom() }
                    else sendToHost(GroupMessage.LinkConfirmed(evidence.lease.link))
                }
            }
            is GroupSessionEvent.Outgoing -> if (current(event.lease) && messageLink(event.message) == event.lease.link) {
                sendMedia(event.lease, event.message)
            }
            is GroupSessionEvent.MediaFailed -> if (current(event.lease)) restartAt.getOrPut(event.lease.link) { nowMs() + 3_000 }
            is GroupSessionEvent.Mute -> participation.token?.let {
                participation = participation.muteSelf(it, event.value); output += GroupSessionEffect.Mute(event.value)
            }
            is GroupSessionEvent.Block -> participation.token?.let {
                participation = participation.block(it, event.peerId, event.value); output += GroupSessionEffect.Block(event.peerId, event.value)
            }
            is GroupSessionEvent.Remove -> if (hostRoom != null && event.peerId != endpoint.deviceId) {
                val before = hostRoom!!
                val removedMember = before.members.singleOrNull { it.lease.deviceId == event.peerId }
                val affectedPartner = privatePartnerOf(before, removedMember)
                val affectedInvitePeers = before.privateInvites.filter { it.involves(event.peerId) }
                if (reduce(GroupEvent.Remove(checkNotNull(local), event.peerId)).result == GroupResult.OK) {
                    channels.remove(event.peerId)?.let { channel -> send(channel.id, GroupControl.Terminated(GroupTermination.REMOVED), true) }
                    publishRoom()
                    notifyPrivateEnded(affectedPartner, event.peerId)
                    affectedInvitePeers.forEach { invite ->
                        val other = if (invite.callerId == event.peerId) invite.calleeId else invite.callerId
                        notifyNotice(other, GroupNoticeCode.PRIVATE_CANCELLED, event.peerId)
                    }
                }
            }
            is GroupSessionEvent.Unblock -> if (hostRoom != null) { reduce(GroupEvent.Unblock(checkNotNull(local), event.peerId)); publishRoom() }
            is GroupSessionEvent.SetRole -> handleSetRole(event)
            is GroupSessionEvent.SetRiderIncludesFormation -> handleSetRiderIncludesFormation(event)
            is GroupSessionEvent.SwitchChannel -> handleSwitchChannel(event)
            is GroupSessionEvent.RequestPrivate -> handleRequestPrivate(event)
            is GroupSessionEvent.AcceptPrivate -> handleAcceptPrivate(event)
            is GroupSessionEvent.DeclinePrivate -> handleDeclinePrivate(event)
            is GroupSessionEvent.CancelPrivate -> handleCancelPrivate(event)
            GroupSessionEvent.EndPrivate -> handleEndPrivate()
            GroupSessionEvent.Tick -> tick()
            GroupSessionEvent.Leave -> {
                if (hostRoom != null) {
                    reduce(GroupEvent.End(checkNotNull(local)))
                    channels.values.forEach { send(it.id, GroupControl.Terminated(GroupTermination.HOST_ENDED), true) }
                }
                else sendToHost(GroupMessage.Leave, terminal = true)
                finish(if (hostRoom != null) "房间已结束" else "已离开房间", true, GroupTerminalReason.USER_LEFT)
            }
            is GroupSessionEvent.ApproveJoin,
            is GroupSessionEvent.RejectJoin,
            is GroupSessionEvent.TransferOwner,
            is GroupSessionEvent.SetRequireApproval,
            GroupSessionEvent.RotateInvite,
            GroupSessionEvent.EndRoom,
            is GroupSessionEvent.SwitchTransport -> Unit
        }
    }
    private fun handleSetRole(event: GroupSessionEvent.SetRole) {
        val room = hostRoom ?: return
        val self = local ?: return
        val beforeRider = room.members.filter { it.channel == GroupChannel.Rider }.map { it.lease.deviceId }.toSet()
        val transition = reduce(GroupEvent.SetRole(self, event.peerId, event.role))
        if (transition.result != GroupResult.OK) return
        val updated = checkNotNull(hostRoom)
        diagnostics.roleChanged(updated.key, role = event.role)
        message = "已将 ${names[event.peerId] ?: "成员"} 设为${event.role.displayLabel()}"
        publishRoom()
        beforeRider.forEach { id ->
            if (updated.members.singleOrNull { it.lease.deviceId == id }?.channel == GroupChannel.Public) {
                notifyNotice(id, GroupNoticeCode.RIDER_REVOKED)
                diagnostics.channelSwitch(updated.key, GroupChannel.Rider, GroupChannel.Public, "revoked")
            }
        }
    }
    private fun handleSetRiderIncludesFormation(event: GroupSessionEvent.SetRiderIncludesFormation) {
        val room = hostRoom ?: return
        val self = local ?: return
        val beforeRider = room.members.filter { it.channel == GroupChannel.Rider }.map { it.lease.deviceId }.toSet()
        val transition = reduce(GroupEvent.SetRiderIncludesFormation(self, event.included))
        if (transition.result != GroupResult.OK) return
        val updated = checkNotNull(hostRoom)
        diagnostics.roleChanged(updated.key, includesFormation = event.included)
        message = if (event.included) "领队频道已包含头车与尾车" else "领队频道已仅限群主与骑行官"
        publishRoom()
        beforeRider.forEach { id ->
            if (updated.members.singleOrNull { it.lease.deviceId == id }?.channel == GroupChannel.Public) {
                notifyNotice(id, GroupNoticeCode.RIDER_REVOKED)
                diagnostics.channelSwitch(updated.key, GroupChannel.Rider, GroupChannel.Public, "revoked")
            }
        }
    }
    private fun handleSwitchChannel(event: GroupSessionEvent.SwitchChannel) {
        if (phase != GroupPhase.IN_ROOM || pendingSwitch != null) return
        val self = local ?: return
        val view = hostRoom ?: roster ?: return
        val selfMember = view.members.singleOrNull { it.lease == self && it.status == GroupMemberStatus.ADMITTED } ?: return
        if (event.channel is GroupChannel.Private) return
        if (event.channel == GroupChannel.Rider && !view.canEnterRider(selfMember.role)) {
            message = "无权进入领队频道"
            diagnostics.channelSwitch(view.key, selfMember.channel, event.channel, "forbidden")
            return
        }
        if (selfMember.channel == event.channel) return
        val oldChannel = selfMember.channel
        if (hostRoom != null) {
            val partner = privatePartnerOf(hostRoom!!, selfMember)
            val transition = reduce(GroupEvent.SwitchChannel(self, event.channel))
            if (transition.result == GroupResult.OK) {
                if (oldChannel is GroupChannel.Private) diagnostics.privateCall(view.key, oldChannel.sessionId, "ended")
                diagnostics.channelSwitch(view.key, oldChannel, event.channel, "ok")
                message = "已切换到${event.channel.displayLabel()}"
                publishRoom()
                if (oldChannel is GroupChannel.Private) notifyPrivateEnded(partner, self.deviceId)
            } else {
                message = "无权进入${event.channel.displayLabel()}"
                diagnostics.channelSwitch(view.key, oldChannel, event.channel, "rejected")
            }
        } else {
            pendingSwitch = PendingChannelSwitch(event.channel, selfMember.channelRevision, nowMs() + CHANNEL_SWITCH_TIMEOUT_MS)
            message = "正在切换到${event.channel.displayLabel()}"
            diagnostics.channelSwitch(view.key, oldChannel, event.channel, "requested")
            sendToHost(GroupMessage.SwitchChannel(event.channel))
        }
    }
    private fun handleRequestPrivate(event: GroupSessionEvent.RequestPrivate) {
        if (phase != GroupPhase.IN_ROOM || pendingSwitch != null) return
        val self = local ?: return
        val view = hostRoom ?: roster ?: return
        val selfMember = view.members.singleOrNull { it.lease == self && it.status == GroupMemberStatus.ADMITTED } ?: return
        if (event.peerId == self.deviceId) return
        if (selfMember.channel is GroupChannel.Private || view.privateInvites.any { it.involves(self.deviceId) }) {
            message = "当前已在私聊或呼叫中"
            return
        }
        val target = view.members.singleOrNull { it.lease.deviceId == event.peerId && it.status == GroupMemberStatus.ADMITTED }
        if (target == null) {
            message = "对方暂不在线，无法发起私聊"
            return
        }
        if (target.channel is GroupChannel.Private || view.privateInvites.any { it.involves(event.peerId) }) {
            message = "对方正在私聊或呼叫中"
            return
        }
        val inviteId = UUID.randomUUID().toString()
        if (hostRoom != null) {
            val transition = reduce(GroupEvent.RequestPrivate(self, event.peerId, inviteId))
            if (transition.result == GroupResult.OK) {
                message = "正在呼叫 ${names[event.peerId] ?: "成员"} 私聊"
                diagnostics.privateCall(view.key, inviteId, "requested")
                publishRoom()
            } else {
                message = if (transition.result == GroupResult.BUSY) "对方正在私聊或呼叫中" else "无法发起私聊"
            }
        } else {
            message = "正在呼叫 ${names[event.peerId] ?: "成员"} 私聊"
            diagnostics.privateCall(view.key, inviteId, "requested")
            sendToHost(GroupMessage.RequestPrivate(event.peerId, inviteId))
        }
    }
    private fun handleAcceptPrivate(event: GroupSessionEvent.AcceptPrivate) {
        if (phase != GroupPhase.IN_ROOM || pendingSwitch != null) return
        val self = local ?: return
        val view = hostRoom ?: roster ?: return
        val selfMember = view.members.singleOrNull { it.lease == self && it.status == GroupMemberStatus.ADMITTED } ?: return
        val invite = view.privateInvites.singleOrNull { it.inviteId == event.inviteId && it.calleeId == self.deviceId } ?: return
        if (hostRoom != null) {
            val transition = reduce(GroupEvent.AcceptPrivate(self, event.inviteId))
            if (transition.result == GroupResult.OK) {
                message = "已与 ${names[invite.callerId] ?: "成员"} 建立 1:1 私聊"
                diagnostics.privateCall(view.key, event.inviteId, "accepted")
                diagnostics.channelSwitch(view.key, selfMember.channel, GroupChannel.Private(event.inviteId), "ok")
                publishRoom()
            } else {
                message = "私聊呼叫已失效"
                publishRoom()
            }
        } else {
            val target = GroupChannel.Private(event.inviteId)
            pendingSwitch = PendingChannelSwitch(target, selfMember.channelRevision, nowMs() + CHANNEL_SWITCH_TIMEOUT_MS)
            message = "正在建立 1:1 私聊"
            diagnostics.privateCall(view.key, event.inviteId, "accept_requested")
            sendToHost(GroupMessage.AcceptPrivate(event.inviteId))
        }
    }
    private fun handleDeclinePrivate(event: GroupSessionEvent.DeclinePrivate) {
        if (phase != GroupPhase.IN_ROOM) return
        val self = local ?: return
        val view = hostRoom ?: roster ?: return
        val invite = view.privateInvites.singleOrNull { it.inviteId == event.inviteId && it.calleeId == self.deviceId } ?: return
        if (hostRoom != null) {
            if (reduce(GroupEvent.DeclinePrivate(self, event.inviteId)).result == GroupResult.OK) {
                message = "已拒绝私聊呼叫"
                diagnostics.privateCall(view.key, event.inviteId, "declined")
                publishRoom()
                notifyNotice(invite.callerId, GroupNoticeCode.PRIVATE_DECLINED, self.deviceId)
            }
        } else {
            message = "已拒绝私聊呼叫"
            diagnostics.privateCall(view.key, event.inviteId, "declined")
            sendToHost(GroupMessage.DeclinePrivate(event.inviteId))
        }
    }
    private fun handleCancelPrivate(event: GroupSessionEvent.CancelPrivate) {
        if (phase != GroupPhase.IN_ROOM) return
        val self = local ?: return
        val view = hostRoom ?: roster ?: return
        val invite = view.privateInvites.singleOrNull { it.inviteId == event.inviteId && it.callerId == self.deviceId } ?: return
        if (hostRoom != null) {
            if (reduce(GroupEvent.CancelPrivate(self, event.inviteId)).result == GroupResult.OK) {
                message = "已取消私聊呼叫"
                diagnostics.privateCall(view.key, event.inviteId, "cancelled")
                publishRoom()
                notifyNotice(invite.calleeId, GroupNoticeCode.PRIVATE_CANCELLED, self.deviceId)
            }
        } else {
            message = "已取消私聊呼叫"
            diagnostics.privateCall(view.key, event.inviteId, "cancelled")
            sendToHost(GroupMessage.CancelPrivate(event.inviteId))
        }
    }
    private fun handleEndPrivate() {
        if (phase != GroupPhase.IN_ROOM || pendingSwitch != null) return
        val self = local ?: return
        val view = hostRoom ?: roster ?: return
        val selfMember = view.members.singleOrNull { it.lease == self && it.status == GroupMemberStatus.ADMITTED } ?: return
        val privateChannel = selfMember.channel as? GroupChannel.Private ?: return
        if (hostRoom != null) {
            val partner = privatePartnerOf(hostRoom!!, selfMember)
            if (reduce(GroupEvent.EndPrivate(self)).result == GroupResult.OK) {
                message = "已结束私聊，返回公共频道"
                diagnostics.privateCall(view.key, privateChannel.sessionId, "ended")
                diagnostics.channelSwitch(view.key, privateChannel, GroupChannel.Public, "ok")
                publishRoom()
                notifyPrivateEnded(partner, self.deviceId)
            }
        } else {
            pendingSwitch = PendingChannelSwitch(GroupChannel.Public, selfMember.channelRevision, nowMs() + CHANNEL_SWITCH_TIMEOUT_MS)
            message = "正在结束私聊并返回公共频道"
            diagnostics.privateCall(view.key, privateChannel.sessionId, "end_requested")
            sendToHost(GroupMessage.EndPrivate)
        }
    }
    private fun select(match: GroupBootstrapMatch) {
        roomStartedAtMs = nowMs()
        selected = match; matches = emptyList(); participation = participation.begin(match.descriptor.room)
        networkAttempt = operation; networkInFlight = true
        phase = GroupPhase.JOINING; message = "正在加入离线 Wi-Fi，请完成系统确认"
        output += GroupSessionEffect.JoinNetwork(checkNotNull(operation), match)
    }
    private fun connectControl() {
        if (clientChannel != null || controlAttempt != null) return
        val match = selected ?: return
        val attempt = UUID.randomUUID(); controlAttempt = attempt
        phase = GroupPhase.JOINING; message = "正在验证并加入房间"
        val context = GroupAuthContext(match.descriptor.room, match.descriptor.host, endpoint, UUID.randomUUID().toString())
        output += GroupSessionEffect.ConnectControl(checkNotNull(operation), attempt, context, checkNotNull(code), match)
    }
    private fun authenticated(event: GroupSessionEvent.Authenticated) {
        val currentTime = nowMs()
        if (event.atMs > currentTime || currentTime - event.atMs >= 10_000) { output += GroupSessionEffect.CloseChannel(event.channel); return }
        val room = hostRoom
        if (room != null) {
            if (event.operation != operation || event.context.room != room.key || event.context.host != endpoint || pending.size >= 4) {
                output += GroupSessionEffect.CloseChannel(event.channel); return
            }
            pending[event.channel] = Pending(event.context, event.atMs)
        } else {
            val match = selected
            if (event.operation != controlAttempt || match == null || clientChannel != null || event.context.room != match.descriptor.room ||
                event.context.host != match.descriptor.host || event.context.client != endpoint) {
                output += GroupSessionEffect.CloseChannel(event.channel); return
            }
            clientChannel = event.channel
            send(event.channel, GroupControl.Join(nickname))
        }
    }
    private fun control(id: UUID, control: GroupControl) {
        if (hostRoom != null) {
            if (control is GroupControl.Join) { admit(id, control.nickname); return }
            val channel = channels.values.singleOrNull { it.id == id } ?: return
            if (control !is GroupControl.Signal) { output += GroupSessionEffect.CloseChannel(id); return }
            val accepted = channel.ingress.accept(control.frame, hostRoom!!, participation) ?: return
            channel.ingress = accepted.next
            val frame = accepted.frame
            if (accepted.needsForwarding) { channels[frame.recipient.deviceId]?.let { send(it.id, control) }; return }
            when (val payload = frame.message) {
                GroupMessage.Leave -> {
                    val before = hostRoom!!
                    val leavingMember = before.members.singleOrNull { it.lease == channel.lease }
                    val partner = privatePartnerOf(before, leavingMember)
                    val invitePeers = before.privateInvites.filter { it.involves(channel.lease.deviceId) }
                    channels.remove(channel.lease.deviceId); reduce(GroupEvent.Leave(channel.lease))
                    output += GroupSessionEffect.CloseChannel(id); publishRoom()
                    notifyPrivateEnded(partner, channel.lease.deviceId)
                    invitePeers.forEach { invite ->
                        val other = if (invite.callerId == channel.lease.deviceId) invite.calleeId else invite.callerId
                        notifyNotice(other, GroupNoticeCode.PRIVATE_CANCELLED, channel.lease.deviceId)
                    }
                    names.remove(channel.lease.deviceId)
                }
                is GroupMessage.AudioAvailable -> {
                    if (hostRoom!!.members.single { it.lease == channel.lease }.audioAvailable != payload.available) {
                        reduce(GroupEvent.AudioAvailability(channel.lease, payload.available)); publishRoom()
                    }
                }
                is GroupMessage.LinkConfirmed -> if (hostRoom!!.members.single { it.lease == channel.lease }.audioAvailable) {
                    reduce(GroupEvent.ConfirmLink(channel.lease, payload.link)); publishRoom()
                }
                is GroupMessage.RestartLink -> restartAt.getOrPut(payload.link) { nowMs() + 3_000 }
                is GroupMessage.SwitchChannel -> {
                    val before = hostRoom!!
                    val actorMember = before.members.single { it.lease == channel.lease }
                    val oldChannel = actorMember.channel
                    val partner = privatePartnerOf(before, actorMember)
                    val transition = reduce(GroupEvent.SwitchChannel(channel.lease, payload.channel))
                    if (transition.result == GroupResult.OK) {
                        if (oldChannel is GroupChannel.Private) diagnostics.privateCall(before.key, oldChannel.sessionId, "ended")
                        diagnostics.channelSwitch(before.key, oldChannel, payload.channel, "ok")
                        publishRoom()
                        if (oldChannel is GroupChannel.Private) notifyPrivateEnded(partner, channel.lease.deviceId)
                    } else {
                        val code = if (transition.result == GroupResult.FORBIDDEN) GroupNoticeCode.CHANNEL_FORBIDDEN else GroupNoticeCode.PRIVATE_STALE
                        send(id, GroupControl.Notice(code))
                        diagnostics.channelSwitch(before.key, oldChannel, payload.channel, "rejected")
                    }
                }
                is GroupMessage.RequestPrivate -> {
                    val room = hostRoom!!
                    val transition = reduce(GroupEvent.RequestPrivate(channel.lease, payload.targetDeviceId, payload.inviteId))
                    if (transition.result == GroupResult.OK) {
                        if (payload.targetDeviceId == endpoint.deviceId) {
                            message = "${names[channel.lease.deviceId] ?: "成员"} 邀请你私聊"
                        }
                        diagnostics.privateCall(room.key, payload.inviteId, "requested")
                        publishRoom()
                    } else {
                        val code = if (transition.result == GroupResult.BUSY) GroupNoticeCode.PRIVATE_BUSY else GroupNoticeCode.PRIVATE_STALE
                        send(id, GroupControl.Notice(code, payload.targetDeviceId))
                    }
                }
                is GroupMessage.AcceptPrivate -> {
                    val before = hostRoom!!
                    val invite = before.privateInvites.singleOrNull { it.inviteId == payload.inviteId }
                    val transition = reduce(GroupEvent.AcceptPrivate(channel.lease, payload.inviteId))
                    if (transition.result == GroupResult.OK) {
                        if (invite?.callerId == endpoint.deviceId) {
                            message = "已与 ${names[channel.lease.deviceId] ?: "成员"} 建立 1:1 私聊"
                        }
                        diagnostics.privateCall(before.key, payload.inviteId, "accepted")
                        publishRoom()
                    } else {
                        val code = if (transition.result == GroupResult.BUSY) GroupNoticeCode.PRIVATE_BUSY else GroupNoticeCode.PRIVATE_STALE
                        publishRoom()
                        send(id, GroupControl.Notice(code, invite?.callerId))
                    }
                }
                is GroupMessage.DeclinePrivate -> {
                    val before = hostRoom!!
                    val invite = before.privateInvites.singleOrNull { it.inviteId == payload.inviteId }
                    if (reduce(GroupEvent.DeclinePrivate(channel.lease, payload.inviteId)).result == GroupResult.OK && invite != null) {
                        diagnostics.privateCall(before.key, payload.inviteId, "declined")
                        publishRoom()
                        notifyNotice(invite.callerId, GroupNoticeCode.PRIVATE_DECLINED, channel.lease.deviceId)
                    }
                }
                is GroupMessage.CancelPrivate -> {
                    val before = hostRoom!!
                    val invite = before.privateInvites.singleOrNull { it.inviteId == payload.inviteId }
                    if (reduce(GroupEvent.CancelPrivate(channel.lease, payload.inviteId)).result == GroupResult.OK && invite != null) {
                        diagnostics.privateCall(before.key, payload.inviteId, "cancelled")
                        publishRoom()
                        notifyNotice(invite.calleeId, GroupNoticeCode.PRIVATE_CANCELLED, channel.lease.deviceId)
                    }
                }
                GroupMessage.EndPrivate -> {
                    val before = hostRoom!!
                    val actorMember = before.members.singleOrNull { it.lease == channel.lease }
                    val oldPrivate = actorMember?.channel as? GroupChannel.Private
                    val partner = privatePartnerOf(before, actorMember)
                    if (reduce(GroupEvent.EndPrivate(channel.lease)).result == GroupResult.OK) {
                        if (oldPrivate != null) diagnostics.privateCall(before.key, oldPrivate.sessionId, "ended")
                        publishRoom()
                        notifyPrivateEnded(partner, channel.lease.deviceId)
                    } else {
                        send(id, GroupControl.Notice(GroupNoticeCode.PRIVATE_STALE))
                    }
                }
                else -> receiveMedia(frame)
            }
        } else if (id == clientChannel) {
            when (control) {
                is GroupControl.Welcome -> {
                    if (local != null || control.member.deviceId != endpoint.deviceId || control.member.runtimeId != endpoint.runtimeId ||
                        control.roster.members.none { it.lease == control.member && it.status == GroupMemberStatus.ADMITTED }) {
                        output += GroupSessionEffect.CloseChannel(id); return
                    }
                    val recovered = recoveryStartedAtMs != null
                    val recoveryElapsed = recoveryStartedAtMs?.let { nowMs() - it }
                    if (!installRoster(control.roster, control.member)) { output += GroupSessionEffect.CloseChannel(id); return }
                    local = control.member; clientIngress = GroupClientIngress(checkNotNull(participation.token), control.member)
                    recoveryDeadline = null
                    recoveryStartedAtMs = null
                    pendingSwitch = null
                    phase = GroupPhase.IN_ROOM; message = "已加入房间"; controlAttempt = null
                    if (recovered) diagnostics.recovery(control.roster.key, "member", "recovered", recoveryElapsed)
                    else diagnostics.join(control.roster.key, "member", control.roster.members.count { it.status != GroupMemberStatus.WAITING })
                    output += GroupSessionEffect.AdmitChannel(id)
                    sendToHost(GroupMessage.AudioAvailable(audioAvailable))
                }
                is GroupControl.Roster -> if (local != null) {
                    if (!installRoster(control.value, local) || control.value.members.none { it.lease == local && it.status == GroupMemberStatus.ADMITTED })
                        output += GroupSessionEffect.CloseChannel(id)
                }
                is GroupControl.Signal -> {
                    val next = clientIngress?.accept(control.frame, roster ?: return, participation) ?: return
                    clientIngress = next; receiveMedia(control.frame)
                }
                is GroupControl.Notice -> handleClientNotice(control)
                is GroupControl.Refused -> if (local == null) {
                    if (control.result == GroupResult.FULL || control.result == GroupResult.BUSY) {
                        finish(if (control.result == GroupResult.FULL) "房间已满，最多 4 人，请稍后重新加入"
                            else "房间正忙，请稍后重新加入", false, GroupTerminalReason.REFUSED)
                    } else finish(if (control.result == GroupResult.REMOVED) "你已被移出该房间" else "房间已不可用", false,
                        if (control.result == GroupResult.REMOVED) GroupTerminalReason.REMOVED else GroupTerminalReason.REFUSED)
                }
                is GroupControl.Terminated -> finish(if (control.reason == GroupTermination.REMOVED) "你已被移出该房间" else "房主已结束房间", false,
                    if (control.reason == GroupTermination.REMOVED) GroupTerminalReason.REMOVED else GroupTerminalReason.HOST_ENDED)
                else -> output += GroupSessionEffect.CloseChannel(id)
            }
        }
    }
    private fun handleClientNotice(notice: GroupControl.Notice) {
        if (notice.code in setOf(
            GroupNoticeCode.CHANNEL_FORBIDDEN,
            GroupNoticeCode.PRIVATE_BUSY,
            GroupNoticeCode.PRIVATE_STALE,
            GroupNoticeCode.RIDER_REVOKED,
            GroupNoticeCode.PRIVATE_ENDED
        )) {
            pendingSwitch = null
        }
        val peerName = notice.peerId?.let { names[it] } ?: "对方"
        message = when (notice.code) {
            GroupNoticeCode.CHANNEL_FORBIDDEN -> "无权进入领队频道"
            GroupNoticeCode.PRIVATE_BUSY -> "$peerName 正在私聊或呼叫中"
            GroupNoticeCode.PRIVATE_DECLINED -> "$peerName 已拒绝私聊"
            GroupNoticeCode.PRIVATE_CANCELLED -> "$peerName 已取消私聊呼叫"
            GroupNoticeCode.PRIVATE_TIMEOUT -> "私聊呼叫已超时"
            GroupNoticeCode.PRIVATE_ENDED -> "私聊已结束，已返回公共频道"
            GroupNoticeCode.PRIVATE_STALE -> "私聊操作已失效，请重试"
            GroupNoticeCode.RIDER_REVOKED -> "领队权限已变更，已返回公共频道"
        }
    }
    private fun privatePartnerOf(room: GroupRoomView, member: GroupMember?): String? {
        val privateChannel = member?.channel as? GroupChannel.Private ?: return null
        return room.members.singleOrNull {
            it.status != GroupMemberStatus.WAITING && it.channel == privateChannel && it.lease.deviceId != member.lease.deviceId
        }?.lease?.deviceId
    }
    private fun notifyPrivateEnded(partnerId: String?, actorId: String?) {
        if (partnerId == null) return
        notifyNotice(partnerId, GroupNoticeCode.PRIVATE_ENDED, actorId)
    }
    private fun notifyNotice(targetId: String, code: GroupNoticeCode, peerId: String? = null) {
        if (targetId == endpoint.deviceId) {
            handleClientNotice(GroupControl.Notice(code, peerId))
        } else {
            channels[targetId]?.let { send(it.id, GroupControl.Notice(code, peerId)) }
        }
    }
    private fun admit(id: UUID, nickname: String) {
        val auth = pending.remove(id) ?: run { output += GroupSessionEffect.CloseChannel(id); return }
        val room = hostRoom ?: return
        val now = nowMs()
        if (now - auth.at >= 10_000 || auth.context.room != room.key) { output += GroupSessionEffect.CloseChannel(id); return }
        trimWaiting()
        val proof = GroupJoinProof(auth.context.client.deviceId, auth.context.client.runtimeId, auth.context.handshakeId, true, true, true, now + 10_000)
        val transition = reduce(GroupEvent.Join(proof))
        if (transition.result != GroupResult.OK) {
            val reason = transition.result.takeIf { it in setOf(GroupResult.FULL, GroupResult.REMOVED, GroupResult.BUSY, GroupResult.ENDED, GroupResult.FORBIDDEN) } ?: GroupResult.BUSY
            send(id, GroupControl.Refused(reason), true); return
        }
        val lease = checkNotNull(transition.member)
        channels.remove(lease.deviceId)?.let { output += GroupSessionEffect.CloseChannel(it.id) }
        channels[lease.deviceId] = MemberChannel(id, lease, GroupHostIngress(checkNotNull(participation.token), checkNotNull(local), lease))
        names[lease.deviceId] = nickname.ifBlank { "骑士" }
        ensureLinks()
        val view = rosterSnapshot()
        send(id, GroupControl.Welcome(lease, view))
        output += GroupSessionEffect.AdmitChannel(id)
        channels.values.filter { it.id != id }.forEach { send(it.id, GroupControl.Roster(view)) }
        diagnostics.join(room.key, "host_admitted", hostRoom!!.occupiedSeats)
        message = "成员已加入"
    }
    private fun installRoster(value: GroupRoster, selfLease: GroupMemberLease?): Boolean {
        val match = selected ?: return false
        if (value.key != match.descriptor.room || value.hostId != match.descriptor.host.deviceId || value.ended ||
            value.publication <= (roster?.publication ?: 0)) return false
        val previous = roster
        val oldSelf = selfLease?.let { lease -> previous?.members?.singleOrNull { it.lease == lease } }
        val oldIncoming = selfLease?.let { lease -> previous?.privateInvites?.singleOrNull { it.calleeId == lease.deviceId } }
        roster = value; names.clear(); names.putAll(value.names)
        val newSelf = selfLease?.let { lease -> value.members.singleOrNull { it.lease == lease } }
        val activeSwitch = pendingSwitch
        if (activeSwitch != null && newSelf != null &&
            (newSelf.channelRevision > activeSwitch.baseRevision || newSelf.channel == activeSwitch.target)) {
            pendingSwitch = null
            diagnostics.channelSwitch(value.key, oldSelf?.channel, newSelf.channel, "ok")
            message = when (newSelf.channel) {
                GroupChannel.Public -> "已切换到公共频道"
                GroupChannel.Rider -> "已切换到领队频道"
                is GroupChannel.Private -> {
                    val partner = privatePartnerOf(value, newSelf)
                    "已与 ${names[partner] ?: "成员"} 建立 1:1 私聊"
                }
            }
        } else if (oldSelf != null && newSelf != null && oldSelf.channel != newSelf.channel) {
            pendingSwitch = null
            message = when (newSelf.channel) {
                GroupChannel.Public -> if (oldSelf.channel is GroupChannel.Private) "私聊已结束，已返回公共频道" else "已返回公共频道"
                GroupChannel.Rider -> "已切换到领队频道"
                is GroupChannel.Private -> {
                    val partner = privatePartnerOf(value, newSelf)
                    "已与 ${names[partner] ?: "成员"} 建立 1:1 私聊"
                }
            }
        }
        val newIncoming = selfLease?.let { lease -> value.privateInvites.singleOrNull { it.calleeId == lease.deviceId } }
        if (newIncoming != null && newIncoming.inviteId != oldIncoming?.inviteId) {
            message = "${names[newIncoming.callerId] ?: "成员"} 邀请你私聊"
        }
        return true
    }
    private fun closed(id: UUID) {
        pending.remove(id)
        val member = channels.values.singleOrNull { it.id == id }
        if (member != null) {
            val before = hostRoom
            val cancelledInvites = before?.privateInvites?.filter { it.involves(member.lease.deviceId) }.orEmpty()
            channels.remove(member.lease.deviceId); reduce(GroupEvent.Lost(member.lease)); publishRoom()
            cancelledInvites.forEach { invite ->
                val other = if (invite.callerId == member.lease.deviceId) invite.calleeId else invite.callerId
                notifyNotice(other, GroupNoticeCode.PRIVATE_CANCELLED, member.lease.deviceId)
            }
        } else if (clientChannel == id) reconnect("连接中断，正在重连原房间")
    }
    private fun reconnect(reason: String) {
        if (selected == null || operation == null) return
        beginRecovery()
        clientChannel?.let { output += GroupSessionEffect.CloseChannel(it) }
        clientChannel = null; clientIngress = null; controlAttempt = null; local = null
        roster = null; pendingSwitch = null; evidenceSent.clear(); restartAt.clear()
        nextRetry = nowMs() + 3_000
        phase = GroupPhase.RECONNECTING; message = reason
        diagnostics.recovery(selected?.descriptor?.room, "member", "started")
    }
    private fun beginRecovery() {
        if (recoveryDeadline == null) {
            recoveryStartedAtMs = nowMs()
            recoveryDeadline = nowMs() + GroupRoom.RESERVATION_MS
        }
    }
    private fun tick() {
        pendingSwitch?.let { active ->
            if (nowMs() >= active.deadlineAtMs) {
                (hostRoom ?: roster)?.let { diagnostics.channelSwitch(it.key, null, active.target, "timeout") }
                pendingSwitch = null
                message = "频道切换超时，请重试"
            }
        }
        if (hostRoom != null) {
            val before = hostRoom!!
            val old = before.rosterRevision
            reduce(GroupEvent.Tick)
            val after = hostRoom!!
            if (after.rosterRevision != old) {
                publishRoom()
                val afterInviteIds = after.privateInvites.map { it.inviteId }.toSet()
                before.privateInvites.filter { it.inviteId !in afterInviteIds }.forEach { expired ->
                    notifyNotice(expired.callerId, GroupNoticeCode.PRIVATE_TIMEOUT, expired.calleeId)
                    notifyNotice(expired.calleeId, GroupNoticeCode.PRIVATE_TIMEOUT, expired.callerId)
                    diagnostics.privateCall(after.key, expired.inviteId, "timeout")
                }
                before.members.filter { it.status == GroupMemberStatus.ADMITTED && it.channel is GroupChannel.Private }.forEach { previous ->
                    val updated = after.members.singleOrNull { it.lease.deviceId == previous.lease.deviceId }
                    if (updated != null && updated.status == GroupMemberStatus.ADMITTED && updated.channel == GroupChannel.Public) {
                        notifyNotice(updated.lease.deviceId, GroupNoticeCode.PRIVATE_ENDED)
                        diagnostics.privateCall(after.key, (previous.channel as GroupChannel.Private).sessionId, "expired_ended")
                    }
                }
            }
            pending.filterValues { nowMs() - it.at >= 10_000 }.keys.toList().forEach { pending.remove(it); output += GroupSessionEffect.CloseChannel(it) }
        }
        if ((phase == GroupPhase.WAITING || phase == GroupPhase.RECONNECTING) && !networkInFlight && nowMs() >= nextRetry) {
            nextRetry = nowMs() + 15_000
            if (hostRoom != null) {
                networkAttempt = UUID.randomUUID(); networkInFlight = true
                output += GroupSessionEffect.RecoverHost(networkAttempt!!, GroupDescriptor(hostRoom!!.key, endpoint, nickname), checkNotNull(code))
            } else if (networkReady) connectControl() else selected?.let {
                networkAttempt = UUID.randomUUID(); networkInFlight = true
                phase = GroupPhase.JOINING
                output += GroupSessionEffect.JoinNetwork(networkAttempt!!, it, checkNotNull(code))
            }
        }
        restartAt.filterValues { it <= nowMs() }.keys.toList().forEach { link ->
            restartAt.remove(link)
            val room = hostRoom
            if (room != null && room.links.any { it.lease == link }) {
                val a = room.members.single { it.lease.deviceId == link.pair.first }.lease
                val b = room.members.single { it.lease.deviceId == link.pair.second }.lease
                reduce(GroupEvent.RestartLink(a, b, link)); publishRoom()
            } else if (roster?.links?.any { it.lease == link } == true) sendToHost(GroupMessage.RestartLink(link))
        }
    }
    private fun reduce(event: GroupEvent): GroupTransition {
        val room = checkNotNull(hostRoom)
        return room.reduce(room.key, event, nowMs()).also { hostRoom = it.room }
    }
    private fun trimWaiting() {
        val room = hostRoom ?: return
        if (room.members.size >= 16) room.members.firstOrNull { it.status == GroupMemberStatus.WAITING }?.let { reduce(GroupEvent.Leave(it.lease)); names.remove(it.lease.deviceId) }
    }
    private fun ensureLinks() {
        val room = hostRoom ?: return
        room.activePairs.forEach { pair ->
            if (hostRoom!!.links.none { it.lease.pair == pair }) {
                val a = hostRoom!!.members.single { it.lease.deviceId == pair.first }.lease
                val b = hostRoom!!.members.single { it.lease.deviceId == pair.second }.lease
                reduce(GroupEvent.RestartLink(a, b))
            }
        }
    }
    private fun rosterSnapshot(): GroupRoster {
        val room = checkNotNull(hostRoom)
        check(publication < Long.MAX_VALUE)
        return GroupRoster(room.key, room.hostId, ++publication, room.ended, room.members, room.links,
            names.filterKeys { key -> room.members.any { it.lease.deviceId == key } },
            room.riderIncludesFormation, room.privateInvites)
    }
    private fun publishRoom() {
        if (hostRoom == null) return
        ensureLinks()
        val snapshot = rosterSnapshot()
        channels.values.forEach { send(it.id, GroupControl.Roster(snapshot)) }
    }
    private fun current(lease: GroupMediaLease) = local == lease.local && (hostRoom ?: roster)?.let { lease.matches(it, participation) } == true
    private fun syncMedia() {
        val view = hostRoom ?: roster
        val self = local
        val token = participation.token
        val selfMember = if (view != null && self != null) view.members.singleOrNull {
            it.lease == self && it.status == GroupMemberStatus.ADMITTED
        } else null
        val desired = if (view != null && selfMember != null && token != null) view.members
            .filter {
                it.status == GroupMemberStatus.ADMITTED &&
                    it.lease.deviceId != selfMember.lease.deviceId &&
                    it.channel == selfMember.channel
            }
            .mapNotNull { peer -> view.links.singleOrNull { it.lease.pair == GroupPair.of(selfMember.lease.deviceId, peer.lease.deviceId) }
                ?.let { GroupMediaLease(token, selfMember.lease, peer.lease, it.lease) } }.associateBy { it.peer.deviceId } else emptyMap()
        media.values.toList().filter { desired[it.peer.deviceId] != it }.forEach { output += GroupSessionEffect.CloseMedia(it); media.remove(it.peer.deviceId) }
        evidenceSent.retainAll(desired.values.map { it.link }.toSet())
        val validRestartLinks = if (hostRoom != null) {
            view?.links?.map { it.lease }?.toSet().orEmpty()
        } else {
            desired.values.map { it.link }.toSet()
        }
        restartAt.keys.retainAll(validRestartLinks)
        if (!mediaCloseBlocked) {
            desired.values.filter { media[it.peer.deviceId] != it }.forEach {
                media[it.peer.deviceId] = it
                output += GroupSessionEffect.OpenMedia(it, it.local.deviceId < it.peer.deviceId)
                if (participation.isBlocked(it.peer.deviceId)) output += GroupSessionEffect.Block(it.peer.deviceId, true)
            }
        }
    }
    private fun messageLink(message: GroupMessage) = when (message) {
        is GroupMessage.Offer -> message.link
        is GroupMessage.Answer -> message.link
        is GroupMessage.Candidate -> message.link
        else -> null
    }
    private fun sendMedia(lease: GroupMediaLease, payload: GroupMessage) {
        if (payload is GroupMessage.Offer && lease.local.deviceId > lease.peer.deviceId ||
            payload is GroupMessage.Answer && lease.local.deviceId < lease.peer.deviceId) return
        val channel = if (hostRoom != null) channels[lease.peer.deviceId]?.id else clientChannel
        channel ?: return
        output += GroupSessionEffect.Send(channel, { seq -> GroupControl.Signal(GroupFrame(lease.intent.room, lease.local, lease.peer, seq, payload)) })
    }
    private fun receiveMedia(frame: GroupFrame) {
        val link = messageLink(frame.message) ?: return
        val self = local ?: return
        val token = participation.token ?: return
        val lease = GroupMediaLease(token, self, frame.sender, link)
        if (current(lease)) output += GroupSessionEffect.MediaSignal(lease, frame.message)
    }
    private fun sendToHost(payload: GroupMessage, terminal: Boolean = false) {
        val id = clientChannel ?: return
        val self = local ?: return
        val view = roster ?: return
        val host = view.members.singleOrNull { it.lease.deviceId == view.hostId }?.lease ?: return
        output += GroupSessionEffect.Send(id, { seq -> GroupControl.Signal(GroupFrame(view.key, self, host, seq, payload)) }, terminal)
    }
    private fun send(id: UUID, control: GroupControl, terminal: Boolean = false) { output += GroupSessionEffect.Send(id, { control }, terminal) }
    private fun finish(reason: String, flush: Boolean, terminal: GroupTerminalReason) {
        diagnostics.end((hostRoom ?: roster)?.key ?: selected?.descriptor?.room, terminal)
        participation.token?.let { participation = participation.stop(it) }
        phase = GroupPhase.IDLE; operation = null; controlAttempt = null
        networkAttempt = null; networkInFlight = false
        hostRoom = null; roster = null; local = null; code = null; selected = null; matches = emptyList()
        pending.clear(); channels.clear(); clientChannel = null; clientIngress = null; names.clear()
        networkReady = false; audioAvailable = false; publication = 0; evidenceSent.clear(); restartAt.clear()
        recoveryDeadline = null; recoveryStartedAtMs = null; pendingSwitch = null; lastVoiceReadyChannel = null
        terminalReason = terminal
        message = reason
        output += GroupSessionEffect.Stop(flush)
    }
    companion object {
        const val CHANNEL_SWITCH_TIMEOUT_MS = 5_000L
    }
}
