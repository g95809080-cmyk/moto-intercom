package com.kuma.motointercom

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

/** Owns permissions, service lifecycle, preferences, and callback forwarding. */
internal class MainActivity : Activity(), IntercomService.Listener {
    private lateinit var screen: MainScreen
    private var intercomService: IntercomService? = null
    private var bindingRegistered = false
    private var serviceConnected = false
    private var intercomState: IntercomState = IntercomState.Offline
    private var lastToggleElapsed = 0L
    private var optionalPermissionRequested = false
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "service connected")
            val local = service as IntercomService.LocalBinder
            intercomService = local.service()
            serviceConnected = true
            intercomService?.setListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "service disconnected")
            serviceConnected = false
            intercomService = null
            setIntercomState(IntercomState.Offline)
            screen.setStatus("后台服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = MainScreen(
            activity = this,
            initialRiderName = prefs.getString(KEY_RIDER_NAME, "").orEmpty(),
            onToggleIntercom = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastToggleElapsed < TOGGLE_DEBOUNCE_MS) return@MainScreen
                when (intercomState) {
                    IntercomState.Offline -> {
                        lastToggleElapsed = now
                        startIntercom()
                    }
                    is IntercomState.Stopping -> Unit
                    else -> {
                        lastToggleElapsed = now
                        stopIntercom()
                    }
                }
            },
            onConnectDevice = { device ->
                intercomService?.connectToLanDevice(device)
                    ?: Toast.makeText(this, "后台服务未就绪", Toast.LENGTH_SHORT).show()
            }
        )
        setContentView(screen.root)
        ensureCorePermissions()
    }

    override fun onStart() {
        super.onStart()
        if (!bindIntercomService(flags = 0)) {
            setIntercomState(IntercomState.Offline)
        }
        requestOptionalPermissionsIfNeeded()
    }

    override fun onStop() {
        intercomService?.setListener(null)
        if (bindingRegistered) unbindService(serviceConnection)
        bindingRegistered = false
        serviceConnected = false
        intercomService = null
        screen.stopAnimations()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CORE_PERMISSIONS -> {
                val canStart = hasCorePermissions()
                screen.setIntercomState(intercomState, canStart)
                screen.setStatus(if (canStart) READY_STATUS else "缺少必要权限，无法启动摩声")
                if (canStart) requestOptionalPermissionsIfNeeded()
            }

            REQUEST_OPTIONAL_PERMISSIONS -> {
                if (permissions.indices.any { grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED }) {
                    screen.appendLog("通知权限未授予；不影响对讲启动")
                }
                screen.setIntercomState(intercomState, hasCorePermissions())
            }
        }
    }

    override fun onStatusChanged(status: String, running: Boolean) {
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            Log.d(TAG, "service status running=$running status=$status")
            screen.setStatus(status)
        }
    }

    override fun onIntercomStateChanged(state: IntercomState) {
        runOnUiThread {
            if (serviceConnected) setIntercomState(state)
        }
    }

    override fun onAudioSourceChanged(status: String, bluetooth: Boolean) {
        runOnUiThread {
            if (serviceConnected) screen.setAudioSource(status, bluetooth)
        }
    }

    override fun onPresencesChanged(presences: List<RiderPresence>) {
        runOnUiThread {
            if (serviceConnected) screen.setPresences(presences)
        }
    }

    override fun onAudioLevelChanged(level: Float) {
        runOnUiThread {
            if (serviceConnected) screen.setAudioLevel(level)
        }
    }

    override fun onLog(message: String) {
        runOnUiThread {
            if (serviceConnected) screen.appendLog(message)
        }
    }

    override fun onToast(message: String) {
        runOnUiThread {
            if (serviceConnected) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRemoteRiderIdentified(name: String) {
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            screen.setRemoteRider(name)
            screen.appendLog("远端骑士昵称：$name")
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            screen.appendLog("错误：$message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureCorePermissions() {
        val missing = PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            screen.setIntercomState(intercomState, true)
            screen.setStatus(READY_STATUS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            screen.setIntercomState(intercomState, false)
            screen.setStatus("正在申请必要权限...")
            requestPermissions(missing.toTypedArray(), REQUEST_CORE_PERMISSIONS)
        }
    }

    private fun requestOptionalPermissionsIfNeeded() {
        if (optionalPermissionRequested || !hasCorePermissions()) return
        val missing = PermissionPolicy.optionalPermissions(Build.VERSION.SDK_INT).filterNot(::hasPermission)
        if (missing.isEmpty()) return
        optionalPermissionRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(missing.toTypedArray(), REQUEST_OPTIONAL_PERMISSIONS)
        }
    }

    private fun startIntercom() {
        if (!ensureWifiEnabled()) return
        if (!hasCorePermissions()) {
            ensureCorePermissions()
            return
        }

        screen.setStatus(SEARCHING_STATUS)
        val riderName = screen.riderName
        prefs.edit().putString(KEY_RIDER_NAME, riderName).apply()
        val intent = IntercomService.startIntent(this, riderName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindIntercomService(intent, Context.BIND_AUTO_CREATE)
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) return true

        screen.setStatus(WIFI_OFF_STATUS)
        Toast.makeText(this, WIFI_OFF_STATUS, Toast.LENGTH_LONG).show()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivityForResult(intent, RC_WIFI_PANEL)
            } else {
                startActivity(intent)
            }
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        return false
    }

    private fun stopIntercom() {
        screen.setStatus(STOPPING_STATUS)
        intercomService?.requestStop() ?: startService(IntercomService.stopIntent(this))
    }

    private fun bindIntercomService(
        intent: Intent = Intent(this, IntercomService::class.java),
        flags: Int
    ): Boolean {
        if (bindingRegistered && serviceConnected) return true
        if (bindingRegistered) {
            runCatching { unbindService(serviceConnection) }
            bindingRegistered = false
        }
        bindingRegistered = bindService(intent, serviceConnection, flags)
        return bindingRegistered
    }

    private fun setIntercomState(state: IntercomState) {
        if (intercomState != state) Log.d(TAG, "intercom UI state $intercomState -> $state")
        intercomState = state
        screen.setIntercomState(state, hasCorePermissions())
    }

    private fun hasCorePermissions(): Boolean =
        PermissionPolicy.canStart(Build.VERSION.SDK_INT, ::hasPermission)

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_CORE_PERMISSIONS = 1001
        const val REQUEST_OPTIONAL_PERMISSIONS = 1002
        const val RC_WIFI_PANEL = 1003
        const val PREFS_NAME = "moto_intercom"
        const val KEY_RIDER_NAME = "rider_name"
        const val READY_STATUS = "请点击下方启动对讲"
        const val SEARCHING_STATUS = "无线配对中，请把两台手机靠近.."
        const val STOPPING_STATUS = "正在结束对讲..."
        const val WIFI_OFF_STATUS = "⚠️ 请先打开 Wi-Fi开关"
        const val TOGGLE_DEBOUNCE_MS = 2_000L
        const val TAG = "MotoIntercomUi"
    }
}
