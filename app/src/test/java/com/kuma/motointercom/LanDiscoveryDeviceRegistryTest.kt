package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun threeDeviceLookupReturnsOnlyTheLockedDeviceAndSession() {
        val registry = LanDiscoveryDeviceRegistry()
        registry.remember("S-c", device("S-c", "session-c", "peer-c"))
        registry.remember("S-a", device("S-a", "session-a", "peer-a"))
        registry.remember("S-b", device("S-b", "session-b", "peer-b"))

        val selected = registry.find(
            TargetLock("peer-b", RuntimeSessionId("session-b"))
        )

        assertEquals("S-b", selected?.discoveryEndpointId)
        assertNull(registry.find(TargetLock("peer-b", RuntimeSessionId("session-old"))))
    }

    @Test
    fun lanHandoffRequiresAnExplicitMatchingAttempt() {
        val attempt = ConnectionAttempt(
            id = ConnectionAttemptId("attempt-lan"),
            runtimeSessionId = RuntimeSessionId("runtime"),
            targetLock = TargetLock("peer-b", RuntimeSessionId("session-b")),
            trigger = ConnectionTrigger.USER,
            channelPlan = ChannelPlan.single(Transport.LAN),
            deadlineElapsedRealtimeMs = 1L
        )

        assertFalse(null.acceptsLanPreflightDevice("peer-b"))
        assertFalse(attempt.acceptsLanPreflightDevice("peer-c"))
        assertEquals(true, attempt.acceptsLanPreflightDevice("peer-b"))
    }

    private fun device(
        serviceName: String,
        sessionId: String,
        deviceId: String = "peer-a"
    ) = LanRiderDevice(
        discoveryEndpointId = serviceName,
        deviceId = deviceId,
        sessionId = RuntimeSessionId(sessionId),
        name = "Rider A",
        deviceName = "Phone A",
        protocolVersion = 1,
        ip = "192.168.1.8",
        port = 8890
    )
}
