package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class RideSessionTest {
    private val now = 1_800_000_000_000L
    private fun ride() = RideSession(
        "ride_test", "pair_test", listOf("a", "b"), RideState.LOCAL_ACTIVE, "123456789",
        3, true, null, mapOf("a" to now, "b" to now)
    )

    @Test fun onlyAnEstablishedLocalTransportFailureCanRequestFallback() {
        val active = ride()
        assertTrue(active.mayRequestFallback(LocalSessionEndReason.TRANSPORT_FAILURE, now - 1, now))
        LocalSessionEndReason.entries.filter { it != LocalSessionEndReason.TRANSPORT_FAILURE }.forEach {
            assertFalse(active.mayRequestFallback(it, now - 1, now))
        }
        assertFalse(active.copy(everLocalActive = false).mayRequestFallback(LocalSessionEndReason.TRANSPORT_FAILURE, now - 1, now))
        assertFalse(active.copy(state = RideState.LOCAL_PENDING).mayRequestFallback(LocalSessionEndReason.TRANSPORT_FAILURE, now - 1, now))
        assertFalse(active.mayRequestFallback(LocalSessionEndReason.TRANSPORT_FAILURE, null, now))
    }

    @Test fun resumeRequiresALiveTwoMemberRideWithinTenMinutes() {
        val active = ride()
        assertTrue(active.canManuallyResume(now + 599_999))
        assertFalse(active.canManuallyResume(now + 600_000))
        assertFalse(active.copy(state = RideState.ENDED).canManuallyResume(now))
        assertFalse(active.copy(members = listOf("a", "a")).canManuallyResume(now))
        assertFalse(active.copy(members = listOf("a", "b", "c")).canManuallyResume(now))
        assertFalse(active.copy(lastHeartbeatAt = mapOf("a" to now)).canManuallyResume(now))
    }

    @Test fun admissionRequiresConsentAndCloudApprovalForThisMember() {
        val ready = ride().copy(state = RideState.CLOUD_PENDING, cloudReason = CloudEntryReason.INVITE_ACCEPTED, cloudStartedAt = now)
        fun approve(r: RideSession = ready, consent: Long? = now - 1, local: String = "a") =
            ApprovedCloudRide.from(r, local, "车友", consent, now)
        assertNotNull(approve())
        assertNull(approve(consent = null))
        assertNull(approve(local = "outsider"))
        assertNull(approve(ready.copy(state = RideState.INVITE_PENDING)))
        assertNull(approve(ready.copy(state = RideState.ENDED)))
        assertNull(approve(ready.copy(cloudReason = CloudEntryReason.LOCAL_FAILURE, everLocalActive = false)))
        assertNull(approve(ready.copy(cloudUsedMs = ready.cloudAllowanceMs)))
        assertEquals("b", approve()!!.peerDeviceId)
        assertEquals(now + 4 * 60 * 60 * 1_000L, approve()!!.cloudDeadlineAt)
    }

    @Test fun mediaPolicyKeepsPreparationMuteAndDoesNotExtendAnEmptyRoomDeadline() {
        val policy = CloudMediaPolicy()
        policy.setMuted(true)
        policy.updatePeers(0, 1_000)
        assertTrue(policy.muted)
        policy.updatePeers(0, 40_000)
        assertFalse(policy.peerWaitExpired(60_999))
        assertTrue(policy.peerWaitExpired(61_000))
        policy.updatePeers(1, 62_000)
        assertFalse(policy.peerWaitExpired(100_000))
        policy.updatePeers(0, 101_000)
        assertEquals(161_000L, policy.peerDeadlineMs)
    }

    @Test fun configurationIsOffByDefaultAndRequiresAnHttpsControlPlaneOrigin() {
        assertFalse(GmeConfig(false, "https://api.example.com").isConfigured)
        assertFalse(GmeConfig(true, "http://api.example.com").isConfigured)
        assertFalse(GmeConfig(true, "https://api.example.com/old-ticket").isConfigured)
        assertFalse(GmeConfig(true, "https://user@api.example.com").isConfigured)
        assertTrue(GmeConfig(true, "https://api.example.com").isConfigured)
    }
}
