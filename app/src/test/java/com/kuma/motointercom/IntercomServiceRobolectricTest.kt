package com.kuma.motointercom

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntercomServiceRobolectricTest {
    @Before
    fun resetPairingDatabaseSingleton() {
        pairingDatabaseInstanceField().set(null, null)
    }

    @Test
    fun manualDiscoveryRefreshWithoutReadyResourcesIsCoalescedWithCurrentStartup() = runBlocking {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val runtime = RuntimeSessionId("runtime-manual-refresh")
        val orchestrator = IntercomService::class.java.getDeclaredField("orchestrator").apply {
            isAccessible = true
        }.get(service) as SessionOrchestrator
        val refreshLogged = CountDownLatch(1)
        val logs = mutableListOf<String>()
        setPrivate(service, "running", true)
        setPrivate(service, "activeRuntimeSessionId", runtime.value)
        assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime)))
        service.setListener(object : IntercomService.Listener {
            override fun onStatusChanged(status: String, running: Boolean) = Unit
            override fun onLog(message: String) {
                logs += message
                if (message == "重新扫描请求未启动新的发现轮次") {
                    refreshLogged.countDown()
                }
            }
            override fun onError(message: String) = Unit
        })

        service.requestDiscoveryRefresh()
        awaitMainCallback(refreshLogged)

        assertTrue(logs.contains("重新扫描请求未启动新的发现轮次"))
        assertEquals(IntercomState.Discovering(runtime), orchestrator.state.value)
        destroyAndAwait(controller)
        Unit
    }

    @Test
    fun preferredPresenceSnapshotStartsAnAutoPairedAttempt() = runBlocking {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        try {
            val service = controller.get()
            val runtime = RuntimeSessionId("auto-connect-runtime")
            val orchestrator = IntercomService::class.java.getDeclaredField("orchestrator").apply {
                isAccessible = true
            }.get(service) as SessionOrchestrator
            setPrivate(service, "running", true)
            setPrivate(service, "activeRuntimeSessionId", runtime.value)
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime)))

            val publish = IntercomService::class.java.getDeclaredMethod(
                "publishPresenceSnapshot",
                PresenceSnapshot::class.java
            ).apply { isAccessible = true }
            publish.invoke(
                service,
                PresenceSnapshot(
                    presences = listOf(preferredPresence()),
                    nextExpiryElapsedRealtimeMs = null
                )
            )

            withTimeout(1_000L) {
                while (orchestrator.state.value !is IntercomState.Connecting) delay(10L)
            }
            val attempt = requireNotNull(orchestrator.currentAttempt)
            assertEquals(ConnectionTrigger.AUTO_PAIRED, attempt.trigger)
            assertEquals("preferred-device", attempt.targetDeviceId)
        } finally {
            destroyAndAwait(controller)
        }
    }

    @Test
    fun serviceDoesNotReadActivityOwnedVoxPreferences() {
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
                AudioControlSettings(),
                VoxRuntimeState.IDLE
            ),
            snapshots.last()
        )
        destroyAndAwait(controller)
    }

    @Test
    fun startIntentCarriesActivityOwnedVoxSettingsWithoutSessionMute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = IntercomService.startIntent(
            context,
            riderName = "Road Captain",
            audioControls = AudioControlSettings(
                muted = true,
                voxEnabled = false,
                voxSensitivity = 73
            ),
            preferredAudioRoute = AudioRouteSelection.EARPIECE,
            automaticReconnectEnabled = false
        )

        assertEquals("Road Captain", intent.getStringExtra(IntercomService.EXTRA_RIDER_NAME))
        assertFalse(
            intent.getBooleanExtra("com.kuma.motointercom.extra.VOX_ENABLED", true)
        )
        assertEquals(
            73,
            intent.getIntExtra("com.kuma.motointercom.extra.VOX_SENSITIVITY", -1)
        )
        assertFalse(intent.hasExtra("com.kuma.motointercom.extra.MUTED"))
        assertEquals(
            AudioRouteSelection.EARPIECE.name,
            intent.getStringExtra("com.kuma.motointercom.extra.PREFERRED_AUDIO_ROUTE")
        )
        assertFalse(
            intent.getBooleanExtra(
                "com.kuma.motointercom.extra.AUTOMATIC_RECONNECT_ENABLED",
                true
            )
        )
    }

    @Test
    fun serviceReplaysAndUpdatesRuntimeAudioRouteWithoutReadingPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioRoutePreferences(context).save(AudioRouteSelection.SPEAKER)
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val selections = mutableListOf<AudioRouteSelection>()

        service.setListener(audioRouteListener(selections))
        service.setPreferredAudioRoute(AudioRouteSelection.EARPIECE)

        assertEquals(
            listOf(AudioRouteSelection.BLUETOOTH, AudioRouteSelection.EARPIECE),
            selections
        )
        destroyAndAwait(controller)
    }

    @Test
    fun staleEngineControlRevisionCannotWinAfterSettingsReturnToTheSameValue() {
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
        service.setVoxSettings(voxEnabled = true, voxSensitivity = 50)
        val currentRevision = audioControlRevision(service)

        invokeVoxState(
            service,
            runtime,
            VersionedAudioControls(
                revision = 0,
                settings = AudioControlSettings(voxEnabled = true, voxSensitivity = 50)
            ),
            VoxRuntimeState.OPEN
        )
        assertEquals(50, snapshots.last().controls.voxSensitivity)
        assertEquals(VoxRuntimeState.IDLE, snapshots.last().voxState)

        invokeVoxState(
            service,
            runtime,
            VersionedAudioControls(
                revision = currentRevision,
                settings = AudioControlSettings(voxEnabled = true, voxSensitivity = 50)
            ),
            VoxRuntimeState.OPEN
        )
        assertEquals(VoxRuntimeState.OPEN, snapshots.last().voxState)
        destroyAndAwait(controller)
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
        destroyAndAwait(controller)
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
        destroyAndAwait(controller)
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
        destroyAndAwait(controller)
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
        destroyAndAwait(controller)
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
        destroyAndAwait(controller)
    }

    @Test
    fun pairingManagementMutatesOnlyTheRequestedLocalRecordAndKeepsProductStateOffline() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val repository = RecordingPairingRepository(
            pairingRecord("peer-a"),
            pairingRecord("peer-b")
        )
        setPrivate(service, "pairingRepository", repository)
        val states = mutableListOf<IntercomState>()
        val toasts = mutableListOf<String>()

        var callback = CountDownLatch(1)
        service.setListener(pairingListener(states, toasts, callback))
        service.setPairingPreferred("peer-a", preferred = true)
        assertEquals("set:peer-a", repository.awaitOperation())
        awaitMainCallback(callback)
        assertTrue(repository.record("peer-a")?.isPreferred == true)
        assertFalse(repository.record("peer-b")?.isPreferred == true)
        assertEquals(
            service.getString(R.string.pairing_preferred_saved, "Rider peer-a"),
            toasts.last()
        )

        callback = CountDownLatch(1)
        service.setListener(pairingListener(states, toasts, callback))
        service.setPairingPreferred("peer-a", preferred = false)
        assertEquals("clear:peer-a", repository.awaitOperation())
        awaitMainCallback(callback)
        assertFalse(repository.record("peer-a")?.isPreferred == true)
        assertFalse(repository.record("peer-b")?.isPreferred == true)
        assertEquals(
            service.getString(R.string.pairing_preferred_cleared, "Rider peer-a"),
            toasts.last()
        )

        callback = CountDownLatch(1)
        service.setListener(pairingListener(states, toasts, callback))
        service.forgetPairing("peer-a")
        assertEquals("forget:peer-a", repository.awaitOperation())
        awaitMainCallback(callback)
        assertNull(repository.record("peer-a"))
        assertEquals(
            service.getString(R.string.pairing_forgotten, "Rider peer-a"),
            toasts.last()
        )
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it == IntercomState.Offline })
        destroyAndAwait(controller)
    }

    @Test
    fun pairingManagementReportsMissingRecordsWithoutCreatingOne() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val repository = RecordingPairingRepository()
        setPrivate(service, "pairingRepository", repository)
        val toasts = mutableListOf<String>()
        val callback = CountDownLatch(1)
        service.setListener(pairingListener(mutableListOf(), toasts, callback))

        service.setPairingPreferred("missing-peer", preferred = true)

        assertEquals("get:missing-peer", repository.awaitOperation())
        awaitMainCallback(callback)
        assertNull(repository.record("missing-peer"))
        assertEquals(
            service.getString(
                R.string.pairing_record_missing,
                service.getString(R.string.pairing_rider_fallback)
            ),
            toasts.last()
        )
        destroyAndAwait(controller)
    }

    @Test
    fun pairingPreferenceRequestsStayInInvocationOrderWhenTheFirstDatabaseCallSuspends() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val repository = RecordingPairingRepository(
            pairingRecord("peer-a"),
            pairingRecord("peer-b"),
            blockFirstPreference = true
        )
        setPrivate(service, "pairingRepository", repository)
        val callback = CountDownLatch(2)
        service.setListener(pairingListener(mutableListOf(), mutableListOf(), callback))

        service.setPairingPreferred("peer-a", preferred = true)
        assertEquals("set-enter:peer-a", repository.awaitOperation())
        service.setPairingPreferred("peer-b", preferred = true)
        repository.releaseFirstPreference()

        assertEquals("set:peer-a", repository.awaitOperation())
        assertEquals("set:peer-b", repository.awaitOperation())
        awaitMainCallback(callback)
        assertFalse(repository.record("peer-a")?.isPreferred == true)
        assertTrue(repository.record("peer-b")?.isPreferred == true)
        destroyAndAwait(controller)
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

    private fun audioRouteListener(
        selections: MutableList<AudioRouteSelection>
    ): IntercomService.Listener = object : IntercomService.Listener {
        override fun onStatusChanged(status: String, running: Boolean) = Unit
        override fun onAudioRouteSelectionChanged(selection: AudioRouteSelection) {
            selections += selection
        }
        override fun onLog(message: String) = Unit
        override fun onError(message: String) = Unit
    }

    private fun pairingListener(
        states: MutableList<IntercomState>,
        toasts: MutableList<String>,
        callback: CountDownLatch
    ): IntercomService.Listener = object : IntercomService.Listener {
        override fun onStatusChanged(status: String, running: Boolean) = Unit
        override fun onIntercomStateChanged(state: IntercomState) {
            states += state
        }
        override fun onLog(message: String) = Unit
        override fun onToast(message: String) {
            toasts += message
            callback.countDown()
        }
        override fun onError(message: String) = Unit
    }

    private fun awaitMainCallback(callback: CountDownLatch) {
        repeat(100) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (callback.await(10L, TimeUnit.MILLISECONDS)) return
        }
        error("timed out waiting for Service callback")
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
        controls: VersionedAudioControls,
        state: VoxRuntimeState
    ) {
        IntercomService::class.java.declaredMethods.single {
            it.name.startsWith("onVoxStateChanged") &&
                it.parameterCount == 3 &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.apply { isAccessible = true }.invoke(service, runtimeSessionId.value, controls, state)
    }

    private fun audioControlRevision(service: IntercomService): Long =
        IntercomService::class.java.getDeclaredField("audioControlRevision").apply {
            isAccessible = true
        }.getLong(service)

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

    private fun pairingRecord(deviceId: String): PairingRecord = PairingRecord(
        remoteDeviceId = deviceId,
        remoteNickname = "Rider $deviceId",
        deviceName = "Phone $deviceId",
        localAlias = "",
        shortCode = "1234",
        pairedAt = 1L,
        lastConnectedAt = 2L,
        isPreferred = false,
        lastTransport = "LAN",
        failureCount = 0
    )

    private fun preferredPresence() = RiderPresence(
        deviceId = "preferred-device",
        sessionId = RuntimeSessionId("preferred-session"),
        nickname = "Preferred Rider",
        deviceName = "Preferred Phone",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = Transport.LAN,
                endpointId = "preferred-endpoint",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = pairingRecord("preferred-device").copy(isPreferred = true)
    )

    private fun destroyAndAwait(controller: ServiceController<IntercomService>) {
        val service = controller.get()
        val scope = IntercomService::class.java.getDeclaredField("serviceScope").apply {
            isAccessible = true
        }.get(service) as CoroutineScope
        val completion = CountDownLatch(1)
        scope.coroutineContext[Job]?.invokeOnCompletion { completion.countDown() }
            ?: completion.countDown()

        controller.destroy()

        assertTrue(
            "IntercomService coroutine scope did not stop",
            completion.await(5L, TimeUnit.SECONDS)
        )
        val databaseField = pairingDatabaseInstanceField()
        (databaseField.get(null) as? PairingDatabase)?.close()
        databaseField.set(null, null)
    }

    private fun pairingDatabaseInstanceField() =
        PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }

    private class RecordingPairingRepository(
        vararg initialRecords: PairingRecord,
        blockFirstPreference: Boolean = false
    ) : PairingRepository {
        private val records = initialRecords.associateByTo(linkedMapOf(), PairingRecord::remoteDeviceId)
        private val observed = MutableStateFlow(records.values.toList())
        private val operations = LinkedBlockingQueue<String>()
        private val blockPreference = AtomicBoolean(blockFirstPreference)
        private val firstPreferenceRelease = CompletableDeferred<Unit>()

        override fun observeAll(): Flow<List<PairingRecord>> = observed

        override suspend fun getAll(): List<PairingRecord> = synchronized(this) {
            records.values.toList()
        }

        override suspend fun getByDeviceId(deviceId: String): PairingRecord? = synchronized(this) {
            records[deviceId].also { record ->
                if (record == null) operations.offer("get:$deviceId")
            }
        }

        override suspend fun saveConnectedPeer(record: PairingRecord) {
            synchronized(this) {
                records[record.remoteDeviceId] = record
                publish()
            }
        }

        override suspend fun setPreferred(deviceId: String): Boolean {
            if (blockPreference.compareAndSet(true, false)) {
                operations.offer("set-enter:$deviceId")
                firstPreferenceRelease.await()
            }
            return synchronized(this) {
                val target = records[deviceId] ?: return@synchronized false
                records.replaceAll { _, record -> record.copy(isPreferred = false) }
                records[deviceId] = target.copy(isPreferred = true)
                operations.offer("set:$deviceId")
                publish()
                true
            }
        }

        override suspend fun clearPreferred(deviceId: String): Boolean = synchronized(this) {
            val target = records[deviceId]
            val changed = target?.isPreferred == true
            if (changed) {
                records[deviceId] = requireNotNull(target).copy(isPreferred = false)
                publish()
            }
            operations.offer("clear:$deviceId")
            changed
        }

        override suspend fun updateLastConnectedAt(
            deviceId: String,
            connectedAt: Long,
            transport: String?
        ): Boolean = false

        override suspend fun incrementFailureCount(deviceId: String): Boolean = false

        override suspend fun clearFailureCount(deviceId: String): Boolean = false

        override suspend fun forget(deviceId: String): Boolean = synchronized(this) {
            val removed = records.remove(deviceId) != null
            operations.offer("forget:$deviceId")
            if (removed) publish()
            removed
        }

        fun record(deviceId: String): PairingRecord? = synchronized(this) { records[deviceId] }

        fun awaitOperation(): String? = operations.poll(5L, TimeUnit.SECONDS)

        fun releaseFirstPreference() {
            firstPreferenceRelease.complete(Unit)
        }

        private fun publish() {
            observed.value = records.values.toList()
        }
    }
}
