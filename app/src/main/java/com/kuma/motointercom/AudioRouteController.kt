package com.kuma.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private val closed = AtomicBoolean(false)

    @Volatile private var wantBluetoothSco = false
    @Volatile private var scoEverConnected = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

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

    /**
     * 切到蓝牙 SCO 通话通道。
     *
     * 调用前请确保：
     * - AndroidManifest.xml 声明 MODIFY_AUDIO_SETTINGS；
     * - Android 12/API 31+ 已授予 BLUETOOTH_CONNECT。
     */
    fun switchToBluetoothSco() {
        if (closed.get()) return
        wantBluetoothSco = true
        registerReceiver()

        routeExecutor.execute {
            try {
                if (!hasRequiredPermissions(appContext)) {
                    throw SecurityException("缺少蓝牙音频路由运行时权限")
                }
                if (!audioManager.isBluetoothScoAvailableOffCall) {
                    if (fallbackToSpeaker) switchToSpeaker(noBluetooth = true)
                    return@execute
                }

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                startSco()
            } catch (t: Throwable) {
                postError(t)
                if (fallbackToSpeaker) switchToSpeaker(noBluetooth = true)
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
                stopSco()
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            } catch (t: Throwable) {
                postError(t)
            } finally {
                unregisterReceiver()
                routeExecutor.shutdown()
            }
        }
    }

    override fun close() = reset()

    @SuppressLint("MissingPermission")
    private fun startSco() {
        // SCO 是蓝牙“电话音频”链路，头盔麦克风和耳机全双工通常依赖它。
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
    }

    @SuppressLint("MissingPermission")
    private fun stopSco() {
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
                stopSco()
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
        mainHandler.post { onError(t) }
    }

    companion object {
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
