package com.kuma.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the process audio mode and routes intercom audio to a headset or phone. */
class AudioRouteController(
    context: Context,
    private val fallbackToSpeaker: Boolean = true,
    private val onScoConnected: (String) -> Unit = {},
    private val onScoDisconnected: () -> Unit = {},
    private val onSpeakerFallback: (noBluetooth: Boolean) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialMode = audioManager.mode
    @Suppress("DEPRECATION")
    private val initialSpeakerphoneOn = audioManager.isSpeakerphoneOn
    private val receiverRegistered = AtomicBoolean(false)
    private val audioDeviceCallbackRegistered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var wantBluetoothSco = false
    @Volatile private var scoEverConnected = false
    @Volatile private var bluetoothReported = false
    private var modernRoute: ModernAudioRoute? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED || closed.get()) return
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.i(TAG, "legacy SCO broadcast state=$state")
            ROUTE_EXECUTOR.execute {
                if (closed.get() || !wantBluetoothSco) return@execute
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        scoEverConnected = true
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        publishBluetoothConnected(bluetoothDeviceName())
                    }

                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        if (scoEverConnected) postMain(onScoDisconnected)
                        if (fallbackToSpeaker) fallbackToPhone(!scoEverConnected, "legacy SCO disconnected")
                    }

                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        reportError(IllegalStateException("蓝牙 SCO 通道开启失败"))
                        if (fallbackToSpeaker) fallbackToPhone(true, "legacy SCO error")
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            logDevices("AudioDeviceCallback added", addedDevices.toList())
            rerouteAfterDeviceChange("device added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            logDevices("AudioDeviceCallback removed", removedDevices.toList())
            rerouteAfterDeviceChange("device removed")
        }
    }

    fun switchToBluetoothSco() {
        if (closed.get()) return
        wantBluetoothSco = true
        ROUTE_EXECUTOR.execute {
            if (closed.get()) return@execute
            try {
                if (!hasRequiredPermissions(appContext)) {
                    throw SecurityException("缺少蓝牙音频路由运行时权限")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    registerAudioDeviceCallback()
                    modernRoute().register()
                    routeModernBluetooth("start")
                } else {
                    registerReceiver()
                    startLegacySco()
                }
            } catch (t: Throwable) {
                reportError(t)
                if (fallbackToSpeaker) fallbackToPhone(true, "route error")
            }
        }
    }

    fun reset() {
        if (!closed.compareAndSet(false, true)) return
        wantBluetoothSco = false
        ROUTE_EXECUTOR.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    modernRoute?.close()
                    modernRoute = null
                } else {
                    stopLegacySco()
                }
            } catch (t: Throwable) {
                logError(t)
            }
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = initialSpeakerphoneOn
                audioManager.mode = initialMode
            } catch (t: Throwable) {
                logError(t)
            } finally {
                unregisterReceiver()
                unregisterAudioDeviceCallback()
            }
        }
    }

    override fun close() = reset()

    private fun routeModernBluetooth(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || closed.get() || !wantBluetoothSco) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val route = modernRoute()
        Log.i(TAG, "modern route[$reason]: ${route.stateSummary()}")
        if (!route.route()) {
            Log.w(TAG, "modern route[$reason]: no usable Bluetooth communication device")
            if (fallbackToSpeaker) fallbackToPhone(true, "modern route unavailable")
            return
        }
        route.currentName()?.let(::publishBluetoothConnected)
        scheduleModernRouteVerification(reason)
    }

    private fun modernRoute(): ModernAudioRoute {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        return modernRoute ?: ModernAudioRoute(
            audioManager = audioManager,
            callbackExecutor = ROUTE_EXECUTOR,
            onBluetoothConnected = { name ->
                if (!closed.get() && wantBluetoothSco) publishBluetoothConnected(name)
            },
            onDeviceLost = {
                if (!closed.get() && wantBluetoothSco) routeModernBluetooth("communication device changed")
            }
        ).also { modernRoute = it }
    }

    private fun scheduleModernRouteVerification(reason: String) {
        mainHandler.postDelayed({
            if (closed.get()) return@postDelayed
            ROUTE_EXECUTOR.execute {
                if (closed.get() || !wantBluetoothSco || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@execute
                val route = modernRoute ?: return@execute
                Log.i(TAG, "modern route[$reason]: delayed verify ${route.stateSummary()}")
                route.currentName()?.let(::publishBluetoothConnected)
            }
        }, MODERN_ROUTE_VERIFY_DELAY_MS)
    }

    private fun rerouteAfterDeviceChange(reason: String) {
        if (closed.get()) return
        ROUTE_EXECUTOR.execute {
            if (closed.get() || !wantBluetoothSco) return@execute
            try {
                routeModernBluetooth(reason)
            } catch (t: Throwable) {
                reportError(t)
                if (fallbackToSpeaker) fallbackToPhone(true, "callback route error")
            }
        }
    }

    private fun fallbackToPhone(noBluetooth: Boolean, reason: String) {
        if (closed.get()) return
        wantBluetoothSco = false
        Log.i(TAG, "fallback to phone: reason=$reason, noBluetooth=$noBluetooth")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernRoute?.clear()
        } else {
            stopLegacySco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (bluetoothReported) {
            bluetoothReported = false
            postMain(onScoDisconnected)
        }
        postMain { onSpeakerFallback(noBluetooth) }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacySco() {
        Log.i(TAG, "legacy SCO route: sdk=${Build.VERSION.SDK_INT}, mode=${modeName(audioManager.mode)}")
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.w(TAG, "legacy SCO route: isBluetoothScoAvailableOffCall=false")
            if (fallbackToSpeaker) fallbackToPhone(true, "legacy SCO unavailable")
            return
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
    }

    @SuppressLint("MissingPermission")
    private fun stopLegacySco() {
        Log.i(TAG, "legacy SCO route: stop")
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
    }

    private fun publishBluetoothConnected(name: String) {
        if (closed.get() || bluetoothReported) return
        bluetoothReported = true
        postMain { onScoConnected(name.ifBlank { "头盔蓝牙" }) }
    }

    private fun bluetoothDeviceName(): String {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        return device?.productName?.toString()?.takeIf(String::isNotBlank) ?: "头盔蓝牙"
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!audioDeviceCallbackRegistered.compareAndSet(false, true)) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        Log.i(TAG, "modern route: AudioDeviceCallback registered")
    }

    private fun unregisterAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered.compareAndSet(true, false)) return
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            Log.i(TAG, "modern route: AudioDeviceCallback unregistered")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun logDevices(reason: String, devices: List<AudioDeviceInfo>) {
        if (devices.isEmpty()) {
            Log.i(TAG, "modern route: $reason: empty")
        } else {
            devices.forEachIndexed { index, device ->
                Log.i(TAG, "modern route: $reason[$index]=${deviceSummary(device)}")
            }
        }
    }

    private fun deviceSummary(device: AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address.takeIf(String::isNotBlank) ?: "-"
        } else {
            "unavailable"
        }
        return "id=${device.id}, type=${typeName(device.type)}(${device.type}), " +
            "productName=${device.productName}, address=$address"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
        else -> "TYPE_$type"
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        else -> "MODE_$mode"
    }

    private fun registerReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered.compareAndSet(true, false)) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post {
            if (!closed.get()) block()
        }
    }

    private fun reportError(t: Throwable) {
        logError(t)
        postMain { onError(t) }
    }

    private fun logError(t: Throwable) {
        Log.e(TAG, "audio route error", t)
    }

    companion object {
        private const val TAG = "AudioRouteController"
        private const val MODERN_ROUTE_VERIFY_DELAY_MS = 1_500L
        private val ROUTE_EXECUTOR = Executors.newSingleThreadExecutor()

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }

        fun hasRequiredPermissions(context: Context): Boolean =
            requiredPermissions().all {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
    }
}
