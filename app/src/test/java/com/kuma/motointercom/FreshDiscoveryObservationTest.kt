package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class FreshDiscoveryObservationTest {
    @Test fun lostNsdKeyCannotRevokeANewerUdpObservationForTheSameDevice() {
        val source = FreshDiscoverySource(Transport.LAN)
        val epoch = source.currentEpoch
        val nsd = requireNotNull(source.capture(DiscoveryObservationKind.LAN_NSD, 100L, epoch, "nsd-endpoint"))
        val nsdObservation = requireNotNull(source.accept(nsd, candidate("nsd-endpoint", SESSION_A)) { true }).second
        assertTrue(source.invalidateKey(DiscoveryObservationKind.LAN_NSD, "nsd-endpoint", epoch))
        assertFalse(source.isCurrent(nsdObservation))
        val udp = requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, 101L))
        val udpObservation = requireNotNull(source.accept(udp, candidate("udp-endpoint", SESSION_A)) { true }).second
        assertTrue(source.invalidateKey(DiscoveryObservationKind.LAN_NSD, "nsd-endpoint", epoch))
        assertTrue(source.isCurrent(udpObservation))
        source.advanceEpoch()
        assertFalse(source.invalidateKey(DiscoveryObservationKind.LAN_NSD, "nsd-endpoint", epoch))
    }

    @Test fun delayedResolveCannotOverwriteANewerEndpointOrDeviceIdentity() {
        val source = FreshDiscoverySource(Transport.LAN)
        val old = requireNotNull(source.capture(DiscoveryObservationKind.LAN_NSD, 100L))
        val newer = requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, 200L))
        val remoteA = candidate("old-endpoint", SESSION_A)
        val remoteB = candidate("new-endpoint", SESSION_B)
        var registered: DiscoveryCandidate? = null
        val current = source.accept(newer, remoteB) { registered = remoteB; true }!!.second
        assertNull(source.accept(old, remoteA) { registered = remoteA; true })
        assertEquals(remoteB, registered)
        assertEquals(200L, current.receivedAtElapsedRealtimeMs)
        assertTrue(source.isCurrent(current))
    }

    @Test fun sameValidBroadcastIsFreshButDuplicateDeliveryIsNot() {
        val source = FreshDiscoverySource(Transport.LAN)
        val device = candidate("udp-endpoint", SESSION_A)
        val first = requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, 100L))
        val firstObservation = source.accept(first, device) { true }!!.second
        assertNull(source.accept(first, device) { error("Duplicate receipt reached registry") })
        val next = requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, 101L))
        val nextObservation = source.accept(next, device) { true }!!.second
        assertTrue(next.sequence > first.sequence)
        assertFalse(source.isCurrent(firstObservation))
        assertTrue(source.isCurrent(nextObservation))
        assertEquals(101L, nextObservation.receivedAtElapsedRealtimeMs)
    }

    @Test fun replacingRegistrationOrAdapterAndClosingRejectsOldReceipts() {
        val source = FreshDiscoverySource(Transport.LAN)
        val oldEpoch = source.currentEpoch
        val old = requireNotNull(source.capture(DiscoveryObservationKind.LAN_NSD, 100L))
        source.advanceEpoch()
        assertNull(source.capture(DiscoveryObservationKind.LAN_NSD, 200L, oldEpoch))
        assertNull(source.accept(old, candidate("endpoint", SESSION_A)) { error("Old registration") })
        val other = FreshDiscoverySource(Transport.LAN)
        assertNull(other.accept(old, candidate("endpoint", SESSION_A)) { error("Old adapter") })
        source.close()
        assertNull(source.capture(DiscoveryObservationKind.LAN_UDP, 201L))
    }

    @Test fun invalidOrProvisionalIdentityDoesNotClaimTheRegistry() {
        val source = FreshDiscoverySource(Transport.LAN)
        val receipt = requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, 100L))
        val valid = candidate("endpoint", SESSION_A)
        for (identity in listOf(
            valid.identity.copy(claimedDeviceId = null),
            valid.identity.copy(sourceSessionId = null),
            valid.identity.copy(claimedDeviceId = "not-a-uuid"),
            valid.identity.copy(protocolVersion = 1)
        )) {
            assertNull(source.accept(receipt, valid.copy(identity = identity)) {
                error("Invalid identity reached registry")
            })
        }
        assertNotNull(source.accept(receipt, valid) { true })
    }

    private fun candidate(endpoint: String, session: RuntimeSessionId) = DiscoveryCandidate(
        Transport.LAN, endpoint, "127.0.0.1", 8890,
        DiscoveryIdentityClaim(DEVICE, session, "rider", "phone", 2)
    )

    companion object {
        private const val DEVICE = "00000000-0000-4000-8000-000000000001"
        private val SESSION_A = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        private val SESSION_B = RuntimeSessionId("10000000-0000-4000-8000-000000000002")
    }
}
