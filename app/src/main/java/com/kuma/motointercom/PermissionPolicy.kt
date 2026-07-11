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
        if (apiLevel >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun optionalPermissions(apiLevel: Int): List<String> =
        if (apiLevel >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun canStart(apiLevel: Int, granted: (String) -> Boolean): Boolean =
        corePermissions(apiLevel).all(granted)
}
