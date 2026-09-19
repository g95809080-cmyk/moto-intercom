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
        val writer = GroupSessionOrchestrator(endpoint, "骑士${endpoint.deviceId.takeLast(2)}", { now }, {}, ::effect)
        var channel: UUID? = null
        private fun effect(effect: GroupSessionEffect) {
            seen += effect
            if (effect is GroupSessionEffect.Send) {
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
    @Test fun lostSeatExpiresAtSixtySecondsAndFullClientWaitsWithoutEviction() {
        host(); val second = join(2); join(3); join(4)
        host.writer.dispatch(GroupSessionEvent.Closed(second.channel!!))
        second.writer.dispatch(GroupSessionEvent.Closed(second.channel!!))
        val fifth = join(5)
        assertEquals(GroupPhase.WAITING, fifth.writer.snapshot.phase)
        assertEquals(4, room().occupiedSeats)
        now = 59_999; host.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupMemberStatus.RESERVED, room().members.single { it.lease.deviceId == id(2) }.status)
        now = 60_000; host.writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(3, room().occupiedSeats)
        fifth.writer.dispatch(GroupSessionEvent.Tick); connect(fifth)
        assertEquals(GroupPhase.IN_ROOM, fifth.writer.snapshot.phase)
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
}
