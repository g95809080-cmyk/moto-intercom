package com.kuma.motointercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台免死对讲服务。
 *
 * Activity 只做遥控器；蓝牙 SCO、Wi-Fi Direct、WebRTC 信令全部由前台服务托管，
 * 锁屏和退到后台时不跟着 Activity 一起释放。
 */
class IntercomService : Service() {

    internal interface Listener {
        fun onStatusChanged(status: String, running: Boolean)
        fun onIntercomStateChanged(state: IntercomState) = Unit
        fun onAudioSourceChanged(status: String, bluetooth: Boolean) = Unit
        fun onPresencesChanged(presences: List<RiderPresence>) = Unit
        fun onAudioLevelChanged(level: Float) = Unit
        fun onLog(message: String)
        fun onToast(message: String) = Unit
        fun onRemoteRiderIdentified(name: String) = Unit
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

    private var listener: Listener? = null
    private var audioRouteController: AudioRouteController? = null
    private var wifiTunnel: WifiDirectTunnel? = null
    private var intercomManager: IntercomManager? = null
    private var lanDiscovery: LanDiscoveryCoordinator? = null

    private var bluetoothReady = false
    private var physicalLinkReady = false
    private var mediaConnected = false
    private var running = false
    private var lastStatus = READY_STATUS
    private var audioSourceStatus = AUDIO_STANDBY_STATUS
    private var audioSourceBluetooth = false
    private var requestedRiderName = ""
    private var localRiderName = ""
    private var remoteRiderName: String? = null
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
            onError = ::handleError
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
        localRiderName = ""
        tunnelChosen.set(NO_SESSION_TOKEN)
        publishPresenceSnapshot(presenceAggregator.clear())
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(SEARCHING_STATUS)
        orchestrator.dispatch(SessionEvent.RuntimeStarted(runtimeSessionId))

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
            onTunnelReady = { targetIp, isServer, attempt, socket ->
                onTunnelReady(
                    token,
                    targetIp,
                    isServer,
                    remoteDeviceId = null,
                    identityVerificationSource = IdentityVerificationSource.NONE,
                    attempt = attempt,
                    transport = Transport.WIFI_DIRECT,
                    signalingSocket = socket
                )
            },
            localDeviceId = deviceId,
            localNickname = requestedRiderName.ifBlank { "骑士" },
            localDeviceName = Build.MODEL.orEmpty(),
            sessionId = runtimeSessionId.value,
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
            protocolVersion = DISCOVERY_PROTOCOL_VERSION,
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
            onTunnelReady = { ip, server, remoteDeviceId, verificationSource, attempt, socket ->
                acceptTunnel(
                    token,
                    ip,
                    server,
                    remoteDeviceId,
                    verificationSource,
                    attempt,
                    Transport.LAN,
                    socket,
                    closeWifiDirect = true
                )
            },
            onLog = { message -> postForSession(token) { publishLog(message) } },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.start() }
        }
        targetAttempt?.let(::openTargetedTransport)
    }

    private fun onTunnelReady(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        remoteDeviceId: String?,
        identityVerificationSource: IdentityVerificationSource,
        attempt: ConnectionAttempt,
        transport: Transport,
        signalingSocket: Socket
    ) {
        acceptTunnel(
            token,
            targetIp,
            isServer,
            remoteDeviceId,
            identityVerificationSource,
            attempt,
            transport,
            signalingSocket,
            closeWifiDirect = false
        )
    }

    private fun acceptTunnel(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        remoteDeviceId: String?,
        identityVerificationSource: IdentityVerificationSource,
        suppliedAttempt: ConnectionAttempt,
        transport: Transport,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ): Boolean {
        if (suppliedAttempt.channelPlan.transport != transport) {
            return closeStaleSocket(signalingSocket)
        }
        if (!sessions.claimIfCurrent(token) {
                tunnelChosen.compareAndSet(NO_SESSION_TOKEN, token.value)
            }
        ) {
            return closeStaleSocket(signalingSocket)
        }
        mainHandler.post {
            if (!isSessionCurrent(token) || tunnelChosen.get() != token.value) {
                tunnelChosen.compareAndSet(token.value, NO_SESSION_TOKEN)
                closeStaleSocket(signalingSocket)
                return@post
            }
            val runtimeSessionId = activeRuntimeSessionId
            if (runtimeSessionId == null) {
                tunnelChosen.compareAndSet(token.value, NO_SESSION_TOKEN)
                closeStaleSocket(signalingSocket)
                return@post
            }
            val attempt = suppliedAttempt
            val queued = orchestrator.dispatch(
                SessionEvent.TunnelReady(
                    attempt,
                    remoteDeviceId,
                    transport,
                    identityVerificationSource
                )
            ) { accepted ->
                dispatchOnMain {
                    if (
                        !accepted ||
                        !isSessionCurrent(token) ||
                        tunnelChosen.get() != token.value
                    ) {
                        tunnelChosen.compareAndSet(token.value, NO_SESSION_TOKEN)
                        closeStaleSocket(signalingSocket)
                        return@dispatchOnMain
                    }
                    activateTunnel(
                        token,
                        targetIp,
                        isServer,
                        remoteDeviceId,
                        identityVerificationSource,
                        attempt,
                        signalingSocket,
                        closeWifiDirect
                    )
                }
            }
            if (!queued) {
                tunnelChosen.compareAndSet(token.value, NO_SESSION_TOKEN)
                closeStaleSocket(signalingSocket)
            }
        }
        return true
    }

    private fun activateTunnel(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        remoteDeviceId: String?,
        identityVerificationSource: IdentityVerificationSource,
        attempt: ConnectionAttempt,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ) {
        lanDiscovery?.close()
        lanDiscovery = null
        if (closeWifiDirect) {
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
        publishStatus(SIGNALING_CONNECTED_STATUS)
        localRiderName = requestedRiderName.ifBlank { if (isServer) "骑士A" else "骑士B" }
        publishLog("本机骑士昵称：$localRiderName")

        val recoverySpec = createRecoverySpec()
        intercomManager = IntercomManager(
            context = this,
            signalingSocket = signalingSocket,
            isServer = isServer,
            localRiderName = localRiderName,
            localDeviceId = localDeviceId,
            localRuntimeSessionId = attempt.runtimeSessionId,
            expectedRemoteDeviceId = attempt.targetDeviceId,
            expectedRemoteRuntimeSessionId = attempt.targetLock.expectedRemoteSessionId,
            requireClaimedRemoteDeviceId =
                !identityVerificationSource.verifiesStableDeviceId,
            onIntercomDisconnected = {
                onIntercomDisconnected(token, attempt, recoverySpec, it)
            },
            onConnectionStateChanged = {
                onConnectionStateChanged(token, attempt, recoverySpec, it)
            },
            onRemoteIdentity = { onRemoteIdentity(token, attempt, it) },
            onAudioLevelChanged = { onAudioLevelChanged(token, it) },
            onError = { error -> postForSession(token) { handleError(error) } },
            isSessionCurrent = { isSessionCurrent(token) }
        ).also {
            publishStatus(MEDIA_INITIALIZING_STATUS)
            it.start()
        }

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
        val deviceId = localDeviceId.takeIf(String::isNotBlank) ?: return
        val generation = ++recoveryGeneration
        markDiscoveryUnavailable()
        sessions.invalidate()
        activeSession = null
        val managerToClose = intercomManager
        val lanToClose = lanDiscovery
        val wifiToClose = wifiTunnel
        val audioToClose = audioRouteController
        intercomManager = null
        lanDiscovery = null
        wifiTunnel = null
        audioRouteController = null

        AttemptResourceController(
            runtimeSessionId = runtimeSessionId,
            closeIntercomAndSocket = { managerToClose?.close() },
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
                localRiderName = ""
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

    private fun onRemoteIdentity(
        token: SessionGeneration.Token,
        attempt: ConnectionAttempt,
        peer: PeerIdentity
    ) {
        postForSession(token) {
            val name = peer.nickname
            remoteRiderName = name
            orchestrator.dispatch(
                SessionEvent.RemoteIdentityReceived(
                    runtimeSessionId = attempt.runtimeSessionId,
                    attemptId = attempt.id,
                    peer = peer
                )
            )
            if (peer.deviceId == null) {
                publishLog("Remote identity has no stable deviceId; pairing will be skipped")
            }
            publishLog("已识别远端骑士：$name")
            listener?.onRemoteRiderIdentified(name)
            updateStageStatus()
        }
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
        sessions.invalidate()
        activeSession = null
        activeRuntimeSessionId = null
        localDeviceId = ""
        running = false
        tunnelChosen.set(NO_SESSION_TOKEN)
        publishPresenceSnapshot(presenceAggregator.clear())
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
        localRiderName = ""
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
                if (openTargetedTransport(effect.attempt)) {
                    publishStatus(PEER_FOUND_STATUS)
                } else {
                    publishLog("Targeted transport is not available for ${effect.attempt.id.value}")
                }
            }
            is SessionEffect.AbortAttemptAndResumeDiscovery -> {
                publishLog("连接尝试已中止：${effect.attemptId.value}")
                abortResourcesAndResumeDiscovery(effect.runtimeSessionId, nextAttempt = null)
            }
            is SessionEffect.RestartDiscovery -> {
                abortResourcesAndResumeDiscovery(effect.runtimeSessionId, effect.attempt)
            }
        }
    }

    private fun openTargetedTransport(attempt: ConnectionAttempt): Boolean {
        if (activeRuntimeSessionId != attempt.runtimeSessionId) return false
        return when (attempt.channelPlan.transport) {
            Transport.LAN -> lanDiscovery?.connect(attempt) == true
            Transport.WIFI_DIRECT -> wifiTunnel?.connect(attempt) == true
        }
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

    private fun closeStaleSocket(socket: Socket): Boolean {
        return try {
            socket.close()
            false
        } catch (_: IOException) {
            false
        }
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
        private const val DISCOVERY_PROTOCOL_VERSION = 1
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 10_000L
        private const val PEER_RECONNECT_BACKOFF_MS = 1_500L
        const val ACTION_START_INTERCOM = "com.kuma.motointercom.action.START_INTERCOM"
        const val ACTION_STOP_INTERCOM = "com.kuma.motointercom.action.STOP_INTERCOM"
        const val EXTRA_RIDER_NAME = "com.kuma.motointercom.extra.RIDER_NAME"
        private const val CHANNEL_ID = "intercom_status"
        private const val NOTIFICATION_ID = 2601
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
    }
}
