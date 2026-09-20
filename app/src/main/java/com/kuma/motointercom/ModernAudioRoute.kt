package com.kuma.motointercom

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal interface CommunicationDeviceRoute : Closeable {
    fun register()
    fun routeTo(selection: AudioRouteSelection): ModernAudioRoute.RouteResult
    fun currentName(): String?
    fun clear()
    fun isActive(selection: AudioRouteSelection): Boolean
    fun stateSummary(): String
    fun close(restoreInitialState: Boolean) = close()
}

@RequiresApi(Build.VERSION_CODES.S)
internal class ModernAudioRoute(
    private val audioManager: AudioManager,
    private val callbackExecutor: Executor,
    private val onBluetoothConnected: (String) -> Unit,
    private val onDeviceLost: () -> Unit,
    private val onDeviceChanged: () -> Unit = {}
) : CommunicationDeviceRoute {
    enum class RouteResult { ROUTED, NO_MATCHING_DEVICE, REJECTED }

    private val initialDevice = audioManager.communicationDevice
    private val closed = AtomicBoolean(false)
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (closed.get()) return@OnCommunicationDeviceChangedListener
        onDeviceChanged()
        if (isBluetooth(device)) {
            onBluetoothConnected(device?.let(::safeProductName).orEmpty())
        } else {
            onDeviceLost()
        }
    }
    private var registered = false

    override fun register() {
        if (registered || closed.get()) return
        audioManager.addOnCommunicationDeviceChangedListener(callbackExecutor, listener)
        registered = true
    }

    override fun routeTo(selection: AudioRouteSelection): RouteResult {
        if (closed.get()) return RouteResult.REJECTED
        val target = audioManager.availableCommunicationDevices
            .filter { matches(selection, it) }
            .minByOrNull {
                if (
                    selection == AudioRouteSelection.BLUETOOTH &&
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                ) {
                    0
                } else {
                    1
                }
            }
            ?: return RouteResult.NO_MATCHING_DEVICE
        Log.i(TAG, "selected target=${summary(target)}")
        return if (audioManager.setCommunicationDevice(target)) {
            RouteResult.ROUTED
        } else {
            RouteResult.REJECTED
        }
    }

    override fun currentName(): String? =
        audioManager.communicationDevice
            ?.takeIf(::isBluetooth)
            ?.let(::safeProductName)
            ?.takeIf(String::isNotBlank)

    override fun clear() {
        if (!closed.get()) audioManager.clearCommunicationDevice()
    }

    override fun isActive(selection: AudioRouteSelection): Boolean =
        !closed.get() && when (selection) {
            AudioRouteSelection.BLUETOOTH -> isBluetooth(audioManager.communicationDevice)
            AudioRouteSelection.EARPIECE ->
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            AudioRouteSelection.SPEAKER ->
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

    override fun stateSummary(): String =
        "communicationDevice=${summary(audioManager.communicationDevice)}, " +
            "available=${audioManager.availableCommunicationDevices.joinToString(prefix = "[", postfix = "]", transform = ::summary)}"

    override fun close(restoreInitialState: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        if (registered) {
            registered = false
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "communication device listener was already removed", t)
            }
        }
        if (!restoreInitialState) {
            audioManager.clearCommunicationDevice()
        } else if (initialDevice == null) {
            audioManager.clearCommunicationDevice()
        } else if (!audioManager.setCommunicationDevice(initialDevice)) {
            Log.w(TAG, "failed to restore initial communicationDevice=${summary(initialDevice)}")
            audioManager.clearCommunicationDevice()
        }
    }

    override fun close() = close(restoreInitialState = true)

    private fun isBluetooth(device: AudioDeviceInfo?): Boolean =
        device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device?.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun matches(selection: AudioRouteSelection, device: AudioDeviceInfo): Boolean =
        when (selection) {
            AudioRouteSelection.BLUETOOTH -> isBluetooth(device)
            AudioRouteSelection.EARPIECE -> device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            AudioRouteSelection.SPEAKER -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

    private fun summary(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val typeName = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
            else -> "TYPE_${device.type}"
        }
        return "id=${device.id}, type=$typeName(${device.type}), " +
            "productName=${safeProductName(device).ifBlank { "-" }}, " +
            "address=${device.address.ifBlank { "-" }}"
    }

    private fun safeProductName(device: AudioDeviceInfo): String =
        runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")

    private companion object {
        const val TAG = "ModernAudioRoute"
    }
}
