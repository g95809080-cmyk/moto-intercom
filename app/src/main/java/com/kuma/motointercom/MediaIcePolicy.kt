package com.kuma.motointercom

import org.webrtc.PeerConnection

/** Credentials are scoped to one media session; the TURN shared secret never reaches this type. */
internal class TurnCredentials(urls: List<String>, val username: String, val credential: String, val expiresAtMs: Long) {
    val urls: List<String> = urls.toList()
    init {
        require(this.urls.isNotEmpty() && this.urls.size <= 8 && this.urls.all { TURN_URL.matches(it) })
        require(username.isNotBlank() && username.length <= 256 && credential.isNotBlank() && credential.length <= 256)
        require(expiresAtMs > 0)
    }
    override fun toString() = "TurnCredentials(urls=$urls, expiresAtMs=$expiresAtMs, credentials=redacted)"
    private companion object {
        val TURN_URL = Regex("turns?:[A-Za-z0-9.\\[\\]:-]+(?:\\?transport=(?:udp|tcp))?")
    }
}

internal sealed interface MediaIcePolicy {
    data object Nearby : MediaIcePolicy

    class Relay(servers: List<TurnCredentials>) : MediaIcePolicy {
        val servers = servers.toList()
        init { require(this.servers.isNotEmpty() && this.servers.size <= 8) }
    }
}

internal fun MediaIcePolicy.rtcConfiguration(nowMs: Long = System.currentTimeMillis()): PeerConnection.RTCConfiguration {
    val servers = when (this) {
        MediaIcePolicy.Nearby -> emptyList()
        is MediaIcePolicy.Relay -> servers.map {
            require(it.expiresAtMs > nowMs + 5_000) { "TURN credentials expired or too close to expiry" }
            PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer()
        }
    }
    return PeerConnection.RTCConfiguration(servers).apply {
        iceTransportsType = if (this@rtcConfiguration is MediaIcePolicy.Relay) PeerConnection.IceTransportsType.RELAY
            else PeerConnection.IceTransportsType.ALL
        candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
}
