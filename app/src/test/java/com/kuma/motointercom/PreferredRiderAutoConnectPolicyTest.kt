package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredRiderAutoConnectPolicyTest {
    @Test
    fun selectsOnlyAnAvailablePreferredPresenceWithStableIdentity() {
        val preferred = presence(
            deviceId = "preferred-device",
            sessionId = RuntimeSessionId("preferred-session"),
            preferred = true,
            transport = Transport.WIFI_DIRECT
        )
        val nearby = presence(
            deviceId = "nearby-device",
            sessionId = RuntimeSessionId("nearby-session"),
            preferred = false,
            transport = Transport.LAN
        )

        assertEquals(
            PreferredAutoConnectTarget(
                deviceId = "preferred-device",
                sessionId = RuntimeSessionId("preferred-session"),
                availableTransports = setOf(Transport.WIFI_DIRECT)
            ),
            preferredAutoConnectTarget(
                state = IntercomState.Discovering(RuntimeSessionId("local-runtime")),
                presences = listOf(nearby, preferred)
            )
        )
    }

    @Test
    fun doesNotSelectUnavailableIncompleteOrNonDiscoveringPresence() {
        val preferred = presence(
            deviceId = "preferred-device",
            sessionId = RuntimeSessionId("preferred-session"),
            preferred = true,
            transport = Transport.LAN
        )
        val unavailable = preferred.copy(
            candidates = preferred.candidates.map { it.copy(isAvailable = false) }
        )
        val incomplete = preferred.copy(deviceId = "")
        val state = IntercomState.Discovering(RuntimeSessionId("local-runtime"))

        assertNull(preferredAutoConnectTarget(state, listOf(unavailable)))
        assertNull(preferredAutoConnectTarget(state, listOf(incomplete)))
        assertNull(
            preferredAutoConnectTarget(
                IntercomState.Offline,
                listOf(preferred)
            )
        )
    }

    private fun presence(
        deviceId: String,
        sessionId: RuntimeSessionId,
        preferred: Boolean,
        transport: Transport
    ) = RiderPresence(
        deviceId = deviceId,
        sessionId = sessionId,
        nickname = "Rider",
        deviceName = "Phone",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = transport,
                endpointId = "endpoint-${deviceId}",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = PairingRecord(
            remoteDeviceId = deviceId,
            remoteNickname = "Rider",
            deviceName = "Phone",
            localAlias = "",
            shortCode = "0000",
            pairedAt = 1L,
            lastConnectedAt = 1L,
            isPreferred = preferred,
            lastTransport = transport.name,
            failureCount = 0
        )
    )
}
