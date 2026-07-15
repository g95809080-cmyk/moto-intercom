package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pServiceInstanceCodecTest {
    @Test
    fun v2InstanceRoundTripsStableDiscoveryIdentityWithinDnsSdLimit() {
        val sessionId = RuntimeSessionId(SESSION_ID)

        val instanceName = P2pServiceInstanceCodec.encode(DEVICE_ID, sessionId)
        val claim = P2pServiceInstanceCodec.decodeClaim(instanceName, "Phone")

        assertTrue(
            instanceName.toByteArray(Charsets.US_ASCII).size <=
                P2pServiceInstanceCodec.MAX_DNS_SD_INSTANCE_BYTES
        )
        requireNotNull(claim)
        assertEquals(DEVICE_ID, claim.claimedDeviceId)
        assertEquals(sessionId, claim.sourceSessionId)
        assertEquals("Phone", claim.nickname)
        assertEquals("Phone", claim.deviceName)
        assertEquals(2, claim.protocolVersion)
        assertTrue(claim.hasStableIdentity)
    }

    @Test
    fun leadingZeroAndMaximumUuidValuesRoundTrip() {
        val values = listOf(
            "00000000-0000-0000-0000-000000000001" to
                "00000000-0000-0000-0000-000000000002",
            "ffffffff-ffff-ffff-ffff-ffffffffffff" to
                "ffffffff-ffff-ffff-ffff-ffffffffffff"
        )

        values.forEach { (deviceId, sessionValue) ->
            val claim = P2pServiceInstanceCodec.decodeClaim(
                P2pServiceInstanceCodec.encode(deviceId, RuntimeSessionId(sessionValue)),
                "Phone"
            )
            requireNotNull(claim)
            assertEquals(deviceId, claim.claimedDeviceId)
            assertEquals(sessionValue, claim.sourceSessionId?.value)
        }
    }

    @Test
    fun malformedV2InstancesFailClosed() {
        val invalid = listOf(
            "MotoCom2-short-short",
            "MotoCom2-${"A".repeat(25)}-${"0".repeat(25)}",
            "MotoCom2-${"z".repeat(25)}-${"0".repeat(25)}",
            "MotoCom2-${"0".repeat(25)}-${"0".repeat(25)}-extra",
            "Other-${"0".repeat(25)}-${"0".repeat(25)}"
        )

        invalid.forEach { instanceName ->
            assertNull(P2pServiceInstanceCodec.decodeClaim(instanceName, "Phone"))
        }
    }

    @Test
    fun legacyInstanceRemainsProvisional() {
        val legacy = "MotoCom-12345678"

        assertTrue(P2pServiceInstanceCodec.isLegacy(legacy))
        assertNull(P2pServiceInstanceCodec.decodeClaim(legacy, "Phone"))
        assertFalse(P2pServiceInstanceCodec.isLegacy("Other-12345678"))
    }

    private companion object {
        const val DEVICE_ID = "a0000000-0000-4000-8000-000000000001"
        const val SESSION_ID = "10000000-0000-4000-8000-000000000001"
    }
}
