package com.kuma.motointercom

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings

internal fun locationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

internal fun backgroundAllowed(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

/** One user start intent, independent of whether the tutorial was skipped. */
internal class StartupAccessController(
    private val activity: Activity,
    private val status: (String) -> Unit,
    private val refresh: () -> Unit,
    private val start: () -> Unit
) {
    private val preferences = activity.getSharedPreferences("startup_access", Context.MODE_PRIVATE)
    private var pending = false
    private var waiting = false
    private var dialog: AlertDialog? = null

    fun restore(state: Bundle?) {
        pending = state?.getBoolean("startup_pending") ?: false
        waiting = state?.getBoolean("startup_waiting") ?: false
    }

    fun save(state: Bundle) {
        state.putBoolean("startup_pending", pending)
        // Dialogs are rebuilt on recreation; OS requests keep their result callback.
        state.putBoolean("startup_waiting", waiting && dialog == null)
    }

    fun begin() {
        pending = true
        advance()
    }

    fun onResume() {
        refresh()
        if (pending && !waiting) advance()
    }

    private fun granted(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun missingCore() = PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).filterNot(::granted)

    private fun advance() {
        if (!pending || waiting) return
        val core = missingCore()
        val optional = PermissionPolicy.optionalPermissions(Build.VERSION.SDK_INT)
            .filterNot(::granted).filterNot { preferences.getBoolean(it, false) }
        if (core.isNotEmpty() || optional.isNotEmpty()) {
            val permanentlyDenied = core.any {
                preferences.getBoolean(it, false) && !activity.shouldShowRequestPermissionRationale(it)
            }
            if (permanentlyDenied) {
                showPermissionSettings()
                return
            }
            val permissions = (core + optional).distinct()
            preferences.edit().apply { permissions.forEach { putBoolean(it, true) } }.apply()
            waiting = true
            status("请允许录音、电话状态及附近设备权限；蓝牙和通知用于耳机与锁屏提醒")
            activity.requestPermissions(permissions.toTypedArray(), PERMISSIONS)
            return
        }
        @Suppress("DEPRECATION")
        val wifiEnabled = (activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        if (!wifiEnabled) {
            status("请打开 Wi-Fi 开关，返回后继续启动")
            launch(Intent(Settings.ACTION_WIFI_SETTINGS), WIFI)
            return
        }
        if (!locationEnabled(activity)) {
            status(LOCATION_REQUIRED)
            showDialog("打开位置信息", "发现附近车友需要系统位置信息开关开启。摩声不会读取或上传你的位置。", "去开启",
                { launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), LOCATION) }, { cancel() })
            return
        }
        if (!backgroundAllowed(activity) && !preferences.getBoolean("background_prompted", false)) {
            requestBackground()
            return
        }
        pending = false
        start()
    }

    fun onPermissionsResult(code: Int): Boolean {
        if (code != PERMISSIONS) return false
        waiting = false
        refresh()
        if (missingCore().isNotEmpty()) {
            pending = false
            status("缺少必要权限：${missingCore().joinToString("、", transform = ::permissionName)}。请授权后重新启动")
        }
        // Continue only from onPostResume, while starting a microphone FGS is legal.
        return true
    }

    fun onActivityResult(code: Int): Boolean {
        if (code !in setOf(WIFI, LOCATION, APP_SETTINGS, BACKGROUND)) return false
        waiting = false
        val ready = when (code) {
            WIFI -> {
                @Suppress("DEPRECATION")
                (activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
            }
            LOCATION -> locationEnabled(activity)
            APP_SETTINGS -> missingCore().isEmpty()
            else -> true // Battery exemption is optional, including a denied OS request.
        }
        if (!ready) {
            pending = false
            status(if (code == LOCATION) LOCATION_REQUIRED else "启动条件尚未满足，请设置后重新启动")
        }
        refresh()
        return true
    }

    fun requestBackground() {
        showDialog("允许后台运行", "锁屏骑行时，建议允许摩声不受电池优化限制。\n\n小米可在应用省电设置中选择“不限制”；荣耀可在应用启动管理中允许后台活动。具体名称以手机设置为准。", "去设置", {
            preferences.edit().putBoolean("background_prompted", true).apply()
            val intent = if (backgroundAllowed(activity)) appSettingsIntent() else
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            launch(intent, BACKGROUND, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, {
            preferences.edit().putBoolean("background_prompted", true).apply()
            advance()
        })
    }

    private fun showPermissionSettings() {
        status("缺少必要权限：${missingCore().joinToString("、", transform = ::permissionName)}")
        showDialog("补齐通话权限", "必要权限尚未授予，请在系统应用权限中允许后返回，无需清除应用数据。", "去授权",
            { launch(appSettingsIntent(), APP_SETTINGS) }, { cancel() })
    }

    private fun appSettingsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

    private fun showDialog(title: String, message: String, positive: String, accept: () -> Unit, skip: () -> Unit) {
        if (dialog != null) return
        waiting = true
        dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(positive) { _, _ -> dialog = null; waiting = false; accept() }
            .setNegativeButton("暂不设置") { _, _ -> dialog = null; waiting = false; skip() }
            .setOnCancelListener { dialog = null; waiting = false; skip() }.show()
    }

    private fun launch(intent: Intent, code: Int, fallback: Intent = appSettingsIntent()) {
        waiting = true
        try {
            activity.startActivityForResult(intent, code)
        } catch (_: RuntimeException) {
            try { activity.startActivityForResult(fallback, code) } catch (_: RuntimeException) {
                waiting = false
                pending = false
                status("无法打开系统设置，请在手机设置中找到摩声后设置，再返回启动")
            }
        }
    }

    private fun cancel() { pending = false }

    fun close() { dialog?.dismiss(); dialog = null }

    companion object {
        const val PERMISSIONS = 1101
        const val WIFI = 1102
        const val LOCATION = 1103
        const val APP_SETTINGS = 1104
        const val BACKGROUND = 1105
        const val LOCATION_REQUIRED = "请开启系统位置信息，以发现附近车友"

        private fun permissionName(permission: String) = when (permission) {
            android.Manifest.permission.RECORD_AUDIO -> "麦克风"
            android.Manifest.permission.READ_PHONE_STATE -> "电话状态"
            android.Manifest.permission.NEARBY_WIFI_DEVICES -> "附近设备"
            else -> "精确位置"
        }
    }
}
