package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class AutomaticRecoveryPolicyTest {
    @Test fun offThenOnCannotReviveAnOldTicketButAllowsANewLoss() {
        val policy = AutomaticRecoveryPolicy()
        val source = attempt()
        val ticket = requireNotNull(policy.captureLoss(source))
        policy.setEnabled(false)
        policy.setEnabled(true)
        assertNull(policy.tryConsume(ticket) { error("Canceled ticket reached graph") })
        assertNotNull(policy.captureLoss(source))
        assertNotNull(policy.captureLoss(source.copy(id = ConnectionAttemptId.create())))
        policy.beginRuntime()
        assertNotNull(policy.captureLoss(source))
    }

    @Test fun forgettingARejectsItsUnpublishedGoalWithoutCancelingB() {
        val policy = AutomaticRecoveryPolicy()
        val a = requireNotNull(policy.capture(DEVICE_A))
        val b = requireNotNull(policy.capture(DEVICE_B))
        policy.cancelTarget(DEVICE_A)
        assertNull(policy.tryConsume(a) { error("Forgotten A reached graph") })
        assertEquals("B", policy.tryConsume(b) { "B" })
        assertNull(policy.capture(DEVICE_A))
        policy.manualChoice(DEVICE_A)
        assertNotNull(policy.capture(DEVICE_A))
        assertNull(policy.capture(DEVICE_A, preferred = true))
    }

    @Test fun standingGoalUsesOriginalAuthorizationAndEachAdoptionIsConsumedOnce() {
        val policy = AutomaticRecoveryPolicy()
        val initial = requireNotNull(policy.capture(DEVICE_A))
        assertEquals(true, policy.tryConsume(initial) { true })
        assertNull(policy.tryConsume(initial) { error("Duplicate adoption") })
        val episode = requireNotNull(policy.captureForGoal(initial))
        policy.cancelAutomaticStarts()
        assertNull(policy.tryConsume(episode) { error("Canceled future episode") })
        assertNull(policy.captureForGoal(initial))
        assertNull(policy.capture(DEVICE_B, preferred = true))
    }

    @Test fun anObservationReplacedDuringPreparationCannotAdopt() {
        val source = FreshDiscoverySource(Transport.LAN)
        val candidate = DiscoveryCandidate(Transport.LAN, "endpoint", "127.0.0.1", 8890,
            DiscoveryIdentityClaim(DEVICE_A, REMOTE, "rider", "phone", 2))
        fun fresh(at: Long) = source.accept(requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, at)),
            candidate) { true }!!.second
        val policy = AutomaticRecoveryPolicy()
        val old = requireNotNull(policy.capture(DEVICE_A, preferred = true)).also { it.observation = fresh(100L) }
        val current = requireNotNull(policy.capture(DEVICE_A, preferred = true)).also { it.observation = fresh(200L) }
        assertNull(policy.tryConsume(old) { error("Superseded observation reached graph") })
        assertEquals(true, policy.tryConsume(current) { true })
        assertNull(policy.tryConsume(current) { error("Observation adopted twice") })
    }

    private fun attempt() = ConnectionAttempt(ConnectionAttemptId.create(), LOCAL, TargetLock(DEVICE_A, REMOTE),
        ConnectionTrigger.USER, ChannelPlan.single(Transport.LAN), 10_100L)

    companion object {
        private const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        private const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        private val LOCAL = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        private val REMOTE = RuntimeSessionId("10000000-0000-4000-8000-000000000002")
    }
}
