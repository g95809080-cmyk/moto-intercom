package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectPeerRegistryTest {
    private val targetLock = TargetLock("device-b", RuntimeSessionId("session-b"))

    @Test
    fun removesDepartedAcceptedPeerWithoutSelectingAnotherPeer() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("A", "B"))
        peers.accept("A")
        peers.accept("B")

        val snapshot = peers.reconcile(setOf("B"))

        assertFalse(snapshot.accepted.contains("A"))
        assertEquals(setOf("B"), snapshot.accepted)
    }

    @Test
    fun clearsCandidatesWhenNoAcceptedPeerRemains() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("A"))
        peers.accept("A")

        val snapshot = peers.reconcile(emptySet())

        assertEquals(emptySet<String>(), snapshot.pending)
        assertEquals(emptySet<String>(), snapshot.accepted)
    }

    @Test
    fun matchesOnlyTheExplicitTargetGroup() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("AA", "BB"))
        peers.markPending("BB")

        assertEquals(
            WifiDirectPeerRegistry.GroupMatch.REJECTED,
            peers.matchGroup("BB", isGroupOwner = true, owner = "LOCAL", clients = listOf("AA"))
        )
        assertEquals(
            WifiDirectPeerRegistry.GroupMatch.PENDING,
            peers.matchGroup("BB", isGroupOwner = true, owner = "LOCAL", clients = listOf("BB"))
        )

        peers.accept("BB")

        assertEquals(
            WifiDirectPeerRegistry.GroupMatch.MATCHED,
            peers.matchGroup("BB", isGroupOwner = true, owner = "LOCAL", clients = listOf("BB"))
        )
    }

    @Test
    fun fasterThirdDeviceCannotReplaceTheLockedTarget() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("C", "B", "A"))
        peers.accept("C")
        peers.accept("A")
        peers.accept("B")
        val claims = mapOf(
            "A" to claim("device-a", "session-a"),
            "B" to claim("device-b", "session-b"),
            "C" to claim("device-c", "session-c")
        )

        assertEquals("B", peers.findAcceptedAddress(claims, targetLock))
    }

    @Test
    fun sessionRolloverInvalidatesAStaleTargetLock() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("B"))
        peers.accept("B")

        assertNull(
            peers.findAcceptedAddress(
                mapOf("B" to claim("device-b", "session-new")),
                targetLock
            )
        )
    }

    @Test
    fun lateOldTxtClaimIsClassifiedAsSuperseded() {
        val tracker = DiscoverySessionTracker()

        assertEquals(
            DiscoverySessionRegistration.NEW_ACTIVE,
            tracker.register(claim("device-b", "session-old"))
        )
        assertEquals(
            DiscoverySessionRegistration.NEW_ACTIVE,
            tracker.register(claim("device-b", "session-new"))
        )
        assertEquals(
            DiscoverySessionRegistration.SUPERSEDED,
            tracker.register(claim("device-b", "session-old"))
        )
        assertEquals(true, tracker.isActive("device-b", RuntimeSessionId("session-new")))
    }

    @Test
    fun rejectsGroupWhenThereIsNoTargetLockAddress() {
        val peers = WifiDirectPeerRegistry()

        assertEquals(
            WifiDirectPeerRegistry.GroupMatch.REJECTED,
            peers.matchGroup(null, isGroupOwner = false, owner = "STRANGER", clients = emptyList())
        )
    }

    private fun claim(deviceId: String, sessionId: String) = DiscoveryIdentityClaim(
        claimedDeviceId = deviceId,
        sourceSessionId = RuntimeSessionId(sessionId),
        nickname = deviceId,
        deviceName = "Phone",
        protocolVersion = 2
    )
}
