package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryCoordinatorTest {
    @Test
    fun higherIpv4AddressInitiatesClient() {
        assertTrue(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.20", "192.168.1.10"))
        assertFalse(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.10", "192.168.1.20"))
        assertFalse(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.10", "192.168.1.10"))
    }
}
