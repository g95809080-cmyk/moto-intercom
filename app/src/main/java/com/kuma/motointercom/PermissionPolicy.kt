package com.kuma.motointercom

import android.Manifest

internal object PermissionPolicy {
    fun corePermissions(apiLevel: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (apiLevel >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            if (apiLevel >= 31) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun optionalPermissions(apiLevel: Int): List<String> = buildList {
        if (apiLevel >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (apiLevel >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun canStart(apiLevel: Int, granted: (String) -> Boolean): Boolean =
        corePermissions(apiLevel).all(granted)
}
