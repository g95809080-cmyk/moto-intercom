package com.kuma.motointercom

import android.Manifest

internal object PermissionPolicy {
    fun corePermissions(apiLevel: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        if (apiLevel >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            if (apiLevel >= 31) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (apiLevel >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    fun optionalPermissions(apiLevel: Int): List<String> = buildList {
        if (apiLevel >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun bluetoothPermissions(apiLevel: Int): List<String> =
        if (apiLevel >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

    fun canStart(apiLevel: Int, granted: (String) -> Boolean): Boolean =
        corePermissions(apiLevel).all(granted)

    fun canBootstrap(apiLevel: Int, granted: (String) -> Boolean): Boolean =
        bluetoothPermissions(apiLevel).all(granted)
}
