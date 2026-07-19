package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAttemptLeaseTest {
    @Test
    fun recoveryAttemptIsBoundBeforeAdapterStarts() {
        val attempt = attempt("10000000-0000-4000-8000-000000000001")

        val lease = LanAttemptLease(attempt)

        assertEquals(attempt, lease.current)
    }

    @Test
    fun completedAttemptReleasesTargetForPassiveIngress() {
        val lease = LanAttemptLease()
        val attempt = attempt("10000000-0000-4000-8000-000000000001")

        lease.bind(attempt)

        assertTrue(lease.release(attempt.copy()))
        assertNull(lease.current)
    }

    @Test
    fun staleAttemptCannotReleaseNewTarget() {
        val lease = LanAttemptLease()
        val old = attempt("10000000-0000-4000-8000-000000000001")
        val current = attempt("10000000-0000-4000-8000-000000000002")

        lease.bind(old)
        lease.bind(current)

        assertFalse(lease.release(old))
        assertEquals(current, lease.current)
    }

    private fun attempt(id: String) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = RuntimeSessionId("20000000-0000-4000-8000-000000000001"),
        targetLock = TargetLock(
            targetDeviceId = "30000000-0000-4000-8000-000000000001",
            expectedRemoteSessionId = RuntimeSessionId(
                "40000000-0000-4000-8000-000000000001"
            )
        ),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 10_000L
    )
}
