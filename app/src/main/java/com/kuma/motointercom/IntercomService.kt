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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
        fun onPresencesChanged(presences: List<RiderPresence>) = Unit
        fun onAudioLevelChanged(level: Float) = Unit
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val presenceAggregator = PresenceAggregator(SystemClock::elapsedRealtime)

    private lateinit var identityStore: LocalIdentityStore
    private lateinit var pairingRepository: PairingRepository
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
    private var audioRouteController: AudioRouteController? = null
    private var wifiTunnel: WifiDirectTunnel? = null
    private var intercomManager: IntercomManager? = null
    private var lanDiscovery: LanDiscoveryCoordinator? = null
    private val signalingSessions = linkedMapOf<ControlChannelId, SignalingSessionV2>()
    private val pendingMediaMessages = linkedMapOf<ControlChannelId, MutableList<SignalingMessageV2>>()
    private var activeMediaChannelId: ControlChannelId? = null

    private var bluetoothReady = false
    private var physicalLinkReady = false
    private var mediaConnected = false
    private var running = false
    private var lastStatus = READY_STATUS
    private var audioSourceStatus = AUDIO_STANDBY_STATUS
    private var audioSourceBluetooth = false
    private var requestedRiderName = ""
    private var remoteRiderName: String? = null
    private var appInForeground = false
    private var activeIncomingPrompt: IncomingConfirmationPrompt? = null
    private var activeSession: SessionGeneration.Token? = null
    private var activeRuntimeSessionId: RuntimeSessionId? = null
    private var localDeviceId = ""
    private var recoveryGeneration = 0
    private var presenceExpiryGeneration = 0
    private val tunnelChosen = AtomicLong(NO_SESSION_TOKEN)

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
                dispatchOnMain { listener?.onIntercomStateChanged(state) }
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
            ACTION_START_INTERCOM -> {
                requestedRiderName = intent.getStringExtra(EXTRA_RIDER_NAME).orEmpty().trim()
                if (!hasRequiredRuntimePermissions()) {
                    publishStatus("缺少必要权限，无法启动摩声")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startIntercom()
                return START_NOT_STICKY
            }
            ACTION_STOP_INTERCOM -> {
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
        listener?.onPresencesChanged(presenceAggregator.snapshot().presences)
        remoteRiderName?.let { listener?.onRemoteRiderIdentified(it) }
    }

    internal fun setAppForeground(foreground: Boolean) {
        dispatchOnMain {
            appInForeground = foreground
            publishConfirmationAvailability()
        }
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

    internal fun connectToPresence(selectedPresence: RiderPresence) {
        mainHandler.post {
            if (activeSession == null || tunnelChosen.get() != NO_SESSION_TOKEN) return@post
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
                    attemptId = ConnectionAttemptId.create(),
                    targetDeviceId = targetDeviceId,
                    targetSessionId = targetSessionId,
                    availableTransports = presence.availableTransports,
                    deadlineElapsedRealtimeMs =
                        SystemClock.elapsedRealtime() + CONNECTION_ATTEMPT_TIMEOUT_MS
                )
            ) { accepted ->
                if (!accepted) {
                    dispatchOnMain { publishLog("Presence connection request was rejected") }
                }
            }
        }
    }

    private fun startIntercom() {
        if (running) {
            publishStatus(lastStatus)
            return
        }

        val token = sessions.start()
        val runtimeSessionId = RuntimeSessionId.create()
        activeSession = token
        activeRuntimeSessionId = runtimeSessionId
        running = true
        bluetoothReady = false
        physicalLinkReady = false
        remoteRiderName = null
        tunnelChosen.set(NO_SESSION_TOKEN)
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
                    localDeviceId = deviceId
                    requestedRiderName = nickname
                    startAudioRoute(token)
                    startDiscoveryTransports(token, deviceId, runtimeSessionId)
                }
            } catch (t: Throwable) {
                postForSession(token) {
                    handleError(t)
                    stopIntercom()
                }
            }
        }
    }

    private fun startAudioRoute(token: SessionGeneration.Token) {
        audioRouteController = AudioRouteController(
            context = this,
            onScoConnected = { deviceName ->
                postForSession(token) {
                    bluetoothReady = true
                    publishAudioSource("当前音频源：蓝牙耳机 ($deviceName)", bluetooth = true)
                    publishToast("头盔蓝牙已连线，对讲音频已就绪")
                    updateStageStatus()
                }
            },
            onScoDisconnected = {
                postForSession(token) {
                    bluetoothReady = false
                    publishToast(BLUETOOTH_RETRY_STATUS)
                    publishLog(BLUETOOTH_RETRY_STATUS)
                    updateStageStatus()
                }
            },
            onSpeakerFallback = { noBluetooth ->
                postForSession(token) {
                    bluetoothReady = false
                    publishAudioSource(AUDIO_SPEAKER_STATUS, bluetooth = false)
                    if (noBluetooth) publishToast("未检测到头盔蓝牙，已切换至手机外放")
                    updateStageStatus()
                }
            },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.switchToBluetoothSco() }
    }

    private fun startDiscoveryTransports(
        token: SessionGeneration.Token,
        deviceId: String,
        runtimeSessionId: RuntimeSessionId,
        targetAttempt: ConnectionAttempt? = null
    ) {
        publishStatus(SEARCHING_STATUS)
        val plannedTransports = plannedDiscoveryTransports(targetAttempt)
        if (Transport.WIFI_DIRECT in plannedTransports) {
        wifiTunnel = WifiDirectTunnel(
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
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.start() }
        }
        if (Transport.LAN in plannedTransports) {
        lanDiscovery = LanDiscoveryCoordinator(
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
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.start() }
        }
        targetAttempt?.let(::beginTargetedTransport)
    }

    private fun registerControlChannel(
        token: SessionGeneration.Token,
        session: SignalingSessionV2
    ) {
        dispatchOnMain {
            if (
                !canRegisterControlChannel(
                    sessionCurrent = isSessionCurrent(token),
                    currentAttempt = orchestrator.currentAttempt,
                    session = session
                )
            ) {
                session.close()
                return@dispatchOnMain
            }

            signalingSessions.put(session.channel.channelId, session)?.close()
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
            !isSessionCurrent(token) ||
            signalingSessions[session.channel.channelId] !== session
        ) {
            closeControlChannel(session.channel.channelId)
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
                message.reason,
                createRecoverySpec()
            )
            is SignalingMessageV2.Offer,
            is SignalingMessageV2.Answer,
            is SignalingMessageV2.Candidate -> {
                val manager = intercomManager
                    ?.takeIf { activeMediaChannelId == channelId }
                if (manager == null) {
                    pendingMediaMessages.getOrPut(channelId, ::mutableListOf) += message
                } else {
                    manager.handleRemoteSignaling(message)
                }
                return
            }
            is SignalingMessageV2.Hello -> SessionEvent.ProtocolViolation(
                runtimeSessionId,
                channelId,
                session.wireRequestKey,
                createRecoverySpec(),
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
        removeSignalingSession(session.channel.channelId, session)
        pendingMediaMessages.remove(session.channel.channelId)
        if (!isSessionCurrent(token)) return
        val runtimeSessionId = session.pinnedIdentity.localSessionId
        val event = if (failure is SignalingV2Exception) {
            SessionEvent.ProtocolViolation(
                runtimeSessionId,
                session.channel.channelId,
                session.wireRequestKey,
                createRecoverySpec(),
                failure.message.orEmpty()
            )
        } else {
            SessionEvent.ChannelClosed(
                runtimeSessionId,
                session.channel.channelId,
                session.wireRequestKey,
                createRecoverySpec(),
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
                dispatchOnMain { closeControlChannel(session.channel.channelId) }
            }
        }
    }

    private fun closeControlChannel(channelId: ControlChannelId) {
        pendingMediaMessages.remove(channelId)
        signalingSessions.remove(channelId)?.close()
    }

    private fun removeSignalingSession(
        channelId: ControlChannelId,
        expected: SignalingSessionV2
    ) {
        if (signalingSessions[channelId] === expected) {
            signalingSessions.remove(channelId)
        }
    }

    private fun startWebRtc(effect: SessionEffect.StartWebRtc) {
        val token = activeSession ?: return
        val controlAttempt = orchestrator.activeControlAttempt
        if (
            controlAttempt?.attempt != effect.attempt ||
            controlAttempt.mediaOwnerChannelId != effect.channelId ||
            controlAttempt.phase != SignalingAttemptPhase.ACCEPTED ||
            controlAttempt.terminalOutcome != AttemptOutcome.ACCEPTED
        ) {
            return
        }
        val session = signalingSessions[effect.channelId]
        if (
            session == null ||
            !canStartWebRtc(
                sessionCurrent = isSessionCurrent(token),
                currentAttempt = orchestrator.currentAttempt,
                expectedAttempt = effect.attempt,
                session = session,
                expectedRole = effect.role
            )
        ) {
            closeControlChannel(effect.channelId)
            return
        }
        val existingOwner = activeMediaChannelId
        if (existingOwner != null && existingOwner != effect.channelId) {
            closeControlChannel(effect.channelId)
            return
        }
        if (intercomManager != null && existingOwner == effect.channelId) return

        activeMediaChannelId = effect.channelId
        tunnelChosen.set(token.value)
        attemptDeadlineScheduler.schedule(effect.attempt)
        lanDiscovery?.close()
        lanDiscovery = null
        if (effect.attempt.channelPlan.transport == Transport.LAN) {
            val closingTunnel = wifiTunnel
            try {
                closingTunnel?.close {
                    dispatchOnMain {
                        if (wifiTunnel === closingTunnel) wifiTunnel = null
                    }
                }
            } catch (t: Throwable) {
                if (wifiTunnel === closingTunnel) wifiTunnel = null
                handleError(t)
            }
        }

        physicalLinkReady = true
        mediaConnected = false
        remoteRiderName = effect.peer.nickname
        publishStatus(SIGNALING_CONNECTED_STATUS)

        val recoverySpec = createRecoverySpec()
        intercomManager = IntercomManager(
            context = this,
            signalingSession = session,
            webRtcRole = effect.role,
            onIntercomDisconnected = {
                onIntercomDisconnected(token, effect.attempt, recoverySpec, it)
            },
            onConnectionStateChanged = {
                onConnectionStateChanged(token, effect.attempt, recoverySpec, it)
            },
            onAudioLevelChanged = { onAudioLevelChanged(token, it) },
            onError = { error -> postForSession(token) { handleError(error) } },
            isSessionCurrent = { isSessionCurrent(token) }
        ).also {
            publishLog(
                "Starting WebRTC after CONNECT_ACCEPT: role=${effect.role} " +
                    "remote=${effect.peer.deviceId}/${effect.peer.runtimeSessionId?.value}"
            )
            listener?.onRemoteRiderIdentified(effect.peer.nickname)
            publishStatus(MEDIA_INITIALIZING_STATUS)
            it.start()
        }
        pendingMediaMessages.remove(effect.channelId)
            .orEmpty()
            .forEach { intercomManager?.handleRemoteSignaling(it) }

        updateStageStatus()
    }

    private fun onConnectionStateChanged(
        token: SessionGeneration.Token,
        attempt: ConnectionAttempt,
        recovery: RecoveryAttemptSpec,
        state: PeerConnection.PeerConnectionState
    ) {
        postForSession(token) {
            publishLog("WebRTC 状态：$state")
            orchestrator.dispatch(
                SessionEvent.WebRtcStateChanged(
                    runtimeSessionId = attempt.runtimeSessionId,
                    attemptId = attempt.id,
                    state = state.toProductState(),
                    occurredAt = System.currentTimeMillis(),
                    recovery = recovery
                )
            )
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    activeMediaChannelId
                        ?.let(signalingSessions::get)
                        ?.let { runCatching(it::markMediaConnected).onFailure(::handleError) }
                    attemptDeadlineScheduler.cancel(attempt)
                    mediaConnected = true
                    publishStatus(VOICE_CONNECTED_STATUS)
                }
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
        attempt: ConnectionAttempt,
        recovery: RecoveryAttemptSpec,
        error: IOException
    ) {
        postForSession(token) {
            publishLog("信令通道断开：${error.message}")
            orchestrator.dispatch(
                SessionEvent.SignalingDisconnected(
                    runtimeSessionId = attempt.runtimeSessionId,
                    attemptId = attempt.id,
                    recovery = recovery
                )
            )
        }
    }

    private fun abortResourcesAndResumeDiscovery(
        runtimeSessionId: RuntimeSessionId,
        nextAttempt: ConnectionAttempt?
    ) {
        val token = activeSession ?: return
        if (!isSessionCurrent(token) || activeRuntimeSessionId != runtimeSessionId) return
        cancelAllIncomingConfirmationSurfaces()
        attemptDeadlineScheduler.cancelRuntime(runtimeSessionId)
        val deviceId = localDeviceId.takeIf(String::isNotBlank) ?: return
        val generation = ++recoveryGeneration
        markDiscoveryUnavailable()
        sessions.invalidate()
        activeSession = null
        val managerToClose = intercomManager
        val lanToClose = lanDiscovery
        val wifiToClose = wifiTunnel
        val audioToClose = audioRouteController
        val signalingToClose = drainSignalingSessions()
        intercomManager = null
        lanDiscovery = null
        wifiTunnel = null
        audioRouteController = null

        AttemptResourceController(
            runtimeSessionId = runtimeSessionId,
            closeIntercomAndSocket = {
                managerToClose?.close()
                signalingToClose.forEach(SignalingSessionV2::close)
            },
            closeLanDiscovery = { lanToClose?.close() },
            closeWifiDirect = { onClosed ->
                if (wifiToClose == null) onClosed() else wifiToClose.close(onClosed)
            },
            closeAudioRoute = { audioToClose?.close() },
            releaseTunnel = { tunnelChosen.set(NO_SESSION_TOKEN) },
            clearConnectionState = {
                bluetoothReady = false
                physicalLinkReady = false
                mediaConnected = false
                remoteRiderName = null
                publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
                publishStatus(SIGNAL_LOST_STATUS)
            },
            resumeDiscovery = { resumedRuntimeSessionId ->
                mainHandler.postDelayed({
                    if (
                        !running ||
                        generation != recoveryGeneration ||
                        activeSession != null ||
                        activeRuntimeSessionId != resumedRuntimeSessionId
                    ) {
                        return@postDelayed
                    }
                    val recoveryToken = sessions.start()
                    activeSession = recoveryToken
                    tunnelChosen.set(NO_SESSION_TOKEN)
                    publishLog("重新启动车友发现")
                    startAudioRoute(recoveryToken)
                    startDiscoveryTransports(
                        recoveryToken,
                        deviceId,
                        resumedRuntimeSessionId,
                        nextAttempt
                    )
                }, PEER_RECONNECT_BACKOFF_MS)
            },
            onError = ::handleError
        ).abortAndResumeDiscovery()
    }

    private fun onAudioLevelChanged(token: SessionGeneration.Token, level: Float) {
        postForSession(token) { listener?.onAudioLevelChanged(level) }
    }

    private fun stopIntercom() {
        val runtimeSessionId = activeRuntimeSessionId
        if (runtimeSessionId != null) {
            orchestrator.dispatch(SessionEvent.StopRequested(runtimeSessionId))
        }
        recoveryGeneration++
        attemptDeadlineScheduler.cancel()
        cancelAllIncomingConfirmationSurfaces()
        sessions.invalidate()
        activeSession = null
        activeRuntimeSessionId = null
        localDeviceId = ""
        running = false
        tunnelChosen.set(NO_SESSION_TOKEN)
        publishPresenceSnapshot(presenceAggregator.clear())
        drainSignalingSessions().forEach(SignalingSessionV2::close)
        lanDiscovery?.close()
        lanDiscovery = null

        try {
            intercomManager?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        try {
            wifiTunnel?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        try {
            audioRouteController?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        intercomManager = null
        wifiTunnel = null
        audioRouteController = null
        bluetoothReady = false
        physicalLinkReady = false
        mediaConnected = false
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

    private fun dispatchOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun postForSession(token: SessionGeneration.Token, action: () -> Unit) {
        dispatchOnMain {
            if (isSessionCurrent(token)) action()
        }
    }

    private fun handleSessionEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.OpenTargetedTransport -> {
                beginTargetedTransport(effect.attempt)
            }
            is SessionEffect.AbortAttemptAndResumeDiscovery -> {
                publishLog("连接尝试已中止：${effect.attemptId.value}")
                abortResourcesAndResumeDiscovery(effect.runtimeSessionId, nextAttempt = null)
            }
            is SessionEffect.RestartDiscovery -> {
                abortResourcesAndResumeDiscovery(effect.runtimeSessionId, effect.attempt)
            }
            is SessionEffect.RescheduleAttemptDeadline -> {
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
            is SessionEffect.SendConnectReject -> sendControlMessage(
                effect.runtimeSessionId,
                effect.attemptId,
                effect.channelId,
                SignalingMessageV2.ConnectReject(effect.reason, effect.retryable)
            )
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
                val candidates = effect.cohort.channelIds.mapNotNull { channelId ->
                    signalingSessions[channelId]
                        ?.takeUnless(SignalingSessionV2::isClosed)
                        ?.let { MediaChannelCandidate(channelId, it.channel.transport) }
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
            is SessionEffect.CloseControlChannel -> closeControlChannel(effect.channelId)
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
        getSystemService(NotificationManager::class.java)?.cancel(INCOMING_NOTIFICATION_ID)
        if (activeIncomingPrompt?.actionNonce == effect.actionNonce) {
            activeIncomingPrompt = null
        }
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
            session.pinnedIdentity.localSessionId != runtimeSessionId ||
            session.wireRequestKey.attemptId != attemptId
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
            dispatchOnMain {
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    orchestrator.dispatch(
                        SessionEvent.SignalingMessageSent(
                            runtimeSessionId,
                            attemptId,
                            channelId,
                            message.type
                        )
                    )
                } else {
                    removeSignalingSession(channelId, session)
                    pendingMediaMessages.remove(channelId)
                    orchestrator.dispatch(
                        SessionEvent.SignalingSendFailed(
                            runtimeSessionId,
                            attemptId,
                            channelId,
                            message.type,
                            failure.message.orEmpty()
                        )
                    )
                }
            }
        }
    }

    private fun openTargetedTransport(attempt: ConnectionAttempt): Boolean {
        if (activeRuntimeSessionId != attempt.runtimeSessionId) return false
        return openPlannedTransport(
            attempt,
            openLan = { lanDiscovery?.connect(it) == true },
            openWifiDirect = { wifiTunnel?.connect(it) == true }
        )
    }

    private fun beginTargetedTransport(attempt: ConnectionAttempt) {
        attemptDeadlineScheduler.schedule(attempt)
        val result = runCatching { openTargetedTransport(attempt) }
        if (result.getOrDefault(false)) {
            publishStatus(PEER_FOUND_STATUS)
            return
        }
        attemptDeadlineScheduler.cancel(attempt)
        val reason = result.exceptionOrNull()?.message ?: "transport adapter unavailable"
        publishLog("Targeted transport open failed for ${attempt.id.value}: $reason")
        orchestrator.dispatch(
            SessionEvent.TargetedTransportOpenFailed(
                runtimeSessionId = attempt.runtimeSessionId,
                attemptId = attempt.id,
                transport = attempt.channelPlan.transport,
                reason = reason
            )
        )
    }

    private fun createRecoverySpec(): RecoveryAttemptSpec = RecoveryAttemptSpec(
        id = ConnectionAttemptId.create(),
        deadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + CONNECTION_ATTEMPT_TIMEOUT_MS
    )

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
            activeMediaChannelId = null
        }

    private fun updateStageStatus() {
        when {
            mediaConnected -> publishStatus(VOICE_CONNECTED_STATUS)
            physicalLinkReady -> publishStatus(MEDIA_INITIALIZING_STATUS)
            bluetoothReady -> publishStatus(SEARCHING_STATUS)
            running -> publishStatus(SEARCHING_STATUS)
        }
    }

    private fun publishPresenceSnapshot(snapshot: PresenceSnapshot) {
        listener?.onPresencesChanged(snapshot.presences)
        val generation = ++presenceExpiryGeneration
        val expiry = snapshot.nextExpiryElapsedRealtimeMs ?: return
        val delayMs = (expiry - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        mainHandler.postDelayed({
            if (generation != presenceExpiryGeneration) return@postDelayed
            publishPresenceSnapshot(presenceAggregator.expire())
        }, delayMs)
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
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
            .setContentText("正在后台运行中")
            .setStyle(Notification.BigTextStyle().bigText(lastStatus))
            .setOngoing(running)
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
                    "${prompt.peer.deviceName.ifBlank { "MotoCom" }} · 当前 Socket 身份已验证"
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
        private const val NO_SESSION_TOKEN = 0L
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 10_000L
        private const val PEER_RECONNECT_BACKOFF_MS = 1_500L
        const val ACTION_START_INTERCOM = "com.kuma.motointercom.action.START_INTERCOM"
        const val ACTION_STOP_INTERCOM = "com.kuma.motointercom.action.STOP_INTERCOM"
        const val ACTION_ACCEPT_INCOMING = "com.kuma.motointercom.action.ACCEPT_INCOMING"
        const val ACTION_REJECT_INCOMING = "com.kuma.motointercom.action.REJECT_INCOMING"
        const val EXTRA_RIDER_NAME = "com.kuma.motointercom.extra.RIDER_NAME"
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
        private const val AUDIO_SPEAKER_STATUS = "当前音频源：手机外放（无蓝牙）"
        private const val READY_STATUS = "请点击下方启动对讲"
        private const val SEARCHING_STATUS = "无线配对中，请把两台手机靠近.."
        private const val PEER_FOUND_STATUS = "已发现车友"
        private const val SIGNALING_CONNECTED_STATUS = "信令已连接"
        private const val MEDIA_INITIALIZING_STATUS = "媒体初始化中"
        private const val VOICE_CONNECTED_STATUS = "语音通道已连接"
        private const val SIGNAL_LOST_STATUS = "队友信号丢失，等待重新连接..."
        private const val ENDED_STATUS = "对讲已结束"
        private const val BLUETOOTH_RETRY_STATUS = "头盔蓝牙已断开，正在尝试重连..."

        fun startIntent(context: Context, riderName: String = ""): Intent =
            Intent(context, IntercomService::class.java)
                .setAction(ACTION_START_INTERCOM)
                .putExtra(EXTRA_RIDER_NAME, riderName)

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

internal fun canActivateTunnel(
    accepted: Boolean,
    sessionCurrent: Boolean,
    tunnelClaimed: Boolean,
    currentAttempt: ConnectionAttempt?,
    expectedAttempt: ConnectionAttempt
): Boolean = accepted && sessionCurrent && tunnelClaimed && currentAttempt == expectedAttempt

internal fun canRegisterControlChannel(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    session: SignalingSessionV2
): Boolean {
    val attempt = session.originatingAttempt
    return !session.isClosed &&
        sessionCurrent &&
        (attempt == null || currentAttempt == attempt) &&
        (attempt == null || attempt.channelPlan.transport == session.channel.transport) &&
        (attempt == null || attempt.targetLock == session.targetLock) &&
        session.peer.isVerifiedFor(session.targetLock)
}

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
    session.channel.transport == expectedAttempt.channelPlan.transport &&
    session.targetLock == expectedAttempt.targetLock &&
    session.peer.isVerifiedFor(expectedAttempt.targetLock) &&
    session.requestRole.webRtcRole == expectedRole &&
    session.phase in setOf(SignalingPhase.ACCEPTED, SignalingPhase.READY_TO_SEND_ANSWER)
