package com.kuma.motointercom

import android.annotation.TargetApi
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(Build.VERSION_CODES.S)
internal class ModernAudioRoute(
    private val audioManager: AudioManager,
    private val callbackExecutor: Executor,
    private val onBluetoothConnected: (String) -> Unit,
    private val onDeviceLost: () -> Unit
) : Closeable {
    enum class RouteResult { ROUTED, NO_BLUETOOTH_DEVICE, REJECTED }

    private val initialDevice = audioManager.communicationDevice
    private val closed = AtomicBoolean(false)
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (closed.get()) return@OnCommunicationDeviceChangedListener
        if (isBluetooth(device)) {
            onBluetoothConnected(device?.productName?.toString().orEmpty())
        } else {
            onDeviceLost()
        }
    }
    private var registered = false

    fun register() {
        if (registered || closed.get()) return
        audioManager.addOnCommunicationDeviceChangedListener(callbackExecutor, listener)
        registered = true
    }

    fun route(): RouteResult {
        if (closed.get()) return RouteResult.REJECTED
        val target = audioManager.availableCommunicationDevices
            .filter(::isBluetooth)
            .minByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 0 else 1 }
            ?: return RouteResult.NO_BLUETOOTH_DEVICE
        Log.i(TAG, "selected target=${summary(target)}")
        return if (audioManager.setCommunicationDevice(target)) {
            RouteResult.ROUTED
        } else {
            RouteResult.REJECTED
        }
    }

    fun currentName(): String? =
        audioManager.communicationDevice
            ?.takeIf(::isBluetooth)
            ?.productName
            ?.toString()
            ?.takeIf(String::isNotBlank)

    fun clear() {
        if (!closed.get()) audioManager.clearCommunicationDevice()
    }

    fun routeToSpeaker(): Boolean {
        if (closed.get()) return false
        val speaker = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return false
        return audioManager.setCommunicationDevice(speaker)
    }

    fun stateSummary(): String =
        "communicationDevice=${summary(audioManager.communicationDevice)}, " +
            "available=${audioManager.availableCommunicationDevices.joinToString(prefix = "[", postfix = "]", transform = ::summary)}"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (registered) {
            registered = false
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "communication device listener was already removed", t)
            }
        }
        if (initialDevice == null) {
            audioManager.clearCommunicationDevice()
        } else if (!audioManager.setCommunicationDevice(initialDevice)) {
            Log.w(TAG, "failed to restore initial communicationDevice=${summary(initialDevice)}")
            audioManager.clearCommunicationDevice()
        }
    }

    private fun isBluetooth(device: AudioDeviceInfo?): Boolean =
        device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device?.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun summary(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val typeName = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
            else -> "TYPE_${device.type}"
        }
        return "id=${device.id}, type=$typeName(${device.type}), " +
            "productName=${device.productName}, address=${device.address.ifBlank { "-" }}"
    }

    private companion object {
        const val TAG = "ModernAudioRoute"
    }
}
