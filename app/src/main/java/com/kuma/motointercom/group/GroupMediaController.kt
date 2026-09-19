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
    fun matches(room: GroupRoom, participation: GroupParticipation): Boolean {
        if (room.ended || room.key != intent.room || !participation.isCurrent(intent) ||
            local.deviceId == peer.deviceId) return false
        val admitted = room.members.filter { it.status == GroupMemberStatus.ADMITTED }.map { it.lease }
        return local in admitted && peer in admitted && link.pair == GroupPair.of(local.deviceId, peer.deviceId) &&
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
    private val blocked = mutableSetOf<String>()
    private var closed = false
    private var demand = false

    fun open(lease: GroupMediaLease, callbacks: RiderMediaSessionCallbacks): Boolean = synchronized(lock) {
        if (closed || !isLeaseCurrent(lease) || !callbacks.isSessionCurrent()) return false
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
                runAllCleanupSteps(session::close, { remove(slot) })
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
        runAllCleanupSteps({ session?.close() }, { if (end) endDemand() })
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        val active = slots.values.toList()
        slots.clear()
        blocked.clear()
        val end = demand
        demand = false
        runAllCleanupSteps(*active.map { slot -> { slot.session?.close(); Unit } }.toTypedArray(),
            { if (end) endDemand() })
    }
}
