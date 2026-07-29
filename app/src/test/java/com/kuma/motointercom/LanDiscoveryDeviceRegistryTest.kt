package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun completeUdpHelloCreatesStableLanDevice() {
        val device = lanBroadcastDeviceOrNull(
            hello = hello(),
            sourceAddress = "192.168.1.45",
            localIp = "192.168.1.44",
            localDeviceId = LOCAL_ID
        )

        assertNotNull(device)
        assertEquals(PEER_ID, device?.deviceId)
        assertEquals(RuntimeSessionId(SESSION_ID), device?.sessionId)
        assertEquals("33", device?.name)
        assertEquals("MI 6", device?.deviceName)
        assertEquals("192.168.1.45", device?.ip)
        assertEquals(8890, device?.port)
    }

    @Test
    fun udpHelloRejectsLocalIncompleteOrInvalidEndpointClaims() {
        assertNull(
            lanBroadcastDeviceOrNull(
                hello(),
                "192.168.1.45",
                "192.168.1.44",
                PEER_ID
            )
        )
        assertNull(
            lanBroadcastDeviceOrNull(
                hello(sessionId = ""),
                "192.168.1.45",
                "192.168.1.44",
                LOCAL_ID
            )
        )
        assertNull(
            lanBroadcastDeviceOrNull(
                hello(port = 0),
                "192.168.1.45",
                "192.168.1.44",
                LOCAL_ID
            )
        )
        assertNull(
            lanBroadcastDeviceOrNull(
                hello(type = "OTHER"),
                "192.168.1.45",
                "192.168.1.44",
                LOCAL_ID
            )
        )
        assertNull(
            lanBroadcastDeviceOrNull(
                hello(deviceId = "peer-a"),
                "192.168.1.45",
                "192.168.1.44",
                LOCAL_ID
            )
        )
    }

    @Test
    fun udpExpiryRefreshesAndDoesNotRemoveNsdDevice() {
        val registry = LanDiscoveryDeviceRegistry()
        registry.remember("S-nsd", device("S-nsd", "session-nsd"))
        registry.remember(
            "S-udp",
            device("S-udp", "session-udp"),
            expiresAtElapsedRealtimeMs = 3_000L
        )
        registry.remember(
            "S-udp",
            device("S-udp", "session-udp"),
            expiresAtElapsedRealtimeMs = 4_000L
        )

        assertNull(registry.expire(3_000L))
        val expired = registry.expire(4_000L)

        assertEquals(listOf("S-nsd"), expired?.map(LanRiderDevice::discoveryEndpointId))
        assertTrue(registry.find(TargetLock("peer-a", RuntimeSessionId("session-nsd"))) != null)
        assertNull(registry.find(TargetLock("peer-a", RuntimeSessionId("session-udp"))))
    }

    @Test
    fun expiredUdpDeviceIsRemovedWhileAnotherDeviceKeepsBroadcasting() {
        val registry = LanDiscoveryDeviceRegistry()
        registry.remember(
            "S-old",
            device("S-old", "session-old"),
            expiresAtElapsedRealtimeMs = 3_000L
        )
        registry.remember(
            "S-current",
            device("S-current", "session-current"),
            expiresAtElapsedRealtimeMs = 5_000L
        )

        val snapshot = registry.expire(3_000L)

        assertEquals(
            listOf("S-current"),
            snapshot?.map(LanRiderDevice::discoveryEndpointId)
        )
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

    private fun hello(
        type: String = "MOTOCOM_HELLO",
        deviceId: String = PEER_ID,
        sessionId: String = SESSION_ID,
        port: Int = 8890
    ) = LanBroadcastHello(
        type = type,
        deviceId = deviceId,
        sessionId = sessionId,
        name = "33",
        deviceName = "MI 6",
        protocolVersion = 2,
        tcpPort = port
    )

    private companion object {
        const val LOCAL_ID = "10000000-0000-4000-8000-000000000001"
        const val PEER_ID = "20000000-0000-4000-8000-000000000002"
        const val SESSION_ID = "30000000-0000-4000-8000-000000000003"
    }
}
