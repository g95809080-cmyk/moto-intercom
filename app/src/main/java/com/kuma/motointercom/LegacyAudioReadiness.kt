package com.kuma.motointercom

/** One instance per media session. Silence keeps an established proof, route/I/O changes revoke it. */
internal class LegacyAudioReadiness {
    private var previous: RiderMediaEvidence? = null
    private var routeRevision: Long? = null
    var ready = false
        private set
    fun reset(): Boolean { previous = null; routeRevision = null; ready = false; return false }
    fun update(value: RiderMediaEvidence?, before: AudioRouteEvidence, after: AudioRouteEvidence): Boolean {
        if (value == null || before != after || !after.ready || !value.connected || !value.remoteTrack || !value.audioIoEnabled)
            return reset()
        val old = previous
        if (routeRevision != after.revision || old == null || old.gateRevision != value.gateRevision ||
            old.nativeRevision != value.nativeRevision || old.counters.streamIds != value.counters.streamIds ||
            value.counters.sent < old.counters.sent || value.counters.received < old.counters.received) {
            ready = false
        } else if (value.counters.sent > old.counters.sent && value.counters.received > old.counters.received) {
            ready = true
        }
        previous = value; routeRevision = after.revision
        return ready
    }
}
