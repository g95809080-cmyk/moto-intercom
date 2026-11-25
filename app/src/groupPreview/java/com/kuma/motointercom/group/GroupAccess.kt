package com.kuma.motointercom.group

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kuma.motointercom.AudioRouteSelection

internal fun groupRequiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (sdkInt >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE))
    if (sdkInt >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    else {
        if (sdkInt >= 31) add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
internal fun Context.hasGroupPermissions(sdkInt: Int = Build.VERSION.SDK_INT) = groupRequiredPermissions(sdkInt).all {
    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

internal fun networkGroupPromptPermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
}

internal fun Context.missingGroupPromptPermissions(required: List<String>, hybrid: Boolean = false): List<String> {
    val requested = required.toMutableSet()
    requested += Manifest.permission.READ_PHONE_STATE
    if (Build.VERSION.SDK_INT >= 33) {
        requested += Manifest.permission.POST_NOTIFICATIONS
        if (hybrid) requested += Manifest.permission.ACCESS_FINE_LOCATION
    }
    requested.removeAll { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    if (Build.VERSION.SDK_INT >= 31 && Manifest.permission.ACCESS_FINE_LOCATION in requested) {
        requested += Manifest.permission.ACCESS_COARSE_LOCATION
    }
    return requested.toList()
}

internal fun Context.hasNetworkGroupPermissions(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

internal data class GroupServiceState(
    val snapshot: GroupSessionSnapshot? = null,
    val message: String = "创建房间或输入六位码，与附近车友离线对讲",
    val busy: Boolean = false,
    val audioLabel: String = "音频待机",
    val route: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    val vox: Boolean = true
)

internal fun GroupRole.displayLabel(): String = when (this) {
    GroupRole.OWNER -> "群主"
    GroupRole.RIDING_OFFICER -> "骑行官"
    GroupRole.HEAD -> "头车"
    GroupRole.TAIL -> "尾车"
    GroupRole.MEMBER -> "普通成员"
}

internal fun GroupChannel.displayLabel(): String = when (this) {
    GroupChannel.Public -> "公共频道"
    GroupChannel.Rider -> "领队频道"
    is GroupChannel.Private -> "私聊频道"
}
