package com.kuma.motointercom

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

internal enum class AudioBluetoothProfile(val logValue: String) {
    NONE("none"),
    CLASSIC_SCO("classic_sco"),
    BLE_AUDIO("ble_audio"),
    UNKNOWN("unknown")
}

internal data class AudioBluetoothRouteProbe(
    val availableProfile: AudioBluetoothProfile,
    val activeProfile: AudioBluetoothProfile,
    val activeDeviceType: Int?,
    val activeDeviceName: String
)

internal object AudioBluetoothProfileDetector {
    fun detect(audioManager: AudioManager): AudioBluetoothProfile =
        probe(audioManager).availableProfile

    fun probe(audioManager: AudioManager): AudioBluetoothRouteProbe = runCatching {
        val sdkInt = Build.VERSION.SDK_INT
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val availableProfile = fromInputDeviceTypes(
            inputDevices.map(AudioDeviceInfo::getType),
            sdkInt
        )
        val activeDevice = actualCommunicationDevice(audioManager, inputDevices, sdkInt)
        AudioBluetoothRouteProbe(
            availableProfile = availableProfile,
            activeProfile = fromActualDeviceType(activeDevice?.type, sdkInt),
            activeDeviceType = activeDevice?.type,
            activeDeviceName = activeDevice?.let(::safeProductName).orEmpty()
        )
    }.getOrDefault(
        AudioBluetoothRouteProbe(
            availableProfile = AudioBluetoothProfile.UNKNOWN,
            activeProfile = AudioBluetoothProfile.UNKNOWN,
            activeDeviceType = null,
            activeDeviceName = ""
        )
    )

    internal fun fromActualDeviceType(
        deviceType: Int?,
        sdkInt: Int
    ): AudioBluetoothProfile = when (deviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioBluetoothProfile.CLASSIC_SCO
        AudioDeviceInfo.TYPE_BLE_HEADSET ->
            if (sdkInt >= Build.VERSION_CODES.S) {
                AudioBluetoothProfile.BLE_AUDIO
            } else {
                AudioBluetoothProfile.UNKNOWN
            }

        null -> AudioBluetoothProfile.NONE
        else -> AudioBluetoothProfile.NONE
    }

    private fun actualCommunicationDevice(
        audioManager: AudioManager,
        inputDevices: List<AudioDeviceInfo>,
        sdkInt: Int
    ): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDeviceApi31(audioManager)?.let { communicationDevice ->
                return communicationDevice
            }
        }

        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            return inputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun communicationDeviceApi31(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.communicationDevice

    private fun safeProductName(device: AudioDeviceInfo): String =
        runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")

    internal fun fromInputDeviceTypes(
        inputTypes: Iterable<Int>,
        sdkInt: Int
    ): AudioBluetoothProfile {
        val types = inputTypes.toSet()
        if (AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types) {
            return AudioBluetoothProfile.CLASSIC_SCO
        }
        if (
            sdkInt >= Build.VERSION_CODES.S &&
            AudioDeviceInfo.TYPE_BLE_HEADSET in types
        ) {
            return AudioBluetoothProfile.BLE_AUDIO
        }
        return if (types.isEmpty()) {
            AudioBluetoothProfile.UNKNOWN
        } else {
            AudioBluetoothProfile.NONE
        }
    }
}
