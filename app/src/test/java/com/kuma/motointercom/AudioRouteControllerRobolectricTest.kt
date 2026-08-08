package com.kuma.motointercom

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioRouteControllerRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val shadowAudioManager = shadowOf(audioManager)

    @Test
    fun bluetoothRecoveryCancelsQueuedSpeakerFallbackBeforeItCanRetakeRoute() {
        val speakerFallbacks = mutableListOf<Boolean>()
        val controller = AudioRouteController(
            context = context,
            onSpeakerFallback = speakerFallbacks::add
        )
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val bluetooth = audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(speaker, bluetooth))
        shadowAudioManager.lockCommunicationDevice(true)
        setField(controller, "wantBluetoothSco", true)
        setField(controller, "bluetoothReported", true)

        invokeFallbackToPhone(controller)
        assertNotNull(field<Runnable?>(controller, "speakerFallbackRunnable"))
        assertEquals(true, field<Boolean>(controller, "modernFallbackActive"))

        val route = field<ModernAudioRoute>(controller, "modernRoute")
        val listener = field<AudioManager.OnCommunicationDeviceChangedListener>(route, "listener")
        shadowAudioManager.lockCommunicationDevice(false)
        audioManager.setCommunicationDevice(bluetooth)
        listener.onCommunicationDeviceChanged(bluetooth)

        assertNull(field<Runnable?>(controller, "speakerFallbackRunnable"))
        assertFalse(field(controller, "modernFallbackActive"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500L))
        assertEquals(bluetooth, audioManager.communicationDevice)
        assertEquals(emptyList<Boolean>(), speakerFallbacks)
        controller.close()
    }

    @Test
    fun closeInvalidatesQueuedSpeakerFallbackAndLateBluetoothCallback() {
        val connected = mutableListOf<String>()
        val controller = AudioRouteController(context = context, onScoConnected = connected::add)
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val bluetooth = audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(speaker, bluetooth))
        shadowAudioManager.lockCommunicationDevice(true)
        setField(controller, "wantBluetoothSco", true)
        invokeFallbackToPhone(controller)
        val route = field<ModernAudioRoute>(controller, "modernRoute")
        val listener = field<AudioManager.OnCommunicationDeviceChangedListener>(route, "listener")
        assertNotNull(field<Runnable?>(controller, "speakerFallbackRunnable"))

        controller.close()
        listener.onCommunicationDeviceChanged(bluetooth)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500L))

        assertNull(field<Runnable?>(controller, "speakerFallbackRunnable"))
        assertEquals(emptyList<String>(), connected)
    }

    private fun invokeFallbackToPhone(controller: AudioRouteController) {
        AudioRouteController::class.java.getDeclaredMethod(
            "fallbackToPhone",
            Boolean::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
            .invoke(controller, true, "test fallback")
    }

    private fun audioDevice(type: Int): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(target) as T
}
