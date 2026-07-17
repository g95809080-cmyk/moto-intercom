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
                runtimeSessionId = "remote-runtime",
                deviceName = "Phone P2P"
            ),
            expectedRemoteDeviceId = null
        )

        assertEquals("peer-stable", peer.deviceId)
        assertEquals(RuntimeSessionId("remote-runtime"), peer.runtimeSessionId)
        assertEquals("Phone P2P", peer.deviceName)
        assertTrue(peer.isDeviceIdVerified)
    }

    @Test
    fun targetLockDoesNotFillMissingSocketIdentity() {
        val peer = resolveRemoteIdentity(
            SignalingProtocol.Message.Identity("Legacy Rider"),
            expectedRemoteDeviceId = "peer-from-previous-session"
        )

        assertNull(peer.deviceId)
        assertNull(peer.runtimeSessionId)
        assertTrue(!peer.isDeviceIdVerified)
    }

    @Test
    fun targetLockedIdentityWithoutRuntimeSessionFailsClosed() {
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            resolveRemoteIdentity(
                SignalingProtocol.Message.Identity(
                    name = "Partial Rider",
                    deviceId = "peer-stable"
                ),
                expectedRemoteDeviceId = "peer-stable",
                requireClaimedDeviceId = true,
                expectedRemoteRuntimeSessionId = RuntimeSessionId("remote-runtime")
            )
        }
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

    @Test
    fun identityClaimMustMatchTheLockedRemoteRuntimeSession() {
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            resolveRemoteIdentity(
                SignalingProtocol.Message.Identity(
                    name = "Restarted Rider",
                    deviceId = "peer-expected",
                    runtimeSessionId = "runtime-new"
                ),
                expectedRemoteDeviceId = "peer-expected",
                requireClaimedDeviceId = true,
                expectedRemoteRuntimeSessionId = RuntimeSessionId("runtime-old")
            )
        }
    }
}
