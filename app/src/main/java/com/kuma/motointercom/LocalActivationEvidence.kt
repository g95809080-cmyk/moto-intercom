package com.kuma.motointercom

// Cumulative positive counters are not fresh evidence after a focus interruption.
internal class LocalActivationEvidence {
    private var windowStart: RiderMediaEvidence?=null
    private var lastSeen: RiderMediaEvidence?=null
    fun sample(value: RiderMediaEvidence?): Boolean {
        if(value==null || !value.connected || !value.remoteTrack || !value.audioIoEnabled) {windowStart=null;lastSeen=null;return false}
        val before=windowStart;val last=lastSeen;lastSeen=value
        if(before==null || before.gateRevision!=value.gateRevision || before.nativeRevision!=value.nativeRevision ||
            before.counters.streamIds!=value.counters.streamIds || last!=null &&
            (value.counters.sent<last.counters.sent || value.counters.received<last.counters.received)) {windowStart=value;return false}
        return value.counters.sent>before.counters.sent && value.counters.received>before.counters.received
    }
}
