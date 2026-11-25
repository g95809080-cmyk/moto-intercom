package com.kuma.motointercom.group

import com.kuma.motointercom.RiderMediaEngine
import com.kuma.motointercom.RiderMediaSession
import com.kuma.motointercom.RiderMediaSessionCallbacks
import com.kuma.motointercom.VersionedAudioControls
import com.kuma.motointercom.runAllCleanupSteps
import java.io.Closeable

internal data class GroupMediaLease(
    val intent: GroupIntentToken,
    val local: GroupMemberLease,
    val peer: GroupMemberLease,
    val link: GroupLinkLease
) {
    fun matches(room: GroupRoomView, participation: GroupParticipation): Boolean {
        if (room.ended || room.key != intent.room || !participation.isCurrent(intent) ||
            local.deviceId == peer.deviceId) return false
        val admittedMembers = room.members.filter { it.status == GroupMemberStatus.ADMITTED }
        val localMember = admittedMembers.singleOrNull { it.lease == local } ?: return false
        val peerMember = admittedMembers.singleOrNull { it.lease == peer } ?: return false
        if (localMember.channel != peerMember.channel) return false
        val admitted = admittedMembers.map { it.lease }
        return link.pair == GroupPair.of(local.deviceId, peer.deviceId) &&
            room.links.any { it.lease == link } &&
            admitted.single { it.deviceId == link.pair.first }.incarnation == link.firstIncarnation &&
            admitted.single { it.deviceId == link.pair.second }.incarnation == link.secondIncarnation
    }
}

/** Executes authorized media effects. Does not own room state, network or the shared platform.
 * The service supplies coordinator.beginMediaSession/endMediaSession as aggregate demand hooks.
 * The single product writer supplies a current-state predicate; it must be safe on callback threads.
 */
internal class GroupMediaController(
    private val engine: RiderMediaEngine,
    private val isLeaseCurrent: (GroupMediaLease) -> Boolean,
    private val beginDemand: () -> Unit,
    private val endDemand: () -> Unit
) : Closeable {
    private class Slot(val lease: GroupMediaLease) { var session: RiderMediaSession? = null }
    private val lock = Any()
    private val slots = mutableMapOf<String, Slot>()
    private val unclosedSessions = mutableListOf<RiderMediaSession>()
    private val blocked = mutableSetOf<String>()
    private var closed = false
    private var closeFailed = false
    private var demand = false

    fun open(lease: GroupMediaLease, callbacks: RiderMediaSessionCallbacks): Boolean = synchronized(lock) {
        if (closed || !isLeaseCurrent(lease) || !callbacks.isSessionCurrent()) return false
        check(!closeFailed) { "previous media session failed to close" }
        val previous = slots[lease.peer.deviceId]
        if (previous?.lease == lease) return true
        if (previous != null) remove(previous)
        check(slots.size < GroupRoom.MAX_MEMBERS - 1) { "group media capacity reached" }
        val slot = Slot(lease)
        slots[lease.peer.deviceId] = slot
        fun current() = synchronized(lock) {
            !closed && slots[lease.peer.deviceId] === slot && isLeaseCurrent(lease) && callbacks.isSessionCurrent()
        }
        val guarded = callbacks.copy(
            isSessionCurrent = ::current,
            initialPlaybackMuted = lease.peer.deviceId in blocked,
            onLocalSdpGenerated = { if (current()) callbacks.onLocalSdpGenerated(it) },
            onLocalIceCandidateGenerated = { if (current()) callbacks.onLocalIceCandidateGenerated(it) },
            onConnectionStateChanged = { if (current()) callbacks.onConnectionStateChanged(it) },
            onRemoteAudioTrack = { if (current()) callbacks.onRemoteAudioTrack(it) },
            onAudioLevelChanged = { if (current()) callbacks.onAudioLevelChanged(it) },
            onError = { error ->
                val report = synchronized(lock) {
                    val wasCurrent = current()
                    if (slots[lease.peer.deviceId] === slot) {
                        runCatching { remove(slot) }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                    wasCurrent
                }
                if (report) callbacks.onError(error)
            }
        )
        try {
            if (!demand) { demand = true; beginDemand() }
            if (!current()) { remove(slot); return false }
            val session = engine.openSession(guarded)
            if (!current()) {
                runAllCleanupSteps({ closeSessionOrLatch(session) }, { remove(slot) })
                return false
            }
            slot.session = session
            session.setPlaybackMuted(lease.peer.deviceId in blocked)
            true
        } catch (error: Throwable) {
            runCatching { remove(slot) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun close(lease: GroupMediaLease) = synchronized(lock) {
        slots[lease.peer.deviceId]?.takeIf { it.lease == lease }?.let(::remove)
        Unit
    }

    fun offer(lease: GroupMediaLease) = withSession(lease) { it.createOffer() }
    fun answer(lease: GroupMediaLease, sdp: String) = withSession(lease) { it.createAnswer(sdp) }
    fun remoteAnswer(lease: GroupMediaLease, sdp: String) = withSession(lease) { it.setRemoteAnswer(sdp) }
    fun candidate(lease: GroupMediaLease, candidate: String) = withSession(lease) { it.addRemoteIceCandidate(candidate) }
    fun evidence(lease: GroupMediaLease, callback: (com.kuma.motointercom.RiderMediaEvidence?) -> Unit) =
        withSession(lease) { it.queryEvidence(callback) }

    fun updateAudioControls(controls: VersionedAudioControls) = synchronized(lock) {
        if (!closed) engine.updateAudioControls(controls)
    }

    fun block(peerId: String, muted: Boolean) = synchronized(lock) {
        if (closed) return
        if (muted) blocked.add(peerId) else blocked.remove(peerId)
        slots[peerId]?.session?.setPlaybackMuted(muted)
        Unit
    }

    private fun withSession(lease: GroupMediaLease, action: (RiderMediaSession) -> Unit) = synchronized(lock) {
        if (!closed && isLeaseCurrent(lease)) slots[lease.peer.deviceId]
            ?.takeIf { it.lease == lease }?.session?.let(action)
        Unit
    }

    private fun remove(slot: Slot) {
        if (slots[slot.lease.peer.deviceId] !== slot) return
        slots.remove(slot.lease.peer.deviceId)
        val session = slot.session
        slot.session = null
        val end = slots.isEmpty() && demand
        if (end) demand = false
        runAllCleanupSteps({ closeSessionOrLatch(session) }, { if (end) endDemand() })
    }

    private fun closeSessionOrLatch(session: RiderMediaSession?) {
        if (session == null) return
        try {
            session.close()
        } catch (error: Throwable) {
            closeFailed = true
            unclosedSessions += session
            throw error
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        val active = slots.values.mapNotNull { it.session } + unclosedSessions
        slots.clear()
        unclosedSessions.clear()
        blocked.clear()
        val end = demand
        demand = false
        runAllCleanupSteps(*active.map { session -> { session.close(); Unit } }.toTypedArray(),
            { if (end) endDemand() })
    }
}
