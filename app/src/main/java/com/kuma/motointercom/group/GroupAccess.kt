package com.kuma.motointercom.group

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kuma.motointercom.AudioRouteSelection

internal fun groupRequiredPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE))
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    else add(Manifest.permission.ACCESS_FINE_LOCATION)
}
internal fun Context.hasGroupPermissions() = groupRequiredPermissions().all {
    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}
internal data class GroupServiceState(
    val snapshot: GroupSessionSnapshot? = null,
    val message: String = "创建房间或输入六位码，与附近车友离线对讲",
    val busy: Boolean = false,
    val audioLabel: String = "音频待机",
    val route: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    val vox: Boolean = true
)
