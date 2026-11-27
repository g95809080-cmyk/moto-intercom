package com.kuma.motointercom.group

import com.kuma.motointercom.group.network.GroupWifiCredentials
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupSessionOrchestratorTest {
    private fun id(n: Long) = UUID(0, n).toString()
    private var now = 0L
    private val clients = mutableMapOf<UUID, Node>()
    private lateinit var host: Node
    private val sequence = mutableMapOf<Pair<Node, UUID>, Long>()
    private inner class Node(val endpoint: GroupAuthEndpoint, val isHost: Boolean = false) {
        val seen = mutableListOf<GroupSessionEffect>()
        val diagnostics = mutableListOf<String>()
        val writer = GroupSessionOrchestrator(
            endpoint,
            "骑士${endpoint.deviceId.takeLast(2)}",
            { now },
            {},
            ::effect,
            GroupDiagnostics { diagnostics += it }
        )
        var channel: UUID? = null
        var fail: (GroupSessionEffect) -> Boolean = { false }
        var dropOutgoingControl = false
        private fun effect(effect: GroupSessionEffect) {
            seen += effect
            if (fail(effect)) throw IllegalStateException("injected failure")
            if (effect is GroupSessionEffect.Send) {
                if (dropOutgoingControl) return
                val key = this to effect.channel
                val seq = (sequence[key] ?: 0) + 1
                sequence[key] = seq
                val wire = GroupControlCodec.encode(effect.build(seq), now)
                val message = GroupControlCodec.decode(wire, now)
                if (isHost) clients[effect.channel]?.writer?.dispatch(GroupSessionEvent.Control(effect.channel, message))
                else host.writer.dispatch(GroupSessionEvent.Control(effect.channel, message))
            }
        }
        fun leases(): List<GroupMediaLease> {
            val snap = writer.snapshot
            val self = snap.local ?: return emptyList()
            return snap.view!!.links.filter { self.deviceId in listOf(it.lease.pair.first, it.lease.pair.second) }.map { link ->
                val peer = snap.view.members.single { it.lease.deviceId != self.deviceId && it.lease.deviceId in listOf(link.lease.pair.first, link.lease.pair.second) }.lease
                GroupMediaLease(snap.participation.token!!, self, peer, link.lease)
            }
        }
        fun evidence(lease: GroupMediaLease, io: Boolean = true, route: Boolean = true) {
            writer.dispatch(GroupSessionEvent.Evidence(GroupVoiceEvidence(lease, true, true, io, route, 0, 0, 100, 100, now)))
        }
    }
    private fun host(): Node {
        host = Node(GroupAuthEndpoint(id(1), id(101)), true)
        host.writer.dispatch(GroupSessionEvent.Create)
        host.writer.dispatch(GroupSessionEvent.HostReady(host.writer.snapshot.operation!!))
        return host
    }
    private fun join(n: Long): Node {
        val node = Node(GroupAuthEndpoint(id(n), id(n + 100)))
        node.writer.dispatch(GroupSessionEvent.Search(host.writer.snapshot.code!!))
        val match = GroupBootstrapMatch(GroupDescriptor(host.writer.snapshot.view!!.key, host.endpoint, "房主"),
            GroupNetworkDescriptor(GroupWifiCredentials("DIRECT-test", "12345678"), "192.168.49.1", 8899))
        node.writer.dispatch(GroupSessionEvent.Found(node.writer.snapshot.operation!!, listOf(match)))
        node.writer.dispatch(GroupSessionEvent.NetworkReady(node.writer.snapshot.operation!!))
        connect(node)
        return node
    }
    private fun connect(node: Node) {
        val effect = node.seen.filterIsInstance<GroupSessionEffect.ConnectControl>().last()
        val channel = UUID.randomUUID(); node.channel = channel; clients[channel] = node
        host.writer.dispatch(GroupSessionEvent.Authenticated(host.writer.snapshot.operation!!, channel, effect.context, now))
        node.writer.dispatch(GroupSessionEvent.Authenticated(effect.attempt, channel, effect.context, now))
    }
    private fun room(): GroupRoom = host.writer.snapshot.view as GroupRoom

    @Test fun recoveryExpiresAtSixtySecondsAndLateAuthenticationCannotReviveClient() {
        host()
        val peer = join(2)
        val oldChannel = peer.channel!!
        host.writer.dispatch(GroupSessionEvent.Closed(oldChannel))
        peer.writer.dispatch(GroupSessionEvent.Closed(oldChannel))
        now = 3_000
        peer.writer.dispatch(GroupSessionEvent.Tick)
        val pending = peer.seen.filterIsInstance<GroupSessionEffect.ConnectControl>().last()
        peer.writer.dispatch(GroupSessionEvent.ControlFailed(pending.attempt))
        now = 59_999
        peer.writer.dispatch(GroupSessionEvent.Tick)
        assertNotEquals(GroupPhase.IDLE, peer.writer.snapshot.phase)
        now = 60_000
        peer.writer.dispatch(GroupSessionEvent.Authenticated(pending.attempt, UUID.randomUUID(), pending.context, now))
        assertEquals(GroupPhase.IDLE, peer.writer.snapshot.phase)
        assertNull(peer.writer.snapshot.code)
        assertNull(peer.writer.snapshot.participation.token)
        assertTrue(peer.seen.any { it is GroupSessionEffect.Stop })
        peer.writer.dispatch(GroupSessionEvent.NetworkReady(pending.operation))
        assertEquals(GroupPhase.IDLE, peer.writer.snapshot.phase)
    }

    @Test fun repeatedHostRecoveryFailuresDoNotExtendTheDeadline() {
        host()
        join(2)
        host.writer.dispatch(GroupSessionEvent.NetworkLost(host.writer.snapshot.networkAttempt!!))
        repeat(3) { index ->
            now = (index + 1) * 10_000L
            host.writer.dispatch(GroupSessionEvent.Tick)
            host.writer.dispatch(GroupSessionEvent.NetworkFailed(host.writer.snapshot.networkAttempt!!, "网络不可用"))
        }
        now = 60_000
        host.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupPhase.IDLE, host.writer.snapshot.phase)
        assertNull(host.writer.snapshot.view)
        assertNull(host.writer.snapshot.code)
    }

    @Test fun clientRecoveryBeforeTheDeadlineRestoresTheOriginalRoom() {
        host()
        val peer = join(2)
        val roomKey = room().key
        host.writer.dispatch(GroupSessionEvent.Closed(peer.channel!!))
        peer.writer.dispatch(GroupSessionEvent.Closed(peer.channel!!))
        now = 59_999
        peer.writer.dispatch(GroupSessionEvent.Tick)
        connect(peer)
        assertEquals(GroupPhase.IN_ROOM, peer.writer.snapshot.phase)
        assertEquals(roomKey, peer.writer.snapshot.view!!.key)
        now = 60_000
        peer.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupPhase.IN_ROOM, peer.writer.snapshot.phase)
    }

    @Test fun hostNetworkRecoveryRetainsRoomCodeIntentAndOriginalReservationDeadline() {
        host(); join(2)
        val before = host.writer.snapshot
        host.writer.dispatch(GroupSessionEvent.NetworkLost(before.operation!!))
        assertEquals(GroupPhase.RECONNECTING, host.writer.snapshot.phase)
        assertEquals(before.view!!.key, room().key)
        assertEquals(before.code, host.writer.snapshot.code)
        assertEquals(before.participation.token, host.writer.snapshot.participation.token)
        now = 3_000; host.writer.dispatch(GroupSessionEvent.Tick)
        assertTrue(host.seen.last { it !is GroupSessionEffect.Publish } is GroupSessionEffect.RecoverHost)
        host.writer.dispatch(GroupSessionEvent.NetworkFailed(host.writer.snapshot.networkAttempt!!, "cleanup pending"))
        now = 6_000; host.writer.dispatch(GroupSessionEvent.NetworkLost(before.operation))
        host.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(60_000L, room().members.single { it.lease.deviceId == id(2) }.reservedUntilMs)
        host.writer.dispatch(GroupSessionEvent.HostReady(host.writer.snapshot.networkAttempt!!))
        assertEquals(GroupPhase.IN_ROOM, host.writer.snapshot.phase)
        assertEquals(before.view.key, room().key)
    }

    @Test fun failedClientNetworkRetryKeepsOriginalRoomIntentAndRetriesAgain() {
        host(); val peer = join(2)
        val before = peer.writer.snapshot
        peer.writer.dispatch(GroupSessionEvent.NetworkLost(before.operation!!))
        now = 3_000; peer.writer.dispatch(GroupSessionEvent.Tick)
        peer.writer.dispatch(GroupSessionEvent.NetworkFailed(peer.writer.snapshot.networkAttempt!!, "network unavailable"))
        assertEquals(GroupPhase.RECONNECTING, peer.writer.snapshot.phase)
        assertEquals(before.participation.token, peer.writer.snapshot.participation.token)
        now = 6_000; peer.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(3, peer.seen.filterIsInstance<GroupSessionEffect.JoinNetwork>().size)
        assertEquals(before.code, peer.writer.snapshot.code)
    }

    @Test fun newPeerMediaFailureDoesNotEndRoomOrReplaceHealthyPairs() {
        host(); join(2); join(3)
        val healthy = room().links.map { it.lease }
        host.fail = { it is GroupSessionEffect.OpenMedia && it.lease.peer.deviceId == id(4) }
        join(4)
        assertEquals(GroupPhase.IN_ROOM, host.writer.snapshot.phase)
        assertEquals(4, room().occupiedSeats)
        now = 3_000; host.writer.dispatch(GroupSessionEvent.Tick)
        assertTrue(room().links.map { it.lease }.containsAll(healthy))
        assertTrue(host.seen.none { it is GroupSessionEffect.Stop })
    }

    @Test fun networkAttemptsRejectLateResultsAndDoNotOverlapDuringSlowCreation() {
        host(); val peer = join(2)
        listOf(host, peer).forEach { node ->
            val old = node.writer.snapshot.networkAttempt!!
            node.writer.dispatch(GroupSessionEvent.NetworkLost(old))
            now += 3_000; node.writer.dispatch(GroupSessionEvent.Tick)
            val attempt = node.writer.snapshot.networkAttempt!!
            assertNotEquals(old, attempt)
            val before = node.seen.count { it is GroupSessionEffect.RecoverHost || it is GroupSessionEffect.JoinNetwork }
            repeat(5) { now += 10_000; node.writer.dispatch(GroupSessionEvent.Tick) }
            assertEquals(before, node.seen.count { it is GroupSessionEffect.RecoverHost || it is GroupSessionEffect.JoinNetwork })
            node.writer.dispatch(GroupSessionEvent.HostReady(old))
            node.writer.dispatch(GroupSessionEvent.NetworkReady(old))
            node.writer.dispatch(GroupSessionEvent.NetworkFailed(old, "late failure"))
            node.writer.dispatch(GroupSessionEvent.NetworkLost(old))
            assertEquals(attempt, node.writer.snapshot.networkAttempt)
            assertNotEquals(GroupPhase.IN_ROOM, node.writer.snapshot.phase)
            if (node.isHost) node.writer.dispatch(GroupSessionEvent.HostReady(attempt))
            else node.writer.dispatch(GroupSessionEvent.NetworkReady(attempt))
            val current = node.writer.snapshot.phase
            node.writer.dispatch(GroupSessionEvent.NetworkLost(old))
            assertEquals(current, node.writer.snapshot.phase)
            assertEquals(attempt, node.writer.snapshot.networkAttempt)
        }
    }

    @Test fun lossDuringHostRecoveryRevokesAttemptWithoutExtendingReservedSeat() {
        host(); join(2)
        val original = host.writer.snapshot
        host.writer.dispatch(GroupSessionEvent.NetworkLost(original.networkAttempt!!))
        now = 3_000; host.writer.dispatch(GroupSessionEvent.Tick)
        val second = host.writer.snapshot.networkAttempt!!
        host.writer.dispatch(GroupSessionEvent.NetworkLost(second))
        assertNull(host.writer.snapshot.networkAttempt)
        now = 6_000; host.writer.dispatch(GroupSessionEvent.Tick)
        val third = host.writer.snapshot.networkAttempt!!
        assertNotEquals(second, third)
        assertEquals(original.view!!.key, room().key)
        assertEquals(original.code, host.writer.snapshot.code)
        assertEquals(original.participation.token, host.writer.snapshot.participation.token)
        assertEquals(60_000L, room().members.single { it.lease.deviceId == id(2) }.reservedUntilMs)
        host.writer.dispatch(GroupSessionEvent.HostReady(second))
        assertEquals(GroupPhase.RECONNECTING, host.writer.snapshot.phase)
        host.writer.dispatch(GroupSessionEvent.HostReady(third))
        assertEquals(GroupPhase.IN_ROOM, host.writer.snapshot.phase)
    }

    @Test fun oneSocketSendFailureReservesOnlyThatMemberAndPreservesHealthyPair() {
        host(); val second = join(2); join(3)
        val healthy = room().links.single { it.lease.pair == GroupPair.of(id(1), id(3)) }.lease
        host.fail = { it is GroupSessionEffect.Send && it.channel == second.channel }
        host.writer.dispatch(GroupSessionEvent.AudioAvailable(true))
        assertEquals(GroupPhase.IN_ROOM, host.writer.snapshot.phase)
        assertEquals(GroupMemberStatus.RESERVED, room().members.single { it.lease.deviceId == id(2) }.status)
        assertTrue(room().links.any { it.lease == healthy })
        assertTrue(host.seen.none { it is GroupSessionEffect.Stop })
    }

    @Test fun fourMembersCreateSixPairsButConnectedTrackWithoutIoAndRouteNeverMeansReady() {
        val nodes = listOf(host(), join(2), join(3), join(4))
        assertEquals(6, room().links.size)
        nodes.forEach { assertEquals(3, it.leases().size); it.writer.dispatch(GroupSessionEvent.AudioAvailable(true)) }
        nodes.forEach { node -> node.leases().forEach { node.evidence(it, io = false) } }
        assertFalse(host.writer.snapshot.voiceReady)
        nodes.forEach { node -> node.leases().forEach { node.evidence(it, route = false) } }
        assertFalse(host.writer.snapshot.voiceReady)
        nodes.forEach { node -> node.leases().forEach { node.evidence(it) } }
        nodes.forEach { assertTrue(it.writer.snapshot.voiceReady) }
        assertEquals(6, room().readyPairs.size)
    }
    @Test fun localAudioInterruptionLeavesOtherPairsHealthyAndPreservesMuteAndBlock() {
        val nodes = listOf(host(), join(2), join(3), join(4))
        nodes.forEach { it.writer.dispatch(GroupSessionEvent.AudioAvailable(true)) }
        nodes.forEach { node -> node.leases().forEach { node.evidence(it) } }
        val peer = nodes[1]
        peer.writer.dispatch(GroupSessionEvent.Mute(true))
        peer.writer.dispatch(GroupSessionEvent.Block(id(3), true))
        val healthy = room().links.filter { id(2) !in listOf(it.lease.pair.first, it.lease.pair.second) }
        val previous = peer.leases()
        peer.writer.dispatch(GroupSessionEvent.AudioAvailable(false))
        assertEquals(4, room().occupiedSeats)
        assertEquals(healthy, room().links.filter { id(2) !in listOf(it.lease.pair.first, it.lease.pair.second) })
        assertFalse(host.writer.snapshot.voiceReady)
        assertTrue(peer.writer.snapshot.participation.selfMuted)
        assertTrue(peer.writer.snapshot.participation.isBlocked(id(3)))
        peer.writer.dispatch(GroupSessionEvent.AudioAvailable(true))
        previous.forEach { peer.evidence(it) }
        assertFalse(host.writer.snapshot.voiceReady)
        nodes.forEach { node -> node.leases().forEach { node.evidence(it) } }
        assertTrue(host.writer.snapshot.voiceReady)
    }
    @Test fun lostSeatExpiresAtSixtySecondsAndFullClientCanRetryWithoutEviction() {
        host(); val second = join(2); join(3); join(4)
        host.writer.dispatch(GroupSessionEvent.Closed(second.channel!!))
        second.writer.dispatch(GroupSessionEvent.Closed(second.channel!!))
        val fifth = join(5)
        assertEquals(GroupPhase.IDLE, fifth.writer.snapshot.phase)
        assertTrue(fifth.writer.snapshot.message.contains("房间已满"))
        assertEquals(4, room().occupiedSeats)
        now = 59_999; host.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupMemberStatus.RESERVED, room().members.single { it.lease.deviceId == id(2) }.status)
        now = 60_000; host.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(3, room().occupiedSeats)
        fifth.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupPhase.IDLE, fifth.writer.snapshot.phase)
        val retried = join(5)
        assertEquals(GroupPhase.IN_ROOM, retried.writer.snapshot.phase)
        assertEquals(4, room().occupiedSeats)
        assertTrue(room().members.any { it.lease.deviceId == id(3) && it.status == GroupMemberStatus.ADMITTED })
    }
    @Test fun reconnectReplacesControlLeaseAndOldClosedOrLeaveCannotEvictSuccessor() {
        host(); val peer = join(2)
        val oldId = peer.channel!!
        val oldLease = peer.writer.snapshot.local!!
        val hostLease = host.writer.snapshot.local!!
        host.writer.dispatch(GroupSessionEvent.Closed(oldId)); peer.writer.dispatch(GroupSessionEvent.Closed(oldId))
        now = 3_000; peer.writer.dispatch(GroupSessionEvent.Tick); connect(peer)
        val replacement = peer.writer.snapshot.local!!
        assertEquals(oldLease.incarnation, replacement.incarnation)
        assertTrue(replacement.controlGeneration > oldLease.controlGeneration)
        host.writer.dispatch(GroupSessionEvent.Closed(oldId))
        host.writer.dispatch(GroupSessionEvent.Control(oldId, GroupControl.Signal(GroupFrame(room().key, oldLease, hostLease, 999, GroupMessage.Leave))))
        assertTrue(room().members.any { it.lease == replacement && it.status == GroupMemberStatus.ADMITTED })
    }
    @Test fun removedMemberCannotRejoinUntilHostUnblocksAndEndClearsEveryone() {
        host(); val peer = join(2); val third = join(3)
        host.writer.dispatch(GroupSessionEvent.Remove(id(2)))
        assertEquals(GroupPhase.IDLE, peer.writer.snapshot.phase)
        val blocked = join(2)
        assertEquals(GroupPhase.IDLE, blocked.writer.snapshot.phase)
        assertEquals(2, room().occupiedSeats)
        host.writer.dispatch(GroupSessionEvent.Unblock(id(2)))
        val restored = join(2)
        assertEquals(GroupPhase.IN_ROOM, restored.writer.snapshot.phase)
        host.writer.dispatch(GroupSessionEvent.Leave)
        listOf(host, third, restored).forEach {
            assertEquals(GroupPhase.IDLE, it.writer.snapshot.phase)
            assertNull(it.writer.snapshot.participation.token)
            assertNull(it.writer.snapshot.code)
        }
    }
    @Test fun ordinaryMemberCannotEndRoomOrClaimAnotherPairsVoiceEvidence() {
        host(); val b = join(2); val c = join(3)
        val lease = b.writer.snapshot.local!!
        val h = host.writer.snapshot.local!!
        val id = b.channel!!
        host.writer.dispatch(GroupSessionEvent.Control(id, GroupControl.Signal(GroupFrame(room().key, lease, h, 100, GroupMessage.End))))
        assertEquals(3, room().occupiedSeats)
        val otherPair = room().links.single { it.lease.pair == GroupPair.of(h.deviceId, c.writer.snapshot.local!!.deviceId) }.lease
        host.writer.dispatch(GroupSessionEvent.Control(id, GroupControl.Signal(GroupFrame(room().key, lease, h, 101, GroupMessage.LinkConfirmed(otherPair)))))
        assertTrue(room().links.single { it.lease == otherPair }.confirmedBy.isEmpty())
    }
    @Test fun hostRetainsRetryForRestartRequestedBetweenTwoOrdinaryMembers() {
        host(); val a = join(2); val b = join(3)
        val hostLease = host.writer.snapshot.local!!
        val link = room().links.single {
            it.lease.pair == GroupPair.of(a.writer.snapshot.local!!.deviceId, b.writer.snapshot.local!!.deviceId)
        }.lease
        host.writer.dispatch(
            GroupSessionEvent.Control(
                a.channel!!,
                GroupControl.Signal(GroupFrame(room().key, a.writer.snapshot.local!!, hostLease, 100, GroupMessage.RestartLink(link)))
            )
        )

        now = 3_000
        host.writer.dispatch(GroupSessionEvent.Tick)

        val restarted = room().links.single { it.lease.pair == link.pair }.lease
        assertEquals(link.generation + 1, restarted.generation)
    }
    @Test fun multipleMatchesRequireSelectionAndCancelledSearchCannotRevive() {
        host()
        val node = Node(GroupAuthEndpoint(id(2), id(102)))
        node.writer.dispatch(GroupSessionEvent.Search(GroupJoinCode("123456")))
        val op = node.writer.snapshot.operation!!
        fun match(n: Long) = GroupBootstrapMatch(GroupDescriptor(GroupRoomKey(id(n), id(201)), GroupAuthEndpoint(id(200), id(201)), "房主"),
            GroupNetworkDescriptor(GroupWifiCredentials("DIRECT-$n", "12345678"), "192.168.49.1", 8899))
        node.writer.dispatch(GroupSessionEvent.Found(op, listOf(match(300), match(301))))
        assertEquals(GroupPhase.SELECTING, node.writer.snapshot.phase)
        assertTrue(node.seen.none { it is GroupSessionEffect.JoinNetwork })
        node.writer.dispatch(GroupSessionEvent.Leave)
        node.writer.dispatch(GroupSessionEvent.Found(op, listOf(match(300))))
        assertEquals(GroupPhase.IDLE, node.writer.snapshot.phase)
        assertNull(node.writer.snapshot.participation.token)
        assertEquals(GroupPhase.IDLE, Node(node.endpoint).writer.snapshot.phase)
    }
    @Test fun unknownOrOldPublicationCannotGrantMembershipAndStaleAuthIsClosed() {
        host()
        val channel = UUID.randomUUID()
        val context = GroupAuthContext(room().key, host.endpoint, GroupAuthEndpoint(id(2), id(102)), id(555))
        now = 10_001
        host.writer.dispatch(GroupSessionEvent.Authenticated(host.writer.snapshot.operation!!, channel, context, 0))
        assertTrue(host.seen.any { it is GroupSessionEffect.CloseChannel && it.channel == channel })
        host.writer.dispatch(GroupSessionEvent.Control(channel, GroupControl.Join("stale")))
        assertEquals(1, room().occupiedSeats)
    }

    @Test fun fourNodeRolesFormationPolicyAndRiderSwitchUseBreakBeforeMakeAndClientLock() {
        host()
        val officer = join(2)
        val head = join(3)
        val member = join(4)

        host.writer.dispatch(GroupSessionEvent.SetRole(id(2), GroupRole.RIDING_OFFICER))
        host.writer.dispatch(GroupSessionEvent.SetRole(id(3), GroupRole.HEAD))
        assertEquals(GroupRole.RIDING_OFFICER, officer.writer.snapshot.localRole)
        assertEquals(GroupRole.HEAD, head.writer.snapshot.localRole)
        assertTrue(officer.writer.snapshot.canEnterRider)
        assertTrue(head.writer.snapshot.canEnterRider)
        assertFalse(member.writer.snapshot.canEnterRider)

        member.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
        assertEquals(GroupChannel.Public, member.writer.snapshot.currentChannel)
        assertEquals("无权进入领队频道", member.writer.snapshot.message)

        val hostIndexBefore = host.seen.size
        host.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
        officer.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
        head.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
        assertEquals(GroupChannel.Rider, host.writer.snapshot.currentChannel)
        assertEquals(GroupChannel.Rider, officer.writer.snapshot.currentChannel)
        assertEquals(GroupChannel.Rider, head.writer.snapshot.currentChannel)
        assertEquals(GroupChannel.Public, member.writer.snapshot.currentChannel)

        val hostEffects = host.seen.drop(hostIndexBefore)
        val lastCloseIndex = hostEffects.indexOfLast { it is GroupSessionEffect.CloseMedia }
        val firstOpenIndex = hostEffects.indexOfFirst { it is GroupSessionEffect.OpenMedia }
        assertTrue(lastCloseIndex >= 0 && firstOpenIndex > lastCloseIndex)
        assertTrue(host.leases().all { it.peer.deviceId != id(4) })
        assertTrue(member.leases().isEmpty())

        listOf(host, officer, head).forEach { node ->
            node.writer.dispatch(GroupSessionEvent.AudioAvailable(true))
        }
        listOf(host, officer, head).forEach { node ->
            node.leases().forEach { lease -> node.evidence(lease) }
        }
        assertTrue(host.writer.snapshot.voiceReady)
        assertTrue(officer.writer.snapshot.voiceReady)
        assertFalse(member.writer.snapshot.voiceReady)

        val headIndexBefore = head.seen.size
        host.writer.dispatch(GroupSessionEvent.SetRiderIncludesFormation(false))
        assertEquals(GroupChannel.Public, head.writer.snapshot.currentChannel)
        assertFalse(head.writer.snapshot.canEnterRider)
        assertEquals("领队权限已变更，已返回公共频道", head.writer.snapshot.message)
        val headEffects = head.seen.drop(headIndexBefore)
        val headCloseIdx = headEffects.indexOfLast { it is GroupSessionEffect.CloseMedia }
        val headOpenIdx = headEffects.indexOfFirst { it is GroupSessionEffect.OpenMedia }
        assertTrue(headCloseIdx >= 0 && headOpenIdx > headCloseIdx)
        assertEquals(setOf(id(4)), head.leases().map { it.peer.deviceId }.toSet())

        officer.dropOutgoingControl = true
        officer.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Public))
        assertTrue(officer.writer.snapshot.channelSwitching)
        assertEquals(GroupChannel.Public, officer.writer.snapshot.pendingSwitchTarget)
        val sendsAfterFirst = officer.seen.count { it is GroupSessionEffect.Send }
        officer.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
        assertEquals(sendsAfterFirst, officer.seen.count { it is GroupSessionEffect.Send })
        now += GroupSessionOrchestrator.CHANNEL_SWITCH_TIMEOUT_MS
        officer.writer.dispatch(GroupSessionEvent.Tick)
        assertFalse(officer.writer.snapshot.channelSwitching)
        assertEquals("频道切换超时，请重试", officer.writer.snapshot.message)
        officer.dropOutgoingControl = false
    }

    @Test fun privateCallFullLifecycleRecoveryNoticesRemoveAndRedactedDiagnostics() {
        host()
        val joinCode = host.writer.snapshot.code!!.digits
        val peerB = join(2)
        val peerC = join(3)
        val peerD = join(4)

        peerB.writer.dispatch(GroupSessionEvent.RequestPrivate(id(3)))
        val declinedInvite = peerC.writer.snapshot.incomingPrivateInvite!!
        peerC.writer.dispatch(GroupSessionEvent.DeclinePrivate(declinedInvite.inviteId))
        assertNull(peerB.writer.snapshot.outgoingPrivateInvite)
        assertTrue(peerB.writer.snapshot.message.contains("拒绝"))

        peerB.writer.dispatch(GroupSessionEvent.RequestPrivate(id(3)))
        val cancelledInvite = peerB.writer.snapshot.outgoingPrivateInvite!!
        peerB.writer.dispatch(GroupSessionEvent.CancelPrivate(cancelledInvite.inviteId))
        assertNull(peerC.writer.snapshot.incomingPrivateInvite)
        assertTrue(peerC.writer.snapshot.message.contains("取消"))

        peerB.writer.dispatch(GroupSessionEvent.RequestPrivate(id(3)))
        now += GroupRoom.PRIVATE_INVITE_TIMEOUT_MS
        host.writer.dispatch(GroupSessionEvent.Tick)
        assertNull(peerB.writer.snapshot.outgoingPrivateInvite)
        assertEquals("私聊呼叫已超时", peerB.writer.snapshot.message)
        assertEquals("私聊呼叫已超时", peerC.writer.snapshot.message)

        val peerBEffectsBefore = peerB.seen.size
        peerB.writer.dispatch(GroupSessionEvent.RequestPrivate(id(3)))
        peerD.writer.dispatch(GroupSessionEvent.RequestPrivate(id(3)))
        assertTrue(peerD.writer.snapshot.message.contains("私聊或呼叫中"))
        val activeInvite = peerC.writer.snapshot.incomingPrivateInvite!!
        peerC.writer.dispatch(GroupSessionEvent.AcceptPrivate(activeInvite.inviteId))
        val privateChannel = GroupChannel.Private(activeInvite.inviteId)
        assertEquals(privateChannel, peerB.writer.snapshot.currentChannel)
        assertEquals(privateChannel, peerC.writer.snapshot.currentChannel)
        assertEquals(setOf(id(3)), peerB.leases().map { it.peer.deviceId }.toSet())
        assertEquals(setOf(id(2)), peerC.leases().map { it.peer.deviceId }.toSet())
        assertEquals(setOf(id(4)), host.leases().map { it.peer.deviceId }.toSet())
        val peerBEffects = peerB.seen.drop(peerBEffectsBefore)
        val closeIdx = peerBEffects.indexOfLast { it is GroupSessionEffect.CloseMedia }
        val openIdx = peerBEffects.indexOfFirst { it is GroupSessionEffect.OpenMedia }
        assertTrue(closeIdx >= 0 && openIdx > closeIdx)

        val oldChannel = peerC.channel!!
        host.writer.dispatch(GroupSessionEvent.Closed(oldChannel))
        peerC.writer.dispatch(GroupSessionEvent.Closed(oldChannel))
        assertEquals(GroupMemberStatus.RESERVED, peerB.writer.snapshot.privatePartner?.status)
        now += 5_000
        peerC.writer.dispatch(GroupSessionEvent.Tick)
        connect(peerC)
        assertEquals(privateChannel, peerC.writer.snapshot.currentChannel)
        assertEquals(GroupMemberStatus.ADMITTED, peerB.writer.snapshot.privatePartner?.status)

        peerB.writer.dispatch(GroupSessionEvent.AudioAvailable(true))
        peerC.writer.dispatch(GroupSessionEvent.AudioAvailable(true))
        peerB.leases().forEach { peerB.evidence(it) }
        peerC.leases().forEach { peerC.evidence(it) }
        assertTrue(peerB.writer.snapshot.voiceReady)
        assertTrue(peerC.writer.snapshot.voiceReady)

        peerB.writer.dispatch(GroupSessionEvent.EndPrivate)
        assertEquals(GroupChannel.Public, peerB.writer.snapshot.currentChannel)
        assertEquals(GroupChannel.Public, peerC.writer.snapshot.currentChannel)
        assertEquals("私聊已结束，已返回公共频道", peerC.writer.snapshot.message)

        peerB.writer.dispatch(GroupSessionEvent.RequestPrivate(id(3)))
        val inviteBeforeRemove = peerC.writer.snapshot.incomingPrivateInvite!!
        peerC.writer.dispatch(GroupSessionEvent.AcceptPrivate(inviteBeforeRemove.inviteId))
        host.writer.dispatch(GroupSessionEvent.Remove(id(3)))
        assertEquals(GroupPhase.IDLE, peerC.writer.snapshot.phase)
        assertEquals(GroupTerminalReason.REMOVED, peerC.writer.snapshot.terminalReason)
        assertEquals(GroupChannel.Public, peerB.writer.snapshot.currentChannel)
        assertEquals("私聊已结束，已返回公共频道", peerB.writer.snapshot.message)

        host.writer.dispatch(GroupSessionEvent.Leave)
        assertEquals(GroupTerminalReason.USER_LEFT, host.writer.snapshot.terminalReason)
        assertEquals(GroupTerminalReason.HOST_ENDED, peerB.writer.snapshot.terminalReason)

        val allLogs = host.diagnostics + peerB.diagnostics + peerC.diagnostics + peerD.diagnostics
        listOf(
            "group_create ",
            "group_join ",
            "group_voice_ready ",
            "group_channel_switch ",
            "group_private_call ",
            "group_recovery ",
            "group_end "
        ).forEach { prefix ->
            assertTrue("Expected diagnostic prefix $prefix", allLogs.any { it.startsWith(prefix) })
        }
        assertTrue(allLogs.none { it.contains(joinCode) })
    }

    @Test fun closeMediaFailureDuringChannelSwitchBlocksSubsequentOpenMediaAndStopsFailClosed() {
        host()
        val officer = join(2)
        val member = join(3)
        host.writer.dispatch(GroupSessionEvent.SetRole(id(2), GroupRole.RIDING_OFFICER))
        officer.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))

        val hostCountBefore = host.seen.size
        host.fail = { effect -> effect is GroupSessionEffect.CloseMedia && effect.lease.peer.deviceId == id(3) }
        host.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))

        val hostEffects = host.seen.drop(hostCountBefore)
        assertTrue(hostEffects.any { it is GroupSessionEffect.CloseMedia && it.lease.peer.deviceId == id(3) })
        assertTrue(hostEffects.none { it is GroupSessionEffect.OpenMedia })
        assertTrue(hostEffects.any { it is GroupSessionEffect.Stop })
        assertEquals(GroupPhase.IDLE, host.writer.snapshot.phase)
        assertEquals(GroupTerminalReason.FAILED, host.writer.snapshot.terminalReason)
        assertEquals(GroupPhase.IDLE, officer.writer.snapshot.phase)
        assertEquals(GroupTerminalReason.HOST_ENDED, officer.writer.snapshot.terminalReason)

        host()
        val clientOfficer = join(2)
        val clientMember = join(3)
        host.writer.dispatch(GroupSessionEvent.SetRole(id(2), GroupRole.RIDING_OFFICER))
        host.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))

        val clientCountBefore = clientOfficer.seen.size
        clientOfficer.fail = { effect -> effect is GroupSessionEffect.CloseMedia && effect.lease.peer.deviceId == id(3) }
        clientOfficer.writer.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))

        val clientEffects = clientOfficer.seen.drop(clientCountBefore)
        assertTrue(clientEffects.any { it is GroupSessionEffect.CloseMedia && it.lease.peer.deviceId == id(3) })
        assertTrue(clientEffects.none { it is GroupSessionEffect.OpenMedia })
        assertTrue(clientEffects.any { it is GroupSessionEffect.Stop })
        assertEquals(GroupPhase.IDLE, clientOfficer.writer.snapshot.phase)
        assertEquals(GroupTerminalReason.FAILED, clientOfficer.writer.snapshot.terminalReason)
        assertEquals(GroupPhase.IN_ROOM, clientMember.writer.snapshot.phase)
    }
}
