package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectPeerRegistryTest {
    @Test
    fun removesDepartedAcceptedPeerAndSelectsRemainingPeer() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("A", "B"))
        peers.accept("A")
        peers.accept("B")

        val snapshot = peers.reconcile(setOf("B"))

        assertFalse(snapshot.accepted.contains("A"))
        assertEquals("B", snapshot.selected)
    }

    @Test
    fun clearsSelectionWhenNoAcceptedPeerRemains() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("A"))
        peers.accept("A")

        val snapshot = peers.reconcile(emptySet())

        assertNull(snapshot.selected)
        assertEquals(emptySet<String>(), snapshot.pending)
        assertEquals(emptySet<String>(), snapshot.accepted)
    }
}
