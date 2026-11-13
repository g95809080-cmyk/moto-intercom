package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleBootstrapEndpointTest {
    @Test
    fun endpointMessageBecomesLanCandidateWithStableIdentity() {
        val device = lanDeviceFromBleEndpointOrNull(
            BleBootstrapMessage(
                type = BleBootstrapMessageType.ENDPOINT_READY,
                requestId = SESSION_A,
                fields = mapOf(
                    "app" to CrossPlatformCapabilityAdvertisement.APP_ID,
                    "hostAddress" to "192.168.43.1",
                    "tcpPort" to "8890",
                    "deviceId" to DEVICE_A,
                    "sessionId" to SESSION_B,
                    "platform" to PeerPlatform.IOS.name,
                    "protocolVersion" to SignalingV2Codec.PROTOCOL_VERSION.toString(),
                    "nickname" to "iPhone Rider",
                    "deviceName" to "iPhone X",
                    "networkRole" to BootstrapNetworkRole.JOINER.name
                ),
                capabilities = setOf(MotoComCapability.LAN)
            )
        )

        assertEquals("192.168.43.1", device?.ip)
        assertEquals(8890, device?.port)
        assertEquals(DEVICE_A, device?.deviceId)
        assertEquals(RuntimeSessionId(SESSION_B), device?.sessionId)
    }

    @Test
    fun endpointMessageRejectsNonLocalAddress() {
        val device = lanDeviceFromBleEndpointOrNull(
            BleBootstrapMessage(
                type = BleBootstrapMessageType.ENDPOINT_READY,
                requestId = SESSION_A,
                fields = mapOf(
                    "hostAddress" to "8.8.8.8",
                    "tcpPort" to "8890",
                    "deviceId" to DEVICE_A,
                    "sessionId" to SESSION_B
                )
            )
        )

        assertNull(device)
    }

    @Test
    fun endpointMessageRejectsMismatchedProtocolField() {
        val device = lanDeviceFromBleEndpointOrNull(
            BleBootstrapMessage(
                type = BleBootstrapMessageType.ENDPOINT_READY,
                requestId = SESSION_A,
                fields = mapOf(
                    "hostAddress" to "192.168.43.1",
                    "tcpPort" to "8890",
                    "deviceId" to DEVICE_A,
                    "sessionId" to SESSION_B,
                    "protocolVersion" to "99"
                )
            )
        )

        assertNull(device)
    }

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val SESSION_B = "10000000-0000-4000-8000-000000000002"
    }
}
