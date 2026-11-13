package com.kuma.motointercom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBootstrapProtocolTest {
    @Test
    fun capabilityAdvertisementRoundTripsWithPlatformAndCapabilities() {
        val advertisement = CrossPlatformCapabilityAdvertisement(
            platform = PeerPlatform.ANDROID,
            deviceId = DEVICE_A,
            sessionId = SESSION_A,
            capabilities = setOf(
                MotoComCapability.BLE_BOOTSTRAP,
                MotoComCapability.LAN,
                MotoComCapability.WIFI_JOIN
            ),
            networkRole = BootstrapNetworkRole.EITHER
        )

        assertEquals(advertisement, BleBootstrapCodec.decodeAdvertisement(
            BleBootstrapCodec.encodeAdvertisement(advertisement)
        ))
    }

    @Test
    fun bootstrapMessageRoundTripsWithoutPersistingSecretOutsideFields() {
        val message = BleBootstrapMessage(
            type = BleBootstrapMessageType.HOTSPOT_READY,
            requestId = ATTEMPT_A,
            fields = mapOf(
                "ssid" to "MotoCom-1234",
                "security" to "WPA2",
                "passphrase" to "123456789",
                "tcpPort" to "8890"
            )
        )

        assertEquals(message, BleBootstrapCodec.decode(BleBootstrapCodec.encode(message)))
    }

    @Test
    fun chunkAssemblerSupportsOutOfOrderAndDuplicateChunks() {
        val message = ByteArray(500) { (it % 251).toByte() }
        val chunks = BleChunkCodec.encode(7, message, maxPacketBytes = 80)
        val assembler = BleMessageAssembler()

        assertNull(assembler.add(chunks[2]))
        assertNull(assembler.add(chunks[0]))
        assertNull(assembler.add(chunks[2]))
        var decoded: ByteArray? = null
        chunks.indices
            .filter { it != 0 && it != 2 }
            .forEach { index -> assembler.add(chunks[index])?.let { decoded = it } }

        assertNotNull(decoded)
        assertArrayEquals(message, decoded)
    }

    @Test
    fun chunkAssemblerExpiresIncompleteMessages() {
        var now = 0L
        val assembler = BleMessageAssembler(nowElapsedRealtimeMs = { now }, timeoutMs = 10L)
        val chunks = BleChunkCodec.encode(8, ByteArray(40), maxPacketBytes = 20)

        assertNull(assembler.add(chunks.first()))
        now = 9L
        assertFalse(assembler.expire())
        now = 10L
        assertTrue(assembler.expire())
        assertNull(assembler.add(chunks.last()))
    }

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
    }
}
