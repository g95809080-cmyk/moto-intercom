package com.kuma.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.content.res.Configuration
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.UUID

/** Owns permissions, service lifecycle, preferences, and callback forwarding. */
internal class MainActivity : Activity(), IntercomService.Listener {
    private lateinit var screen: MainScreen
    private var intercomService: IntercomService? = null
    private var bindingRegistered = false
    private var serviceConnected = false
    private var intercomState: IntercomState = IntercomState.Offline
    private var lastToggleElapsed: Long? = null
    private var replayingServiceSnapshot = false
    private var incomingConfirmationDialog: AlertDialog? = null
    private var incomingConfirmationNonce: String? = null
    private var platformBackCallback: Any? = null
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!bindingRegistered) {
                Log.d(TAG, "ignoring late service connection after unbind")
                return
            }
            Log.d(TAG, "service connected")
            val local = service as IntercomService.LocalBinder
            intercomService = local.service()
            serviceConnected = true
            replayingServiceSnapshot = true
            try {
                intercomService?.setListener(this@MainActivity)
            } finally {
                replayingServiceSnapshot = false
            }
            intercomService?.setAppForeground(true)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (!bindingRegistered || !serviceConnected) {
                Log.d(TAG, "ignoring service disconnection before active binding")
                return
            }
            Log.d(TAG, "service disconnected")
            serviceConnected = false
            intercomService = null
            dismissIncomingConfirmation()
            screen.clearServiceOwnedFacts()
            setIntercomState(IntercomState.Offline)
            screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = MainScreen(
            activity = this,
            initialRiderName = prefs.getString(KEY_RIDER_NAME, "").orEmpty(),
            savedState = savedInstanceState?.takeIf {
                it.getString(KEY_PROCESS_SESSION_TOKEN) == PROCESS_SESSION_TOKEN
            },
            onToggleIntercom = {
                val now = SystemClock.elapsedRealtime()
                if (shouldIgnoreToggle(now, lastToggleElapsed)) return@MainScreen
                val action = primaryIntercomAction(intercomState)
                when (action) {
                    PrimaryIntercomAction.START -> {
                        if (shouldRecordToggle(action, startIntercom())) lastToggleElapsed = now
                    }
                    PrimaryIntercomAction.DISCONNECT_CURRENT -> {
                        lastToggleElapsed = now
                        intercomService?.requestDisconnectCurrent()
                    }
                    PrimaryIntercomAction.STOP_RUNTIME -> {
                        lastToggleElapsed = now
                        stopIntercom()
                    }
                    PrimaryIntercomAction.NONE -> Unit
                }
            },
            onConnectPresence = { presence ->
                val service = intercomService
                if (service == null) {
                    showServiceUnavailable()
                    false
                } else {
                    try {
                        service.connectToPresence(presence)
                        true
                    } catch (error: RuntimeException) {
                        Log.e(TAG, "failed to dispatch Presence connection", error)
                        showServiceUnavailable()
                        false
                    }
                }
            },
            onSaveRiderName = { name ->
                prefs.edit().putString(KEY_RIDER_NAME, name).commit()
            },
            onRequestCorePermissions = {
                requestCorePermissions()
            },
            onRequestOptionalPermissions = {
                requestOptionalPermissions()
            },
            onOpenWifiSettings = {
                openWifiSettings()
            },
            onOpenPermissionSettings = {
                openAppPermissionSettings()
            }
        )
        setContentView(screen.root)
        registerPlatformBackCallback()
        refreshCorePermissionPresentation()
        refreshWifiAvailability()
        refreshOptionalPermissionPresentation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PROCESS_SESSION_TOKEN, PROCESS_SESSION_TOKEN)
        screen.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (!bindIntercomService(flags = 0)) {
            screen.clearServiceOwnedFacts()
            setIntercomState(IntercomState.Offline)
            screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        }
        refreshWifiAvailability()
        refreshOptionalPermissionPresentation()
    }

    override fun onResume() {
        super.onResume()
        // Settings.Panel can pause/resume this Activity without a new onStart.
        // Refresh capability facts and permission-owned copy here. Product State
        // remains the last Service state, while Service-owned supplemental copy
        // is preserved by MainScreen's source-aware permission update.
        refreshCorePermissionPresentation()
        refreshWifiAvailability()
        refreshOptionalPermissionPresentation()
    }

    override fun onPostResume() {
        super.onPostResume()
        // Focus restoration for an EditText can move its ScrollView after
        // onCreate's restore pass; apply the saved route position once more.
        screen.onWindowSizeChanged()
        screen.restoreCurrentPageScrollAfterResume()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.onWindowSizeChanged()
        screen.restoreCurrentPageScrollAfterResume()
    }

    override fun onStop() {
        intercomService?.setAppForeground(false)
        intercomService?.setListener(null)
        if (bindingRegistered) {
            runCatching { unbindService(serviceConnection) }
                .onFailure { error -> Log.w(TAG, "service already unbound", error) }
        }
        bindingRegistered = false
        serviceConnected = false
        intercomService = null
        dismissIncomingConfirmation()
        screen.stopAnimations()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterPlatformBackCallback()
        super.onDestroy()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!screen.handleBack()) super.onBackPressed()
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
                refreshCorePermissionPresentation()
                refreshOptionalPermissionPresentation()
            }

            REQUEST_OPTIONAL_PERMISSIONS -> {
                if (permissions.indices.any { grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED }) {
                    screen.appendLog("通知或蓝牙权限未授予；不影响对讲启动")
                }
                intercomService?.refreshConfirmationAvailability()
                screen.setIntercomState(intercomState, hasCorePermissions())
                refreshOptionalPermissionPresentation()
            }
        }
    }

    override fun onStatusChanged(status: String, running: Boolean) {
        val replayed = replayingServiceSnapshot
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            Log.d(TAG, "service status running=$running status=$status")
            screen.setStatus(status, appendLog = !replayed)
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
        val replayed = replayingServiceSnapshot
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            screen.setRemoteRider(name)
            if (!replayed) {
                screen.appendLog("远端骑士昵称：$name")
            }
        }
    }

    override fun onIncomingConfirmation(prompt: IncomingConfirmationPrompt) {
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            showIncomingConfirmation(prompt)
        }
    }

    override fun onIncomingConfirmationCanceled(actionNonce: String) {
        runOnUiThread {
            if (shouldDismissIncomingConfirmation(incomingConfirmationNonce, actionNonce)) {
                dismissIncomingConfirmation()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            if (!serviceConnected) return@runOnUiThread
            screen.setIntercomError(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshCorePermissionPresentation() {
        val canStart = hasCorePermissions()
        screen.setIntercomState(intercomState, canStart)
        screen.setPermissionStatus(
            if (intercomState == IntercomState.Offline) {
                if (canStart) READY_STATUS else PERMISSION_REQUIRED_STATUS
            } else {
                null
            }
        )
    }

    private fun requestCorePermissions() {
        val missing = PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            refreshCorePermissionPresentation()
            refreshOptionalPermissionPresentation()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            screen.setIntercomState(intercomState, false)
            screen.setPermissionStatus(PERMISSION_REQUESTING_STATUS)
            requestPermissions(missing.toTypedArray(), REQUEST_CORE_PERMISSIONS)
        }
    }

    private fun refreshOptionalPermissionPresentation() {
        val missing = PermissionPolicy.optionalPermissions(Build.VERSION.SDK_INT).filterNot(::hasPermission)
        screen.setOptionalPermissionState(
            bluetoothPermissionMissing = missing.contains(Manifest.permission.BLUETOOTH_CONNECT),
            notificationPermissionMissing = missing.contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun requestOptionalPermissions() {
        val missing = PermissionPolicy.optionalPermissions(Build.VERSION.SDK_INT).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            refreshOptionalPermissionPresentation()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(missing.toTypedArray(), REQUEST_OPTIONAL_PERMISSIONS)
        }
    }

    private fun startIntercom(): Boolean {
        val canStart = hasCorePermissions()
        val wifiAvailable = if (canStart) {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } else {
            true
        }
        when (startPrecondition(canStart, wifiAvailable)) {
            StartPrecondition.MISSING_CORE_PERMISSION -> {
                requestCorePermissions()
                return false
            }
            StartPrecondition.WIFI_UNAVAILABLE -> {
                showWifiUnavailable()
                openWifiSettings()
                return false
            }
            StartPrecondition.READY -> screen.setWifiUnavailable(false)
        }

        val riderName = prefs.getString(KEY_RIDER_NAME, "").orEmpty()
        val intent = IntercomService.startIntent(this, riderName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "failed to start IntercomService", error)
            showServiceUnavailable()
            return false
        }
        if (!bindIntercomService(intent, Context.BIND_AUTO_CREATE)) {
            showServiceUnavailable()
            return false
        }
        return true
    }

    private fun refreshWifiAvailability() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        screen.setWifiUnavailable(!wifiManager.isWifiEnabled)
    }

    private fun showWifiUnavailable() {
        screen.setWifiUnavailable(true)
        screen.setStatus(WIFI_UNAVAILABLE_TEXT)
        Toast.makeText(this, WIFI_UNAVAILABLE_TEXT, Toast.LENGTH_LONG).show()
    }

    private fun showServiceUnavailable() {
        screen.clearServiceOwnedFacts()
        screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        Toast.makeText(this, SERVICE_UNAVAILABLE_STATUS, Toast.LENGTH_LONG).show()
    }

    private fun openWifiSettings() {
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
    }

    private fun stopIntercom() {
        screen.setStatus(STOPPING_STATUS)
        if (intercomService != null) {
            intercomService?.requestStop()
            return
        }
        try {
            startService(IntercomService.stopIntent(this))
        } catch (error: RuntimeException) {
            Log.e(TAG, "failed to stop IntercomService", error)
            showServiceUnavailable()
        }
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.permission_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerPlatformBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        platformBackCallback = Api33BackDispatcher.register(this) {
            if (!screen.handleBack()) moveTaskToBack(false)
        }
    }

    private fun unregisterPlatformBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        platformBackCallback?.let { Api33BackDispatcher.unregister(this, it) }
        platformBackCallback = null
    }

    private fun showIncomingConfirmation(prompt: IncomingConfirmationPrompt) {
        screen.dismissPlaceholderDialog()
        dismissIncomingConfirmation()
        screen.setIncomingConfirmationVisible(true)
        incomingConfirmationNonce = prompt.actionNonce
        val riderName = prompt.peer.nickname.ifBlank { "附近车友" }
        val deviceName = prompt.peer.deviceName.ifBlank { DEVICE_NAME_UNAVAILABLE }
        incomingConfirmationDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.incoming_confirmation_title, riderName))
            .setMessage(getString(R.string.incoming_confirmation_message, deviceName))
            .setNegativeButton(R.string.incoming_confirmation_reject) { _, _ ->
                if (!shouldDismissIncomingConfirmation(incomingConfirmationNonce, prompt.actionNonce)) {
                    return@setNegativeButton
                }
                incomingConfirmationNonce = null
                incomingConfirmationDialog = null
                screen.setIncomingConfirmationVisible(false)
                intercomService?.respondToIncomingConfirmation(prompt, accepted = false)
            }
            .setPositiveButton(R.string.incoming_confirmation_accept) { _, _ ->
                if (!shouldDismissIncomingConfirmation(incomingConfirmationNonce, prompt.actionNonce)) {
                    return@setPositiveButton
                }
                incomingConfirmationNonce = null
                incomingConfirmationDialog = null
                screen.setIncomingConfirmationVisible(false)
                screen.navigateHome()
                intercomService?.respondToIncomingConfirmation(prompt, accepted = true)
            }
            .setCancelable(false)
            .create()
            .also(AlertDialog::show)
    }

    private fun dismissIncomingConfirmation() {
        incomingConfirmationDialog?.dismiss()
        incomingConfirmationDialog = null
        incomingConfirmationNonce = null
        screen.setIncomingConfirmationVisible(false)
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
        bindingRegistered = try {
            bindService(intent, serviceConnection, flags)
        } catch (error: RuntimeException) {
            Log.e(TAG, "failed to bind IntercomService", error)
            false
        }
        return bindingRegistered
    }

    private fun setIntercomState(state: IntercomState) {
        if (state !is IntercomState.IncomingConfirmation && incomingConfirmationDialog?.isShowing == true) {
            dismissIncomingConfirmation()
        }
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
        const val KEY_PROCESS_SESSION_TOKEN = "process_session_token"
        val PROCESS_SESSION_TOKEN: String = UUID.randomUUID().toString()
        const val READY_STATUS = "请点击下方启动对讲"
        const val PERMISSION_REQUIRED_STATUS = "缺少必要权限，请先授权"
        const val PERMISSION_REQUESTING_STATUS = "正在申请必要权限..."
        const val STOPPING_STATUS = "正在结束对讲..."
        const val TAG = "MotoIntercomUi"
    }
}
