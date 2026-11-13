package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.PeerConnection

class MediaIcePolicyTest {
    @Test fun nearbyRetainsNoServerAndAllCandidates() {
        val config = MediaIcePolicy.Nearby.rtcConfiguration(0)
        assertTrue(config.iceServers.isEmpty())
        assertEquals(PeerConnection.IceTransportsType.ALL, config.iceTransportsType)
    }

    @Test fun relayCopiesCredentialsAndCannotFallBackWhenExpired() {
        val urls = mutableListOf("turn:127.0.0.1:3478?transport=udp")
        val servers = mutableListOf(TurnCredentials(urls, "60:device", "temporary-password", 60_000))
        val policy = MediaIcePolicy.Relay(servers)
        urls.clear(); servers.clear()
        val config = policy.rtcConfiguration(1)
        assertEquals(PeerConnection.IceTransportsType.RELAY, config.iceTransportsType)
        assertEquals("temporary-password", config.iceServers.single().password)
        assertThrows(IllegalArgumentException::class.java) { policy.rtcConfiguration(55_000) }
        assertThrows(IllegalArgumentException::class.java) { MediaIcePolicy.Relay(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TurnCredentials(listOf("stun:example.org"), "u", "p", 100) }
        assertFalse(policy.servers.single().toString().contains("temporary-password"))
    }

    private fun stats(selected: Boolean = true): List<Triple<String, String, Map<String, Any>>> = listOf(
        Triple("t", "transport", if (selected) mapOf<String, Any>("selectedCandidatePairId" to "p") else emptyMap()),
        Triple("p", "candidate-pair", mapOf("localCandidateId" to "l", "remoteCandidateId" to "r", "state" to "succeeded",
            "nominated" to true, "bytesSent" to 100L, "bytesReceived" to 200L, "currentRoundTripTime" to 0.1)),
        Triple("l", "local-candidate", mapOf("candidateType" to "relay", "protocol" to "udp", "networkType" to "wifi")),
        Triple("r", "remote-candidate", mapOf("candidateType" to "relay"))
    )

    @Test fun onlyActualTransportSelectionIsEvidence() {
        assertNull(selectedTransportEvidence("s", 1, stats(false)))
        val evidence = selectedTransportEvidence("s", 2, stats())!!
        assertEquals("s", evidence.sessionId)
        assertEquals("relay", evidence.localType)
        assertEquals(200L, evidence.bytesReceived)
        assertNull(selectedTransportEvidence("s", 3, stats().filter { it.first != "l" }))
        assertNull(selectedTransportEvidence("s", 3, stats() + Triple("t2", "transport", mapOf("selectedCandidatePairId" to "other"))))
    }
}
