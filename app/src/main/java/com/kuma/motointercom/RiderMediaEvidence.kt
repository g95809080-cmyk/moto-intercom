package com.kuma.motointercom

internal object AudioPlatformOwnership {
    private val owners = mutableSetOf<Any>()
    @Synchronized fun acquire() = Any().also { owners += it }
    @Synchronized fun release(token: Any) { owners.remove(token) }
    @Synchronized fun hasOwner() = owners.isNotEmpty()
}

internal data class RiderRtpCounters(val streamIds: Set<String>, val sent: Long, val received: Long)
internal data class RiderMediaEvidence(val counters: RiderRtpCounters, val connected: Boolean,
    val remoteTrack: Boolean, val audioIoEnabled: Boolean, val gateRevision: Long, val nativeRevision: Long,
    val renderRevision: Long = 0)

internal fun audioRtpCounters(stats: Iterable<Triple<String, String, Map<String, Any>>>): RiderRtpCounters? {
    var sent = 0L; var received = 0L
    var hasSent = false; var hasReceived = false
    val ids = mutableSetOf<String>()
    for ((id, type, fields) in stats) {
        if (fields["kind"] != "audio" && fields["mediaType"] != "audio") continue
        val key = when (type) { "outbound-rtp" -> "bytesSent"; "inbound-rtp" -> "bytesReceived"; else -> continue }
        val count = (fields[key] as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
        ids += "$type:$id:${fields["ssrc"]}"
        if (type == "outbound-rtp") { if (Long.MAX_VALUE - sent < count) return null; sent += count; hasSent = true }
        else { if (Long.MAX_VALUE - received < count) return null; received += count; hasReceived = true }
    }
    return if (hasSent && hasReceived) RiderRtpCounters(ids.toSet(), sent, received) else null
}
