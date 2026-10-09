package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class DiscoveryPresenceAdmissionTest {
    @Test fun cacheCannotIntroduceRuntimeAndDelayedOlderProducerCannotRetireNewerRuntime() {
        val gate = DiscoveryPresenceAdmission()
        val lan = FreshDiscoverySource(Transport.LAN)
        val wifi = FreshDiscoverySource(Transport.WIFI_DIRECT)
        val old = input(lan, A, 100L)
        val current = input(wifi, B, 200L)
        gate.replaceCached(Transport.WIFI_DIRECT, listOf(current.candidate))
        assertTrue(gate.eligibleCandidates(Transport.WIFI_DIRECT).isEmpty())
        assertTrue(gate.admit(current))
        gate.replaceCached(Transport.LAN, listOf(old.candidate))
        assertFalse(gate.admit(old))
        assertTrue(gate.eligibleCandidates(Transport.LAN).isEmpty())
        assertEquals(listOf(current.candidate), gate.eligibleCandidates(Transport.WIFI_DIRECT))
        // The known older runtime stays retired even if its other producer receives again.
        val oldAgain = input(lan, A, 300L)
        gate.replaceCached(Transport.LAN, listOf(oldAgain.candidate))
        assertFalse(gate.admit(oldAgain))
        val next = input(wifi, C, 400L)
        gate.replaceCached(Transport.WIFI_DIRECT, listOf(next.candidate))
        assertTrue(gate.admit(next))
        assertEquals(listOf(next.candidate), gate.eligibleCandidates(Transport.WIFI_DIRECT))
    }

    @Test fun replacementOrRemovalRevokesTheFinalClaimWithoutCreatingFreshEvidence() {
        val gate = DiscoveryPresenceAdmission()
        val lan = FreshDiscoverySource(Transport.LAN)
        val wifi = FreshDiscoverySource(Transport.WIFI_DIRECT)
        val a = input(lan, A, 100L)
        gate.replaceCached(Transport.LAN, listOf(a.candidate)); assertTrue(gate.admit(a))
        assertEquals(true, gate.claimIfCurrent(a) { true })
        val b = input(wifi, B, 200L)
        gate.replaceCached(Transport.WIFI_DIRECT, listOf(b.candidate)); assertTrue(gate.admit(b))
        assertNull(gate.claimIfCurrent(a) { error("Superseded runtime claimed") })
        gate.replaceCached(Transport.WIFI_DIRECT, emptyList())
        assertNull(gate.claimIfCurrent(b) { error("Removed candidate claimed") })
        gate.replaceCached(Transport.WIFI_DIRECT, listOf(b.candidate))
        assertEquals(true, gate.claimIfCurrent(b) { true })
        gate.clear()
        assertNull(gate.claimIfCurrent(b) { error("Old runtime claimed after restart") })
        assertTrue(gate.eligibleCandidates(Transport.WIFI_DIRECT).isEmpty())
    }

    @Test fun EqualTimeRuntimeChangeWaitsForNextPhysicalInputWithoutRetiringIt() {
        val gate = DiscoveryPresenceAdmission()
        val lan = FreshDiscoverySource(Transport.LAN)
        val wifi = FreshDiscoverySource(Transport.WIFI_DIRECT)
        val a = input(lan, A, 100L)
        gate.replaceCached(Transport.LAN, listOf(a.candidate)); assertTrue(gate.admit(a))
        val b = input(wifi, B, 100L)
        gate.replaceCached(Transport.WIFI_DIRECT, listOf(b.candidate)); assertFalse(gate.admit(b))
        val bLater = input(wifi, B, 101L)
        gate.replaceCached(Transport.WIFI_DIRECT, listOf(bLater.candidate)); assertTrue(gate.admit(bLater))
    }

    @Test fun staleSourceAndAbsentRawProofCannotWriteGlobalHistory() {
        val gate = DiscoveryPresenceAdmission()
        val source = FreshDiscoverySource(Transport.LAN)
        val absent = input(source, A, 100L)
        assertFalse(gate.admit(absent))
        gate.replaceCached(Transport.LAN, listOf(absent.candidate))
        source.advanceEpoch()
        assertFalse(gate.admit(absent))
        val actual = input(source, A, 101L)
        assertTrue(gate.admit(actual))
    }

    private fun input(source: FreshDiscoverySource, runtime: RuntimeSessionId, at: Long): FreshDiscoveryObservation {
        val kind = if (source.transport == Transport.LAN) DiscoveryObservationKind.LAN_UDP else DiscoveryObservationKind.WIFI_DIRECT_TXT
        val receipt = requireNotNull(source.capture(kind, at))
        val candidate = DiscoveryCandidate(source.transport, source.transport.name, "127.0.0.1", 8888,
            DiscoveryIdentityClaim(DEVICE, runtime, "Rider", "Phone", 2))
        return requireNotNull(source.accept(receipt, candidate) { true }).second
    }

    companion object {
        private const val DEVICE = "00000000-0000-4000-8000-000000000001"
        private val A = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        private val B = RuntimeSessionId("10000000-0000-4000-8000-000000000002")
        private val C = RuntimeSessionId("10000000-0000-4000-8000-000000000003")
    }
}
