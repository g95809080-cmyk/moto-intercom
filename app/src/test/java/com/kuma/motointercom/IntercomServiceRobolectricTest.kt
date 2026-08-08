package com.kuma.motointercom

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntercomServiceRobolectricTest {
    @Test
    fun listenerReplaysPersistedVoxSettingsWithoutPersistingMute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioControlPreferences(context).saveVoxSettings(
            AudioControlSettings(muted = true, voxEnabled = false, voxSensitivity = 73)
        )
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val snapshots = mutableListOf<AudioControlSnapshot>()

        service.setListener(audioControlListener(snapshots))

        assertEquals(
            AudioControlSnapshot(
                AudioControlSettings(muted = false, voxEnabled = false, voxSensitivity = 73),
                VoxRuntimeState.DISABLED
            ),
            snapshots.last()
        )
        controller.destroy()
    }

    @Test
    fun staleEngineControlSnapshotCannotOverwriteCurrentVoxState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioControlPreferences(context).saveVoxSettings(AudioControlSettings())
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val snapshots = mutableListOf<AudioControlSnapshot>()
        val runtime = RuntimeSessionId("audio-controls-runtime")
        setPrivate(service, "running", true)
        setPrivate(service, "activeRuntimeSessionId", runtime.value)
        service.setListener(audioControlListener(snapshots))
        service.setVoxSettings(voxEnabled = true, voxSensitivity = 80)

        invokeVoxState(
            service,
            runtime,
            AudioControlSettings(voxEnabled = true, voxSensitivity = 50),
            VoxRuntimeState.OPEN
        )
        assertEquals(80, snapshots.last().controls.voxSensitivity)
        assertEquals(VoxRuntimeState.LISTENING, snapshots.last().voxState)

        invokeVoxState(
            service,
            runtime,
            AudioControlSettings(voxEnabled = true, voxSensitivity = 80),
            VoxRuntimeState.OPEN
        )
        assertEquals(VoxRuntimeState.OPEN, snapshots.last().voxState)
        controller.destroy()
    }

    @Test
    fun stoppingRuntimeClearsTransientMuteButKeepsVoxSettings() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val snapshots = mutableListOf<AudioControlSnapshot>()
        val runtime = RuntimeSessionId("muted-runtime")
        setPrivate(service, "running", true)
        setPrivate(service, "activeRuntimeSessionId", runtime.value)
        service.setListener(audioControlListener(snapshots))
        service.setVoxSettings(voxEnabled = true, voxSensitivity = 64)
        service.setMuted(true)
        assertEquals(VoxRuntimeState.MUTED, snapshots.last().voxState)

        IntercomService::class.java.getDeclaredMethod("stopIntercom").apply {
            isAccessible = true
        }.invoke(service)

        assertEquals(
            AudioControlSnapshot(
                AudioControlSettings(muted = false, voxEnabled = true, voxSensitivity = 64),
                VoxRuntimeState.IDLE
            ),
            snapshots.last()
        )
        controller.destroy()
    }

    @Test
    fun startupFailureIsReportedBeforeRuntimeIsStopped() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("startup failed")

        val started = runSafelyOrStop(
            action = { throw failure },
            onFailure = { error -> events += "failure:${error.message}" },
            onStop = { events += "stop" }
        )

        assertFalse(started)
        assertEquals(listOf("failure:startup failed", "stop"), events)
    }

    @Test
    fun cleanupFailureDoesNotEscapeTheStartupBoundary() {
        val events = mutableListOf<String>()

        val started = runSafelyOrStop(
            action = { throw IllegalStateException("startup failed") },
            onFailure = { events += "failure" },
            onStop = { throw IllegalStateException("cleanup failed") }
        )

        assertFalse(started)
        assertEquals(listOf("failure"), events)
    }

    @Test
    fun staleConfirmationCancellationCannotClearTheCurrentPrompt() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val current = incomingPrompt(
            nonce = "current-confirmation",
            surface = ConfirmationSurface.IN_APP,
            deadline = SystemClock.elapsedRealtime() + 60_000L
        )
        val stale = incomingPrompt(
            nonce = "stale-confirmation",
            surface = ConfirmationSurface.IN_APP,
            deadline = SystemClock.elapsedRealtime() + 60_000L
        )
        val canceled = mutableListOf<String>()
        setActiveIncomingPrompt(service, current)
        service.setListener(recordingListener(mutableListOf(), canceled))

        val method = IntercomService::class.java.getDeclaredMethod(
            "cancelIncomingConfirmation",
            SessionEffect.CancelIncomingConfirmation::class.java
        ).apply { isAccessible = true }
        method.invoke(
            service,
            SessionEffect.CancelIncomingConfirmation(
                stale.runtimeSessionId,
                stale.attemptId,
                stale.actionNonce
            )
        )

        assertEquals(current, activeIncomingPrompt(service))
        assertTrue(canceled.isEmpty())
        controller.destroy()
    }

    @Test
    fun listenerReplaysOnlyTheCurrentInAppConfirmationAfterActivityRebind() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val prompt = incomingPrompt(
            nonce = "rebind-in-app",
            surface = ConfirmationSurface.IN_APP,
            deadline = SystemClock.elapsedRealtime() + 60_000L
        )
        setActiveIncomingPrompt(service, prompt)
        val replayed = mutableListOf<IncomingConfirmationPrompt>()

        service.setListener(recordingListener(replayed))

        assertEquals(listOf(prompt), replayed)
        controller.destroy()
    }

    @Test
    fun listenerDoesNotReplayNotificationOrExpiredConfirmationIntoTheActivity() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val replayed = mutableListOf<IncomingConfirmationPrompt>()
        val listener = recordingListener(replayed)

        setActiveIncomingPrompt(
            service,
            incomingPrompt(
                nonce = "rebind-notification",
                surface = ConfirmationSurface.NOTIFICATION,
                deadline = SystemClock.elapsedRealtime() + 60_000L
            )
        )
        service.setListener(listener)
        assertTrue(replayed.isEmpty())

        setActiveIncomingPrompt(
            service,
            incomingPrompt(
                nonce = "rebind-expired",
                surface = ConfirmationSurface.IN_APP,
                deadline = SystemClock.elapsedRealtime() - 1L
            )
        )
        service.setListener(listener)
        assertTrue(replayed.isEmpty())
        controller.destroy()
    }

    @Test
    fun incomingNotificationDoesNotInventDeviceOrSocketVerificationCopy() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val prompt = incomingPrompt(
            nonce = "notification-copy",
            surface = ConfirmationSurface.NOTIFICATION,
            deadline = SystemClock.elapsedRealtime() + 60_000L,
            deviceName = " "
        )
        val method = IntercomService::class.java.getDeclaredMethod(
            "buildIncomingConfirmationNotification",
            IncomingConfirmationPrompt::class.java
        ).apply { isAccessible = true }

        val notification = method.invoke(service, prompt) as Notification
        assertEquals(
            "设备名称未提供 · 请在应用内确认",
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        )
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString()
                ?.contains("Socket 身份") == false
        )
        controller.destroy()
    }

    private fun recordingListener(
        replayed: MutableList<IncomingConfirmationPrompt>,
        canceled: MutableList<String> = mutableListOf()
    ): IntercomService.Listener = object : IntercomService.Listener {
        override fun onStatusChanged(status: String, running: Boolean) = Unit
        override fun onLog(message: String) = Unit
        override fun onError(message: String) = Unit
        override fun onIncomingConfirmation(prompt: IncomingConfirmationPrompt) {
            replayed += prompt
        }
        override fun onIncomingConfirmationCanceled(actionNonce: String) {
            canceled += actionNonce
        }
    }

    private fun audioControlListener(
        snapshots: MutableList<AudioControlSnapshot>
    ): IntercomService.Listener = object : IntercomService.Listener {
        override fun onStatusChanged(status: String, running: Boolean) = Unit
        override fun onAudioControlsChanged(snapshot: AudioControlSnapshot) {
            snapshots += snapshot
        }
        override fun onLog(message: String) = Unit
        override fun onError(message: String) = Unit
    }

    private fun setPrivate(service: IntercomService, fieldName: String, value: Any) {
        IntercomService::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun invokeVoxState(
        service: IntercomService,
        runtimeSessionId: RuntimeSessionId,
        controls: AudioControlSettings,
        state: VoxRuntimeState
    ) {
        IntercomService::class.java.declaredMethods.single {
            it.name.startsWith("onVoxStateChanged") &&
                it.parameterCount == 3 &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.apply { isAccessible = true }.invoke(service, runtimeSessionId.value, controls, state)
    }

    private fun setActiveIncomingPrompt(
        service: IntercomService,
        prompt: IncomingConfirmationPrompt?
    ) {
        IntercomService::class.java.getDeclaredField("activeIncomingPrompt").apply {
            isAccessible = true
            set(service, prompt)
        }
    }

    private fun activeIncomingPrompt(service: IntercomService): IncomingConfirmationPrompt? =
        IntercomService::class.java.getDeclaredField("activeIncomingPrompt").apply {
            isAccessible = true
        }.get(service) as IncomingConfirmationPrompt?

    private fun incomingPrompt(
        nonce: String,
        surface: ConfirmationSurface,
        deadline: Long,
        deviceName: String = "Test Device"
    ): IncomingConfirmationPrompt = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("runtime-$nonce"),
        attemptId = ConnectionAttemptId("attempt-$nonce"),
        channelId = ControlChannelId.create(),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "device-$nonce",
            nickname = "Incoming Rider",
            deviceName = deviceName,
            runtimeSessionId = RuntimeSessionId("peer-runtime-$nonce"),
            isDeviceIdVerified = false
        ),
        decisionDeadlineElapsedMs = deadline,
        surface = surface
    )
}
