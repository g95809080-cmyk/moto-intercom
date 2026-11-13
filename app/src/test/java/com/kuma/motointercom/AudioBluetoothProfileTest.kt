package com.kuma.motointercom

import android.media.AudioDeviceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBluetoothProfileTest {
    @Test
    fun detectsClassicScoBeforeOtherInputDevices() {
        assertEquals(
            AudioBluetoothProfile.CLASSIC_SCO,
            AudioBluetoothProfileDetector.fromInputDeviceTypes(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                ),
                Build.VERSION_CODES.R
            )
        )
    }

    @Test
    fun detectsBleHeadsetOnAndroidSAndAbove() {
        assertEquals(
            AudioBluetoothProfile.BLE_AUDIO,
            AudioBluetoothProfileDetector.fromInputDeviceTypes(
                listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
                Build.VERSION_CODES.S
            )
        )
    }

    @Test
    fun treatsBuiltInMicAsNonBluetooth() {
        assertEquals(
            AudioBluetoothProfile.NONE,
            AudioBluetoothProfileDetector.fromInputDeviceTypes(
                listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
                Build.VERSION_CODES.S
            )
        )
    }

    @Test
    fun reportsUnknownWhenNoInputDeviceIsVisible() {
        assertEquals(
            AudioBluetoothProfile.UNKNOWN,
            AudioBluetoothProfileDetector.fromInputDeviceTypes(emptyList(), Build.VERSION_CODES.S)
        )
    }

    @Test
    fun mapsActualCommunicationDeviceSeparatelyFromAvailableInputs() {
        assertEquals(
            AudioBluetoothProfile.CLASSIC_SCO,
            AudioBluetoothProfileDetector.fromActualDeviceType(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                Build.VERSION_CODES.S
            )
        )
        assertEquals(
            AudioBluetoothProfile.NONE,
            AudioBluetoothProfileDetector.fromActualDeviceType(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                Build.VERSION_CODES.S
            )
        )
    }
}
