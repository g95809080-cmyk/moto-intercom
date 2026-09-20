package com.kuma.motointercom

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioRouteControllerRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val shadowAudioManager = shadowOf(audioManager)

    @Test fun consecutiveInvalidationsDeliverUnavailableBeforeFastReverification() {
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val earpiece = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(speaker, earpiece))
        val states = mutableListOf<String>()
        val controller = AudioRouteController(context,
            onRouteInvalidated = { states += "unavailable" }, onRouteReady = { states += "verified" })
        try {
            controller.select(AudioRouteSelection.SPEAKER)
            drainRouteExecutor(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.evidence().ready)
            states.clear()
            audioManager.setCommunicationDevice(earpiece)
            assertFalse(controller.evidence().ready)
            // A second invalidation and successful verification occur before main consumes the first.
            controller.select(AudioRouteSelection.SPEAKER)
            drainRouteExecutor()
            assertTrue(states.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("unavailable", "verified"), states)
            assertTrue(controller.evidence().ready)
        } finally { controller.close(); drainRouteExecutor() }
    }

    @Test fun replacedPhoneDeviceRevokesEvidenceBeforeStatsDelivery() {
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val earpiece = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(speaker, earpiece))
        var invalidations = 0
        val controller = AudioRouteController(context, onRouteInvalidated = { invalidations++ })
        try {
            controller.select(AudioRouteSelection.SPEAKER)
            drainRouteExecutor(); shadowOf(Looper.getMainLooper()).idle()
            val beforeQuery = controller.evidence()
            assertTrue(beforeQuery.ready)
            audioManager.setCommunicationDevice(earpiece)
            val afterQuery = controller.evidence()
            assertFalse(afterQuery.ready)
            assertTrue(beforeQuery.revision != afterQuery.revision)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, invalidations)
        } finally { controller.close(); drainRouteExecutor() }
    }

    @Test fun bluetoothLossWithRejectedRerouteCannotRetainVerifiedEvidence() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val bluetooth = audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(bluetooth, speaker))
        val controller = AudioRouteController(context, fallbackToSpeaker = false)
        try {
            controller.select(AudioRouteSelection.BLUETOOTH)
            // Selecting the device queues its listener behind the first executor barrier.
            // Drain that callback before consuming its main-thread verification delivery.
            drainRouteExecutor()
            drainRouteExecutor(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.evidence().ready)
            audioManager.setCommunicationDevice(speaker)
            shadowAudioManager.lockCommunicationDevice(true)
            val route = field<ModernAudioRoute>(controller, "modernRoute")
            field<AudioManager.OnCommunicationDeviceChangedListener>(route, "listener")
                .onCommunicationDeviceChanged(speaker)
            drainRouteExecutor(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(controller.evidence().ready)
        } finally { shadowAudioManager.lockCommunicationDevice(false); controller.close(); drainRouteExecutor() }
    }

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

    @Test
    fun modernRouteSelectsEachRequestedCommunicationDeviceWithoutInference() {
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val earpiece = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val bluetooth = audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(speaker, earpiece, bluetooth))
        val route = ModernAudioRoute(
            audioManager = audioManager,
            callbackExecutor = { command -> command.run() },
            onBluetoothConnected = {},
            onDeviceLost = {}
        )

        assertEquals(
            ModernAudioRoute.RouteResult.ROUTED,
            route.routeTo(AudioRouteSelection.EARPIECE)
        )
        assertEquals(earpiece, audioManager.communicationDevice)
        assertEquals(
            ModernAudioRoute.RouteResult.ROUTED,
            route.routeTo(AudioRouteSelection.SPEAKER)
        )
        assertEquals(speaker, audioManager.communicationDevice)
        assertEquals(
            ModernAudioRoute.RouteResult.ROUTED,
            route.routeTo(AudioRouteSelection.BLUETOOTH)
        )
        assertEquals(bluetooth, audioManager.communicationDevice)

        route.close()
    }

    @Test
    fun staleRouteGenerationCannotPublishAfterANewerSelection() {
        val controller = AudioRouteController(context = context)
        val stale = VersionedAudioRouteSelection(1, AudioRouteSelection.EARPIECE)
        val current = VersionedAudioRouteSelection(2, AudioRouteSelection.SPEAKER)
        setField(controller, "routeRequest", current)
        val callbacks = mutableListOf<String>()

        invokePostMainForRoute(controller, stale) { callbacks += "stale" }
        invokePostMainForRoute(controller, current) { callbacks += "current" }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("current"), callbacks)
        controller.close()
    }

    @Test
    fun realBluetoothPhoneBluetoothSelectionsOnlyPublishTheLatestGeneration() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val route = DelayedCommunicationRoute().apply {
            activeSelection = AudioRouteSelection.BLUETOOTH
        }
        val connected = mutableListOf<String>()
        val phoneRoutes = mutableListOf<AudioRouteSelection>()
        val controller = AudioRouteController(
            context = context,
            onScoConnected = connected::add,
            onSpeakerFallback = { phoneRoutes += AudioRouteSelection.SPEAKER },
            modernRouteFactory = { route }
        )

        // Hold the worker so this test deterministically covers superseded queued requests.
        val executor = AudioRouteController::class.java.getDeclaredField("ROUTE_EXECUTOR")
            .apply { isAccessible = true }.get(null) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            controller.select(AudioRouteSelection.BLUETOOTH)
            controller.select(AudioRouteSelection.SPEAKER)
            controller.select(AudioRouteSelection.BLUETOOTH)
        } finally { release.countDown() }
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(route.requests.isNotEmpty())
        assertTrue(route.requests.all { it == AudioRouteSelection.BLUETOOTH })
        assertEquals(listOf("test Bluetooth"), connected)
        assertEquals(emptyList<AudioRouteSelection>(), phoneRoutes)
        controller.close()
        drainRouteExecutor()
    }

    @Test
    fun delayedModernPhoneConfirmationPublishesOnlyWhileItsGenerationIsCurrent() {
        val route = DelayedCommunicationRoute()
        val active = mutableListOf<AudioRouteSelection>()
        val errors = mutableListOf<Throwable>()
        val controller = AudioRouteController(
            context = context,
            onEarpieceActive = { active += AudioRouteSelection.EARPIECE },
            onSpeakerFallback = { active += AudioRouteSelection.SPEAKER },
            onError = errors::add,
            modernRouteFactory = { route }
        )

        controller.select(AudioRouteSelection.EARPIECE)
        drainRouteExecutor()
        assertEquals(emptyList<AudioRouteSelection>(), active)

        route.activeSelection = AudioRouteSelection.SPEAKER
        controller.select(AudioRouteSelection.SPEAKER)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(AudioRouteSelection.EARPIECE, AudioRouteSelection.SPEAKER),
            route.requests
        )
        assertEquals(listOf(AudioRouteSelection.SPEAKER), active)
        assertEquals(emptyList<Throwable>(), errors)
        controller.close()
        drainRouteExecutor()
    }

    @Test
    fun closeCancelsPendingModernPhoneConfirmationAndError() {
        val route = DelayedCommunicationRoute()
        val active = mutableListOf<AudioRouteSelection>()
        val errors = mutableListOf<Throwable>()
        val controller = AudioRouteController(
            context = context,
            onEarpieceActive = { active += AudioRouteSelection.EARPIECE },
            onError = errors::add,
            modernRouteFactory = { route }
        )

        controller.select(AudioRouteSelection.EARPIECE)
        drainRouteExecutor()
        controller.close()
        route.activeSelection = AudioRouteSelection.EARPIECE
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2L))
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<AudioRouteSelection>(), active)
        assertEquals(emptyList<Throwable>(), errors)
    }

    @Test
    fun phoneRoutesDoNotRequireOptionalBluetoothPermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val earpiece = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        val active = mutableListOf<AudioRouteSelection>()
        val errors = mutableListOf<Throwable>()
        val controller = AudioRouteController(
            context = context,
            onEarpieceActive = { active += AudioRouteSelection.EARPIECE },
            onSpeakerFallback = { active += AudioRouteSelection.SPEAKER },
            onError = errors::add
        )

        controller.select(AudioRouteSelection.EARPIECE)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()
        controller.select(AudioRouteSelection.SPEAKER)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(AudioRouteSelection.EARPIECE, AudioRouteSelection.SPEAKER),
            active
        )
        assertEquals(emptyList<Throwable>(), errors)
        controller.close()
        drainRouteExecutor()
    }

    @Test
    fun closeRestoresInitialModernModeAndCommunicationDevice() {
        val earpiece = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        shadowAudioManager.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        assertTrue(audioManager.setCommunicationDevice(earpiece))
        audioManager.mode = AudioManager.MODE_NORMAL
        val controller = AudioRouteController(context = context)

        controller.select(AudioRouteSelection.SPEAKER)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(speaker, audioManager.communicationDevice)

        controller.close()
        drainRouteExecutor()

        assertEquals(earpiece, audioManager.communicationDevice)
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    @Test
    @Config(sdk = [28])
    fun legacyPhoneRoutesSwitchBetweenEarpieceAndSpeaker() {
        val active = mutableListOf<AudioRouteSelection>()
        val controller = AudioRouteController(
            context = context,
            onEarpieceActive = { active += AudioRouteSelection.EARPIECE },
            onSpeakerFallback = { active += AudioRouteSelection.SPEAKER }
        )
        val earpiece = VersionedAudioRouteSelection(1, AudioRouteSelection.EARPIECE)
        setField(controller, "routeRequest", earpiece)

        invokeSelectPhoneRoute(controller, earpiece)
        shadowOf(Looper.getMainLooper()).idle()

        @Suppress("DEPRECATION")
        assertFalse(audioManager.isSpeakerphoneOn)
        val speaker = VersionedAudioRouteSelection(2, AudioRouteSelection.SPEAKER)
        setField(controller, "routeRequest", speaker)
        invokeSelectPhoneRoute(controller, speaker)
        shadowOf(Looper.getMainLooper()).idle()

        @Suppress("DEPRECATION")
        assertEquals(true, audioManager.isSpeakerphoneOn)
        assertEquals(
            listOf(AudioRouteSelection.EARPIECE, AudioRouteSelection.SPEAKER),
            active
        )
        controller.close()
    }

    @Test
    @Config(sdk = [28])
    fun legacyEarpiecePreferenceReportsConnectedWiredOutputInsteadOfClaimingEarpiece() {
        val wired = audioDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        shadowAudioManager.setOutputDevices(listOf(wired))
        val earpieceCallbacks = mutableListOf<Unit>()
        val externalOutputs = mutableListOf<String>()
        val controller = AudioRouteController(
            context = context,
            onEarpieceActive = { earpieceCallbacks += Unit },
            onExternalAudioActive = externalOutputs::add
        )

        controller.select(AudioRouteSelection.EARPIECE)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<Unit>(), earpieceCallbacks)
        assertEquals(listOf("有线耳机"), externalOutputs)
        controller.close()
        drainRouteExecutor()
    }

    @Test
    @Config(sdk = [28])
    fun legacyBluetoothFallbackKeepsPreferenceAndRetriesWhenScoDeviceAppears() {
        val connected = mutableListOf<String>()
        val speakerFallbacks = mutableListOf<Boolean>()
        shadowAudioManager.setIsBluetoothScoAvailableOffCall(false)
        val controller = AudioRouteController(
            context = context,
            onScoConnected = connected::add,
            onSpeakerFallback = speakerFallbacks::add
        )

        controller.select(AudioRouteSelection.BLUETOOTH)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(true), speakerFallbacks)
        assertTrue(field(controller, "wantBluetoothSco"))
        assertTrue(field<java.util.concurrent.atomic.AtomicBoolean>(controller, "audioDeviceCallbackRegistered").get())

        shadowAudioManager.setIsBluetoothScoAvailableOffCall(true)
        val bluetooth = audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        field<AudioDeviceCallback>(controller, "audioDeviceCallback")
            .onAudioDevicesAdded(arrayOf(bluetooth))
        drainRouteExecutor()

        @Suppress("DEPRECATION")
        assertTrue(audioManager.isBluetoothScoOn)
        @Suppress("DEPRECATION")
        assertFalse(audioManager.isSpeakerphoneOn)

        field<BroadcastReceiver>(controller, "receiver").onReceive(
            context,
            Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED).putExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_CONNECTED
            )
        )
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("头盔蓝牙"), connected)
        controller.close()
        drainRouteExecutor()
    }

    @Test
    @Config(sdk = [28])
    fun closeRestoresInitialLegacyModeAndSpeakerState() {
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = true }
        val controller = AudioRouteController(context = context)

        controller.select(AudioRouteSelection.EARPIECE)
        drainRouteExecutor()
        shadowOf(Looper.getMainLooper()).idle()
        @Suppress("DEPRECATION")
        assertFalse(audioManager.isSpeakerphoneOn)

        controller.close()
        drainRouteExecutor()

        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        @Suppress("DEPRECATION")
        assertTrue(audioManager.isSpeakerphoneOn)
    }

    private fun invokeFallbackToPhone(controller: AudioRouteController) {
        AudioRouteController::class.java.getDeclaredMethod(
            "fallbackToPhone",
            Boolean::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }
            .invoke(controller, true, "test fallback")
    }

    private fun invokePostMainForRoute(
        controller: AudioRouteController,
        request: VersionedAudioRouteSelection,
        callback: () -> Unit
    ) {
        AudioRouteController::class.java.declaredMethods.single {
            it.name.startsWith("postMainForRoute") && it.parameterCount == 2
        }.apply { isAccessible = true }.invoke(controller, request, callback)
    }

    private fun invokeSelectPhoneRoute(
        controller: AudioRouteController,
        request: VersionedAudioRouteSelection
    ) {
        AudioRouteController::class.java.declaredMethods.single {
            it.name.startsWith("selectPhoneRoute") && it.parameterCount == 1
        }.apply { isAccessible = true }.invoke(controller, request)
    }

    private fun audioDevice(type: Int): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    private fun drainRouteExecutor() {
        val executor = AudioRouteController::class.java.getDeclaredField("ROUTE_EXECUTOR")
            .apply { isAccessible = true }
            .get(null) as ExecutorService
        val drained = CountDownLatch(1)
        executor.execute(drained::countDown)
        assertTrue(drained.await(2, TimeUnit.SECONDS))
    }

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

    private class DelayedCommunicationRoute : CommunicationDeviceRoute {
        val requests = mutableListOf<AudioRouteSelection>()
        var activeSelection: AudioRouteSelection? = null

        override fun register() = Unit

        override fun routeTo(selection: AudioRouteSelection): ModernAudioRoute.RouteResult {
            requests += selection
            return ModernAudioRoute.RouteResult.ROUTED
        }

        override fun currentName(): String? =
            if (activeSelection == AudioRouteSelection.BLUETOOTH) "test Bluetooth" else null

        override fun clear() = Unit

        override fun isActive(selection: AudioRouteSelection): Boolean =
            activeSelection == selection

        override fun stateSummary(): String = "active=$activeSelection"

        override fun close() = Unit
    }
}
