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
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 头盔蓝牙耳机音频路由控制器。
 *
 * WebRTC 负责采集/播放；这个类只负责把 Android 系统音频路由切到
 * 通话模式 + 蓝牙 SCO，全双工收发才会走头盔麦克风和耳机。
 */
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
    private val routeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val receiverRegistered = AtomicBoolean(false)
    private val audioDeviceCallbackRegistered = AtomicBoolean(false)
    private val communicationDeviceListenerRegistered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var wantBluetoothSco = false
    @Volatile private var scoEverConnected = false
    @Volatile private var selectedCommunicationDeviceId = NO_DEVICE_ID
    @Volatile private var communicationDeviceChangedListener: Any? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

            Log.i(TAG, "legacy SCO broadcast state=${intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)}")
            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    scoEverConnected = true
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                    mainHandler.post { onScoConnected(bluetoothDeviceName()) }
                }

                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (closed.get()) return
                    if (scoEverConnected) mainHandler.post { onScoDisconnected() }
                    if (fallbackToSpeaker) {
                        switchToSpeaker(noBluetooth = !scoEverConnected)
                    }
                }

                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    val error = IllegalStateException("蓝牙 SCO 通道开启失败")
                    postError(error)
                    if (fallbackToSpeaker) switchToSpeaker(noBluetooth = true)
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

    /**
     * 切到蓝牙通话通道。
     *
     * 调用前请确保：
     * - AndroidManifest.xml 声明 MODIFY_AUDIO_SETTINGS；
     * - Android 12/API 31+ 已授予 BLUETOOTH_CONNECT。
     */
    fun switchToBluetoothSco() {
        if (closed.get()) return
        wantBluetoothSco = true

        routeExecutor.execute {
            try {
                if (!hasRequiredPermissions(appContext)) {
                    throw SecurityException("缺少蓝牙音频路由运行时权限")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    registerAudioDeviceCallback()
                    registerCommunicationDeviceListener()
                    routeModernBluetooth("start")
                } else {
                    registerReceiver()
                    startLegacySco()
                }
            } catch (t: Throwable) {
                postError(t)
                if (fallbackToSpeaker) fallbackToPhone(noBluetooth = true, reason = "route error")
            }
        }
    }

    /**
     * 退出对讲时调用，恢复普通媒体模式，避免影响用户听歌或接电话。
     */
    fun reset() {
        if (!closed.compareAndSet(false, true)) return
        wantBluetoothSco = false

        routeExecutor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    clearModernRoute("reset")
                } else {
                    stopLegacySco()
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (t: Throwable) {
                postError(t)
            } finally {
                unregisterReceiver()
                unregisterAudioDeviceCallback()
                unregisterCommunicationDeviceListener()
                routeExecutor.shutdown()
            }
        }
    }

    override fun close() = reset()

    private fun routeModernBluetooth(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || closed.get()) return

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val devices = dumpModernState(reason)
        val target = devices
            .filter(::isBluetoothCommunicationDevice)
            .minByOrNull(::bluetoothPriority)

        if (target == null) {
            Log.w(TAG, "modern route[$reason]: no Bluetooth communication candidate")
            if (fallbackToSpeaker) fallbackToPhone(noBluetooth = true, reason = "no communication Bluetooth")
            return
        }

        Log.i(TAG, "modern route[$reason]: selected target=${deviceSummary(target)}")
        val success = audioManager.setCommunicationDevice(target)
        Log.i(TAG, "modern route[$reason]: setCommunicationDevice returned=$success")

        val current = audioManager.communicationDevice
        Log.i(TAG, "modern route[$reason]: after communicationDevice=${deviceSummary(current)}")

        if (success) {
            if (current != null && isBluetoothCommunicationDevice(current)) {
                val changed = selectedCommunicationDeviceId != current.id
                selectedCommunicationDeviceId = current.id
                if (changed) mainHandler.post { onScoConnected(deviceName(current)) }
            } else {
                selectedCommunicationDeviceId = target.id
                Log.w(TAG, "modern route[$reason]: request accepted, communicationDevice not updated yet; keeping request")
            }
            scheduleModernRouteVerification(reason, target)
            return
        }

        Log.w(TAG, "modern route[$reason]: setCommunicationDevice failed")
        if (fallbackToSpeaker) fallbackToPhone(noBluetooth = false, reason = "setCommunicationDevice returned false")
    }

    private fun scheduleModernRouteVerification(reason: String, target: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetId = target.id
        mainHandler.postDelayed({
            if (closed.get() || routeExecutor.isShutdown || routeExecutor.isTerminated) return@postDelayed
            try {
                routeExecutor.execute {
                    if (closed.get() || !wantBluetoothSco || selectedCommunicationDeviceId != targetId) return@execute
                    val current = audioManager.communicationDevice
                    Log.i(
                        TAG,
                        "modern route[$reason]: delayed verify target=${deviceSummary(target)}, " +
                            "communicationDevice=${deviceSummary(current)}"
                    )
                    if (current != null && isBluetoothCommunicationDevice(current)) {
                        val changed = selectedCommunicationDeviceId != current.id
                        selectedCommunicationDeviceId = current.id
                        if (changed) mainHandler.post { onScoConnected(deviceName(current)) }
                    } else {
                        Log.w(TAG, "modern route[$reason]: Bluetooth request still pending or blocked by system")
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (!closed.get()) Log.w(TAG, "modern route[$reason]: delayed verify rejected")
            }
        }, MODERN_ROUTE_VERIFY_DELAY_MS)
    }

    private fun rerouteAfterDeviceChange(reason: String) {
        if (closed.get()) return
        routeExecutor.execute {
            if (closed.get() || !wantBluetoothSco) return@execute
            try {
                routeModernBluetooth(reason)
            } catch (t: Throwable) {
                postError(t)
                if (fallbackToSpeaker) fallbackToPhone(noBluetooth = true, reason = "callback route error")
            }
        }
    }

    private fun fallbackToPhone(noBluetooth: Boolean, reason: String) {
        if (closed.get()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearModernRoute(reason)
            mainHandler.post { onSpeakerFallback(noBluetooth) }
        } else {
            switchToSpeaker(noBluetooth)
        }
    }

    private fun clearModernRoute(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val hadBluetooth = selectedCommunicationDeviceId != NO_DEVICE_ID
        Log.i(TAG, "modern route[$reason]: clear/fallback, before=${deviceSummary(audioManager.communicationDevice)}")
        audioManager.clearCommunicationDevice()
        selectedCommunicationDeviceId = NO_DEVICE_ID
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "modern route[$reason]: after clear communicationDevice=${deviceSummary(audioManager.communicationDevice)}")
        if (hadBluetooth) mainHandler.post { onScoDisconnected() }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacySco() {
        Log.i(TAG, "legacy SCO route: sdk=${Build.VERSION.SDK_INT}, mode=${modeName(audioManager.mode)}, hasBluetoothConnect=${hasRequiredPermissions(appContext)}")
        // SCO 是蓝牙“电话音频”链路，头盔麦克风和耳机全双工通常依赖它。
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.w(TAG, "legacy SCO route: isBluetoothScoAvailableOffCall=false")
            if (fallbackToSpeaker) switchToSpeaker(noBluetooth = true)
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

    private fun switchToSpeaker(noBluetooth: Boolean) {
        if (closed.get()) return
        routeExecutor.execute {
            try {
                if (closed.get()) return@execute
                wantBluetoothSco = false
                Log.i(TAG, "legacy SCO route: fallback to speaker noBluetooth=$noBluetooth")
                stopLegacySco()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                mainHandler.post { onSpeakerFallback(noBluetooth) }
            } catch (t: Throwable) {
                postError(t)
            }
        }
    }

    private fun bluetoothDeviceName(): String {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        return device?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "头盔蓝牙"
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

    private fun registerCommunicationDeviceListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!communicationDeviceListenerRegistered.compareAndSet(false, true)) return

        try {
            val listenerClass = Class.forName(COMMUNICATION_DEVICE_LISTENER_CLASS)
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onCommunicationDeviceChanged") {
                    onModernCommunicationDeviceChanged(args?.firstOrNull() as? AudioDeviceInfo)
                }
                null
            }

            audioManager.javaClass
                .getMethod("addOnCommunicationDeviceChangedListener", java.util.concurrent.Executor::class.java, listenerClass)
                .invoke(audioManager, routeExecutor, listener)
            communicationDeviceChangedListener = listener
            Log.i(TAG, "modern route: communication device listener registered")
        } catch (t: Throwable) {
            communicationDeviceListenerRegistered.set(false)
            communicationDeviceChangedListener = null
            Log.e(TAG, "modern route: communication device listener registration failed", t)
        }
    }

    private fun unregisterCommunicationDeviceListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!communicationDeviceListenerRegistered.compareAndSet(true, false)) return

        val listener = communicationDeviceChangedListener
        communicationDeviceChangedListener = null
        try {
            val listenerClass = Class.forName(COMMUNICATION_DEVICE_LISTENER_CLASS)
            audioManager.javaClass
                .getMethod("removeOnCommunicationDeviceChangedListener", listenerClass)
                .invoke(audioManager, listener)
            Log.i(TAG, "modern route: communication device listener unregistered")
        } catch (_: Throwable) {
        }
    }

    private fun onModernCommunicationDeviceChanged(device: AudioDeviceInfo?) {
        Log.i(TAG, "modern route: communicationDevice changed=${deviceSummary(device)}")
        if (closed.get() || !wantBluetoothSco) return
        if (isBluetoothCommunicationDevice(device)) {
            val changed = selectedCommunicationDeviceId != device?.id
            selectedCommunicationDeviceId = device?.id ?: NO_DEVICE_ID
            if (changed && device != null) mainHandler.post { onScoConnected(deviceName(device)) }
        } else {
            routeModernBluetooth("communication device changed")
        }
    }

    private fun dumpModernState(reason: String): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()

        val hasBluetoothConnect = hasRequiredPermissions(appContext)
        Log.i(
            TAG,
            "modern route[$reason]: sdk=${Build.VERSION.SDK_INT}, mode=${modeName(audioManager.mode)}(${audioManager.mode}), " +
                "hasBluetoothConnect=$hasBluetoothConnect, communicationDevice=${deviceSummary(audioManager.communicationDevice)}"
        )

        val devices = try {
            audioManager.availableCommunicationDevices
        } catch (t: Throwable) {
            Log.e(TAG, "modern route[$reason]: availableCommunicationDevices failed", t)
            emptyList()
        }

        if (devices.isEmpty()) {
            Log.w(TAG, "modern route[$reason]: availableCommunicationDevices empty")
        }
        devices.forEachIndexed { index, device ->
            Log.i(
                TAG,
                "modern route[$reason]: availableCommunicationDevices[$index]=${deviceSummary(device)}, " +
                    "bluetoothCandidate=${isBluetoothCommunicationDevice(device)}"
            )
        }
        return devices
    }

    private fun logDevices(reason: String, devices: List<AudioDeviceInfo>) {
        if (devices.isEmpty()) {
            Log.i(TAG, "modern route: $reason: empty")
            return
        }
        devices.forEachIndexed { index, device ->
            Log.i(TAG, "modern route: $reason[$index]=${deviceSummary(device)}")
        }
    }

    private fun isBluetoothCommunicationDevice(device: AudioDeviceInfo?): Boolean {
        return device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun bluetoothPriority(device: AudioDeviceInfo): Int {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
            else -> 100
        }
    }

    private fun deviceSummary(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        return "id=${device.id}, type=${typeName(device.type)}(${device.type}), " +
            "productName=${deviceName(device)}, isSource=${device.isSource}, isSink=${device.isSink}, " +
            "address=${deviceAddress(device)}"
    }

    private fun deviceName(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"

    private fun deviceAddress(device: AudioDeviceInfo): String {
        return try {
            device.address.takeIf { it.isNotBlank() } ?: "-"
        } catch (t: Throwable) {
            "unavailable:${t.javaClass.simpleName}"
        }
    }

    private fun typeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
            AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
            else -> "TYPE_$type"
        }
    }

    private fun modeName(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            else -> "MODE_$mode"
        }
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

    private fun postError(t: Throwable) {
        Log.e(TAG, "audio route error", t)
        mainHandler.post { onError(t) }
    }

    companion object {
        private const val TAG = "AudioRouteController"
        private const val NO_DEVICE_ID = -1
        private const val MODERN_ROUTE_VERIFY_DELAY_MS = 1_500L
        private const val COMMUNICATION_DEVICE_LISTENER_CLASS =
            "android.media.AudioManager\$OnCommunicationDeviceChangedListener"

        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }
        }

        fun hasRequiredPermissions(context: Context): Boolean {
            return requiredPermissions().all {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
