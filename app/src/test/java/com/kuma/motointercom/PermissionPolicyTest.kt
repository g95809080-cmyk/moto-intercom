package com.kuma.motointercom

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun api32NeedsAudioLocationAndBluetooth() {
        assertEquals(
            setOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            PermissionPolicy.corePermissions(32).toSet()
        )
    }

    @Test
    fun notificationDenialDoesNotBlockApi33Core() {
        assertFalse(PermissionPolicy.corePermissions(33).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            PermissionPolicy.optionalPermissions(33)
        )
        assertTrue(PermissionPolicy.canStart(33) { it != Manifest.permission.POST_NOTIFICATIONS })
    }
}
