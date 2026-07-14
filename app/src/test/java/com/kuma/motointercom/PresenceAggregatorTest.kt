package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceAggregatorTest {
    private var now = 1_000L
    private val aggregator = PresenceAggregator(nowElapsedRealtimeMs = { now })

    @Test
    fun mergesLanAndWifiDirectCandidatesByStableDeviceAndSession() {
        aggregator.replaceCandidates(Transport.LAN, listOf(candidate(Transport.LAN)))
        val snapshot = aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(candidate(Transport.WIFI_DIRECT))
        )

        val presence = snapshot.presences.single()
        assertEquals("peer-a", presence.deviceId)
        assertEquals(RuntimeSessionId("session-a"), presence.sessionId)
        assertEquals(setOf(Transport.LAN, Transport.WIFI_DIRECT), presence.candidates.map { it.transport }.toSet())
        assertTrue(presence.isSelectable)
    }

    @Test
    fun retainsLostTransportForTenSecondsWithoutRemovingAvailableTransport() {
        aggregator.replaceCandidates(Transport.LAN, listOf(candidate(Transport.LAN)))
        aggregator.replaceCandidates(Transport.WIFI_DIRECT, listOf(candidate(Transport.WIFI_DIRECT)))

        val retained = aggregator.replaceCandidates(Transport.LAN, emptyList()).presences.single()
        assertFalse(retained.candidates.single { it.transport == Transport.LAN }.isAvailable)
        assertTrue(retained.candidates.single { it.transport == Transport.WIFI_DIRECT }.isAvailable)

        now += PresenceAggregator.DEFAULT_RETENTION_MS - 1
        assertEquals(2, aggregator.expire().presences.single().candidates.size)

        now += 1
        val expired = aggregator.expire().presences.single()
        assertEquals(listOf(Transport.WIFI_DIRECT), expired.candidates.map { it.transport })
    }

    @Test
    fun removesPresenceAfterAllCandidatesFinishRetention() {
        aggregator.replaceCandidates(Transport.LAN, listOf(candidate(Transport.LAN)))
        aggregator.replaceCandidates(Transport.LAN, emptyList())

        assertFalse(aggregator.snapshot().presences.single().isSelectable)
        now += PresenceAggregator.DEFAULT_RETENTION_MS

        assertTrue(aggregator.expire().presences.isEmpty())
    }

    @Test
    fun joinsPairingMetadataAndKeepsPreferredPeerFirst() {
        aggregator.replaceCandidates(Transport.LAN, listOf(candidate(Transport.LAN, deviceId = "peer-b")))
        aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(candidate(Transport.WIFI_DIRECT, deviceId = "peer-a"))
        )

        val snapshot = aggregator.updatePairings(
            listOf(pairing("peer-a", alias = "Road Captain", preferred = true))
        )

        assertEquals("peer-a", snapshot.presences.first().deviceId)
        assertEquals("Road Captain", snapshot.presences.first().displayName)
        assertTrue(snapshot.presences.first().isPaired)
        assertFalse(snapshot.presences.last().isPaired)
    }

    @Test
    fun sessionRolloverDoesNotMixOrRestoreSupersededSession() {
        aggregator.replaceCandidates(
            Transport.LAN,
            listOf(candidate(Transport.LAN, sessionId = "session-old"))
        )
        aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(candidate(Transport.WIFI_DIRECT, sessionId = "session-new"))
        )

        assertEquals(
            RuntimeSessionId("session-new"),
            aggregator.snapshot().presences.single().sessionId
        )

        aggregator.replaceCandidates(
            Transport.LAN,
            listOf(candidate(Transport.LAN, sessionId = "session-old"))
        )

        val presence = aggregator.snapshot().presences.single()
        assertEquals(RuntimeSessionId("session-new"), presence.sessionId)
        assertEquals(listOf(Transport.WIFI_DIRECT), presence.candidates.map { it.transport })
    }

    @Test
    fun lateSupersededSessionAtSameEndpointKeepsCurrentCandidate() {
        val endpointId = "shared-endpoint"
        aggregator.replaceCandidates(
            Transport.LAN,
            listOf(candidate(Transport.LAN, sessionId = "session-old", endpointId = endpointId))
        )
        aggregator.replaceCandidates(
            Transport.LAN,
            listOf(candidate(Transport.LAN, sessionId = "session-new", endpointId = endpointId))
        )

        val snapshot = aggregator.replaceCandidates(
            Transport.LAN,
            listOf(candidate(Transport.LAN, sessionId = "session-old", endpointId = endpointId))
        )

        val presence = snapshot.presences.single()
        assertEquals(RuntimeSessionId("session-new"), presence.sessionId)
        assertTrue(presence.candidates.single().isAvailable)
        assertNull(snapshot.nextExpiryElapsedRealtimeMs)
    }

    @Test
    fun p2pSameMacLateTxtClaimCannotRollbackCurrentSession() {
        val mac = "aa:bb:cc:dd:ee:ff"
        aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(candidate(Transport.WIFI_DIRECT, sessionId = "session-old", endpointId = mac))
        )
        aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(candidate(Transport.WIFI_DIRECT, sessionId = "session-new", endpointId = mac))
        )

        val presence = aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(candidate(Transport.WIFI_DIRECT, sessionId = "session-old", endpointId = mac))
        ).presences.single()

        assertEquals(RuntimeSessionId("session-new"), presence.sessionId)
        assertEquals(mac, presence.candidates.single().endpointId)
        assertTrue(presence.candidates.single().isAvailable)
    }

    @Test
    fun candidateRecoveryWithinRetentionCancelsOldExpiry() {
        val lan = candidate(Transport.LAN)
        aggregator.replaceCandidates(Transport.LAN, listOf(lan))
        val unavailable = aggregator.replaceCandidates(Transport.LAN, emptyList())
        assertEquals(now + PresenceAggregator.DEFAULT_RETENTION_MS, unavailable.nextExpiryElapsedRealtimeMs)

        now += PresenceAggregator.DEFAULT_RETENTION_MS / 2
        val recovered = aggregator.replaceCandidates(Transport.LAN, listOf(lan))
        assertTrue(recovered.presences.single().candidates.single().isAvailable)
        assertNull(recovered.nextExpiryElapsedRealtimeMs)

        now += PresenceAggregator.DEFAULT_RETENTION_MS
        assertTrue(aggregator.expire().presences.single().candidates.single().isAvailable)
    }

    @Test
    fun provisionalCandidateIsDisplayOnlyAndNeverPaired() {
        val provisional = candidate(
            transport = Transport.WIFI_DIRECT,
            deviceId = null,
            sessionId = null
        )
        aggregator.updatePairings(listOf(pairing("peer-a", alias = "Known", preferred = false)))

        val presence = aggregator.replaceCandidates(
            Transport.WIFI_DIRECT,
            listOf(provisional)
        ).presences.single()

        assertNull(presence.deviceId)
        assertNull(presence.sessionId)
        assertFalse(presence.isSelectable)
        assertFalse(presence.isPaired)
    }

    private fun candidate(
        transport: Transport,
        deviceId: String? = "peer-a",
        sessionId: String? = "session-a",
        endpointId: String = if (transport == Transport.LAN) {
            "192.168.1.8:8890"
        } else {
            "aa:bb:cc:dd:ee:ff"
        }
    ): DiscoveryCandidate = DiscoveryCandidate(
        transport = transport,
        endpointId = endpointId,
        address = if (transport == Transport.LAN) "192.168.1.8" else "aa:bb:cc:dd:ee:ff",
        port = if (transport == Transport.LAN) 8890 else null,
        identity = DiscoveryIdentityClaim(
            claimedDeviceId = deviceId,
            sourceSessionId = sessionId?.let(::RuntimeSessionId),
            nickname = "Rider A",
            deviceName = "Phone A",
            protocolVersion = 1
        )
    )

    private fun pairing(deviceId: String, alias: String, preferred: Boolean) = PairingRecord(
        remoteDeviceId = deviceId,
        remoteNickname = "Paired $deviceId",
        deviceName = "Paired Phone",
        localAlias = alias,
        shortCode = "A001",
        pairedAt = 1L,
        lastConnectedAt = 2L,
        isPreferred = preferred,
        lastTransport = null,
        failureCount = 0
    )
}
