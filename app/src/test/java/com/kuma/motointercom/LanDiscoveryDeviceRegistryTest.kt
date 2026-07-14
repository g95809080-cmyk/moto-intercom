package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class LanDiscoveryDeviceRegistryTest {
    @Test
    fun losingOldServiceDoesNotRemoveNewSessionForSameDevice() {
        val registry = LanDiscoveryDeviceRegistry()
        registry.remember("S-old", device("S-old", "session-old"))
        registry.remember("S-new", device("S-new", "session-new"))

        val snapshot = registry.remove("S-old")

        assertEquals(1, snapshot.size)
        assertEquals("S-new", snapshot.single().discoveryEndpointId)
        assertEquals(RuntimeSessionId("session-new"), snapshot.single().sessionId)
    }

    @Test
    fun multipleServicesForSameDeviceRemainSeparateCandidates() {
        val registry = LanDiscoveryDeviceRegistry()

        registry.remember("S-old", device("S-old", "session-old"))
        val snapshot = registry.remember("S-new", device("S-new", "session-new"))

        assertEquals(2, snapshot.size)
        assertEquals(setOf("S-old", "S-new"), snapshot.map { it.discoveryEndpointId }.toSet())
        assertEquals(setOf("session-old", "session-new"), snapshot.mapNotNull { it.sessionId?.value }.toSet())
    }

    private fun device(serviceName: String, sessionId: String) = LanRiderDevice(
        discoveryEndpointId = serviceName,
        deviceId = "peer-a",
        sessionId = RuntimeSessionId(sessionId),
        name = "Rider A",
        deviceName = "Phone A",
        protocolVersion = 1,
        ip = "192.168.1.8",
        port = 8890
    )
}
