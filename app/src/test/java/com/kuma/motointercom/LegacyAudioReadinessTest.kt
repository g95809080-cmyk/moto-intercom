package com.kuma.motointercom
import org.junit.Assert.*
import org.junit.Test
class LegacyAudioReadinessTest {
    private fun sample(n: Long, io: Boolean = true, gate: Long = 1) = RiderMediaEvidence(
        RiderRtpCounters(setOf("in", "out"), n, n), true, true, io, gate, 1)
    private val route = AudioRouteEvidence(1, true)
    @Test fun realGrowthEnablesReadinessAndVoxSilenceDoesNotUndoProof() {
        val gate = LegacyAudioReadiness()
        assertFalse(gate.update(sample(1), route, route))
        assertTrue(gate.update(sample(2), route, route))
        assertTrue(gate.update(sample(2), route, route))
        assertFalse(gate.update(sample(3, io = false), route, route))
        assertFalse(gate.update(sample(4), route, route))
        assertTrue(gate.update(sample(5), route, route))
    }
    @Test fun routeChangeDuringQueryOrBetweenSamplesRequiresNewProof() {
        val gate = LegacyAudioReadiness(); val next = route.copy(revision = 2)
        gate.update(sample(1), route, route)
        assertFalse(gate.update(sample(2), route, next))
        assertFalse(gate.update(sample(3), next, next))
        assertTrue(gate.update(sample(4), next, next))
        assertFalse(gate.update(sample(5, gate = 2), next, next))
        assertFalse(gate.update(null, next, next))
    }
    @Test fun connectedTrackAloneAndReplacedStreamsCannotReportReady() {
        val gate = LegacyAudioReadiness()
        assertFalse(gate.update(sample(1), route.copy(ready = false), route.copy(ready = false)))
        gate.update(sample(2), route, route)
        assertFalse(gate.update(sample(3).copy(remoteTrack = false), route, route))
        gate.update(sample(4), route, route)
        assertFalse(gate.update(sample(5).copy(counters = RiderRtpCounters(setOf("new"), 5, 5)), route, route))
        assertFalse(gate.reset())
    }
}
