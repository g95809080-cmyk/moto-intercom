package com.kuma.motointercom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.PeerConnection
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.network.GroupGoOwnership
import java.util.UUID

private const val PEER_RECONNECT_BACKOFF_MS = 1_500L

internal class DiscoveryRefreshGate {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    fun begin(): Long? {
        if (activeGeneration != null) return null
        val generation = ++nextGeneration
        activeGeneration = generation
        return generation
    }

    fun accepts(generation: Long): Boolean = activeGeneration == generation

    fun complete(generation: Long) {
        if (activeGeneration == generation) activeGeneration = null
    }

    fun cancel() {
        activeGeneration = null
    }
}

internal fun restartDiscoveryDelayMillis(nextAttempt: ConnectionAttempt?): Long =
    if (nextAttempt?.trigger == ConnectionTrigger.RECOVERY) 0L else PEER_RECONNECT_BACKOFF_MS

internal fun shouldReuseRecoveryDiscovery(effect: SessionEffect.RestartDiscovery): Boolean =
    effect.attempt.trigger == ConnectionTrigger.RECOVERY &&
        effect.restartDelayMillis > 0L

internal fun prepareRecoveryDiscoveryAdapters(
    planned: Set<Transport>,
    prepare: (Transport) -> Boolean
): Boolean {
    for (transport in listOf(Transport.WIFI_DIRECT, Transport.LAN)) {
        if (transport in planned && !prepare(transport)) return false
    }
    return true
}

internal fun runSafelyOrStop(
    action: () -> Unit,
    onFailure: (Throwable) -> Unit,
    onStop: () -> Unit
): Boolean = try {
    action()
    true
} catch (t: Throwable) {
    runCatching { onFailure(t) }
    runCatching { onStop() }
    false
}

internal class RecoveryTransportStartup(
    private val expectedAttempt: ConnectionAttempt?,
    private val dispatch: (SessionEvent.RecoveryTransportReady) -> Unit
) {
    fun <WifiDirectAdapter, LanAdapter> start(
        plannedTransports: Set<Transport>,
        createWifiDirect: ((ConnectionAttempt) -> Unit) -> WifiDirectAdapter,
        installWifiDirect: (WifiDirectAdapter) -> Unit,
        startWifiDirect: (WifiDirectAdapter) -> Unit,
        createLan: () -> LanAdapter,
        installLan: (LanAdapter) -> Unit,
        startLan: (LanAdapter) -> Boolean
    ) {
        if (Transport.WIFI_DIRECT in plannedTransports) {
            val adapter = createWifiDirect { reportReady(it, Transport.WIFI_DIRECT) }
            installWifiDirect(adapter)
            startWifiDirect(adapter)
        }
        if (Transport.LAN in plannedTransports) {
            val adapter = createLan()
            installLan(adapter)
            if (startLan(adapter)) {
                expectedAttempt?.let { reportReady(it, Transport.LAN) }
            }
        }
    }

    private fun reportReady(attempt: ConnectionAttempt, transport: Transport) {
        val expected = expectedAttempt ?: return
        if (attempt != expected || transport !in expected.channelPlan) return
        dispatch(SessionEvent.RecoveryTransportReady(expected, transport))
    }
}

/**
 * 后台免死对讲服务。
 *
 * Activity 只做遥控器；蓝牙 SCO、Wi-Fi Direct、WebRTC 信令全部由前台服务托管，
 * 锁屏和退到后台时不跟着 Activity 一起释放。
 */
class IntercomService : Service() {

    private data class IncomingActionIdentity(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId,
        val actionNonce: String
    )

    internal interface Listener {
        fun onStatusChanged(status: String, running: Boolean)
        fun onIntercomStateChanged(state: IntercomState) = Unit
        fun onAudioSourceChanged(status: String, bluetooth: Boolean) = Unit
        fun onAudioInterruptionChanged(state: AudioInterruptionState) = Unit
        fun onAudioRouteSelectionChanged(selection: AudioRouteSelection) = Unit
        fun onPresencesChanged(presences: List<RiderPresence>) = Unit
        fun onAudioLevelChanged(level: Float) = Unit
        fun onAudioControlsChanged(snapshot: AudioControlSnapshot) = Unit
        fun onLog(message: String)
        fun onToast(message: String) = Unit
        fun onRemoteRiderIdentified(name: String) = Unit
        fun onIncomingConfirmation(prompt: IncomingConfirmationPrompt) = Unit
        fun onIncomingConfirmationCanceled(actionNonce: String) = Unit
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): IntercomService = this@IntercomService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = SessionGeneration()
    private val recoveryCleanupCoordinator = RecoveryCleanupCoordinator(
        postDelayed = { callback, delayMs -> mainHandler.postDelayed(callback, delayMs) },
        removeCallbacks = mainHandler::removeCallbacks,
        restart = ::restartAfterRecoveryCleanup
    )
    private val discoveryRefreshGate = DiscoveryRefreshGate()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val presenceAggregator = PresenceAggregator(SystemClock::elapsedRealtime)

    private lateinit var identityStore: LocalIdentityStore
    private lateinit var pairingRepository: PairingRepository
    private val pairingMutationMutex = Mutex()
    private lateinit var orchestrator: SessionOrchestrator
    private val attemptDeadlineScheduler = AttemptDeadlineScheduler(
        elapsedRealtime = SystemClock::elapsedRealtime,
        postDelayed = { callback, delayMs -> mainHandler.postDelayed(callback, delayMs) },
        removeCallbacks = mainHandler::removeCallbacks,
        onTimedOut = { attempt ->
            publishLog("Connection attempt timed out: ${attempt.id.value}")
            orchestrator.dispatch(
                SessionEvent.AttemptTimedOut(
                    attempt.runtimeSessionId,
                    attempt.id,
                    attempt.deadlineElapsedRealtimeMs
                )
            )
        }
    )
    private val attemptMilestoneScheduler = AttemptMilestoneScheduler(
        elapsedRealtime = SystemClock::elapsedRealtime,
        postDelayed = { callback, delayMs -> mainHandler.postDelayed(callback, delayMs) },
        removeCallbacks = mainHandler::removeCallbacks,
        onElapsed = { milestone ->
            orchestrator.dispatch(SessionEvent.AttemptMilestoneElapsed(milestone))
        }
    )
    private val controlChannelCloseDeadlineScheduler = ControlChannelCloseDeadlineScheduler(
        elapsedRealtime = SystemClock::elapsedRealtime,
        postDelayed = { callback, delayMs -> mainHandler.postDelayed(callback, delayMs) },
        removeCallbacks = mainHandler::removeCallbacks,
        onTimedOut = onTimedOut@ { deadline ->
            val session = signalingSessions[deadline.channelId]
                ?.takeIf {
                    !it.isClosed &&
                        it.matchesControlHandle(
                            deadline.runtimeSessionId,
                            deadline.attemptId,
                            deadline.channelId
                        )
                }
                ?: return@onTimedOut
            closeControlChannel(session)
            orchestrator.dispatch(
                SessionEvent.SignalingSendFailed(
                    deadline.runtimeSessionId,
                    deadline.attemptId,
                    deadline.channelId,
                    SignalingMessageTypeV2.CONNECT_REJECT,
                    "superseded channel close deadline elapsed"
                )
            )
        }
    )
    private val incomingConfirmationScheduler = IncomingConfirmationDeadlineScheduler(
        elapsedRealtime = SystemClock::elapsedRealtime,
        postDelayed = { callback, delayMs -> mainHandler.postDelayed(callback, delayMs) },
        removeCallbacks = mainHandler::removeCallbacks,
        onTimedOut = { prompt ->
            orchestrator.dispatch(
                SessionEvent.IncomingDecisionTimedOut(
                    runtimeSessionId = prompt.runtimeSessionId,
                    attemptId = prompt.attemptId,
                    channelId = prompt.channelId,
                    actionNonce = prompt.actionNonce,
                    occurredAtElapsedMs = SystemClock.elapsedRealtime()
                )
            )
        }
    )

    private var listener: Listener? = null
    private var groupRuntime: GroupRuntime? = null
    private var groupStarting: UUID? = null
    private var legacyOwnership: Any? = null
    private var groupState = GroupServiceState()
    private val groupListeners = mutableSetOf<(GroupServiceState) -> Unit>()
    private fun localGroupActive() = groupStarting != null || groupRuntime != null
    private fun groupOwnsResources() = localGroupActive() || GroupGoOwnership.process.hasOwner() || GroupRuntimeOwnership.hasOwner()
    internal fun addGroupListener(listener: (GroupServiceState) -> Unit) { groupListeners += listener; listener(groupState) }
    internal fun removeGroupListener(listener: (GroupServiceState) -> Unit) { groupListeners -= listener }
    private fun publishGroup(state: GroupServiceState) {
        groupState = state
        groupListeners.toList().forEach { runCatching { it(state) } }
        if (localGroupActive()) runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification()) }
    }
    internal fun groupAction(event: GroupSessionEvent) {
        if (event == GroupSessionEvent.Leave && groupStarting != null) {
            groupStarting = null; publishGroup(GroupServiceState(message = "已取消")); stopForegroundCompat()
        } else groupRuntime?.writer?.dispatch(event)
    }
    internal fun groupRoute(selection: AudioRouteSelection) {
        groupRuntime?.route(selection); publishGroup(groupState.copy(route = selection))
    }
    internal fun groupVox(value: Boolean) {
        groupRuntime?.vox(value); publishGroup(groupState.copy(vox = value))
    }
    private fun startGroup(code: GroupJoinCode?) {
        if (running || LegacyRuntimeOwnership.hasOwner() || AudioPlatformOwnership.hasOwner() || wifiTunnelCloseOwner.hasPending || groupOwnsResources()) {
            publishGroup(groupState.copy(message = "请先结束当前对讲，等待网络释放后重试")); return
        }
        if (!hasGroupPermissions()) { publishGroup(GroupServiceState(message = "请先授予麦克风和附近设备权限")); return }
        val token = UUID.randomUUID(); groupStarting = token
        groupState = GroupServiceState(message = "正在准备离线对讲", busy = true,
            route = AudioRoutePreferences(this).load(), vox = AudioControlPreferences(this).load().voxEnabled)
        try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {
            groupStarting = null
            publishGroup(GroupServiceState(message = "无法启动前台对讲，请返回页面重试"))
            return
        }
        publishGroup(groupState)
        serviceScope.launch {
            try {
                val device = identityStore.getOrCreateDeviceId()
                val nickname = identityStore.getNickname().ifBlank { "骑士" }
                dispatchOnMain {
                    if (groupStarting != token) return@dispatchOnMain
                    lateinit var runtime: GroupRuntime
                    runtime = GroupRuntime(this@IntercomService, GroupAuthEndpoint(device, UUID.randomUUID().toString()), nickname,
                        groupState.route, AudioControlPreferences(this@IntercomService).load(),
                        onSnapshot = { value ->
                            if (groupRuntime === runtime) publishGroup(groupState.copy(snapshot = value, message = value.message,
                                busy = value.phase != GroupPhase.IDLE))
                        }, onAudioLabel = { label ->
                            if (groupRuntime === runtime) publishGroup(groupState.copy(audioLabel = label))
                        }, onReleased = {
                            if (groupRuntime === runtime) {
                                groupRuntime = null; publishGroup(groupState.copy(busy = false))
                                stopForegroundCompat()
                            }
                        })
                    groupRuntime = runtime; groupStarting = null
                    try { runtime.start(code) } catch (_: Exception) {
                        publishGroup(groupState.copy(message = "群组启动失败，请检查无线网络后重试"))
                    }
                }
            } catch (_: Exception) {
                dispatchOnMain {
                    if (groupStarting == token) {
                        groupStarting = null; publishGroup(GroupServiceState(message = "无法读取设备身份，请重试"))
                        stopForegroundCompat()
                    }
                }
            }
        }
    }
    private var runtimeKeepAlive: IntercomRuntimeKeepAlive? = null
    private var audioSessionController: AudioSessionController? = null
    private var wifiTunnel: WifiDirectTunnel? = null
    private val wifiTunnelCloseOwner = PendingCloseOwner<WifiDirectTunnel> { tunnel, onComplete ->
        tunnel.close(onComplete)
    }
    private var intercomManager: IntercomManager? = null
    private var lanDiscovery: LanDiscoveryCoordinator? = null
    private val signalingSessions = linkedMapOf<ControlChannelId, SignalingSessionV2>()
    private val pendingMediaMessages =
        linkedMapOf<ConnectionCandidateContext, MutableList<SignalingMessageV2>>()
    private var activeMediaContext: ConnectionCandidateContext? = null
    private var activeMediaSession: SignalingSessionV2? = null

    private var bluetoothReady = false
    private var physicalLinkReady = false
    private var mediaConnected = false
    private var audioControls = AudioControlSettings()
    private var audioControlRevision = 0L
    private var voxRuntimeState = VoxRuntimeState.IDLE
    private var running = false
    private var lastStatus = READY_STATUS
    private var audioSourceStatus = AUDIO_STANDBY_STATUS
    private var audioSourceBluetooth = false
    private var audioInterruptionState = AudioInterruptionState.NORMAL
    private var preferredAudioRoute = AudioRouteSelection.BLUETOOTH
    private var requestedRiderName = ""
    private var remoteRiderName: String? = null
    private var appInForeground = false
    private var activeIncomingPrompt: IncomingConfirmationPrompt? = null
    private var activeSession: SessionGeneration.Token? = null
    private var activeRuntimeSessionId: RuntimeSessionId? = null
    private var localDeviceId = ""
    private var presenceExpiryGeneration = 0
    private var autoConnectTargetKey: PreferredAutoConnectTargetKey? = null

    override fun onCreate() {
        super.onCreate()
        identityStore = DataStoreLocalIdentityStore(this)
        pairingRepository = RoomPairingRepository(PairingDatabase.getInstance(this).pairingDao())
        orchestrator = SessionOrchestrator(
            pairingRepository,
            onLog = ::publishLog,
            onError = ::handleError,
            elapsedRealtime = SystemClock::elapsedRealtime
        )
        serviceScope.launch {
            orchestrator.state.collect { state ->
                dispatchOnMain {
                    listener?.onIntercomStateChanged(state)
                    if (running) updateNotification()
                }
            }
        }
        serviceScope.launch {
            orchestrator.effects.collect { effect ->
                dispatchOnMain { handleSessionEffect(effect) }
            }
        }
        serviceScope.launch {
            pairingRepository.observeAll().collect { records ->
                dispatchOnMain {
                    publishPresenceSnapshot(presenceAggregator.updatePairings(records))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GROUP -> {
                val raw = intent.getStringExtra(EXTRA_GROUP_CODE)
                if (raw != null && !raw.matches(Regex("[0-9]{6}"))) {
                    publishGroup(groupState.copy(message = "请输入六位数字房间码"))
                    if (!localGroupActive() && !running) stopSelf(startId)
                    return START_NOT_STICKY
                }
                startGroup(raw?.let(::GroupJoinCode))
                if (!localGroupActive() && !running) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START_INTERCOM -> {
                if (groupOwnsResources()) {
                    publishToast("四人对讲进行中，请先离开房间")
                    if (!localGroupActive() && !running) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!running && AudioPlatformOwnership.hasOwner()) { publishToast("音频正在释放，请稍后重试"); stopSelf(startId); return START_NOT_STICKY }
                if (!running && LegacyRuntimeOwnership.hasOwner()) { publishToast("网络正在释放，请稍后重试"); stopSelf(startId); return START_NOT_STICKY }
                requestedRiderName = intent.getStringExtra(EXTRA_RIDER_NAME).orEmpty().trim()
                setPreferredAudioRoute(
                    audioRouteSelectionFromPersisted(
                        intent.getStringExtra(EXTRA_PREFERRED_AUDIO_ROUTE)
                    )
                )
                setVoxSettings(
                    voxEnabled = intent.getBooleanExtra(
                        EXTRA_VOX_ENABLED,
                        audioControls.voxEnabled
                    ),
                    voxSensitivity = intent.getIntExtra(
                        EXTRA_VOX_SENSITIVITY,
                        audioControls.voxSensitivity
                    )
                )
                setAutomaticReconnectEnabled(
                    intent.getBooleanExtra(EXTRA_AUTOMATIC_RECONNECT_ENABLED, true)
                )
                if (!hasRequiredRuntimePermissions()) {
                    publishStatus("缺少必要权限，无法启动摩声")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (
                    !runSafelyOrStop(
                        action = {
                            startForeground(NOTIFICATION_ID, buildNotification())
                            startIntercom()
                        },
                        onFailure = ::handleError,
                        onStop = {
                            stopIntercom()
                            stopSelf(startId)
                        }
                    )
                ) {
                    return START_NOT_STICKY
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_INTERCOM -> {
                if (groupOwnsResources()) return START_NOT_STICKY
                stopIntercom()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACCEPT_INCOMING -> {
                handleIncomingConfirmationAction(intent, accepted = true)
                return START_NOT_STICKY
            }
            ACTION_REJECT_INCOMING -> {
                handleIncomingConfirmationAction(intent, accepted = false)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        groupStarting = null
        groupRuntime?.close()
        groupListeners.clear()
        stopIntercom()
        orchestrator.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    internal fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onStatusChanged(lastStatus, running)
        listener?.onIntercomStateChanged(orchestrator.state.value)
        listener?.onAudioSourceChanged(audioSourceStatus, audioSourceBluetooth)
        listener?.onAudioInterruptionChanged(audioInterruptionState)
        listener?.onAudioRouteSelectionChanged(preferredAudioRoute)
        listener?.onAudioControlsChanged(currentAudioControlSnapshot())
        listener?.onPresencesChanged(presenceAggregator.snapshot().presences)
        remoteRiderName?.let { listener?.onRemoteRiderIdentified(it) }
        listener?.let(::replayActiveIncomingConfirmation)
    }

    private fun replayActiveIncomingConfirmation(target: Listener) {
        val prompt = activeIncomingPrompt
            ?.takeIf { it.surface == ConfirmationSurface.IN_APP }
            ?.takeIf { it.decisionDeadlineElapsedMs > SystemClock.elapsedRealtime() }
            ?: return
        try {
            target.onIncomingConfirmation(prompt)
        } catch (t: Throwable) {
            handleError(t)
        }
    }

    internal fun setAppForeground(foreground: Boolean) {
        dispatchOnMain {
            appInForeground = foreground
            publishConfirmationAvailability()
        }
    }

    internal fun setMuted(muted: Boolean) {
        dispatchOnMain {
            applyAudioControls(audioControls.copy(muted = running && muted))
        }
    }

    internal fun setVoxSettings(voxEnabled: Boolean, voxSensitivity: Int) {
        dispatchOnMain {
            applyAudioControls(
                audioControls.copy(
                    voxEnabled = voxEnabled,
                    voxSensitivity = voxSensitivity
                )
            )
        }
    }

    internal fun setPreferredAudioRoute(selection: AudioRouteSelection) {
        dispatchOnMain {
            val changed = selection != preferredAudioRoute
            preferredAudioRoute = selection
            if (changed) listener?.onAudioRouteSelectionChanged(selection)
            try {
                audioSessionController?.updateAudioRoute(selection)
            } catch (error: RuntimeException) {
                handleError(error)
            }
        }
    }

    internal fun setAutomaticReconnectEnabled(enabled: Boolean) {
        dispatchOnMain {
            orchestrator.dispatch(SessionEvent.AutomaticReconnectChanged(enabled))
        }
    }

    internal fun setPairingPreferred(deviceId: String, preferred: Boolean) {
        val normalizedDeviceId = deviceId.trim()
        if (normalizedDeviceId.isBlank()) {
            publishToast(
                getString(
                    R.string.pairing_record_missing,
                    getString(R.string.pairing_rider_fallback)
                )
            )
            return
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pairingMutationMutex.withLock {
                try {
                    val record = pairingRepository.getByDeviceId(normalizedDeviceId)
                    if (record == null) {
                        publishMissingPairing()
                        return@withLock
                    }
                    val riderName = pairingRiderName(record)
                    if (record.isPreferred == preferred) {
                        publishPairingPreferenceFeedback(riderName, preferred)
                        return@withLock
                    }
                    val changed = if (preferred) {
                        pairingRepository.setPreferred(normalizedDeviceId)
                    } else {
                        pairingRepository.clearPreferred(normalizedDeviceId)
                    }
                    if (changed) {
                        publishPairingPreferenceFeedback(riderName, preferred)
                    } else {
                        val current = pairingRepository.getByDeviceId(normalizedDeviceId)
                        when {
                            current == null -> publishMissingPairing(riderName)
                            current.isPreferred == preferred ->
                                publishPairingPreferenceFeedback(pairingRiderName(current), preferred)
                            else -> publishToast(getString(R.string.pairing_update_failed))
                        }
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    publishLog("Pairing preference update failed: ${error.message ?: error.javaClass.simpleName}")
                    publishToast(getString(R.string.pairing_update_failed))
                }
            }
        }
    }

    internal fun forgetPairing(deviceId: String) {
        val normalizedDeviceId = deviceId.trim()
        if (normalizedDeviceId.isBlank()) {
            publishMissingPairing()
            return
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pairingMutationMutex.withLock {
                try {
                    val record = pairingRepository.getByDeviceId(normalizedDeviceId)
                    if (record == null) {
                        publishMissingPairing()
                        return@withLock
                    }
                    val riderName = pairingRiderName(record)
                    val forgotten = pairingRepository.forget(normalizedDeviceId)
                    if (forgotten) {
                        publishToast(getString(R.string.pairing_forgotten, riderName))
                    } else {
                        publishMissingPairing(riderName)
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    publishLog("Forget pairing failed: ${error.message ?: error.javaClass.simpleName}")
                    publishToast(getString(R.string.pairing_update_failed))
                }
            }
        }
    }

    private fun publishPairingPreferenceFeedback(riderName: String, preferred: Boolean) {
        publishToast(
            getString(
                if (preferred) {
                    R.string.pairing_preferred_saved
                } else {
                    R.string.pairing_preferred_cleared
                },
                riderName
            )
        )
    }

    private fun publishMissingPairing(
        riderName: String = getString(R.string.pairing_rider_fallback)
    ) {
        publishToast(getString(R.string.pairing_record_missing, riderName))
    }

    private fun pairingRiderName(record: PairingRecord): String =
        record.localAlias.trim()
            .ifBlank { record.remoteNickname.trim() }
            .ifBlank { record.deviceName.trim() }
            .ifBlank { getString(R.string.pairing_rider_fallback) }

    private fun applyAudioControls(requested: AudioControlSettings) {
        val next = requested.normalized().let {
            if (running) it else it.copy(muted = false)
        }
        if (next == audioControls) return
        val previous = audioControls
        val nextVersioned = VersionedAudioControls(
            revision = audioControlRevision + 1,
            settings = next
        )
        try {
            audioSessionController?.updateAudioControls(nextVersioned)
        } catch (error: RuntimeException) {
            handleError(error)
            return
        }
        audioControls = next
        audioControlRevision = nextVersioned.revision
        when {
            !running -> {
                voxRuntimeState = if (next.voxEnabled) {
                    VoxRuntimeState.IDLE
                } else {
                    VoxRuntimeState.DISABLED
                }
                publishAudioControls()
            }
            next.muted -> {
                voxRuntimeState = VoxRuntimeState.MUTED
                publishAudioControls()
            }
            !next.voxEnabled -> {
                voxRuntimeState = VoxRuntimeState.DISABLED
                publishAudioControls()
            }
            audioSessionController == null -> {
                voxRuntimeState = VoxRuntimeState.IDLE
                publishAudioControls()
            }
            previous.voxEnabled != next.voxEnabled ||
                previous.voxSensitivity != next.voxSensitivity -> {
                // With an engine, the gate is synchronously replaced before this returns.
                voxRuntimeState = VoxRuntimeState.LISTENING
                publishAudioControls()
            }
            previous.muted == next.muted -> publishAudioControls()
            // Unmute waits for RiderAudioEngine to report its current gate state.
        }
    }

    private fun onVoxStateChanged(
        runtimeSessionId: RuntimeSessionId,
        callbackControls: VersionedAudioControls,
        state: VoxRuntimeState
    ) {
        postForRuntime(runtimeSessionId) {
            if (
                callbackControls.revision != audioControlRevision ||
                callbackControls.settings != audioControls
            ) {
                return@postForRuntime
            }
            voxRuntimeState = state
            publishAudioControls()
        }
    }

    private fun currentAudioControlSnapshot(): AudioControlSnapshot =
        AudioControlSnapshot(audioControls, voxRuntimeState)

    private fun publishAudioControls() {
        listener?.onAudioControlsChanged(currentAudioControlSnapshot())
    }

    private fun onAudioInterruptionChanged(state: AudioInterruptionState) {
        audioInterruptionState = state
        listener?.onAudioInterruptionChanged(state)
        when (state) {
            AudioInterruptionState.PHONE_RINGING,
            AudioInterruptionState.PHONE_ACTIVE -> publishStatus(AUDIO_PAUSED_STATUS)
            AudioInterruptionState.RESUMING -> publishStatus(AUDIO_RESUMING_STATUS)
            AudioInterruptionState.FOCUS_LOST -> publishStatus(AUDIO_FOCUS_LOST_STATUS)
            AudioInterruptionState.ROUTE_UNAVAILABLE -> publishStatus(AUDIO_ROUTE_UNAVAILABLE_STATUS)
            AudioInterruptionState.NORMAL -> updateStageStatus()
        }
    }

    private fun resetSessionAudioControls() {
        val snapshot = idleAudioControlSnapshot(audioControls)
        if (snapshot.controls != audioControls) audioControlRevision++
        audioControls = snapshot.controls
        voxRuntimeState = snapshot.voxState
    }

    internal fun refreshConfirmationAvailability() {
        dispatchOnMain(::publishConfirmationAvailability)
    }

    internal fun respondToIncomingConfirmation(
        prompt: IncomingConfirmationPrompt,
        accepted: Boolean
    ) {
        dispatchOnMain {
            dispatchIncomingConfirmationAction(
                runtimeSessionId = prompt.runtimeSessionId,
                attemptId = prompt.attemptId,
                channelId = prompt.channelId,
                actionNonce = prompt.actionNonce,
                accepted = accepted
            )
        }
    }

    fun requestStart(riderName: String = "") {
        mainHandler.post {
            requestedRiderName = riderName.trim()
            if (hasRequiredRuntimePermissions()) startIntercom() else publishStatus("缺少必要权限，无法启动摩声")
        }
    }

    fun requestStop() {
        mainHandler.post {
            stopIntercom()
            stopSelf()
        }
    }

    fun requestDisconnectCurrent() {
        mainHandler.post {
            if (!running) return@post
            val runtimeSessionId = activeRuntimeSessionId ?: return@post
            if (primaryIntercomAction(orchestrator.state.value) !=
                PrimaryIntercomAction.DISCONNECT_CURRENT
            ) {
                return@post
            }
            val attempt = orchestrator.currentAttempt
                ?.takeIf { it.runtimeSessionId == runtimeSessionId }
                ?: return@post
            orchestrator.dispatch(SessionEvent.DisconnectRequested(runtimeSessionId, attempt.id))
        }
    }

    internal fun connectToPresence(selectedPresence: RiderPresence) {
        mainHandler.post {
            if (activeSession == null) return@post
            val runtimeSessionId = activeRuntimeSessionId ?: return@post
            val presence = presenceAggregator.snapshot().resolveCurrentSelection(selectedPresence)
            if (presence == null) {
                publishLog("Ignored stale or unavailable Presence selection")
                return@post
            }
            val targetDeviceId = requireNotNull(presence.deviceId)
            val targetSessionId = requireNotNull(presence.sessionId)
            orchestrator.dispatch(
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = runtimeSessionId,
                    targetDeviceId = targetDeviceId,
                    targetSessionId = targetSessionId,
                    availableTransports = presence.availableTransports
                )
            ) { accepted ->
                if (!accepted) {
                    dispatchOnMain { publishLog("Presence connection request was rejected") }
                }
            }
        }
    }

    internal fun requestDiscoveryRefresh() {
        mainHandler.post {
            if (!running) return@post
            val runtimeSessionId = activeRuntimeSessionId ?: return@post
            val generation = discoveryRefreshGate.begin() ?: return@post
            val queued = orchestrator.dispatch(
                SessionEvent.DiscoveryRefreshRequested(runtimeSessionId, generation)
            ) { accepted ->
                if (!accepted) {
                    dispatchOnMain { discoveryRefreshGate.complete(generation) }
                }
            }
            if (!queued) discoveryRefreshGate.complete(generation)
        }
    }

    private fun startIntercom() {
        if (running) {
            publishStatus(lastStatus)
            return
        }

        legacyOwnership = LegacyRuntimeOwnership.acquire() ?: run { publishStatus("网络正在释放，请稍后重试"); stopForegroundCompat(); return }

        runtimeKeepAlive = try {
            IntercomRuntimeKeepAlive.acquire(this)
        } catch (failure: Throwable) {
            legacyOwnership?.let(LegacyRuntimeOwnership::release); legacyOwnership = null
            handleError(failure)
            stopForegroundCompat()
            stopSelf()
            return
        }

        val token = sessions.start()
        val runtimeSessionId = RuntimeSessionId.create()
        activeSession = token
        activeRuntimeSessionId = runtimeSessionId
        running = true
        autoConnectTargetKey = null
        resetSessionAudioControls()
        publishAudioControls()
        bluetoothReady = false
        physicalLinkReady = false
        audioInterruptionState = AudioInterruptionState.NORMAL
        remoteRiderName = null
        publishPresenceSnapshot(presenceAggregator.clear())
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(SEARCHING_STATUS)
        orchestrator.dispatch(SessionEvent.RuntimeStarted(runtimeSessionId))
        publishConfirmationAvailability(runtimeSessionId)

        serviceScope.launch {
            try {
                val deviceId = identityStore.getOrCreateDeviceId()
                val storedNickname = identityStore.getNickname()
                val nickname = requestedRiderName.ifBlank { storedNickname }.ifBlank { "骑士" }
                if (requestedRiderName.isNotBlank()) identityStore.updateNickname(requestedRiderName)
                postForSession(token) {
                    if (activeRuntimeSessionId != runtimeSessionId) return@postForSession
                    runSafelyOrStop(
                        action = {
                            localDeviceId = deviceId
                            requestedRiderName = nickname
                            startAudioSession(runtimeSessionId)
                            startDiscoveryTransports(token, deviceId, runtimeSessionId)
                        },
                        onFailure = ::handleError,
                        onStop = ::stopIntercom
                    )
                }
            } catch (t: Throwable) {
                postForSession(token) {
                    handleError(t)
                    stopIntercom()
                }
            }
        }
    }

    private fun startAudioSession(runtimeSessionId: RuntimeSessionId) {
        if (audioSessionController != null) return
        audioSessionController = AudioSessionController.start(
            context = this,
            onScoConnected = { deviceName ->
                postForRuntime(runtimeSessionId) {
                    bluetoothReady = true
                    publishAudioSource("当前音频源：蓝牙耳机 ($deviceName)", bluetooth = true)
                    publishToast("头盔蓝牙已连线，对讲音频已就绪")
                    updateStageStatus()
                }
            },
            onScoDisconnected = {
                postForRuntime(runtimeSessionId) {
                    bluetoothReady = false
                    publishToast(BLUETOOTH_RETRY_STATUS)
                    publishLog(BLUETOOTH_RETRY_STATUS)
                    updateStageStatus()
                }
            },
            onSpeakerFallback = { noBluetooth ->
                postForRuntime(runtimeSessionId) {
                    bluetoothReady = false
                    publishAudioSource(AUDIO_SPEAKER_STATUS, bluetooth = false)
                    if (noBluetooth) publishToast("未检测到头盔蓝牙，已切换至手机外放")
                    updateStageStatus()
                }
            },
            onEarpieceActive = {
                postForRuntime(runtimeSessionId) {
                    bluetoothReady = false
                    publishAudioSource(AUDIO_EARPIECE_STATUS, bluetooth = false)
                    updateStageStatus()
                }
            },
            onExternalAudioActive = { outputLabel ->
                postForRuntime(runtimeSessionId) {
                    bluetoothReady = false
                    publishAudioSource("当前音频源：$outputLabel", bluetooth = false)
                    updateStageStatus()
                }
            },
            onError = { error -> postForRuntime(runtimeSessionId) { handleAudioRouteError(error) } },
            isRuntimeCurrent = {
                running && activeRuntimeSessionId == runtimeSessionId
            },
            initialAudioControls = VersionedAudioControls(
                revision = audioControlRevision,
                settings = audioControls
            ),
            onVoxStateChanged = { callbackControls, state ->
                onVoxStateChanged(runtimeSessionId, callbackControls, state)
            },
            initialAudioRoute = preferredAudioRoute,
            onAudioInterruptionChanged = { state ->
                postForRuntime(runtimeSessionId) { onAudioInterruptionChanged(state) }
            }
        )
    }

    private fun startDiscoveryTransports(
        token: SessionGeneration.Token,
        deviceId: String,
        runtimeSessionId: RuntimeSessionId,
        targetAttempt: ConnectionAttempt? = null
    ) {
        publishStatus(SEARCHING_STATUS)
        val recoveryStartup = RecoveryTransportStartup(targetAttempt) { event ->
            postForSession(token) { orchestrator.dispatch(event) }
        }
        val plannedTransports = plannedDiscoveryTransports(targetAttempt)
        recoveryStartup.start(
            plannedTransports = plannedTransports,
            createWifiDirect = { onStartupReady ->
                WifiDirectTunnel(
            context = this,
            onControlChannelReady = { session ->
                registerControlChannel(token, session)
            },
            localDeviceId = deviceId,
            localNickname = requestedRiderName.ifBlank { "骑士" },
            localDeviceName = Build.MODEL.orEmpty(),
            sessionId = runtimeSessionId,
            onPeersChanged = { peers ->
                postForSession(token) {
                    publishPresenceSnapshot(
                        presenceAggregator.replaceCandidates(
                            Transport.WIFI_DIRECT,
                            peers.map { peer ->
                                val address = peer.device.deviceAddress.trim()
                                DiscoveryCandidate(
                                    transport = Transport.WIFI_DIRECT,
                                    endpointId = address.lowercase(Locale.ROOT),
                                    address = address,
                                    port = null,
                                    identity = peer.identity
                                )
                            }
                        )
                    )
                    publishLog("发现附近设备：${peers.size}")
                    if (peers.isNotEmpty() && !physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
                }
            },
            onDiscoveryStatus = {
                postForSession(token) {
                    publishStatus(it)
                    publishLog(it)
                }
            },
            onDisconnected = {
                postForSession(token) { publishStatus(SIGNAL_LOST_STATUS) }
            },
            onTargetedOverlapUnavailable = { attempt ->
                postForSession(token) {
                    orchestrator.dispatch(
                        SessionEvent.TargetedTransportOverlapUnavailable(
                            attempt,
                            Transport.WIFI_DIRECT
                        )
                    )
                }
            },
            onStartupReady = onStartupReady,
            onError = { error -> postForSession(token) { handleError(error) } },
            initialTargetAttempt = targetAttempt
        )
            },
            installWifiDirect = { wifiTunnel = it },
            startWifiDirect = { it.start() },
            createLan = {
                LanDiscoveryCoordinator(
            context = this,
            token = token,
            isSessionCurrent = ::isSessionCurrent,
            nodeId = deviceId,
            runtimeSessionId = runtimeSessionId,
            riderName = requestedRiderName.ifBlank { "骑士" },
            deviceName = Build.MODEL.orEmpty(),
            protocolVersion = SignalingV2Codec.PROTOCOL_VERSION,
            onDevicesChanged = { devices ->
                postForSession(token) {
                    publishPresenceSnapshot(
                        presenceAggregator.replaceCandidates(
                            Transport.LAN,
                            devices.map { device ->
                                DiscoveryCandidate(
                                    transport = Transport.LAN,
                                    endpointId = device.discoveryEndpointId,
                                    address = device.ip,
                                    port = device.port,
                                    identity = DiscoveryIdentityClaim(
                                        claimedDeviceId = device.deviceId,
                                        sourceSessionId = device.sessionId,
                                        nickname = device.name,
                                        deviceName = device.deviceName,
                                        protocolVersion = device.protocolVersion
                                    )
                                )
                            }
                        )
                    )
                    if (!physicalLinkReady && devices.isNotEmpty()) publishStatus(PEER_FOUND_STATUS)
                }
            },
            onControlChannelReady = { session ->
                registerControlChannel(token, session)
            },
            onLog = { message -> postForSession(token) { publishLog(message) } },
            onError = { error -> postForSession(token) { handleError(error) } },
            initialTargetAttempt = targetAttempt
        )
            },
            installLan = { lanDiscovery = it },
            startLan = { it.start() }
        )
    }

    private fun registerControlChannel(
        token: SessionGeneration.Token,
        session: SignalingSessionV2
    ) {
        dispatchOnMain {
            val currentAttempt = orchestrator.currentAttempt
            val currentState = orchestrator.state.value
            if (
                admitControlSession(
                    sessionCurrent = isSessionCurrent(token),
                    currentAttempt = currentAttempt,
                    existingSession = signalingSessions[session.channel.channelId],
                    session = session,
                    currentState = currentState,
                    onRejectedNonTargetWifiDirect = { expectedAttempt, actualTargetLock ->
                        wifiTunnel?.rejectNonTargetGroup(expectedAttempt, actualTargetLock)
                    }
                ) != ControlChannelAdmissionOutcome.ADMITTED
            ) {
                return@dispatchOnMain
            }

            signalingSessions[session.channel.channelId] = session
            val runtimeSessionId = session.pinnedIdentity.localSessionId
            orchestrator.dispatch(
                SessionEvent.ControlChannelVerified(
                    runtimeSessionId = runtimeSessionId,
                    channel = VerifiedControlChannel(
                        channelId = session.channel.channelId,
                        transport = session.channel.transport,
                        requestRole = session.requestRole,
                        wireRequestKey = session.wireRequestKey,
                        targetLock = session.targetLock,
                        peer = session.peer,
                        originatingAttempt = session.originatingAttempt
                    )
                )
            ) { accepted ->
                dispatchOnMain {
                    if (
                        !accepted ||
                        !isSessionCurrent(token) ||
                        signalingSessions[session.channel.channelId] !== session
                    ) {
                        removeSignalingSession(session.channel.channelId, session)
                        session.close()
                        return@dispatchOnMain
                    }
                    publishLog(
                        "Verified v2 control channel ${session.channel.channelId.value}: " +
                            "transport=${session.channel.transport} " +
                            "physicalRole=${session.channel.physicalRole} " +
                            "requestRole=${session.requestRole} " +
                            "remote=${session.peer.deviceId}/${session.peer.runtimeSessionId?.value}"
                    )
                    session.startReader(
                        onMessage = { envelope ->
                            dispatchOnMain {
                                handleSignalingMessage(token, session, envelope)
                            }
                        },
                        onFailure = { failure ->
                            dispatchOnMain {
                                handleSignalingFailure(token, session, failure)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun handleSignalingMessage(
        token: SessionGeneration.Token,
        session: SignalingSessionV2,
        envelope: SignalingEnvelopeV2
    ) {
        if (
            !canDeliverDecodedControlEnvelope(
                sessionCurrent = isSessionCurrent(token),
                sessionClosed = session.isClosed,
                registeredSessionMatches =
                    signalingSessions[session.channel.channelId] === session
            )
        ) {
            closeControlChannel(session)
            return
        }
        val runtimeSessionId = session.pinnedIdentity.localSessionId
        val channelId = session.channel.channelId
        val event = when (val message = envelope.message) {
            is SignalingMessageV2.ConnectRequest -> {
                publishConfirmationAvailability(runtimeSessionId)
                SessionEvent.IncomingConnectRequest(
                    runtimeSessionId = runtimeSessionId,
                    channelId = channelId,
                    wireRequestKey = session.wireRequestKey,
                    trigger = message.trigger,
                    preferredTransportHint = message.preferredTransportHint,
                    occurredAtElapsedMs = SystemClock.elapsedRealtime()
                )
            }
            is SignalingMessageV2.ConnectAccept -> SessionEvent.RemoteConnectAccepted(
                runtimeSessionId,
                envelope.attemptId,
                channelId,
                session.wireRequestKey
            )
            is SignalingMessageV2.ConnectReject -> SessionEvent.RemoteConnectRejected(
                runtimeSessionId,
                envelope.attemptId,
                channelId,
                session.wireRequestKey,
                message.reason,
                message.retryable
            )
            is SignalingMessageV2.Busy -> SessionEvent.RemoteBusy(
                runtimeSessionId,
                envelope.attemptId,
                channelId,
                session.wireRequestKey,
                message.reason,
                message.retryAfterMs
            )
            is SignalingMessageV2.Disconnect -> SessionEvent.RemoteDisconnect(
                runtimeSessionId,
                envelope.attemptId,
                channelId,
                session.wireRequestKey,
                message.reason
            )
            is SignalingMessageV2.Offer,
            is SignalingMessageV2.Answer,
            is SignalingMessageV2.Candidate -> {
                val attempt = orchestrator.currentAttempt
                val candidate = attempt?.let(session::toConnectionCandidateContext)
                if (
                    candidate == null ||
                    !canUseMediaCandidate(
                        sessionCurrent = isSessionCurrent(token),
                        currentAttempt = attempt,
                        activeAttempt = orchestrator.activeControlAttempt,
                        candidate = candidate,
                        session = session
                    )
                ) {
                    closeControlChannel(session)
                    return
                }
                val manager = intercomManager?.takeIf { activeMediaContext == candidate }
                if (manager == null) {
                    pendingMediaMessages.getOrPut(candidate, ::mutableListOf) += message
                } else {
                    manager.handleRemoteSignaling(message)
                }
                return
            }
            is SignalingMessageV2.Hello -> SessionEvent.ProtocolViolation(
                runtimeSessionId,
                channelId,
                session.wireRequestKey,
                "HELLO is not allowed after channel identity is pinned"
            )
        }
        dispatchControlEvent(session, event)
    }

    private fun handleSignalingFailure(
        token: SessionGeneration.Token,
        session: SignalingSessionV2,
        failure: Throwable
    ) {
        if (
            !isSessionCurrent(token) ||
            signalingSessions[session.channel.channelId] !== session
        ) {
            closeControlChannel(session)
            return
        }
        closeControlChannel(session)
        val runtimeSessionId = session.pinnedIdentity.localSessionId
        val event = if (failure is SignalingV2Exception) {
            SessionEvent.ProtocolViolation(
                runtimeSessionId,
                session.channel.channelId,
                session.wireRequestKey,
                failure.message.orEmpty()
            )
        } else {
            SessionEvent.ChannelClosed(
                runtimeSessionId,
                session.channel.channelId,
                session.wireRequestKey,
                failure.message.orEmpty()
            )
        }
        orchestrator.dispatch(event)
    }

    private fun dispatchControlEvent(
        session: SignalingSessionV2,
        event: SessionEvent
    ) {
        orchestrator.dispatch(event) { accepted ->
            if (!accepted) {
                dispatchOnMain { closeControlChannel(session) }
            }
        }
    }

    private fun closeControlChannel(session: SignalingSessionV2) {
        controlChannelCloseDeadlineScheduler.cancel(
            session.pinnedIdentity.localSessionId,
            session.wireRequestKey.attemptId,
            session.channel.channelId
        )
        if (signalingSessions[session.channel.channelId] === session) {
            signalingSessions.remove(session.channel.channelId)
            removePendingMediaMessages(session)
            closeMediaIfMatches(session)
        }
        session.close()
    }

    private fun closeControlChannel(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId,
        targetLock: TargetLock
    ) {
        val session = signalingSessions[channelId]
            ?.takeIf {
                it.matchesControlHandle(
                    runtimeSessionId,
                    attemptId,
                    channelId,
                    targetLock
                )
            }
            ?: return
        closeControlChannel(session)
    }

    private fun removeSignalingSession(
        channelId: ControlChannelId,
        expected: SignalingSessionV2
    ) {
        if (signalingSessions[channelId] === expected) {
            signalingSessions.remove(channelId)
        }
    }

    private fun removePendingMediaMessages(session: SignalingSessionV2) {
        pendingMediaMessages.keys.removeAll(session::hasCandidateIdentity)
    }

    private fun closeMediaIfMatches(session: SignalingSessionV2) {
        val context = activeMediaContext
            ?.takeIf { activeMediaSession === session && session.hasCandidateIdentity(it) }
            ?: return
        closeActiveMediaContext()
        pendingMediaMessages.remove(context)
    }

    private fun closeActiveMediaContext() {
        val manager = intercomManager
        activeMediaContext = null
        activeMediaSession = null
        intercomManager = null
        mediaConnected = false
        runCatching { manager?.close() }.onFailure(::handleError)
    }

    private fun releaseActiveSessionAndContinueDiscovery(
        effect: SessionEffect.ReleaseActiveSessionAndContinueDiscovery
    ) {
        if (!running || activeRuntimeSessionId != effect.attempt.runtimeSessionId) return
        // Effects are FIFO: drain old exact resources before any later transport open.
        val finalizeDiscovery = canFinalizeActiveSessionReleaseEffect(
            effect,
            orchestrator.state.value,
            orchestrator.currentAttempt,
            orchestrator.activeControlAttempt,
            orchestrator.pendingInboundRequest
        )

        ActiveSessionResourceController(
            attempt = effect.attempt,
            cancelAttemptSchedules = { attempt ->
                attemptDeadlineScheduler.cancel(attempt)
                attemptMilestoneScheduler.cancel(attempt)
            },
            closeSignalingAndMedia = ::closeSignalingAndMediaForAttempt,
            releaseLanAttempt = { lanDiscovery?.retainPassiveIngress(it) },
            releaseWifiDirectAttempt = { wifiTunnel?.retainPassiveIngress(it) },
            clearConnectionState = {
                physicalLinkReady = false
                mediaConnected = false
                remoteRiderName = null
                listener?.onRemoteRiderIdentified("")
            },
            continueDiscovery = { runtimeSessionId ->
                if (running && activeRuntimeSessionId == runtimeSessionId) {
                    publishStatus(SEARCHING_STATUS)
                }
            },
            onError = ::handleError
        ).releaseAndContinueDiscovery(finalizeDiscovery)
    }

    private fun closeSignalingAndMediaForAttempt(attempt: ConnectionAttempt) {
        signalingSessions.values
            .filter {
                it.matchesControlHandle(
                    attempt.runtimeSessionId,
                    attempt.id,
                    it.channel.channelId,
                    attempt.targetLock
                )
            }
            .toList()
            .forEach(::closeControlChannel)

        if (activeMediaContext?.attempt?.hasSameImmutableIdentity(attempt) == true) {
            closeActiveMediaContext()
        }
        pendingMediaMessages.keys.removeAll {
            it.attempt.hasSameImmutableIdentity(attempt)
        }
    }

    private fun startWebRtc(effect: SessionEffect.StartWebRtc) {
        val token = activeSession ?: return
        val controlAttempt = orchestrator.activeControlAttempt
        val session = signalingSessions[effect.channelId]
        val candidate = session?.toConnectionCandidateContext(effect.attempt)
        if (
            session == null ||
            candidate == null ||
            !canUseMediaCandidate(
                sessionCurrent = isSessionCurrent(token),
                currentAttempt = orchestrator.currentAttempt,
                activeAttempt = controlAttempt,
                candidate = candidate,
                session = session,
                expectedRole = effect.role
            ) ||
            !canStartWebRtc(
                sessionCurrent = isSessionCurrent(token),
                currentAttempt = orchestrator.currentAttempt,
                expectedAttempt = effect.attempt,
                session = session,
                expectedRole = effect.role
            )
        ) {
            if (session != null && candidate != null) closeControlChannel(session)
            return
        }
        val audioController = audioSessionController
        if (audioController == null) {
            handleError(IllegalStateException("online audio session is unavailable"))
            closeControlChannel(session)
            return
        }
        if (
            intercomManager != null &&
            activeMediaContext == candidate &&
            activeMediaSession === session
        ) {
            return
        }
        if (
            intercomManager != null ||
            activeMediaContext != null ||
            activeMediaSession != null
        ) {
            closeActiveMediaContext()
        }

        activeMediaContext = candidate
        activeMediaSession = session
        attemptMilestoneScheduler.cancel(effect.attempt)
        lanDiscovery?.retainPassiveIngress(effect.attempt)
        if (candidate.transport == Transport.LAN) {
            runCatching {
                wifiTunnel?.retainPassiveIngress(effect.attempt)
            }.onFailure(::handleError)
        } else {
            runCatching {
                wifiTunnel?.retainSelectedChannel(effect.attempt)
            }.onFailure(::handleError)
        }

        physicalLinkReady = true
        mediaConnected = false
        remoteRiderName = effect.peer.nickname
        publishStatus(SIGNALING_CONNECTED_STATUS)

        intercomManager = IntercomManager(
            audioSessionController = audioController,
            signalingSession = session,
            webRtcRole = effect.role,
            onIntercomDisconnected = {
                onIntercomDisconnected(token, candidate, it)
            },
            onConnectionStateChanged = {
                onConnectionStateChanged(token, candidate, it)
            },
            onAudioLevelChanged = { onAudioLevelChanged(token, candidate, it) },
            onError = { error ->
                postForMediaContext(token, candidate) { handleError(error) }
            },
            isSessionCurrent = { isCurrentMediaContext(token, candidate) }
        ).also {
            publishLog(
                "Starting WebRTC after CONNECT_ACCEPT: role=${effect.role} " +
                    "remote=${effect.peer.deviceId}/${effect.peer.runtimeSessionId?.value}"
            )
            listener?.onRemoteRiderIdentified(effect.peer.nickname)
            publishStatus(MEDIA_INITIALIZING_STATUS)
            it.start()
        }
        pendingMediaMessages.remove(candidate)
            .orEmpty()
            .forEach { intercomManager?.handleRemoteSignaling(it) }

        updateStageStatus()
    }

    private fun onConnectionStateChanged(
        token: SessionGeneration.Token,
        candidate: ConnectionCandidateContext,
        state: PeerConnection.PeerConnectionState
    ) {
        postForMediaContext(token, candidate) {
            publishLog("WebRTC 状态：$state")
            orchestrator.dispatch(
                SessionEvent.WebRtcStateChanged(
                    runtimeSessionId = candidate.runtimeSessionId,
                    attemptId = candidate.attemptId,
                    state = state.toProductState(),
                    occurredAt = System.currentTimeMillis()
                )
            ) { accepted ->
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    postForMediaContext(token, candidate) {
                        val connected = orchestrator.state.value as? IntercomState.Connected
                        if (accepted && connected?.attempt == candidate.attempt) {
                            signalingSessions[candidate.channelId]
                                ?.takeIf { it.matchesCandidate(candidate) }
                                ?.let {
                                    runCatching(it::markMediaConnected).onFailure(::handleError)
                                }
                            attemptDeadlineScheduler.cancel(candidate.attempt)
                            mediaConnected = true
                            publishStatus(VOICE_CONNECTED_STATUS)
                        }
                    }
                }
            }
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> Unit
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    mediaConnected = false
                    publishStatus(SIGNAL_LOST_STATUS)
                }
                else -> updateStageStatus()
            }
        }
    }

    private fun onIntercomDisconnected(
        token: SessionGeneration.Token,
        candidate: ConnectionCandidateContext,
        error: IOException
    ) {
        postForMediaContext(token, candidate) {
            publishLog("信令通道断开：${error.message}")
            orchestrator.dispatch(
                SessionEvent.SignalingDisconnected(
                    runtimeSessionId = candidate.runtimeSessionId,
                    attemptId = candidate.attemptId
                )
            )
        }
    }

    private fun abortResourcesAndResumeDiscovery(
        runtimeSessionId: RuntimeSessionId,
        nextAttempt: ConnectionAttempt?,
        restartDelayMillis: Long = restartDiscoveryDelayMillis(nextAttempt),
        resetEffect: SessionEffect.ResetWirelessEnvironment? = null,
        cleanupStatus: String = SIGNAL_LOST_STATUS,
        discoveryRefreshGeneration: Long? = null
    ): Boolean {
        if (!running || activeRuntimeSessionId != runtimeSessionId) return false
        if (
            resetEffect != null &&
            !canExecuteResetWirelessEnvironmentEffect(
                resetEffect,
                orchestrator.state.value,
                orchestrator.currentAttempt,
                orchestrator.activeControlAttempt,
                orchestrator.pendingInboundRequest
            )
        ) {
            return false
        }
        val request = RecoveryCleanupRequest(
            runtimeSessionId = runtimeSessionId,
            nextAttempt = nextAttempt,
            restartDelayMillis = restartDelayMillis,
            resetEffect = resetEffect,
            discoveryRefreshGeneration = discoveryRefreshGeneration
        )
        if (recoveryCleanupCoordinator.updateIfActive(request)) return true
        val token = activeSession ?: return false
        if (!isSessionCurrent(token)) return false
        if (localDeviceId.isBlank()) return false
        val cleanupToken = recoveryCleanupCoordinator.start(request)
        cancelAllIncomingConfirmationSurfaces()
        attemptDeadlineScheduler.cancelRuntime(runtimeSessionId)
        attemptMilestoneScheduler.cancelRuntime(runtimeSessionId)
        controlChannelCloseDeadlineScheduler.cancelRuntime(runtimeSessionId)
        markDiscoveryUnavailable()
        sessions.invalidate()
        activeSession = null
        val managerToClose = intercomManager
        val lanToClose = lanDiscovery
        val wifiToClose = wifiTunnel
        val signalingToClose = drainSignalingSessions()
        intercomManager = null
        lanDiscovery = null
        wifiTunnel = null

        AttemptResourceController(
            runtimeSessionId = runtimeSessionId,
            closeIntercomAndSocket = {
                managerToClose?.close()
                signalingToClose.forEach(SignalingSessionV2::close)
            },
            closeLanDiscovery = { lanToClose?.close() },
            closeWifiDirect = { onClosed ->
                wifiTunnelCloseOwner.closeAll(
                    additionalResources = listOfNotNull(wifiToClose),
                    onError = ::handleError,
                    onComplete = onClosed
                )
            },
            clearMediaLocator = {
                activeMediaContext = null
                activeMediaSession = null
            },
            clearConnectionState = {
                physicalLinkReady = false
                mediaConnected = false
                remoteRiderName = null
                publishStatus(cleanupStatus)
            },
            resumeDiscovery = { resumedRuntimeSessionId ->
                if (resumedRuntimeSessionId == runtimeSessionId) {
                    recoveryCleanupCoordinator.complete(cleanupToken)
                }
            },
            onError = ::handleError
        ).abortAndResumeDiscovery()
        return true
    }

    private fun restartAfterRecoveryCleanup(request: RecoveryCleanupRequest): Boolean {
        fun completeManualRefresh() {
            request.discoveryRefreshGeneration?.let(discoveryRefreshGate::complete)
        }
        if (
            !running ||
            activeSession != null ||
            activeRuntimeSessionId != request.runtimeSessionId
        ) {
            completeManualRefresh()
            return true
        }
        val resetEffect = request.resetEffect
        if (
            resetEffect != null &&
            !canExecuteResetWirelessEnvironmentEffect(
                resetEffect,
                orchestrator.state.value,
                orchestrator.currentAttempt,
                orchestrator.activeControlAttempt,
                orchestrator.pendingInboundRequest
            )
        ) {
            completeManualRefresh()
            return false
        }
        if (
            resetEffect == null &&
            request.nextAttempt == null &&
            !canResumePassiveDiscovery(
                request.runtimeSessionId,
                orchestrator.state.value,
                orchestrator.currentAttempt,
                orchestrator.activeControlAttempt,
                orchestrator.pendingInboundRequest
            )
        ) {
            completeManualRefresh()
            return false
        }
        if (
            !canRestartRecoveryAttempt(
                expectedAttempt = request.nextAttempt,
                currentAttempt = orchestrator.currentAttempt,
                now = MonotonicTimestamp(SystemClock.elapsedRealtime())
            )
        ) {
            publishLog("忽略已过期或已替换的恢复尝试")
            completeManualRefresh()
            return false
        }
        val deviceId = localDeviceId.takeIf(String::isNotBlank) ?: run {
            completeManualRefresh()
            return false
        }
        val recoveryToken = sessions.start()
        activeSession = recoveryToken
        publishLog("重新启动车友发现")
        if (
            !canRestartRecoveryAttempt(
                expectedAttempt = request.nextAttempt,
                currentAttempt = orchestrator.currentAttempt,
                now = MonotonicTimestamp(SystemClock.elapsedRealtime())
            )
        ) {
            activeSession = null
            sessions.invalidate()
            completeManualRefresh()
            return false
        }
        startDiscoveryTransports(
            recoveryToken,
            deviceId,
            request.runtimeSessionId,
            request.nextAttempt
        )
        resetEffect?.let {
            orchestrator.dispatch(
                SessionEvent.ResetCompleted(it.runtimeSessionId, it.failedAttemptId)
            )
        }
        completeManualRefresh()
        return true
    }

    private fun onAudioLevelChanged(
        token: SessionGeneration.Token,
        candidate: ConnectionCandidateContext,
        level: Float
    ) {
        postForMediaContext(token, candidate) { listener?.onAudioLevelChanged(level) }
    }

    private fun stopIntercom() {
        val ownershipToRelease = legacyOwnership
        legacyOwnership = null
        val runtimeSessionId = activeRuntimeSessionId
        val keepAliveToRelease = runtimeKeepAlive
        runtimeKeepAlive = null
        if (runtimeSessionId != null) {
            orchestrator.dispatch(SessionEvent.StopRequested(runtimeSessionId))
        }
        recoveryCleanupCoordinator.cancel()
        discoveryRefreshGate.cancel()
        attemptDeadlineScheduler.cancel()
        attemptMilestoneScheduler.cancel()
        controlChannelCloseDeadlineScheduler.cancel()
        cancelAllIncomingConfirmationSurfaces()
        sessions.invalidate()
        activeSession = null
        activeRuntimeSessionId = null
        localDeviceId = ""
        running = false
        autoConnectTargetKey = null
        publishPresenceSnapshot(presenceAggregator.clear())
        drainSignalingSessions().forEach(SignalingSessionV2::close)
        lanDiscovery?.close()
        lanDiscovery = null

        try {
            intercomManager?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        val wifiToClose = wifiTunnel
        wifiTunnel = null
        wifiTunnelCloseOwner.closeAll(
            additionalResources = listOfNotNull(wifiToClose),
            onError = ::handleError
        ) {
            try { keepAliveToRelease?.close() } finally { ownershipToRelease?.let(LegacyRuntimeOwnership::release) }
        }
        try {
            audioSessionController?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        intercomManager = null
        audioSessionController = null
        resetSessionAudioControls()
        publishAudioControls()
        bluetoothReady = false
        physicalLinkReady = false
        mediaConnected = false
        audioInterruptionState = AudioInterruptionState.NORMAL
        remoteRiderName = null
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(ENDED_STATUS)
        if (runtimeSessionId != null) {
            orchestrator.dispatch(SessionEvent.RuntimeStopped(runtimeSessionId))
        }
        stopForegroundCompat()
    }

    private fun isSessionCurrent(token: SessionGeneration.Token): Boolean =
        running && sessions.isCurrent(token) && activeSession == token

    private fun isCurrentMediaContext(
        token: SessionGeneration.Token,
        candidate: ConnectionCandidateContext
    ): Boolean {
        val session = signalingSessions[candidate.channelId] ?: return false
        return activeMediaContext == candidate &&
            activeMediaSession === session &&
            canUseMediaCandidate(
                sessionCurrent = isSessionCurrent(token),
                currentAttempt = orchestrator.currentAttempt,
                activeAttempt = orchestrator.activeControlAttempt,
                candidate = candidate,
                session = session
            )
    }

    private fun dispatchOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun postForRuntime(runtimeSessionId: RuntimeSessionId, action: () -> Unit) {
        dispatchOnMain {
            if (
                canDeliverRuntimeAudioCallback(
                    running = running,
                    activeRuntimeSessionId = activeRuntimeSessionId,
                    callbackRuntimeSessionId = runtimeSessionId
                )
            ) {
                action()
            }
        }
    }

    private fun postForSession(token: SessionGeneration.Token, action: () -> Unit) {
        dispatchOnMain {
            if (isSessionCurrent(token)) action()
        }
    }

    private fun postForMediaContext(
        token: SessionGeneration.Token,
        candidate: ConnectionCandidateContext,
        action: () -> Unit
    ) {
        dispatchOnMain {
            if (isCurrentMediaContext(token, candidate)) action()
        }
    }

    private fun handleSessionEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.RefreshDiscovery -> {
                if (!discoveryRefreshGate.accepts(effect.generation)) return
                if (
                    canExecuteRefreshDiscoveryEffect(
                        effect,
                        orchestrator.state.value,
                        orchestrator.currentAttempt,
                        orchestrator.activeControlAttempt,
                        orchestrator.pendingInboundRequest
                    )
                ) {
                    val started = abortResourcesAndResumeDiscovery(
                        runtimeSessionId = effect.runtimeSessionId,
                        nextAttempt = null,
                        restartDelayMillis = 0L,
                        cleanupStatus = RESCANNING_STATUS,
                        discoveryRefreshGeneration = effect.generation
                    )
                    if (started) {
                        publishLog("手动重新扫描附近车友")
                    } else {
                        discoveryRefreshGate.complete(effect.generation)
                        publishLog("重新扫描请求未启动新的发现轮次")
                    }
                } else {
                    discoveryRefreshGate.complete(effect.generation)
                }
            }
            is SessionEffect.RetireTargetedTransport -> {
                if (orchestrator.currentAttempt == effect.attempt) {
                    retireTargetedTransport(effect.attempt, effect.transport)
                }
            }
            is SessionEffect.OpenTargetedTransport -> {
                if (
                    orchestrator.currentAttempt == effect.attempt &&
                    effect.transport in effect.attempt.channelPlan
                ) {
                    beginTargetedTransport(effect.attempt, effect.transport)
                }
            }
            is SessionEffect.ScheduleAttemptMilestone -> {
                if (orchestrator.currentAttempt == effect.milestone.attempt) {
                    attemptMilestoneScheduler.schedule(effect.milestone)
                }
            }
            is SessionEffect.AbortAttemptAndResumeDiscovery -> {
                if (
                    canExecuteAbortAttemptEffect(
                        effect = effect,
                        currentState = orchestrator.state.value,
                        currentAttempt = orchestrator.currentAttempt,
                        activeAttempt = orchestrator.activeControlAttempt,
                        pendingInbound = orchestrator.pendingInboundRequest,
                        terminalOutcome = orchestrator.terminalOutcome(effect.attemptId)
                    )
                ) {
                    publishLog("连接尝试已中止：${effect.attemptId.value}")
                    abortResourcesAndResumeDiscovery(effect.runtimeSessionId, nextAttempt = null)
                }
            }
            is SessionEffect.ReleaseActiveSessionAndContinueDiscovery ->
                releaseActiveSessionAndContinueDiscovery(effect)
            is SessionEffect.RestartDiscovery -> {
                if (
                    canExecuteRestartDiscoveryEffect(
                        effect,
                        orchestrator.state.value,
                        orchestrator.currentAttempt
                    )
                ) {
                    if (
                        !shouldReuseRecoveryDiscovery(effect) ||
                        !retryRecoveryWithExistingDiscovery(effect)
                    ) {
                        abortResourcesAndResumeDiscovery(
                            runtimeSessionId = effect.runtimeSessionId,
                            nextAttempt = effect.attempt,
                            restartDelayMillis = effect.restartDelayMillis
                        )
                    }
                }
            }
            is SessionEffect.ResetWirelessEnvironment -> {
                if (
                    canExecuteResetWirelessEnvironmentEffect(
                        effect,
                        orchestrator.state.value,
                        orchestrator.currentAttempt,
                        orchestrator.activeControlAttempt,
                        orchestrator.pendingInboundRequest
                    )
                ) {
                    publishLog(
                        "Resetting wireless environment after " +
                            "${effect.consecutiveFinalFailures} final recovery failures"
                    )
                    abortResourcesAndResumeDiscovery(
                        runtimeSessionId = effect.runtimeSessionId,
                        nextAttempt = null,
                        restartDelayMillis = 0L,
                        resetEffect = effect
                    )
                }
            }
            is SessionEffect.ScheduleAttemptDeadline -> {
                if (orchestrator.currentAttempt == effect.attempt) {
                    attemptDeadlineScheduler.schedule(effect.attempt)
                }
            }
            is SessionEffect.SendConnectRequest -> sendControlMessage(
                effect.runtimeSessionId,
                effect.attemptId,
                effect.channelId,
                SignalingMessageV2.ConnectRequest(
                    effect.trigger,
                    effect.preferredTransportHint
                )
            )
            is SessionEffect.SendConnectAccept -> sendControlMessage(
                effect.runtimeSessionId,
                effect.attemptId,
                effect.channelId,
                SignalingMessageV2.ConnectAccept(
                    nickname = requestedRiderName.ifBlank { "Rider" },
                    deviceName = Build.MODEL.orEmpty()
                )
            )
            is SessionEffect.SendConnectReject -> {
                if (effect.reason == RejectReason.SUPERSEDED_CHANNEL) {
                    controlChannelCloseDeadlineScheduler.schedule(
                        ControlChannelCloseDeadline(
                            effect.runtimeSessionId,
                            effect.attemptId,
                            effect.channelId,
                            Math.addExact(
                                SystemClock.elapsedRealtime(),
                                LOSER_CHANNEL_CLOSE_TIMEOUT_MS
                            )
                        )
                    )
                }
                sendControlMessage(
                    effect.runtimeSessionId,
                    effect.attemptId,
                    effect.channelId,
                    SignalingMessageV2.ConnectReject(effect.reason, effect.retryable)
                )
            }
            is SessionEffect.SendBusy -> sendControlMessage(
                effect.runtimeSessionId,
                effect.attemptId,
                effect.channelId,
                SignalingMessageV2.Busy(effect.reason, effect.retryAfterMs)
            )
            is SessionEffect.SendDisconnect -> sendControlMessage(
                effect.runtimeSessionId,
                effect.attemptId,
                effect.channelId,
                SignalingMessageV2.Disconnect(effect.reason)
            )
            is SessionEffect.SelectMediaChannel -> {
                val currentAttempt = orchestrator.currentAttempt
                val activeAttempt = orchestrator.activeControlAttempt
                val candidates = effect.cohort.channelIds.mapNotNull { channelId ->
                    signalingSessions[channelId]
                        ?.takeUnless(SignalingSessionV2::isClosed)
                        ?.let { session ->
                            currentAttempt
                                ?.let(session::toConnectionCandidateContext)
                                ?.takeIf { candidate ->
                                    session.matchesCandidate(candidate) &&
                                        isCurrentSelectionCandidate(
                                            currentAttempt,
                                            activeAttempt,
                                            candidate,
                                            effect.wireRequestKey
                                        )
                                }
                                ?.let { MediaChannelCandidate(channelId, it.transport) }
                        }
                }
                orchestrator.dispatch(
                    SessionEvent.MediaChannelSelected(
                        effect.runtimeSessionId,
                        effect.attemptId,
                        effect.wireRequestKey,
                        selectMediaChannel(candidates, effect.preferredTransport)
                    )
                )
            }
            is SessionEffect.StartWebRtc -> startWebRtc(effect)
            is SessionEffect.CloseControlChannel -> closeControlChannel(
                effect.runtimeSessionId,
                effect.attemptId,
                effect.channelId,
                effect.targetLock
            )
            is SessionEffect.PublishIncomingConfirmation ->
                publishIncomingConfirmation(effect.prompt)
            is SessionEffect.CancelIncomingConfirmation -> cancelIncomingConfirmation(effect)
        }
    }

    private fun publishConfirmationAvailability(
        runtimeSessionId: RuntimeSessionId? = activeRuntimeSessionId
    ) {
        val runtime = runtimeSessionId ?: return
        orchestrator.dispatch(
            SessionEvent.ConfirmationAvailabilityChanged(
                runtime,
                ConfirmationAvailability(
                    appForeground = appInForeground,
                    notificationAvailable = isIncomingNotificationAvailable()
                )
            )
        )
    }

    private fun publishIncomingConfirmation(prompt: IncomingConfirmationPrompt) {
        activeIncomingPrompt = prompt
        incomingConfirmationScheduler.schedule(prompt)
        when (prompt.surface) {
            ConfirmationSurface.IN_APP -> {
                val currentListener = listener
                if (!appInForeground || currentListener == null) {
                    reportConfirmationSurfaceUnavailable(prompt)
                    return
                }
                try {
                    currentListener.onIncomingConfirmation(prompt)
                } catch (t: Throwable) {
                    handleError(t)
                    reportConfirmationSurfaceUnavailable(prompt)
                }
            }
            ConfirmationSurface.NOTIFICATION -> {
                if (!isIncomingNotificationAvailable()) {
                    reportConfirmationSurfaceUnavailable(prompt)
                    return
                }
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                        ?: throw IllegalStateException("NotificationManager is unavailable")
                    manager.notify(
                        INCOMING_NOTIFICATION_ID,
                        buildIncomingConfirmationNotification(prompt)
                    )
                } catch (t: Throwable) {
                    handleError(t)
                    reportConfirmationSurfaceUnavailable(prompt)
                }
            }
        }
    }

    private fun cancelIncomingConfirmation(effect: SessionEffect.CancelIncomingConfirmation) {
        incomingConfirmationScheduler.cancel(
            effect.runtimeSessionId,
            effect.attemptId,
            effect.actionNonce
        )
        activeIncomingPrompt
            ?.takeIf { it.runtimeSessionId == effect.runtimeSessionId }
            ?.takeIf { it.attemptId == effect.attemptId }
            ?.takeIf { it.actionNonce == effect.actionNonce }
            ?: return
        getSystemService(NotificationManager::class.java)?.cancel(INCOMING_NOTIFICATION_ID)
        activeIncomingPrompt = null
        listener?.onIncomingConfirmationCanceled(effect.actionNonce)
    }

    private fun cancelAllIncomingConfirmationSurfaces() {
        val nonce = activeIncomingPrompt?.actionNonce
        incomingConfirmationScheduler.cancel()
        activeIncomingPrompt = null
        getSystemService(NotificationManager::class.java)?.cancel(INCOMING_NOTIFICATION_ID)
        if (nonce != null) listener?.onIncomingConfirmationCanceled(nonce)
    }

    private fun reportConfirmationSurfaceUnavailable(prompt: IncomingConfirmationPrompt) {
        orchestrator.dispatch(
            SessionEvent.ConfirmationSurfaceUnavailable(
                prompt.runtimeSessionId,
                prompt.attemptId,
                prompt.channelId,
                prompt.actionNonce
            )
        )
    }

    private fun dispatchIncomingConfirmationAction(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId,
        actionNonce: String,
        accepted: Boolean
    ) {
        val occurredAtElapsedMs = SystemClock.elapsedRealtime()
        val event = if (accepted) {
            SessionEvent.IncomingAccepted(
                runtimeSessionId,
                attemptId,
                channelId,
                actionNonce,
                occurredAtElapsedMs
            )
        } else {
            SessionEvent.IncomingRejected(
                runtimeSessionId,
                attemptId,
                channelId,
                actionNonce,
                occurredAtElapsedMs
            )
        }
        orchestrator.dispatch(event)
    }

    private fun handleIncomingConfirmationAction(intent: Intent, accepted: Boolean) {
        val action = runCatching {
            val runtimeValue = requireNotNull(intent.getStringExtra(EXTRA_RUNTIME_SESSION_ID))
            val attemptValue = requireNotNull(intent.getStringExtra(EXTRA_ATTEMPT_ID))
            val channelValue = requireNotNull(intent.getStringExtra(EXTRA_CHANNEL_ID))
            val nonce = requireNotNull(intent.getStringExtra(EXTRA_ACTION_NONCE))
            requireCanonicalUuid(runtimeValue, "runtimeSessionId")
            requireCanonicalUuid(attemptValue, "attemptId")
            require(nonce.isNotBlank()) { "action nonce is missing" }
            require(intent.data?.pathSegments?.firstOrNull() == nonce) {
                "action nonce does not match Intent data"
            }
            require(
                intent.data?.pathSegments?.getOrNull(1) ==
                    if (accepted) ACTION_PATH_ACCEPT else ACTION_PATH_REJECT
            ) { "action type does not match Intent data" }
            IncomingActionIdentity(
                RuntimeSessionId(runtimeValue),
                ConnectionAttemptId(attemptValue),
                ControlChannelId.parse(channelValue),
                nonce
            )
        }.getOrElse {
            publishLog("Ignored invalid incoming confirmation action: ${it.message}")
            return
        }
        dispatchIncomingConfirmationAction(
            action.runtimeSessionId,
            action.attemptId,
            action.channelId,
            action.actionNonce,
            accepted
        )
    }

    private fun sendControlMessage(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId,
        message: SignalingMessageV2
    ) {
        val session = signalingSessions[channelId]
        if (
            session == null ||
            session.isClosed ||
            !session.matchesControlHandle(runtimeSessionId, attemptId, channelId)
        ) {
            orchestrator.dispatch(
                SessionEvent.SignalingSendFailed(
                    runtimeSessionId,
                    attemptId,
                    channelId,
                    message.type,
                    "control channel is unavailable"
                )
            )
            return
        }
        session.send(message) { result ->
            val completionEvent = controlSendCompletionEvent(
                runtimeSessionId,
                attemptId,
                channelId,
                message.type,
                result
            )
            dispatchOnMain {
                if (
                    signalingSessions[channelId] !== session ||
                    session.isClosed ||
                    !session.matchesControlHandle(runtimeSessionId, attemptId, channelId)
                ) {
                    closeControlChannel(session)
                }
                if (result.isFailure) {
                    closeControlChannel(session)
                }
                orchestrator.dispatch(completionEvent)
            }
        }
    }

    private fun openTargetedTransport(
        attempt: ConnectionAttempt,
        transport: Transport
    ): Boolean {
        if (activeRuntimeSessionId != attempt.runtimeSessionId) return false
        bindPlannedAdapterIngress(
            attempt,
            bindLan = { lanDiscovery?.restrictIngress(it) },
            bindWifiDirect = { wifiTunnel?.restrictIngress(it) }
        )
        return openPlannedTransport(
            attempt,
            transport,
            openLan = { lanDiscovery?.connect(it) == true },
            openWifiDirect = { wifiTunnel?.connect(it) == true }
        )
    }

    private fun retireTargetedTransport(attempt: ConnectionAttempt, transport: Transport) {
        retirePlannedTransport(
            attempt,
            transport,
            retireLan = { lanDiscovery?.retainPassiveIngress(it) },
            retireWifiDirect = { wifiTunnel?.retainPassiveIngress(it) }
        )
    }

    private fun beginTargetedTransport(attempt: ConnectionAttempt, transport: Transport) {
        val result = runCatching { openTargetedTransport(attempt, transport) }
        if (result.getOrDefault(false)) {
            publishStatus(PEER_FOUND_STATUS)
            return
        }
        val reason = result.exceptionOrNull()?.message ?: "transport adapter unavailable"
        publishLog("Targeted transport open failed for ${attempt.id.value}/$transport: $reason")
        orchestrator.dispatch(
            SessionEvent.TargetedTransportOpenFailed(
                runtimeSessionId = attempt.runtimeSessionId,
                attemptId = attempt.id,
                transport = transport,
                reason = reason
            )
        )
    }

    private fun retryRecoveryWithExistingDiscovery(
        effect: SessionEffect.RestartDiscovery
    ): Boolean {
        val request = RecoveryCleanupRequest(
            runtimeSessionId = effect.runtimeSessionId,
            nextAttempt = effect.attempt,
            restartDelayMillis = effect.restartDelayMillis
        )
        if (recoveryCleanupCoordinator.updateIfActive(request)) return true
        if (
            !running ||
            activeRuntimeSessionId != effect.runtimeSessionId ||
            orchestrator.currentAttempt != effect.attempt ||
            effect.attempt.isExpiredAt(MonotonicTimestamp(SystemClock.elapsedRealtime()))
        ) {
            return true
        }
        val token = activeSession ?: return false
        val planned = plannedDiscoveryTransports(effect.attempt)
        if (
            (Transport.WIFI_DIRECT in planned && wifiTunnel == null) ||
            (Transport.LAN in planned && lanDiscovery == null)
        ) {
            return false
        }

        if (
            !prepareRecoveryDiscoveryAdapters(planned) { transport ->
                when (transport) {
                    Transport.WIFI_DIRECT -> wifiTunnel?.prepareRetry(effect.attempt) == true
                    Transport.LAN -> lanDiscovery?.prepareRetry(effect.attempt) == true
                }
            }
        ) return false

        attemptMilestoneScheduler.cancelRuntime(effect.runtimeSessionId)
        controlChannelCloseDeadlineScheduler.cancelRuntime(effect.runtimeSessionId)
        drainSignalingSessions().forEach(SignalingSessionV2::close)
        runCatching { intercomManager?.close() }.onFailure(::handleError)
        intercomManager = null
        physicalLinkReady = false
        mediaConnected = false
        publishStatus(SIGNAL_LOST_STATUS)

        val delayMillis = effect.attempt.boundedTimeoutMillis(
            MonotonicClock { MonotonicTimestamp(SystemClock.elapsedRealtime()) },
            effect.restartDelayMillis
        )
        if (delayMillis <= 0L) return true
        mainHandler.postDelayed({
            if (
                !isSessionCurrent(token) ||
                !canRestartRecoveryAttempt(
                    expectedAttempt = effect.attempt,
                    currentAttempt = orchestrator.currentAttempt,
                    now = MonotonicTimestamp(SystemClock.elapsedRealtime())
                )
            ) {
                return@postDelayed
            }
            planned.forEach { transport ->
                orchestrator.dispatch(
                    SessionEvent.RecoveryTransportReady(effect.attempt, transport)
                )
            }
        }, delayMillis)
        return true
    }

    private fun PeerConnection.PeerConnectionState.toProductState(): WebRtcConnectionState =
        when (this) {
            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
            else -> WebRtcConnectionState.OTHER
        }

    private fun drainSignalingSessions(): List<SignalingSessionV2> =
        signalingSessions.values.toList().also {
            signalingSessions.clear()
            pendingMediaMessages.clear()
            activeMediaContext = null
            activeMediaSession = null
        }

    private fun updateStageStatus() {
        if (audioInterruptionState != AudioInterruptionState.NORMAL) {
            publishStatus(
                when (audioInterruptionState) {
                    AudioInterruptionState.PHONE_RINGING,
                    AudioInterruptionState.PHONE_ACTIVE -> AUDIO_PAUSED_STATUS
                    AudioInterruptionState.RESUMING -> AUDIO_RESUMING_STATUS
                    AudioInterruptionState.FOCUS_LOST -> AUDIO_FOCUS_LOST_STATUS
                    AudioInterruptionState.ROUTE_UNAVAILABLE -> AUDIO_ROUTE_UNAVAILABLE_STATUS
                    AudioInterruptionState.NORMAL -> return
                }
            )
            return
        }
        when {
            mediaConnected -> publishStatus(VOICE_CONNECTED_STATUS)
            physicalLinkReady -> publishStatus(MEDIA_INITIALIZING_STATUS)
            bluetoothReady -> publishStatus(SEARCHING_STATUS)
            running -> publishStatus(SEARCHING_STATUS)
        }
    }

    private fun publishPresenceSnapshot(snapshot: PresenceSnapshot) {
        listener?.onPresencesChanged(snapshot.presences)
        maybeAutoConnectPreferred(snapshot)
        val generation = ++presenceExpiryGeneration
        val expiry = snapshot.nextExpiryElapsedRealtimeMs ?: return
        val delayMs = (expiry - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        mainHandler.postDelayed({
            if (generation != presenceExpiryGeneration) return@postDelayed
            publishPresenceSnapshot(presenceAggregator.expire())
        }, delayMs)
    }

    private fun maybeAutoConnectPreferred(snapshot: PresenceSnapshot) {
        if (!running) return
        val runtimeSessionId = activeRuntimeSessionId ?: return
        val target = preferredAutoConnectTarget(
            state = orchestrator.state.value,
            presences = snapshot.presences
        ) ?: run {
            if (orchestrator.state.value is IntercomState.Discovering) {
                autoConnectTargetKey = null
            }
            return
        }
        if (target.key == autoConnectTargetKey) return

        autoConnectTargetKey = target.key
        val queued = orchestrator.dispatch(
            SessionEvent.ConnectPresenceRequested(
                runtimeSessionId = runtimeSessionId,
                targetDeviceId = target.deviceId,
                targetSessionId = target.sessionId,
                availableTransports = target.availableTransports,
                trigger = ConnectionTrigger.AUTO_PAIRED
            )
        ) { accepted ->
            if (!accepted) {
                dispatchOnMain {
                    if (autoConnectTargetKey == target.key) autoConnectTargetKey = null
                }
            }
        }
        if (!queued && autoConnectTargetKey == target.key) {
            autoConnectTargetKey = null
        }
    }

    private fun markDiscoveryUnavailable() {
        presenceAggregator.replaceCandidates(Transport.LAN, emptyList())
        publishPresenceSnapshot(
            presenceAggregator.replaceCandidates(Transport.WIFI_DIRECT, emptyList())
        )
    }

    private fun publishStatus(status: String) {
        dispatchOnMain {
            lastStatus = status
            listener?.onStatusChanged(status, running)
            updateNotification()
        }
    }

    private fun publishAudioSource(status: String, bluetooth: Boolean) {
        dispatchOnMain {
            audioSourceStatus = status
            audioSourceBluetooth = bluetooth
            listener?.onAudioSourceChanged(status, bluetooth)
        }
    }

    private fun publishLog(message: String) {
        dispatchOnMain { listener?.onLog(message) }
    }

    private fun publishToast(message: String) {
        dispatchOnMain { listener?.onToast(message) }
    }

    private fun handleError(t: Throwable) {
        val message = t.message ?: t.javaClass.simpleName
        dispatchOnMain { listener?.onError(message) }
    }

    private fun handleAudioRouteError(t: Throwable) {
        // Route loss is recoverable while media remains active; the coordinator
        // keeps waiting for a verified route. Only an actual runtime failure is
        // surfaced as an error, avoiding a false failure prompt during handoff.
        publishLog("通信设备路由异常：${t.message ?: t.javaClass.simpleName}")
    }

    private fun hasRequiredRuntimePermissions(): Boolean {
        return PermissionPolicy.canStart(Build.VERSION.SDK_INT) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isIncomingNotificationAvailable(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(INCOMING_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    private fun updateNotification() {
        val notification = buildNotification()
        if (running) {
            runCatching {
                // MIUI can keep the original foreground-service record when the
                // same notification is updated only through NotificationManager.
                startForeground(NOTIFICATION_ID, notification)
            }.onFailure {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, notification)
            }
            return
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val notificationText = if (groupOwnsResources()) groupState.message else foregroundNotificationText(
            orchestrator.state.value,
            lastStatus,
            audioInterruptionState
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, if (groupOwnsResources()) GroupActivity::class.java else MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("摩声")
            .setContentText(notificationText)
            .setStyle(Notification.BigTextStyle().bigText(notificationText))
            .setOngoing(running || groupOwnsResources())
            .setContentIntent(contentIntent)
            .build()
    }

    private fun buildIncomingConfirmationNotification(
        prompt: IncomingConfirmationPrompt
    ): Notification {
        ensureIncomingNotificationChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val acceptIntent = incomingConfirmationPendingIntent(prompt, accepted = true)
        val rejectIntent = incomingConfirmationPendingIntent(prompt, accepted = false)
        val riderName = prompt.peer.nickname.ifBlank { "附近车友" }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, INCOMING_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$riderName 请求加入对讲")
            .setContentText("请在 15 秒内接受或拒绝")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    incomingConfirmationNotificationMessage(prompt.peer.deviceName)
                )
            )
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .addAction(0, "拒绝本次", rejectIntent)
            .addAction(0, "接受", acceptIntent)
            .build()
    }

    private fun incomingConfirmationPendingIntent(
        prompt: IncomingConfirmationPrompt,
        accepted: Boolean
    ): PendingIntent {
        return PendingIntent.getService(
            this,
            0,
            incomingConfirmationActionIntent(this, prompt, accepted),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "对讲状态提示",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun ensureIncomingNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(INCOMING_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                INCOMING_CHANNEL_ID,
                "车友连接请求",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "陌生车友的接受与拒绝操作"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        internal const val ACTION_START_GROUP = "com.kuma.motointercom.action.START_GROUP"
        internal const val EXTRA_GROUP_CODE = "com.kuma.motointercom.extra.GROUP_CODE"
        private const val LOSER_CHANNEL_CLOSE_TIMEOUT_MS = 1_000L
        const val ACTION_START_INTERCOM = "com.kuma.motointercom.action.START_INTERCOM"
        const val ACTION_STOP_INTERCOM = "com.kuma.motointercom.action.STOP_INTERCOM"
        const val ACTION_ACCEPT_INCOMING = "com.kuma.motointercom.action.ACCEPT_INCOMING"
        const val ACTION_REJECT_INCOMING = "com.kuma.motointercom.action.REJECT_INCOMING"
        const val EXTRA_RIDER_NAME = "com.kuma.motointercom.extra.RIDER_NAME"
        private const val EXTRA_VOX_ENABLED = "com.kuma.motointercom.extra.VOX_ENABLED"
        private const val EXTRA_VOX_SENSITIVITY = "com.kuma.motointercom.extra.VOX_SENSITIVITY"
        private const val EXTRA_PREFERRED_AUDIO_ROUTE =
            "com.kuma.motointercom.extra.PREFERRED_AUDIO_ROUTE"
        private const val EXTRA_AUTOMATIC_RECONNECT_ENABLED =
            "com.kuma.motointercom.extra.AUTOMATIC_RECONNECT_ENABLED"
        private const val EXTRA_RUNTIME_SESSION_ID = "com.kuma.motointercom.extra.RUNTIME_SESSION_ID"
        private const val EXTRA_ATTEMPT_ID = "com.kuma.motointercom.extra.ATTEMPT_ID"
        private const val EXTRA_CHANNEL_ID = "com.kuma.motointercom.extra.CHANNEL_ID"
        private const val EXTRA_ACTION_NONCE = "com.kuma.motointercom.extra.ACTION_NONCE"
        private const val ACTION_URI_SCHEME = "motointercom"
        private const val ACTION_URI_AUTHORITY = "incoming"
        private const val ACTION_PATH_ACCEPT = "accept"
        private const val ACTION_PATH_REJECT = "reject"
        private const val CHANNEL_ID = "intercom_status"
        private const val INCOMING_CHANNEL_ID = "incoming_confirmation"
        private const val NOTIFICATION_ID = 2601
        private const val INCOMING_NOTIFICATION_ID = 2602
        private const val AUDIO_STANDBY_STATUS = "当前音频源：待机"
        private const val AUDIO_EARPIECE_STATUS = "当前音频源：手机听筒"
        private const val AUDIO_SPEAKER_STATUS = "当前音频源：手机外放"
        private const val READY_STATUS = "请点击下方启动对讲"
        private const val SEARCHING_STATUS = "无线配对中，请把两台手机靠近.."
        private const val PEER_FOUND_STATUS = "已发现车友"
        private const val SIGNALING_CONNECTED_STATUS = "信令已连接"
        private const val MEDIA_INITIALIZING_STATUS = "媒体初始化中"
        private const val VOICE_CONNECTED_STATUS = "语音通道已连接"
        private const val SIGNAL_LOST_STATUS = "队友信号丢失，等待重新连接..."
        private const val RESCANNING_STATUS = "正在重新扫描附近车友..."
        private const val ENDED_STATUS = "对讲已结束"
        private const val BLUETOOTH_RETRY_STATUS = "头盔蓝牙已断开，正在尝试重连..."
        private const val AUDIO_PAUSED_STATUS = "对讲已暂停"
        private const val AUDIO_RESUMING_STATUS = "正在恢复对讲音频"
        private const val AUDIO_FOCUS_LOST_STATUS = "音频被其他应用占用"
        private const val AUDIO_ROUTE_UNAVAILABLE_STATUS = "通信设备暂不可用"

        internal fun startIntent(
            context: Context,
            riderName: String = "",
            audioControls: AudioControlSettings = AudioControlSettings(),
            preferredAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
            automaticReconnectEnabled: Boolean = true
        ): Intent =
            Intent(context, IntercomService::class.java)
                .setAction(ACTION_START_INTERCOM)
                .putExtra(EXTRA_RIDER_NAME, riderName)
                .putExtra(EXTRA_VOX_ENABLED, audioControls.voxEnabled)
                .putExtra(EXTRA_VOX_SENSITIVITY, audioControls.normalized().voxSensitivity)
                .putExtra(EXTRA_PREFERRED_AUDIO_ROUTE, preferredAudioRoute.name)
                .putExtra(EXTRA_AUTOMATIC_RECONNECT_ENABLED, automaticReconnectEnabled)

        fun stopIntent(context: Context): Intent =
            Intent(context, IntercomService::class.java).setAction(ACTION_STOP_INTERCOM)

        internal fun incomingConfirmationActionIntent(
            context: Context,
            prompt: IncomingConfirmationPrompt,
            accepted: Boolean
        ): Intent {
            val action = if (accepted) ACTION_ACCEPT_INCOMING else ACTION_REJECT_INCOMING
            val actionPath = if (accepted) ACTION_PATH_ACCEPT else ACTION_PATH_REJECT
            return Intent(context, IntercomService::class.java)
                .setAction(action)
                .setData(
                    Uri.Builder()
                        .scheme(ACTION_URI_SCHEME)
                        .authority(ACTION_URI_AUTHORITY)
                        .appendPath(prompt.actionNonce)
                        .appendPath(actionPath)
                        .build()
                )
                .putExtra(EXTRA_RUNTIME_SESSION_ID, prompt.runtimeSessionId.value)
                .putExtra(EXTRA_ATTEMPT_ID, prompt.attemptId.value)
                .putExtra(EXTRA_CHANNEL_ID, prompt.channelId.value)
                .putExtra(EXTRA_ACTION_NONCE, prompt.actionNonce)
        }
    }
}

internal fun canApplyAttemptCallback(
    accepted: Boolean,
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    expectedAttempt: ConnectionAttempt
): Boolean = accepted && sessionCurrent && currentAttempt == expectedAttempt

internal fun canRegisterControlChannel(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    session: SignalingSessionV2,
    currentState: IntercomState? = null
): Boolean {
    val attempt = session.originatingAttempt
    val recoveryAttempt = (currentState as? IntercomState.Recovering)?.attempt
    return !session.isClosed &&
        sessionCurrent &&
        (
            recoveryAttempt == null ||
                (currentAttempt == recoveryAttempt && !isNonTargetRecoveryChannel(currentState, session.targetLock))
            ) &&
        (attempt == null || currentAttempt == attempt) &&
        (attempt == null || session.channel.transport in attempt.channelPlan) &&
        (attempt == null || attempt.targetLock == session.targetLock) &&
        session.peer.isVerifiedFor(session.targetLock)
}

internal fun canInstallControlSession(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    existingSession: SignalingSessionV2?,
    session: SignalingSessionV2,
    currentState: IntercomState? = null
): Boolean = existingSession == null &&
    canRegisterControlChannel(sessionCurrent, currentAttempt, session, currentState)

internal enum class ControlChannelAdmissionOutcome {
    ADMITTED,
    REJECTED,
    REJECTED_NON_TARGET_WIFI_DIRECT
}

internal fun admitControlSession(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    existingSession: SignalingSessionV2?,
    session: SignalingSessionV2,
    currentState: IntercomState? = null,
    onRejectedNonTargetWifiDirect: (ConnectionAttempt, TargetLock) -> Unit
): ControlChannelAdmissionOutcome {
    if (
        canInstallControlSession(
            sessionCurrent,
            currentAttempt,
            existingSession,
            session,
            currentState
        )
    ) {
        return ControlChannelAdmissionOutcome.ADMITTED
    }

    val recoveryAttempt = (currentState as? IntercomState.Recovering)?.attempt
    val rejectedNonTargetWifiDirect =
        session.channel.transport == Transport.WIFI_DIRECT &&
            recoveryAttempt != null &&
            recoveryAttempt.targetLock != session.targetLock
    session.close()
    if (rejectedNonTargetWifiDirect) {
        onRejectedNonTargetWifiDirect(requireNotNull(recoveryAttempt), session.targetLock)
        return ControlChannelAdmissionOutcome.REJECTED_NON_TARGET_WIFI_DIRECT
    }
    return ControlChannelAdmissionOutcome.REJECTED
}

internal fun isNonTargetRecoveryChannel(
    currentState: IntercomState?,
    targetLock: TargetLock
): Boolean = (currentState as? IntercomState.Recovering)
    ?.attempt
    ?.targetLock
    ?.let { it != targetLock } == true

internal fun canExecuteAbortAttemptEffect(
    effect: SessionEffect.AbortAttemptAndResumeDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?,
    terminalOutcome: ConnectionAttemptTerminalOutcome?
): Boolean = currentState == IntercomState.Discovering(effect.runtimeSessionId) &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null &&
    terminalOutcome != null

internal fun canExecuteRefreshDiscoveryEffect(
    effect: SessionEffect.RefreshDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = canResumePassiveDiscovery(
    effect.runtimeSessionId,
    currentState,
    currentAttempt,
    activeAttempt,
    pendingInbound
)

internal fun canResumePassiveDiscovery(
    runtimeSessionId: RuntimeSessionId,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = currentState == IntercomState.Discovering(runtimeSessionId) &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null

internal fun canFinalizeActiveSessionReleaseEffect(
    effect: SessionEffect.ReleaseActiveSessionAndContinueDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = currentState == IntercomState.Discovering(effect.attempt.runtimeSessionId) &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null

internal fun canDeliverRuntimeAudioCallback(
    running: Boolean,
    activeRuntimeSessionId: RuntimeSessionId?,
    callbackRuntimeSessionId: RuntimeSessionId
): Boolean = running && activeRuntimeSessionId == callbackRuntimeSessionId

internal fun controlSendCompletionEvent(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    channelId: ControlChannelId,
    messageType: SignalingMessageTypeV2,
    result: Result<Unit>
): SessionEvent = result.fold(
    onSuccess = {
        SessionEvent.SignalingMessageSent(
            runtimeSessionId,
            attemptId,
            channelId,
            messageType
        )
    },
    onFailure = { failure ->
        SessionEvent.SignalingSendFailed(
            runtimeSessionId,
            attemptId,
            channelId,
            messageType,
            failure.message.orEmpty()
        )
    }
)

internal fun canDeliverDecodedControlEnvelope(
    sessionCurrent: Boolean,
    sessionClosed: Boolean,
    registeredSessionMatches: Boolean
): Boolean {
    // The frame was decoded and identity/phase checked before this main-thread seam.
    // A following EOF may close the reader, but cannot invalidate that earlier frame.
    return sessionCurrent && registeredSessionMatches
}

internal fun canExecuteRestartDiscoveryEffect(
    effect: SessionEffect.RestartDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?
): Boolean = currentState is IntercomState.Recovering &&
    effect.runtimeSessionId == effect.attempt.runtimeSessionId &&
    currentState.attempt == effect.attempt &&
    currentAttempt == effect.attempt

internal fun canExecuteResetWirelessEnvironmentEffect(
    effect: SessionEffect.ResetWirelessEnvironment,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = currentState is IntercomState.Resetting &&
    currentState.runtimeSessionId == effect.runtimeSessionId &&
    currentState.targetDeviceId == effect.targetDeviceId &&
    currentState.failedAttemptId == effect.failedAttemptId &&
    currentState.consecutiveFinalFailures == effect.consecutiveFinalFailures &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null

internal fun canRestartRecoveryAttempt(
    expectedAttempt: ConnectionAttempt?,
    currentAttempt: ConnectionAttempt?,
    now: MonotonicTimestamp
): Boolean = if (expectedAttempt == null) {
    currentAttempt == null
} else {
    expectedAttempt.hasSameImmutableIdentity(currentAttempt) &&
        expectedAttempt.remainingMillis(now) > 0L
}

internal fun SignalingSessionV2.toConnectionCandidateContext(
    attempt: ConnectionAttempt
): ConnectionCandidateContext? = runCatching {
    ConnectionCandidateContext(
        attempt = attempt,
        channelId = channel.channelId,
        wireRequestKey = wireRequestKey,
        targetLock = targetLock,
        transport = channel.transport,
        requestRole = requestRole,
        peer = peer
    )
}.getOrNull()

internal fun SignalingSessionV2.matchesControlHandle(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    channelId: ControlChannelId
): Boolean = pinnedIdentity.localSessionId == runtimeSessionId &&
    wireRequestKey.attemptId == attemptId &&
    channel.channelId == channelId

internal fun SignalingSessionV2.matchesControlHandle(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    channelId: ControlChannelId,
    targetLock: TargetLock
): Boolean = matchesControlHandle(runtimeSessionId, attemptId, channelId) &&
    this.targetLock == targetLock

internal fun SignalingSessionV2.hasCandidateIdentity(
    candidate: ConnectionCandidateContext
): Boolean = matchesControlHandle(
        candidate.runtimeSessionId,
        candidate.attemptId,
        candidate.channelId
    ) &&
    wireRequestKey == candidate.wireRequestKey &&
    targetLock == candidate.targetLock &&
    channel.transport == candidate.transport &&
    requestRole == candidate.requestRole &&
    peer == candidate.peer

internal fun SignalingSessionV2.matchesCandidate(
    candidate: ConnectionCandidateContext
): Boolean = !isClosed && hasCandidateIdentity(candidate)

internal fun canUseMediaCandidate(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    candidate: ConnectionCandidateContext,
    session: SignalingSessionV2,
    expectedRole: WebRtcRole? = null
): Boolean = sessionCurrent &&
    isCurrentMediaCandidate(currentAttempt, activeAttempt, candidate) &&
    session.matchesCandidate(candidate) &&
    (expectedRole == null || session.requestRole.webRtcRole == expectedRole)

internal fun canStartWebRtc(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    expectedAttempt: ConnectionAttempt,
    session: SignalingSessionV2,
    expectedRole: WebRtcRole
): Boolean = !session.isClosed &&
    sessionCurrent &&
    currentAttempt == expectedAttempt &&
    session.wireRequestKey.attemptId == expectedAttempt.id &&
    session.channel.transport in expectedAttempt.channelPlan &&
    session.targetLock == expectedAttempt.targetLock &&
    session.peer.isVerifiedFor(expectedAttempt.targetLock) &&
    session.requestRole.webRtcRole == expectedRole &&
    session.phase in setOf(SignalingPhase.ACCEPTED, SignalingPhase.READY_TO_SEND_ANSWER)
