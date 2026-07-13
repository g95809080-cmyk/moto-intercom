package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectAutoConnectPolicyTest {
    @Test
    fun waitsUntilVerifiedPeerBecomesAvailable() {
        assertFalse(
            WifiDirectAutoConnectPolicy.shouldConnect(
                autoConnect = true,
                peerAvailable = false,
                validatingGroup = false,
                connecting = false,
                connectionActive = false
            )
        )
        assertTrue(
            WifiDirectAutoConnectPolicy.shouldConnect(
                autoConnect = true,
                peerAvailable = true,
                validatingGroup = false,
                connecting = false,
                connectionActive = false
            )
        )
    }

    @Test
    fun doesNotReconnectDuringConnectionValidationOrSignaling() {
        assertFalse(WifiDirectAutoConnectPolicy.shouldConnect(true, true, false, true, false))
        assertFalse(WifiDirectAutoConnectPolicy.shouldConnect(true, true, true, false, false))
        assertFalse(WifiDirectAutoConnectPolicy.shouldConnect(true, true, false, false, true))
    }

    @Test
    fun respectsDisabledAutoConnect() {
        assertFalse(WifiDirectAutoConnectPolicy.shouldConnect(false, true, false, false, false))
    }
}
