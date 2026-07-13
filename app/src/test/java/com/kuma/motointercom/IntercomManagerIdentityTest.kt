package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomManagerIdentityTest {
    @Test
    fun p2pIdentityUsesStableIdFromCurrentSignalingChannel() {
        val peer = resolveRemoteIdentity(
            SignalingProtocol.Message.Identity(
                name = "P2P Rider",
                deviceId = "peer-stable",
                runtimeSessionId = "remote-runtime"
            ),
            expectedRemoteDeviceId = null
        )

        assertEquals("peer-stable", peer.deviceId)
        assertEquals(RuntimeSessionId("remote-runtime"), peer.runtimeSessionId)
        assertTrue(peer.isDeviceIdVerified)
    }

    @Test
    fun legacyP2pIdentityRemainsUnknown() {
        val peer = resolveRemoteIdentity(
            SignalingProtocol.Message.Identity("Legacy Rider"),
            expectedRemoteDeviceId = "peer-from-previous-session",
            requireClaimedDeviceId = true
        )

        assertNull(peer.deviceId)
        assertNull(peer.runtimeSessionId)
    }

    @Test
    fun p2pIdentityWithoutRuntimeSessionIsNotVerified() {
        val peer = resolveRemoteIdentity(
            SignalingProtocol.Message.Identity(
                name = "Partial Rider",
                deviceId = "peer-stable"
            ),
            expectedRemoteDeviceId = null,
            requireClaimedDeviceId = true
        )

        assertNull(peer.deviceId)
        assertNull(peer.runtimeSessionId)
    }

    @Test
    fun identityClaimMustMatchDeviceIdAlreadyVerifiedByTransport() {
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            resolveRemoteIdentity(
                SignalingProtocol.Message.Identity(
                    name = "Wrong Rider",
                    deviceId = "peer-other",
                    runtimeSessionId = "remote-runtime"
                ),
                expectedRemoteDeviceId = "peer-expected"
            )
        }
    }
}
