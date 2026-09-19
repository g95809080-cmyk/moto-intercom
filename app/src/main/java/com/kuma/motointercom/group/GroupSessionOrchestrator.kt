package com.kuma.motointercom.group

import java.util.UUID

internal enum class GroupPhase { IDLE, CREATING, SEARCHING, SELECTING, JOINING, IN_ROOM, RECONNECTING, WAITING }
internal class GroupSessionSnapshot(
    val phase: GroupPhase,
    val operation: UUID?,
    val view: GroupRoomView?,
    val local: GroupMemberLease?,
    val participation: GroupParticipation,
    val code: GroupJoinCode?,
    val host: Boolean,
    val message: String,
    val matches: List<GroupBootstrapMatch>,
    val names: Map<String, String>,
    val removed: Set<String>
) {
    fun allows(lease: GroupMediaLease) = local == lease.local && view?.let { lease.matches(it, participation) } == true
    val voiceReady: Boolean get() {
        val room = view ?: return false
        val active = room.members.filter { it.status == GroupMemberStatus.ADMITTED }
        if (active.size < 2 || room.members.any { it.status == GroupMemberStatus.RESERVED } || active.any { !it.audioAvailable }) return false
        val expected = active.size * (active.size - 1) / 2
        return room.links.size == expected && room.links.all { it.confirmedBy.containsAll(listOf(it.lease.pair.first, it.lease.pair.second)) }
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
    class HostReady(val operation: UUID) : GroupSessionEvent
    class NetworkReady(val operation: UUID) : GroupSessionEvent
    class NetworkLost(val operation: UUID) : GroupSessionEvent
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
    data object Tick : GroupSessionEvent
    data object Leave : GroupSessionEvent
}

internal sealed interface GroupSessionEffect {
    class StartHost(val operation: UUID, val descriptor: GroupDescriptor, val code: GroupJoinCode) : GroupSessionEffect
    class Search(val operation: UUID, val endpoint: GroupAuthEndpoint, val code: GroupJoinCode) : GroupSessionEffect
    class JoinNetwork(val operation: UUID, val match: GroupBootstrapMatch) : GroupSessionEffect
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
    private val effects: (GroupSessionEffect) -> Unit
) {
    private data class Pending(val context: GroupAuthContext, val at: Long)
    private class MemberChannel(val id: UUID, val lease: GroupMemberLease, var ingress: GroupHostIngress)
    private var phase = GroupPhase.IDLE
    private var operation: UUID? = null
    private var controlAttempt: UUID? = null
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
                output += GroupSessionEffect.Publish(snapshot)
                for (effect in output.toList()) {
                    try { effects(effect) }
                    catch (_: Exception) {
                        operation?.let { queue.addFirst(GroupSessionEvent.Failed(it, "房间运行失败，请重试")) }
                        break
                    }
                }
            }
        } finally { dispatching = false }
    }
    private fun snapshot() = GroupSessionSnapshot(phase, operation, hostRoom ?: roster, local,
        participation, code, hostRoom != null, message, matches.toList(), names.toMap(), hostRoom?.removedDeviceIds.orEmpty())
    private fun handle(event: GroupSessionEvent) {
        when (event) {
            GroupSessionEvent.Create -> if (phase == GroupPhase.IDLE) {
                operation = UUID.randomUUID(); code = GroupJoinCode.generate()
                val room = GroupRoom.create(endpoint.deviceId, endpoint.runtimeId, nowMs(), checkNotNull(code))
                hostRoom = room; local = room.members.single().lease
                participation = participation.begin(room.key); names[endpoint.deviceId] = nickname
                phase = GroupPhase.CREATING; message = "正在创建离线房间"
                output += GroupSessionEffect.StartHost(operation!!, GroupDescriptor(room.key, endpoint, nickname), room.joinCode)
            }
            is GroupSessionEvent.Search -> if (phase == GroupPhase.IDLE) {
                operation = UUID.randomUUID(); code = event.code; phase = GroupPhase.SEARCHING
                message = "正在查找并验证附近房间"
                output += GroupSessionEffect.Search(operation!!, endpoint, event.code)
            }
            is GroupSessionEvent.Found -> if (event.operation == operation && phase == GroupPhase.SEARCHING) {
                matches = event.matches.distinctBy { it.descriptor.room }.take(16)
                if (matches.isEmpty()) finish("没有验证通过的房间，请检查口令后重试", false)
                else if (matches.size == 1) select(matches.single())
                else { phase = GroupPhase.SELECTING; message = "多个房间使用此口令，请选择房间" }
            }
            is GroupSessionEvent.Select -> if (phase == GroupPhase.SELECTING) matches.singleOrNull { it.descriptor.room == event.room }?.let(::select)
            is GroupSessionEvent.HostReady -> if (event.operation == operation && hostRoom != null) {
                phase = GroupPhase.IN_ROOM; message = "房间已创建，等待成员加入"
            }
            is GroupSessionEvent.NetworkReady -> if (event.operation == operation && selected != null && phase != GroupPhase.IDLE) {
                networkReady = true; connectControl()
            }
            is GroupSessionEvent.NetworkLost -> if (event.operation == operation) {
                if (hostRoom != null) finish("离线网络已断开", false)
                else { networkReady = false; reconnect("Wi-Fi 已断开，正在重连") }
            }
            is GroupSessionEvent.Authenticated -> authenticated(event)
            is GroupSessionEvent.Control -> control(event.channel, event.message)
            is GroupSessionEvent.Closed -> closed(event.channel)
            is GroupSessionEvent.ControlFailed -> if (event.attempt == controlAttempt && selected != null) reconnect("连接中断，正在重连原房间")
            is GroupSessionEvent.Failed -> if (event.operation == operation) finish(event.message, false)
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
            is GroupSessionEvent.MediaFailed -> if (current(event.lease)) restartAt.putIfAbsent(event.lease.link, nowMs() + 3_000)
            is GroupSessionEvent.Mute -> participation.token?.let {
                participation = participation.muteSelf(it, event.value); output += GroupSessionEffect.Mute(event.value)
            }
            is GroupSessionEvent.Block -> participation.token?.let {
                participation = participation.block(it, event.peerId, event.value); output += GroupSessionEffect.Block(event.peerId, event.value)
            }
            is GroupSessionEvent.Remove -> if (hostRoom != null && event.peerId != endpoint.deviceId) {
                reduce(GroupEvent.Remove(checkNotNull(local), event.peerId))
                channels.remove(event.peerId)?.let { channel -> send(channel.id, GroupControl.Terminated(GroupTermination.REMOVED), true) }
                names.remove(event.peerId); publishRoom()
            }
            is GroupSessionEvent.Unblock -> if (hostRoom != null) { reduce(GroupEvent.Unblock(checkNotNull(local), event.peerId)); publishRoom() }
            GroupSessionEvent.Tick -> tick()
            GroupSessionEvent.Leave -> {
                if (hostRoom != null) channels.values.forEach { send(it.id, GroupControl.Terminated(GroupTermination.HOST_ENDED), true) }
                else sendToHost(GroupMessage.Leave, terminal = true)
                finish(if (hostRoom != null) "房间已结束" else "已离开房间", true)
            }
        }
    }
    private fun select(match: GroupBootstrapMatch) {
        selected = match; matches = emptyList(); participation = participation.begin(match.descriptor.room)
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
                    channels.remove(channel.lease.deviceId); reduce(GroupEvent.Leave(channel.lease))
                    names.remove(channel.lease.deviceId); output += GroupSessionEffect.CloseChannel(id); publishRoom()
                }
                is GroupMessage.AudioAvailable -> {
                    if (hostRoom!!.members.single { it.lease == channel.lease }.audioAvailable != payload.available) {
                        reduce(GroupEvent.AudioAvailability(channel.lease, payload.available)); publishRoom()
                    }
                }
                is GroupMessage.LinkConfirmed -> if (hostRoom!!.members.single { it.lease == channel.lease }.audioAvailable) {
                    reduce(GroupEvent.ConfirmLink(channel.lease, payload.link)); publishRoom()
                }
                is GroupMessage.RestartLink -> restartAt.putIfAbsent(payload.link, nowMs() + 3_000)
                else -> receiveMedia(frame)
            }
        } else if (id == clientChannel) {
            when (control) {
                is GroupControl.Welcome -> {
                    if (local != null || control.member.deviceId != endpoint.deviceId || control.member.runtimeId != endpoint.runtimeId ||
                        control.roster.members.none { it.lease == control.member && it.status == GroupMemberStatus.ADMITTED }) {
                        output += GroupSessionEffect.CloseChannel(id); return
                    }
                    if (!installRoster(control.roster)) { output += GroupSessionEffect.CloseChannel(id); return }
                    local = control.member; clientIngress = GroupClientIngress(checkNotNull(participation.token), control.member)
                    phase = GroupPhase.IN_ROOM; message = "已加入房间，语音待确认"; controlAttempt = null
                    output += GroupSessionEffect.AdmitChannel(id)
                    sendToHost(GroupMessage.AudioAvailable(audioAvailable))
                }
                is GroupControl.Roster -> if (local != null) {
                    if (!installRoster(control.value) || control.value.members.none { it.lease == local && it.status == GroupMemberStatus.ADMITTED })
                        output += GroupSessionEffect.CloseChannel(id)
                }
                is GroupControl.Signal -> {
                    val next = clientIngress?.accept(control.frame, roster ?: return, participation) ?: return
                    clientIngress = next; receiveMedia(control.frame)
                }
                is GroupControl.Refused -> if (local == null) {
                    if (control.result == GroupResult.FULL || control.result == GroupResult.BUSY) {
                        reconnect("房间已满或繁忙，等待空位", waiting = true)
                    } else finish(if (control.result == GroupResult.REMOVED) "你已被移出该房间" else "房间已不可用", false)
                }
                is GroupControl.Terminated -> finish(if (control.reason == GroupTermination.REMOVED) "你已被移出该房间" else "房主已结束房间", false)
                else -> output += GroupSessionEffect.CloseChannel(id)
            }
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
        message = "成员已加入，语音待确认"
    }
    private fun installRoster(value: GroupRoster): Boolean {
        val match = selected ?: return false
        if (value.key != match.descriptor.room || value.hostId != match.descriptor.host.deviceId || value.ended ||
            value.publication <= (roster?.publication ?: 0)) return false
        roster = value; names.clear(); names.putAll(value.names)
        return true
    }
    private fun closed(id: UUID) {
        pending.remove(id)
        val member = channels.values.singleOrNull { it.id == id }
        if (member != null) {
            channels.remove(member.lease.deviceId); reduce(GroupEvent.Lost(member.lease)); publishRoom()
        } else if (clientChannel == id) reconnect("连接中断，正在重连原房间")
    }
    private fun reconnect(reason: String, waiting: Boolean = false) {
        if (selected == null || operation == null) return
        clientChannel?.let { output += GroupSessionEffect.CloseChannel(it) }
        clientChannel = null; clientIngress = null; controlAttempt = null; local = null
        roster = null; evidenceSent.clear(); restartAt.clear()
        nextRetry = nowMs() + if (waiting) 15_000 else 3_000
        phase = if (waiting) GroupPhase.WAITING else GroupPhase.RECONNECTING; message = reason
    }
    private fun tick() {
        if (hostRoom != null) {
            val old = hostRoom!!.rosterRevision
            reduce(GroupEvent.Tick)
            if (hostRoom!!.rosterRevision != old) publishRoom()
            pending.filterValues { nowMs() - it.at >= 10_000 }.keys.toList().forEach { pending.remove(it); output += GroupSessionEffect.CloseChannel(it) }
        }
        if ((phase == GroupPhase.WAITING || phase == GroupPhase.RECONNECTING) && nowMs() >= nextRetry) {
            nextRetry = nowMs() + 15_000
            if (networkReady) connectControl() else selected?.let {
                phase = GroupPhase.JOINING
                output += GroupSessionEffect.JoinNetwork(checkNotNull(operation), it)
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
            names.filterKeys { key -> room.members.any { it.lease.deviceId == key } })
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
        val desired = if (view != null && self != null && token != null) view.members
            .filter { it.status == GroupMemberStatus.ADMITTED && it.lease.deviceId != self.deviceId }
            .mapNotNull { peer -> view.links.singleOrNull { it.lease.pair == GroupPair.of(self.deviceId, peer.lease.deviceId) }
                ?.let { GroupMediaLease(token, self, peer.lease, it.lease) } }.associateBy { it.peer.deviceId } else emptyMap()
        media.values.toList().filter { desired[it.peer.deviceId] != it }.forEach { output += GroupSessionEffect.CloseMedia(it); media.remove(it.peer.deviceId) }
        evidenceSent.retainAll(desired.values.map { it.link }.toSet())
        desired.values.filter { media[it.peer.deviceId] != it }.forEach {
            media[it.peer.deviceId] = it
            output += GroupSessionEffect.OpenMedia(it, it.local.deviceId < it.peer.deviceId)
            if (participation.isBlocked(it.peer.deviceId)) output += GroupSessionEffect.Block(it.peer.deviceId, true)
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
    private fun finish(reason: String, flush: Boolean) {
        participation.token?.let { participation = participation.stop(it) }
        phase = GroupPhase.IDLE; operation = null; controlAttempt = null
        hostRoom = null; roster = null; local = null; code = null; selected = null; matches = emptyList()
        pending.clear(); channels.clear(); clientChannel = null; clientIngress = null; names.clear()
        networkReady = false; audioAvailable = false; publication = 0; evidenceSent.clear(); restartAt.clear()
        message = reason
        output += GroupSessionEffect.Stop(flush)
    }
}
