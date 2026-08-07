package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectPendingDiscoveryRecoveryTest {
    @Test
    fun escalatesFromDiscoverToRequestReprimeAndLocalServiceReregister() {
        val recovery = WifiDirectPendingDiscoveryRecovery()

        assertEquals(
            WifiDirectPendingDiscoveryRecovery.Action.DISCOVER,
            checkNotNull(recovery.next(true, false, false)).action
        )
        assertEquals(
            WifiDirectPendingDiscoveryRecovery.Action.REPRIME_REQUEST,
            checkNotNull(recovery.next(true, false, false)).action
        )
        assertEquals(
            WifiDirectPendingDiscoveryRecovery.Action.REPRIME_REQUEST,
            checkNotNull(recovery.next(true, false, false)).action
        )
        assertEquals(
            WifiDirectPendingDiscoveryRecovery.Action.REREGISTER_LOCAL_SERVICE,
            checkNotNull(recovery.next(true, false, false)).action
        )
        assertNull(recovery.next(true, false, false))
    }

    @Test
    fun stopsWhenPeerIsAcceptedOrRetryIsBlocked() {
        val recovery = WifiDirectPendingDiscoveryRecovery()

        assertNull(recovery.next(false, false, false))
        assertNull(recovery.next(true, true, false))
        assertNull(recovery.next(true, false, true))

        val first = checkNotNull(recovery.next(true, false, false))
        assertEquals(1, first.attempt)
    }

    @Test
    fun invalidationRejectsStaleRetryAndStartsFreshGeneration() {
        val recovery = WifiDirectPendingDiscoveryRecovery()
        val stale = checkNotNull(recovery.next(true, false, false))

        recovery.invalidate()

        assertFalse(recovery.isCurrent(stale))
        val fresh = checkNotNull(recovery.next(true, false, false))
        assertTrue(recovery.isCurrent(fresh))
        assertEquals(stale.generation + 1, fresh.generation)
    }
}
