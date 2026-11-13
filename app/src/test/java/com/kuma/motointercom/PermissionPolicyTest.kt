package com.kuma.motointercom

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun api30RequiresLegacyBluetoothPermissionsForBootstrap() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ),
            PermissionPolicy.bluetoothPermissions(30).toSet()
        )
        assertTrue(
            PermissionPolicy.canBootstrap(30) { permission ->
                permission == Manifest.permission.BLUETOOTH ||
                    permission == Manifest.permission.BLUETOOTH_ADMIN
            }
        )
    }

    @Test
    fun api30BluetoothDenialBlocksAutomaticBootstrap() {
        assertFalse(
            PermissionPolicy.canBootstrap(30) {
                it != Manifest.permission.BLUETOOTH_ADMIN
            }
        )
    }

    @Test
    fun api32CoreRequiresBluetoothForAutomaticBootstrap() {
        assertEquals(
            setOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ),
            PermissionPolicy.corePermissions(32).toSet()
        )
        assertTrue(PermissionPolicy.optionalPermissions(32).isEmpty())
    }

    @Test
    fun bluetoothDenialBlocksAutomaticBootstrap() {
        assertFalse(PermissionPolicy.canBootstrap(32) { it != Manifest.permission.BLUETOOTH_CONNECT })
    }

    @Test
    fun phonePermissionDenialBlocksIntercomStart() {
        assertFalse(
            PermissionPolicy.canStart(32) {
                it != Manifest.permission.READ_PHONE_STATE
            }
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
