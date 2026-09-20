package com.kuma.motointercom

internal object AudioPlatformOwnership {
    private val owners = mutableSetOf<Any>()
    @Synchronized fun acquire() = Any().also { owners += it }
    @Synchronized fun release(token: Any) { owners.remove(token) }
    @Synchronized fun hasOwner() = owners.isNotEmpty()
}

internal data class RiderRtpCounters(val streamIds: Set<String>, val sent: Long, val received: Long)
internal data class RiderMediaEvidence(val counters: RiderRtpCounters, val connected: Boolean,
    val remoteTrack: Boolean, val audioIoEnabled: Boolean, val gateRevision: Long, val nativeRevision: Long)

/** Native ADM callbacks, never merely the requested recording/playout switches. */
internal class RiderAudioIoEvidence {
    private var recording = false
    private var playing = false
    private var pcmAt = Long.MIN_VALUE
    private var revision = 0L
    @Synchronized fun recording(value: Boolean) { recording = value; pcmAt = Long.MIN_VALUE; revision++ }
    @Synchronized fun playing(value: Boolean) { playing = value; revision++ }
    @Synchronized fun pcm(now: Long) { if (recording) pcmAt = now }
    @Synchronized fun revision() = revision
    @Synchronized fun ready(expected: Long, now: Long) = expected == revision && recording && playing &&
        pcmAt != Long.MIN_VALUE && now >= pcmAt && now - pcmAt <= 2_000
}

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
