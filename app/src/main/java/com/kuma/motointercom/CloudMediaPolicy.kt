package com.kuma.motointercom

internal class CloudMediaPolicy {
    var muted: Boolean = false
        private set
    var peerDeadlineMs: Long? = null
        private set

    fun setMuted(value: Boolean) { muted = value }

    fun updatePeers(count: Int, nowMs: Long) {
        require(count in 0..1)
        if (count == 1) peerDeadlineMs = null
        else if (peerDeadlineMs == null) peerDeadlineMs = nowMs + PEER_WAIT_MS
    }

    fun peerWaitExpired(nowMs: Long): Boolean = peerDeadlineMs?.let { nowMs >= it } == true

    companion object { const val PEER_WAIT_MS = 60_000L }
}
