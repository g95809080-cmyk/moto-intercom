package com.kuma.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 摩托车对讲 App 的 Wi-Fi Direct 连接层。
 *
 * 这里只负责：
 * 1. 搜索并连接附近设备；
 * 2. 判断组长/组员并拿到组长 IP；
 * 3. 建立一条可靠 TCP 信令通道，用来交换 WebRTC 的 SDP/ICE。
 *
 * 语音采集、3A、Opus、RTP/UDP 交给 WebRTC，别在这里手写。
 */
internal class WifiDirectTunnel(
    context: Context,
    private val onControlChannelReady: (SignalingSessionV2) -> Unit,
    private val signalingPort: Int = 8888,
    private val localDeviceId: String,
    private val localNickname: String = "骑士",
    private val localDeviceName: String,
    private val sessionId: RuntimeSessionId,
    private val onPeersChanged: (List<WifiDirectRiderDevice>) -> Unit = {},
    private val onDiscoveryStatus: (String) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onTargetedOverlapUnavailable: (ConnectionAttempt) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    initialTargetAttempt: ConnectionAttempt? = null,
    private val monotonicClock: MonotonicClock = MonotonicClock {
        MonotonicTimestamp(SystemClock.elapsedRealtime())
    }
) : Closeable {

    private data class TargetedTaskContext(
        val attemptContext: AttemptTaskContext,
        val targetAddress: String?
    ) {
        val attempt: ConnectionAttempt
            get() = attemptContext.attempt
    }

    private enum class State {
        DISCOVERING,
        P2P_CONNECTING,
        GROUP_READY,
        SIGNALING_READY,
        CLOSED
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    @Volatile private var running = false
    @Volatile private var connectingAddress: String? = null
    @Volatile private var tunnelStarted = false
    @Volatile private var removingGroup = false
    @Volatile private var validatingGroup = false
    @Volatile private var state = State.DISCOVERING

    @Volatile private var socketTransport: WifiDirectSignalingSocket? = null
    @Volatile private var socketTransportGeneration = 0
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private val peerRegistry = WifiDirectPeerRegistry()
    private val peerSessionTracker = DiscoverySessionTracker()
    private val groupValidationGate = WifiDirectGroupValidationGate {
        monotonicClock.now().elapsedRealtimeMs
    }
    private val setupRecoveryGate = WifiDirectSetupRecoveryGate()
    private val peerDevices = linkedMapOf<String, WifiP2pDevice>()
    private val peerClaims = linkedMapOf<String, DiscoveryIdentityClaim>()
    @Volatile private var ingressAttempt: ConnectionAttempt? = initialTargetAttempt
    @Volatile private var targetAttempt: ConnectionAttempt? = null
    @Volatile private var targetAddress: String? = null
    private var targetAttemptGeneration = 0
    private var pendingRetryAttempt = 0
    private var pendingRetryGeneration = 0
    private var pendingRetryScheduled = false
    private var connectWatchdogGeneration = 0
    private var groupRemovalGeneration = 0
    private var lifecycleGeneration = 0
    private var serviceDiscoveryReady = false
    private val closeLock = Any()
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    val shouldRestartSetup = setupRecoveryGate.updateP2pEnabled(enabled)
                    if (!enabled) {
                        resetDiscoveryCandidates()
                        postError(IllegalStateException("Wi-Fi Direct 未开启"))
                    } else if (shouldRestartSetup && running) {
                        Log.d(TAG, "Wi-Fi Direct 已恢复，重新初始化服务发现")
                        setupServiceDiscovery()
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.parcelableCompat<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else if (state == State.P2P_CONNECTING) {
                        Log.d(TAG, "ignore disconnected broadcast while connecting: $connectingAddress")
                    } else {
                        val taskContext = currentTargetContext()
                        if (
                            taskContext != null &&
                            !isTargetedContextIdentityCurrent(taskContext)
                        ) return
                        resetTunnelOnly(taskContext)
                        state = State.DISCOVERING
                        if (isCapturedTaskCurrent(taskContext)) {
                            mainHandler.post {
                                if (isCapturedTaskCurrent(taskContext)) onDisconnected()
                            }
                        }
                        if (isCapturedTaskCurrent(taskContext) && !removingGroup) discoverPeers()
                    }
                }
            }
        }
    }

    fun start() {
        lifecycleGeneration++
        running = true
        if (!hasRequiredPermissions(appContext)) {
            postError(SecurityException("缺少 Wi-Fi Direct 运行时权限"))
            return
        }

        initP2p()
        registerReceiver()
        clearUntrustedGroupBeforeDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (!running || !setupRecoveryGate.isEnabled) return
        if (!serviceDiscoveryReady) {
            Log.d(TAG, "discoverServices skipped: service request not ready")
            return
        }
        val m = manager ?: return
        val c = channel ?: return
        val taskContext = currentTargetContext()

        try {
            val peers = peerRegistry.snapshot()
            Log.d(TAG, "discoverServices start pending=${peers.pending.size} accepted=${peers.accepted.size}")
            m.discoverServices(
                c,
                discoverAction(
                    "开始搜索 MotoCom 服务失败",
                    onFailed = { Log.w(TAG, "discoverServices failure") },
                    onSuccess = { Log.d(TAG, "discoverServices success") },
                    taskContext = taskContext
                )
            )
        } catch (t: Throwable) {
            postError(t, taskContext)
        }
    }

    fun restrictIngress(attempt: ConnectionAttempt): Boolean {
        if (
            !running ||
            Transport.WIFI_DIRECT !in attempt.channelPlan ||
            attempt.remainingMillis(monotonicClock) <= 0L
        ) return false
        ingressAttempt = attempt
        return true
    }

    fun connect(attempt: ConnectionAttempt): Boolean {
        if (!restrictIngress(attempt)) return false
        groupValidationGate.cancel()
        validatingGroup = false
        cancelConnectWatchdog()
        targetAttemptGeneration++
        targetAttempt = attempt
        targetAddress = null
        connectTargetIfAvailable()
        return true
    }

    fun retainPassiveIngress(completedAttempt: ConnectionAttempt) {
        if (!running) return
        val currentTarget = targetAttempt
        if (currentTarget != null && !currentTarget.hasSameImmutableIdentity(completedAttempt)) {
            return
        }
        if (currentTarget != null) {
            targetAttemptGeneration++
            targetAttempt = null
            targetAddress = null
        }
        if (ingressAttempt?.hasSameImmutableIdentity(completedAttempt) == true) {
            ingressAttempt = null
        }
        groupValidationGate.cancel()
        validatingGroup = false
        cancelPendingRetry()
        cancelConnectWatchdog()
        if (
            state == State.DISCOVERING &&
            connectingAddress == null &&
            !tunnelStarted &&
            socketTransport == null
        ) {
            return
        }
        removeGroupAndRediscover("transport race selected LAN")
    }

    fun retainSelectedChannel(completedAttempt: ConnectionAttempt) {
        val currentTarget = targetAttempt ?: return
        if (!currentTarget.hasSameImmutableIdentity(completedAttempt)) return
        targetAttemptGeneration++
        targetAttempt = null
        ingressAttempt = null
        targetAddress = null
        groupValidationGate.cancel()
        validatingGroup = false
        cancelPendingRetry()
        cancelConnectWatchdog()
    }

    fun rejectNonTargetGroup(
        expectedAttempt: ConnectionAttempt,
        actualTargetLock: TargetLock
    ) {
        if (!running || expectedAttempt.targetLock == actualTargetLock) return
        ingressAttempt = expectedAttempt
        groupValidationGate.cancel()
        validatingGroup = false
        removeGroupAndRediscover(
            "verified Socket TargetLock mismatch: expected=${expectedAttempt.targetLock} " +
                "actual=$actualTargetLock",
            taskContext = currentTargetContext()
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectTarget(device: WifiP2pDevice, attempt: ConnectionAttempt) {
        if (!isTargetedAttemptCurrent(attempt)) return
        val address = normalizedAddress(device.deviceAddress)
        if (!peerRegistry.isAccepted(address)) {
            logPeer(device, accepted = false, reason = "未发布 MotoCom DNS-SD 身份")
            postDiscoveryStatus(NO_MOTOCOM_PEER_STATUS, currentTargetContext())
            return
        }
        if (peerClaims[address]?.matches(attempt.targetLock) != true) return
        if (connectingAddress == address || state == State.SIGNALING_READY) return

        val m = manager ?: return
        val c = channel ?: return
        targetAddress = address
        connectingAddress = address
        val taskContext = currentTargetContext(address) ?: return
        startConnectWatchdog(taskContext)

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        try {
            cancelPendingRetry()
            Log.d(TAG, "connect start targetPeer=${peerSummary(device)}")
            state = State.P2P_CONNECTING
            m.connect(
                c,
                config,
                action("连接 ${device.deviceName} 失败", onFailed = {
                    if (!isTargetedContextCurrent(taskContext)) return@action
                    cancelConnectWatchdog()
                    connectingAddress = null
                    state = State.DISCOVERING
                }, onBusy = {
                    if (!isTargetedContextCurrent(taskContext)) return@action
                    resetTunnelOnly(taskContext)
                    val delay = boundedTaskDelay(taskContext, BUSY_RETRY_DELAY_MS)
                        ?: return@action
                    mainHandler.postDelayed(
                        { if (isTargetedContextCurrent(taskContext)) discoverPeers() },
                        delay
                    )
                }, isCurrent = { isTargetedContextCurrent(taskContext) }, taskContext = taskContext)
            )
        } catch (t: Throwable) {
            if (!isTargetedContextCurrent(taskContext)) return
            cancelConnectWatchdog()
            connectingAddress = null
            state = State.DISCOVERING
            postError(t, taskContext)
        }
    }

    private fun connectTargetIfAvailable() {
        val attempt = targetAttempt ?: return
        if (!isTargetedAttemptCurrent(attempt)) return
        val address = peerRegistry.findAcceptedAddress(peerClaims, attempt.targetLock) ?: run {
            targetAddress = null
            return
        }
        val device = peerDevices[address] ?: return
        targetAddress = address
        if (device.status == WifiP2pDevice.AVAILABLE) connectTarget(device, attempt)
    }

    private fun currentTargetContext(address: String? = targetAddress): TargetedTaskContext? {
        val attempt = targetAttempt ?: return null
        return TargetedTaskContext(
            attemptContext = AttemptTaskContext(attempt, targetAttemptGeneration),
            targetAddress = address
        )
    }

    private fun isTargetedAttemptCurrent(attempt: ConnectionAttempt): Boolean =
        running &&
            attempt.remainingMillis(monotonicClock) > 0L &&
            targetAttempt?.hasSameImmutableIdentity(attempt) == true

    private fun isTargetedContextIdentityCurrent(context: TargetedTaskContext): Boolean =
        running &&
            context.attemptContext.generation == targetAttemptGeneration &&
            context.attemptContext.matchesAttempt(targetAttempt) &&
            (context.targetAddress == null || targetAddress == context.targetAddress)

    private fun isTargetedContextCurrent(context: TargetedTaskContext): Boolean =
        isTargetedContextIdentityCurrent(context) &&
            context.attempt.remainingMillis(monotonicClock) > 0L

    override fun close() = close {}

    internal fun close(onComplete: () -> Unit) {
        var startCleanup = false
        var completeNow = false
        synchronized(closeLock) {
            when {
                state != State.CLOSED -> {
                    closeCallbacks += onComplete
                    state = State.CLOSED
                    startCleanup = true
                }
                manager == null && channel == null -> completeNow = true
                else -> closeCallbacks += onComplete
            }
        }
        if (completeNow) {
            onComplete()
            return
        }
        if (!startCleanup) return

        val cleanupGeneration = ++lifecycleGeneration
        Log.d(TAG, "close cleanup start generation=$cleanupGeneration")
        running = false
        groupRemovalGeneration++
        targetAttemptGeneration++
        targetAttempt = null
        ingressAttempt = null
        targetAddress = null
        peerSessionTracker.clear()
        setupRecoveryGate.cancel()
        resetTunnelOnly()
        cancelPendingRetry()
        cancelConnectWatchdog()
        unregisterReceiver()
        val m = manager
        val c = channel
        if (m == null || c == null) {
            finishCloseCleanup(cleanupGeneration, c)
            return
        }
        cancelConnectForClose(m, c, cleanupGeneration)
    }

    private fun cancelConnectForClose(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        generation: Int
    ) {
        runCloseCleanupAction(
            label = "cancelConnect",
            action = { listener -> m.cancelConnect(c, listener) },
            onComplete = { removeGroupForClose(m, c, generation, attempt = 1) }
        )
    }

    private fun removeGroupForClose(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        generation: Int,
        attempt: Int
    ) {
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "removeGroup success during close")
                    clearServiceRequestsForClose(m, c, generation)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "removeGroup failure during close: ${reasonText(reason)}")
                    if (reason == WifiP2pManager.BUSY && attempt < REMOVE_GROUP_BUSY_RETRY_COUNT) {
                        Log.w(
                            TAG,
                            "removeGroup BUSY retry during close " +
                                "attempt=${attempt + 1}/$REMOVE_GROUP_BUSY_RETRY_COUNT"
                        )
                        mainHandler.postDelayed(
                            { removeGroupForClose(m, c, generation, attempt + 1) },
                            BUSY_RETRY_DELAY_MS
                        )
                    } else {
                        clearServiceRequestsForClose(m, c, generation)
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "removeGroup failure during close", t)
            clearServiceRequestsForClose(m, c, generation)
        }
    }

    private fun clearServiceRequestsForClose(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        generation: Int
    ) {
        runCloseCleanupAction(
            label = "clearServiceRequests",
            action = { listener -> m.clearServiceRequests(c, listener) },
            onComplete = { clearLocalServicesForClose(m, c, generation) }
        )
    }

    private fun clearLocalServicesForClose(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        generation: Int
    ) {
        runCloseCleanupAction(
            label = "clearLocalServices",
            action = { listener -> m.clearLocalServices(c, listener) },
            onComplete = { finishCloseCleanup(generation, c) }
        )
    }

    private fun runCloseCleanupAction(
        label: String,
        action: (WifiP2pManager.ActionListener) -> Unit,
        onComplete: () -> Unit
    ) {
        try {
            action(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "$label success during close")
                    onComplete()
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "$label failure during close: ${reasonText(reason)}")
                    onComplete()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "$label failure during close", t)
            onComplete()
        }
    }

    private fun finishCloseCleanup(generation: Int, c: WifiP2pManager.Channel?) {
        if (generation != lifecycleGeneration) return
        serviceRequest = null
        serviceDiscoveryReady = false
        peerRegistry.reset()
        peerDevices.clear()
        removingGroup = false
        connectingAddress = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                c?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "channel close failure", t)
            }
        }
        channel = null
        manager = null
        Log.d(TAG, "close cleanup finished generation=$generation")
        notifyCloseCompleted()
    }

    private fun notifyCloseCompleted() {
        val callbacks = synchronized(closeLock) {
            val result = closeCallbacks.toList()
            closeCallbacks.clear()
            result
        }
        callbacks.forEach { callback ->
            try {
                callback()
            } catch (t: Throwable) {
                Log.w(TAG, "close completion callback failure", t)
            }
        }
    }

    private fun initP2p() {
        if (manager != null && channel != null) return

        val p2pManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (p2pManager == null) {
            postError(IllegalStateException("设备不支持 Wi-Fi Direct"))
            return
        }

        manager = p2pManager
        channel = p2pManager.initialize(appContext, Looper.getMainLooper()) {
            channel = null
            if (running) {
                setupRecoveryGate.cancel()
                initP2p()
                clearUntrustedGroupBeforeDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearUntrustedGroupBeforeDiscovery() {
        val m = manager ?: return
        val c = channel ?: return
        val taskContext = currentTargetContext()
        try {
            m.requestGroupInfo(c) { group ->
                if (!isCapturedTaskCurrent(taskContext)) return@requestGroupInfo
                if (group == null) {
                    cancelPendingConnectThenDiscover(taskContext)
                } else {
                    Log.w(TAG, "启动时发现未验证 P2P group，主动清理: ${groupSummary(group)}")
                    removeGroupAndRediscover(
                        "启动时存在未验证 P2P group",
                        taskContext = taskContext
                    )
                }
            }
        } catch (t: Throwable) {
            if (isCapturedTaskCurrent(taskContext)) postError(t, taskContext)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelPendingConnectThenDiscover(taskContext: TargetedTaskContext? = null) {
        val m = manager ?: return
        val c = channel ?: return
        val removalGeneration = ++groupRemovalGeneration
        removingGroup = true
        val finish: () -> Unit = finish@{
            if (removalGeneration != groupRemovalGeneration) return@finish
            removingGroup = false
            val delay = boundedTaskDelay(taskContext, GROUP_REMOVAL_SETTLE_MS)
            if (delay != null && isCapturedTaskCurrent(taskContext)) {
                mainHandler.postDelayed(
                    { if (isCapturedTaskCurrent(taskContext)) setupServiceDiscovery() },
                    delay
                )
            }
            Unit
        }
        try {
            m.cancelConnect(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (removalGeneration != groupRemovalGeneration) return
                    if (isCapturedTaskCurrent(taskContext)) finish() else removingGroup = false
                }

                override fun onFailure(reason: Int) {
                    if (removalGeneration != groupRemovalGeneration) return
                    if (isCapturedTaskCurrent(taskContext)) finish() else removingGroup = false
                }
            })
        } catch (_: Throwable) {
            if (removalGeneration == groupRemovalGeneration) {
                if (isCapturedTaskCurrent(taskContext)) finish() else removingGroup = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupServiceDiscovery() {
        if (!running) return
        val setup = setupRecoveryGate.beginSetup() ?: return
        val m = manager ?: return
        val c = channel ?: return
        resetDiscoveryCandidates()

        m.setDnsSdResponseListeners(
            c,
            { instanceName, registrationType, device ->
                handleServiceResponse(instanceName, registrationType, device)
            },
            { _, record, device -> handleTxtRecord(record, device) }
        )

        val record = mapOf(
            TXT_APP_ID to APP_ID,
            TXT_PROTOCOL_VERSION to PROTOCOL_VERSION,
            TXT_DEVICE_ID to localDeviceId,
            TXT_NICKNAME to localNickname.ifBlank { "骑士" },
            TXT_DEVICE_NAME to localDeviceName,
            TXT_SESSION_ID to sessionId.value
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            P2pServiceInstanceCodec.encode(localDeviceId, sessionId),
            SERVICE_TYPE,
            record
        )
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        serviceRequest = request

        try {
            m.clearLocalServices(c, setupAction(setup, "清理本机 P2P 服务失败", onSuccess = {
                m.addLocalService(c, serviceInfo, setupAction(setup, "发布 MotoCom P2P 服务失败", onSuccess = {
                    Log.d(TAG, "local service publish success: $record")
                    m.clearServiceRequests(c, setupAction(setup, "清理 P2P 服务请求失败", onSuccess = {
                        m.addServiceRequest(c, request, setupAction(setup, "添加 MotoCom 服务请求失败", onSuccess = {
                            serviceDiscoveryReady = true
                            Log.d(TAG, "service request add success type=$SERVICE_TYPE")
                            discoverPeers()
                        }))
                    }))
                }))
            }))
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun handleTxtRecord(record: Map<String, String>, device: WifiP2pDevice) {
        Log.d(TAG, "TXT received from ${peerSummary(device)} record=$record")
        val reason = when {
            record[TXT_APP_ID] != APP_ID -> "appId 不匹配"
            record[TXT_PROTOCOL_VERSION] != PROTOCOL_VERSION -> "protocolVersion 不兼容"
            record[TXT_SESSION_ID].isNullOrBlank() -> "缺少 sessionId"
            else -> null
        }
        if (reason != null) {
            logPeer(device, accepted = false, reason = reason)
            return
        }

        val identity = DiscoveryIdentityClaim(
            claimedDeviceId = record[TXT_DEVICE_ID]?.trim()?.takeIf(String::isNotEmpty),
            sourceSessionId = record[TXT_SESSION_ID]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::RuntimeSessionId),
            nickname = record[TXT_NICKNAME].orEmpty().trim(),
            deviceName = record[TXT_DEVICE_NAME].orEmpty().trim().ifBlank { device.deviceName },
            protocolVersion = record[TXT_PROTOCOL_VERSION]?.toIntOrNull() ?: 0
        )
        acceptPeer(
            device,
            identity,
            "MotoCom TXT 校验通过 nickname=${record[TXT_NICKNAME]} " +
                "sessionId=${record[TXT_SESSION_ID]}"
        )
    }

    private fun handleServiceResponse(
        instanceName: String,
        registrationType: String,
        device: WifiP2pDevice
    ) {
        Log.d(
            TAG,
            "service instance received from ${peerSummary(device)} " +
                "instance=$instanceName type=$registrationType"
        )
        val validType = registrationType.startsWith("$SERVICE_TYPE.")
        if (!validType) {
            logPeer(
                device,
                accepted = false,
                reason = "DNS-SD 服务标识不匹配 instance=$instanceName type=$registrationType"
            )
            return
        }

        val instanceIdentity = P2pServiceInstanceCodec.decodeClaim(
            instanceName,
            device.deviceName.orEmpty()
        )
        if (instanceIdentity != null) {
            acceptPeer(
                device,
                instanceIdentity,
                "MotoCom v2 service instance identity claim instance=$instanceName"
            )
            return
        }
        if (!P2pServiceInstanceCodec.isLegacy(instanceName)) {
            logPeer(
                device,
                accepted = false,
                reason = "DNS-SD 服务实例身份无效 instance=$instanceName"
            )
            return
        }

        // Legacy instances remain provisional when a vendor omits TXT callbacks.
        acceptPeer(
            device,
            DiscoveryIdentityClaim(
                claimedDeviceId = null,
                sourceSessionId = null,
                nickname = device.deviceName.orEmpty(),
                deviceName = device.deviceName.orEmpty(),
                protocolVersion = 0
            ),
            "MotoCom DNS-SD 服务校验通过 instance=$instanceName type=$registrationType"
        )
    }

    private fun acceptPeer(
        device: WifiP2pDevice,
        identity: DiscoveryIdentityClaim,
        reason: String
    ) {
        if (device.deviceAddress.isBlank()) return

        val address = normalizedAddress(device.deviceAddress)
        peerDevices[address] = device
        if (
            peerSessionTracker.register(identity) ==
            DiscoverySessionRegistration.SUPERSEDED
        ) {
            Log.d(TAG, "ignored superseded P2P identity for ${peerSummary(device)}")
            return
        }
        val currentIdentity = peerClaims[address]
        if (currentIdentity?.hasStableIdentity != true || identity.hasStableIdentity) {
            peerClaims[address] = identity
        }
        val wasPending = address in peerRegistry.snapshot().pending
        val snapshot = peerRegistry.accept(address)
        Log.d(TAG, "peer accepted: ${peerSummary(device)} reason=$reason pendingBefore=$wasPending")
        if (wasPending) {
            Log.d(TAG, "pending -> accepted: ${peerSummary(device)} pending=${snapshot.pending.size}")
        }
        logPeer(device, accepted = true, reason = reason)
        publishPeers(snapshot)
        connectTargetIfAvailable()
    }

    private fun publishPeers(snapshot: WifiDirectPeerRegistry.Snapshot) {
        val peers = snapshot.accepted.mapNotNull { address ->
            val device = peerDevices[address] ?: return@mapNotNull null
            val identity = peerClaims[address] ?: DiscoveryIdentityClaim(
                claimedDeviceId = null,
                sourceSessionId = null,
                nickname = device.deviceName.orEmpty(),
                deviceName = device.deviceName.orEmpty(),
                protocolVersion = 0
            )
            WifiDirectRiderDevice(device, identity)
        }
        mainHandler.post { onPeersChanged(peers) }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val m = manager ?: return
        val c = channel ?: return

        try {
            m.requestPeers(c) { list ->
                if (!running) return@requestPeers
                val current = list.deviceList
                    .filter { it.deviceAddress.isNotBlank() }
                    .associateBy { normalizedAddress(it.deviceAddress) }
                peerDevices.keys.retainAll(current.keys)
                peerClaims.keys.retainAll(current.keys)
                peerDevices.putAll(current)
                var snapshot = peerRegistry.reconcile(current.keys)
                current.forEach { (address, peer) ->
                    val accepted = address in snapshot.accepted
                    if (accepted) {
                        logPeer(peer, accepted = true, reason = "已通过 MotoCom 服务校验")
                    } else {
                        val added = address !in snapshot.pending
                        snapshot = peerRegistry.markPending(address)
                        logPeerPending(peer, "等待 MotoCom TXT/service 身份")
                        if (added) {
                            Log.d(
                                TAG,
                                "pending peer added: ${peerSummary(peer)} pending=${snapshot.pending.size}"
                            )
                        }
                    }
                }
                val motoComPeers = snapshot.accepted.mapNotNull(peerDevices::get)
                publishPeers(snapshot)
                if (current.isNotEmpty() && motoComPeers.isEmpty()) {
                    postDiscoveryStatus(NO_MOTOCOM_PEER_STATUS)
                }
                connectTargetIfAvailable()
                schedulePendingRetryIfNeeded()
            }
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun requestConnectionInfo() {
        if (!running) return
        val m = manager ?: return
        val c = channel ?: return
        val taskContext = currentTargetContext()
        if (taskContext != null && !isTargetedContextCurrent(taskContext)) return

        try {
            m.requestConnectionInfo(c) { info -> handleConnectionInfo(info, taskContext) }
        } catch (t: Throwable) {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                postError(t, taskContext)
            }
        }
    }

    private fun handleConnectionInfo(
        info: WifiP2pInfo,
        taskContext: TargetedTaskContext?
    ) {
        if (!running) return
        if (
            (taskContext == null && targetAttempt != null) ||
            (taskContext != null && !isTargetedContextCurrent(taskContext))
        ) {
            if (taskContext != null) {
                removeGroupAndRediscover(
                    "stale P2P connection-info callback",
                    taskContext = taskContext
                )
            }
            return
        }
        if (!info.groupFormed) {
            resetTunnelOnly(taskContext)
            state = State.DISCOVERING
            mainHandler.post { onDisconnected() }
            if (isCapturedTaskCurrent(taskContext)) discoverPeers()
            return
        }
        if (tunnelStarted || validatingGroup) return

        cancelConnectWatchdog()
        validatingGroup = true
        val validation = groupValidationGate.start(
            GROUP_IDENTITY_VALIDATION_TIMEOUT_MS,
            taskContext?.attemptContext
        )
        scheduleGroupValidationDeadline(validation, taskContext)
        requestValidatedGroup(info, attempt = 0, validation, taskContext)
    }

    @SuppressLint("MissingPermission")
    private fun requestValidatedGroup(
        info: WifiP2pInfo,
        attempt: Int,
        validation: WifiDirectGroupValidationGate.Session,
        taskContext: TargetedTaskContext?
    ) {
        if (!isValidationCurrent(validation, taskContext)) {
            abandonExpiredValidation(validation, taskContext)
            return
        }
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.requestGroupInfo(c) { group ->
                if (!isValidationCurrent(validation, taskContext)) {
                    abandonExpiredValidation(validation, taskContext)
                    return@requestGroupInfo
                }
                if (group == null) {
                    if (attempt < GROUP_VALIDATION_RETRIES) {
                        val delay = boundedTaskDelay(taskContext, GROUP_VALIDATION_RETRY_MS)
                            ?: run {
                                abandonExpiredValidation(validation, taskContext)
                                return@requestGroupInfo
                            }
                        mainHandler.postDelayed(
                            { requestValidatedGroup(info, attempt + 1, validation, taskContext) },
                            delay
                        )
                    } else {
                        groupValidationGate.cancel()
                        validatingGroup = false
                        rejectCurrentGroup(
                            "groupFormed=true 但 requestGroupInfo 为空",
                            taskContext
                        )
                    }
                    return@requestGroupInfo
                }
                validateGroup(info, group, attempt, validation, taskContext)
            }
        } catch (t: Throwable) {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                postError(t, taskContext)
            }
        }
    }

    private fun validateGroup(
        info: WifiP2pInfo,
        group: WifiP2pGroup,
        attempt: Int,
        validation: WifiDirectGroupValidationGate.Session,
        taskContext: TargetedTaskContext?
    ) {
        if (!isValidationCurrent(validation, taskContext)) {
            abandonExpiredValidation(validation, taskContext)
            return
        }
        val connectionAttempt = taskContext?.attempt
        val requiredAttempt = connectionAttempt ?: ingressAttempt
        val ownerDeviceAddress = group.owner?.deviceAddress
        val clientAddresses = group.clientList.map { it.deviceAddress }
        val groupRemoteAddress = if (info.isGroupOwner) {
            clientAddresses.singleOrNull()
        } else {
            ownerDeviceAddress
        }
        val expectedAddress = taskContext?.targetAddress
            ?: targetAddress
            ?: requiredAttempt?.let {
                peerRegistry.findAcceptedAddress(peerClaims, it.targetLock)
            }
            ?: groupRemoteAddress?.let(::normalizedAddress)
        val target = expectedAddress?.let(peerDevices::get)
        val claim = expectedAddress?.let(peerClaims::get)
        val expectedTargetLock = requiredAttempt?.targetLock ?: claim?.toTargetLockOrNull()
        val membershipMatch = peerRegistry.matchGroup(
            expectedAddress,
            info.isGroupOwner,
            ownerDeviceAddress,
            clientAddresses
        )
        val identityMatches = expectedTargetLock != null && claim?.matches(expectedTargetLock) == true
        val groupMatch = when {
            membershipMatch == WifiDirectPeerRegistry.GroupMatch.PENDING -> membershipMatch
            membershipMatch == WifiDirectPeerRegistry.GroupMatch.MATCHED && identityMatches -> {
                WifiDirectPeerRegistry.GroupMatch.MATCHED
            }
            else -> WifiDirectPeerRegistry.GroupMatch.REJECTED
        }
        val targetMatches = groupMatch == WifiDirectPeerRegistry.GroupMatch.MATCHED

        Log.d(
            TAG,
            "校验 P2P group: targetPeer=${target?.let(::peerSummary)} " +
                "groupOwner=${group.owner?.let(::peerSummary)} networkName=${group.networkName} " +
                "interface=${group.`interface`} clients=$clientAddresses " +
                "localP2pIp=${localP2pIp(group.`interface`)} isGroupOwner=${info.isGroupOwner} " +
                "targetMatches=$targetMatches groupMatch=$groupMatch attempt=$attempt"
        )

        val retryLimit = if (groupMatch == WifiDirectPeerRegistry.GroupMatch.PENDING) {
            GROUP_IDENTITY_VALIDATION_RETRIES
        } else {
            0
        }
        if (!targetMatches && attempt < retryLimit) {
            val delay = boundedTaskDelay(taskContext, GROUP_VALIDATION_RETRY_MS)
                ?: run {
                    abandonExpiredValidation(validation, taskContext)
                    return
                }
            mainHandler.postDelayed(
                { requestValidatedGroup(info, attempt + 1, validation, taskContext) },
                delay
            )
            return
        }
        if (!targetMatches) {
            groupValidationGate.cancel()
            validatingGroup = false
            rejectCurrentGroup(
                "group owner/client 与 Target Lock 不匹配: owner=$ownerDeviceAddress " +
                    "clients=$clientAddresses target=$expectedAddress networkName=${group.networkName}",
                taskContext
            )
            return
        }
        if (connectionAttempt == null && requiredAttempt != null) {
            groupValidationGate.cancel()
            validatingGroup = false
            rejectCurrentGroup(
                "recovery TargetLock matched before this transport was opened",
                taskContext
            )
            return
        }

        val ownerAddress = info.groupOwnerAddress
        if (ownerAddress == null) {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                postError(IllegalStateException("未获取到组长 IP"), taskContext)
            }
            removeGroupAndRediscover("P2P group 没有组长 IP", taskContext = taskContext)
            return
        }

        val localAddress = localP2pAddress(group.`interface`)
        if (localAddress == null) {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                postError(IllegalStateException("未获取到本机 P2P 接口 IP"), taskContext)
            }
            removeGroupAndRediscover("P2P 接口没有可用 IPv4 地址", taskContext = taskContext)
            return
        }

        val resolvedTaskContext = taskContext?.copy(targetAddress = expectedAddress)
        if (resolvedTaskContext != null) {
            if (!isTargetedContextIdentityCurrent(taskContext)) return
            targetAddress = expectedAddress
            if (!isTargetedContextCurrent(resolvedTaskContext)) {
                removeGroupAndRediscover(
                    "P2P attempt budget expired before group became ready",
                    taskContext = taskContext
                )
                return
            }
        }
        groupValidationGate.cancel()
        validatingGroup = false
        tunnelStarted = true
        state = State.GROUP_READY
        Log.d(
            TAG,
            "MotoCom P2P group 校验通过: groupOwner=$ownerAddress networkName=${group.networkName} " +
                "interface=${group.`interface`} localP2pIp=${localAddress.hostAddress} " +
                "remoteTargetIp=${ownerAddress.hostAddress}"
        )
        startSocketTransport(
            info,
            group,
            localAddress,
            ownerAddress,
            resolvedTaskContext,
            requireNotNull(expectedAddress),
            requireNotNull(expectedTargetLock)
        )
    }

    private fun DiscoveryIdentityClaim.toTargetLockOrNull(): TargetLock? {
        val deviceId = claimedDeviceId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val remoteSessionId = sourceSessionId ?: return null
        return TargetLock(deviceId, remoteSessionId)
    }

    private fun isValidationCurrent(
        validation: WifiDirectGroupValidationGate.Session,
        taskContext: TargetedTaskContext?
    ): Boolean =
        running &&
            groupValidationGate.isCurrent(validation) &&
            validation.taskContext == taskContext?.attemptContext &&
            (taskContext == null || isTargetedContextCurrent(taskContext))

    private fun abandonExpiredValidation(
        validation: WifiDirectGroupValidationGate.Session,
        taskContext: TargetedTaskContext?
    ) {
        if (
            taskContext != null &&
            groupValidationGate.isCurrent(validation) &&
            validation.taskContext == taskContext.attemptContext &&
            isTargetedContextIdentityCurrent(taskContext) &&
            !isTargetedContextCurrent(taskContext)
        ) {
            groupValidationGate.cancel()
            validatingGroup = false
            removeGroupAndRediscover(
                "P2P attempt budget expired during group validation",
                taskContext = taskContext
            )
        }
    }

    private fun boundedTaskDelay(
        taskContext: TargetedTaskContext?,
        localDelayMillis: Long
    ): Long? {
        val delay = taskContext?.attempt?.boundedTimeoutMillis(monotonicClock, localDelayMillis)
            ?: localDelayMillis
        return delay.takeIf { it > 0L }
    }

    private fun rejectCurrentGroup(
        reason: String,
        taskContext: TargetedTaskContext? = null
    ) {
        if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) return
        Log.w(TAG, "检测到外部/错误 P2P group，禁止启动 TCP 并清理: $reason")
        if (taskContext == null || isTargetedContextCurrent(taskContext)) {
            postDiscoveryStatus(
                "检测到非 MotoCom P2P 组，正在清理并重新搜索",
                taskContext
            )
        }
        removeGroupAndRediscover(reason, taskContext = taskContext)
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRediscover(
        reason: String,
        taskContext: TargetedTaskContext? = null
    ) {
        removeGroupAndRediscover(reason, attempt = 1, taskContext = taskContext)
    }

    private fun scheduleGroupValidationDeadline(
        validation: WifiDirectGroupValidationGate.Session,
        taskContext: TargetedTaskContext?
    ) {
        mainHandler.postDelayed({
            if (!isValidationCurrent(validation, taskContext)) {
                abandonExpiredValidation(validation, taskContext)
                return@postDelayed
            }
            if (!groupValidationGate.isExpired(validation)) {
                scheduleGroupValidationDeadline(validation, taskContext)
                return@postDelayed
            }
            groupValidationGate.cancel()
            validatingGroup = false
            rejectCurrentGroup("等待 P2P group MotoCom 身份超时", taskContext)
        }, groupValidationGate.remainingMillis(validation))
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRediscover(
        reason: String,
        attempt: Int,
        taskContext: TargetedTaskContext?
    ) {
        if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) return
        if (removingGroup) return
        val removalGeneration = ++groupRemovalGeneration
        val m = manager ?: return recoverAfterGroupRemovalFailure(taskContext, removalGeneration)
        val c = channel ?: return recoverAfterGroupRemovalFailure(taskContext, removalGeneration)
        removingGroup = true
        resetTunnelOnly(taskContext)
        cancelConnectWatchdog()
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (removalGeneration != groupRemovalGeneration) return
                    if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) {
                        removingGroup = false
                        return
                    }
                    Log.d(TAG, "已清理误连 P2P group: $reason")
                    finishGroupRemoval(taskContext, removalGeneration)
                }

                override fun onFailure(code: Int) {
                    if (removalGeneration != groupRemovalGeneration) return
                    if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) {
                        removingGroup = false
                        return
                    }
                    Log.w(TAG, "removeGroup 失败: reason=$reason code=${reasonText(code)}")
                    removingGroup = false
                    if (!running) return
                    val mayRediscover = taskContext == null || isTargetedContextCurrent(taskContext)
                    if (code == WifiP2pManager.BUSY) {
                        if (attempt < REMOVE_GROUP_BUSY_RETRY_COUNT && mayRediscover) {
                            val delay = boundedTaskDelay(taskContext, BUSY_RETRY_DELAY_MS)
                                ?: return
                            Log.w(
                                TAG,
                                "removeGroup BUSY retry attempt=${attempt + 1}/$REMOVE_GROUP_BUSY_RETRY_COUNT " +
                                    "reason=$reason"
                            )
                            mainHandler.postDelayed(
                                {
                                    if (
                                        taskContext == null ||
                                            isTargetedContextCurrent(taskContext)
                                    ) {
                                        removeGroupAndRediscover(
                                            reason,
                                            attempt + 1,
                                            taskContext
                                        )
                                    }
                                },
                                delay
                            )
                        } else if (mayRediscover) {
                            val settleDelay = boundedTaskDelay(taskContext, GROUP_REMOVAL_SETTLE_MS)
                                ?: return
                            Log.w(TAG, "removeGroup BUSY exhausted, rediscover anyway: reason=$reason")
                            resetDiscoveryCandidates(taskContext)
                            state = State.DISCOVERING
                            mainHandler.postDelayed(
                                {
                                    if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                                        setupServiceDiscovery()
                                    }
                                },
                                settleDelay
                            )
                        } else {
                            finishGroupRemoval(taskContext, removalGeneration)
                        }
                        return
                    }

                    if (mayRediscover) {
                        postError(
                            IllegalStateException("清理错误 P2P group 失败: ${reasonText(code)}"),
                            taskContext
                        )
                    }
                    recoverAfterGroupRemovalFailure(taskContext, removalGeneration)
                }
            })
        } catch (t: Throwable) {
            if (removalGeneration != groupRemovalGeneration) return
            if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) {
                removingGroup = false
                return
            }
            removingGroup = false
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                postError(t, taskContext)
            }
            recoverAfterGroupRemovalFailure(taskContext, removalGeneration)
        }
    }

    private fun recoverAfterGroupRemovalFailure(
        taskContext: TargetedTaskContext? = null,
        removalGeneration: Int = groupRemovalGeneration
    ) {
        if (removalGeneration != groupRemovalGeneration) return
        if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) return
        removingGroup = false
        if (!running) return
        connectingAddress = null
        resetDiscoveryCandidates(taskContext)
        state = State.DISCOVERING
        if (taskContext != null && !isTargetedContextCurrent(taskContext)) return
        mainHandler.postDelayed(
            {
                if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                    clearUntrustedGroupBeforeDiscovery()
                }
            },
            boundedTaskDelay(taskContext, BUSY_RETRY_DELAY_MS) ?: return
        )
    }

    private fun finishGroupRemoval(
        taskContext: TargetedTaskContext? = null,
        removalGeneration: Int = groupRemovalGeneration
    ) {
        if (removalGeneration != groupRemovalGeneration) return
        if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) return
        removingGroup = false
        if (!running) return
        connectingAddress = null
        resetDiscoveryCandidates(taskContext)
        state = State.DISCOVERING
        if (taskContext != null && !isTargetedContextCurrent(taskContext)) return
        mainHandler.postDelayed(
            {
                if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                    setupServiceDiscovery()
                }
            },
            boundedTaskDelay(taskContext, GROUP_REMOVAL_SETTLE_MS) ?: return
        )
    }

    private fun startSocketTransport(
        info: WifiP2pInfo,
        group: WifiP2pGroup,
        localAddress: InetAddress,
        ownerAddress: InetAddress,
        taskContext: TargetedTaskContext?,
        expectedRemoteAddress: String,
        expectedTargetLock: TargetLock
    ) {
        socketTransport?.close()
        val generation = ++socketTransportGeneration
        val transport = WifiDirectSignalingSocket(
            port = signalingPort,
            readyTimeoutMillis = CONNECT_WATCHDOG_MS,
            connectTimeoutMillis = SOCKET_CONNECT_TIMEOUT_MS,
            retryDelayMillis = SOCKET_RETRY_DELAY_MS,
            clock = monotonicClock,
            attemptContext = taskContext?.attemptContext,
            isSessionCurrent = {
                running &&
                    generation == socketTransportGeneration &&
                    state == State.GROUP_READY &&
                    (taskContext == null || isTargetedContextCurrent(taskContext)) &&
                    (taskContext != null || targetAttempt == null)
            },
            onReady = { _, physicalRole, socket ->
                postTransportReady(
                    generation,
                    taskContext,
                    expectedTargetLock,
                    physicalRole,
                    socket
                )
            },
            onFailure = { error -> postTransportFailure(generation, taskContext, error) }
        )
        socketTransport = transport

        if (info.isGroupOwner) {
            val hasOnlySelectedClient = group.clientList.size == 1 &&
                sameAddress(group.clientList.single().deviceAddress, expectedRemoteAddress)
            transport.startServer(localAddress) { remoteAddress ->
                hasOnlySelectedClient && remoteOnP2pInterface(
                    remoteAddress,
                    localAddress,
                    group.`interface`
                )
            }
        } else {
            transport.startClient(localAddress, ownerAddress)
        }
    }

    private fun resetTunnelOnly(taskContext: TargetedTaskContext? = null) {
        if (taskContext != null && !isTargetedContextIdentityCurrent(taskContext)) return
        groupValidationGate.cancel()
        tunnelStarted = false
        validatingGroup = false
        connectingAddress = null
        cancelConnectWatchdog()
        socketTransportGeneration++
        socketTransport?.close()
        socketTransport = null
    }

    private fun resetDiscoveryCandidates(taskContext: TargetedTaskContext? = null) {
        cancelPendingRetry()
        serviceDiscoveryReady = false
        targetAddress = taskContext
            ?.takeIf(::isTargetedContextCurrent)
            ?.targetAddress
        peerRegistry.reset()
        peerDevices.clear()
        peerClaims.clear()
        mainHandler.post {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                onPeersChanged(emptyList())
            }
        }
    }

    private fun cancelPendingRetry() {
        pendingRetryGeneration++
        pendingRetryAttempt = 0
        pendingRetryScheduled = false
    }

    private fun schedulePendingRetryIfNeeded() {
        var peers = peerRegistry.snapshot()
        if (!running || peers.pending.isEmpty() || peers.accepted.isNotEmpty()) return
        if (connectingAddress != null || state == State.SIGNALING_READY || pendingRetryScheduled) return

        if (pendingRetryAttempt >= PENDING_DISCOVERY_RETRY_COUNT) {
            Log.w(
                TAG,
                "retry discoverServices exhausted pending=${peers.pending.size} " +
                    "max=$PENDING_DISCOVERY_RETRY_COUNT"
            )
            return
        }

        val generation = pendingRetryGeneration
        val attempt = pendingRetryAttempt + 1
        pendingRetryScheduled = true
        mainHandler.postDelayed({
            pendingRetryScheduled = false
            if (!running || generation != pendingRetryGeneration) return@postDelayed
            peers = peerRegistry.snapshot()
            if (peers.pending.isEmpty() || peers.accepted.isNotEmpty()) return@postDelayed
            if (connectingAddress != null || state == State.SIGNALING_READY) return@postDelayed

            pendingRetryAttempt = attempt
            Log.d(
                TAG,
                "retry discoverServices attempt=$attempt/$PENDING_DISCOVERY_RETRY_COUNT " +
                    "pending=${peers.pending.size}"
            )
            discoverPeers()
            schedulePendingRetryIfNeeded()
        }, PENDING_DISCOVERY_RETRY_DELAY_MS)
    }

    private fun startConnectWatchdog(taskContext: TargetedTaskContext) {
        val generation = ++connectWatchdogGeneration
        val delay = taskContext.attempt.boundedTimeoutMillis(
            monotonicClock,
            CONNECT_WATCHDOG_MS
        )
        if (delay <= 0L) return
        mainHandler.postDelayed({
            if (
                !running ||
                generation != connectWatchdogGeneration ||
                !isTargetedContextIdentityCurrent(taskContext)
            ) return@postDelayed
            if (!isTargetedContextCurrent(taskContext)) {
                removeGroupAndRediscover(
                    "P2P attempt budget expired during connect watchdog",
                    taskContext = taskContext
                )
                return@postDelayed
            }
            if (state == State.SIGNALING_READY || connectingAddress != taskContext.targetAddress) {
                return@postDelayed
            }

            Log.w(TAG, "P2P connect timeout: peer=${taskContext.targetAddress}")
            connectingAddress = null
            peerRegistry.reset()
            peerDevices.clear()
            serviceDiscoveryReady = false
            cancelPendingRetry()
            Log.w(TAG, "P2P connect timeout cleanup: peer=${taskContext.targetAddress}")
            Log.d(TAG, "reset discovery candidates after connect timeout")
            removeGroupAndRediscover("P2P connect timeout", taskContext = taskContext)
        }, delay)
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogGeneration++
    }

    private fun setupAction(
        setup: WifiDirectSetupRecoveryGate.Session,
        message: String,
        onFailed: () -> Unit = {},
        onSuccess: () -> Unit = {},
        taskContext: TargetedTaskContext? = currentTargetContext()
    ) = action(
        message = message,
        onFailed = onFailed,
        onSuccess = onSuccess,
        onBusy = {
            if (!isCapturedTaskCurrent(taskContext)) return@action
            if (setupRecoveryGate.scheduleRetry(setup)) {
                Log.w(TAG, "setup BUSY retry setupServiceDiscovery")
                resetTunnelOnly(taskContext)
                val delay = boundedTaskDelay(taskContext, BUSY_RETRY_DELAY_MS)
                    ?: return@action
                mainHandler.postDelayed({
                    if (
                        setupRecoveryGate.takeRetry(setup) &&
                        isCapturedTaskCurrent(taskContext)
                    ) {
                        setupServiceDiscovery()
                    }
                }, delay)
            }
        },
        isCurrent = { isSetupCurrent(setup) },
        taskContext = taskContext
    )

    private fun isSetupCurrent(setup: WifiDirectSetupRecoveryGate.Session): Boolean =
        running && setupRecoveryGate.isCurrent(setup)

    private fun discoverAction(
        message: String,
        onFailed: () -> Unit = {},
        onSuccess: () -> Unit = {},
        taskContext: TargetedTaskContext? = currentTargetContext()
    ) = action(
        message = message,
        onFailed = onFailed,
        onSuccess = onSuccess,
        onBusy = {
            if (!isCapturedTaskCurrent(taskContext)) return@action
            Log.w(TAG, "discover BUSY retry discoverServices")
            resetTunnelOnly(taskContext)
            val delay = boundedTaskDelay(taskContext, BUSY_RETRY_DELAY_MS) ?: return@action
            mainHandler.postDelayed(
                { if (isCapturedTaskCurrent(taskContext)) discoverPeers() },
                delay
            )
        },
        isCurrent = {
            running && setupRecoveryGate.isEnabled && isCapturedTaskCurrent(taskContext)
        },
        taskContext = taskContext
    )

    private fun isCapturedTaskCurrent(taskContext: TargetedTaskContext?): Boolean =
        if (taskContext == null) targetAttempt == null
        else isTargetedContextCurrent(taskContext)

    private fun action(
        message: String,
        onFailed: () -> Unit = {},
        onSuccess: () -> Unit = {},
        onBusy: () -> Unit = {
            resetTunnelOnly()
            mainHandler.postDelayed({ if (running) discoverPeers() }, BUSY_RETRY_DELAY_MS)
        },
        isCurrent: () -> Boolean = { true },
        taskContext: TargetedTaskContext? = null
    ) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (isCurrent()) onSuccess()
            }

            override fun onFailure(reason: Int) {
                if (!isCurrent()) return
                onFailed()
                val attempt = taskContext?.attempt
                if (shouldReportSequentialFallback(reason == WifiP2pManager.BUSY, attempt)) {
                    onTargetedOverlapUnavailable(requireNotNull(attempt))
                }
                if (reason == WifiP2pManager.BUSY) {
                    postError(IllegalStateException(BUSY_STATUS), taskContext)
                    onBusy()
                } else {
                    postError(
                        IllegalStateException("$message: ${reasonText(reason)}"),
                        taskContext
                    )
                }
            }
        }

    private fun postTransportReady(
        generation: Int,
        taskContext: TargetedTaskContext?,
        expectedTargetLock: TargetLock,
        physicalRole: PhysicalSocketRole,
        socket: Socket
    ) {
        val session = establishWifiDirectSignalingSession(
            socket = socket,
            establish = {
                SignalingSessionV2.establish(
                    socket = socket,
                    transport = Transport.WIFI_DIRECT,
                    physicalRole = physicalRole,
                    openedAtElapsedMs = monotonicClock.now().elapsedRealtimeMs,
                    localDeviceId = localDeviceId,
                    localRuntimeSessionId = sessionId,
                    localNickname = localNickname,
                    localDeviceName = localDeviceName,
                    originatingAttempt = taskContext?.attempt,
                    expectedRemoteTargetLock = expectedTargetLock,
                    monotonicClock = monotonicClock
                )
            },
            onFailure = { failure ->
                postTransportFailure(
                    generation,
                    taskContext,
                    failure as? IOException
                        ?: IOException("Wi-Fi Direct signaling HELLO failed", failure)
                )
            }
        ) ?: return
        mainHandler.post {
            if (
                taskContext != null &&
                isTargetedContextIdentityCurrent(taskContext) &&
                !isTargetedContextCurrent(taskContext)
            ) {
                session.close()
                removeGroupAndRediscover(
                    "P2P attempt budget expired before Socket handoff",
                    taskContext = taskContext
                )
                return@post
            }
            if (
                !isTransportCurrent(generation) ||
                (taskContext != null && !isTargetedContextCurrent(taskContext)) ||
                (taskContext == null && targetAttempt != null) ||
                session.isClosed
            ) {
                session.close()
                return@post
            }

            state = State.SIGNALING_READY
            connectingAddress = null
            cancelConnectWatchdog()
            try {
                onControlChannelReady(session)
            } catch (t: Throwable) {
                session.close()
                if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                    postError(t, taskContext)
                    removeGroupAndRediscover(
                        "signaling socket handoff failure",
                        taskContext = taskContext
                    )
                }
            }
        }
    }

    private fun postTransportFailure(
        generation: Int,
        taskContext: TargetedTaskContext?,
        error: IOException
    ) {
        mainHandler.post {
            if (
                taskContext != null &&
                isTargetedContextIdentityCurrent(taskContext) &&
                !isTargetedContextCurrent(taskContext)
            ) {
                removeGroupAndRediscover(
                    "P2P attempt budget expired after Socket failure",
                    taskContext = taskContext
                )
                return@post
            }
            if (
                !isTransportCurrent(generation) ||
                (taskContext != null && !isTargetedContextCurrent(taskContext)) ||
                (taskContext == null && targetAttempt != null)
            ) return@post
            postError(error, taskContext)
            removeGroupAndRediscover("signaling socket failure", taskContext = taskContext)
        }
    }

    private fun isTransportCurrent(generation: Int): Boolean =
        running && generation == socketTransportGeneration &&
            socketTransport != null && state == State.GROUP_READY

    private fun postError(t: Throwable, taskContext: TargetedTaskContext? = null) {
        mainHandler.post {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) onError(t)
        }
    }

    private fun postDiscoveryStatus(
        message: String,
        taskContext: TargetedTaskContext? = null
    ) {
        mainHandler.post {
            if (taskContext == null || isTargetedContextCurrent(taskContext)) {
                onDiscoveryStatus(message)
            }
        }
    }

    private fun logPeer(device: WifiP2pDevice, accepted: Boolean, reason: String) {
        Log.d(
            TAG,
            "P2P peer ${if (accepted) "ACCEPT" else "REJECT"}: ${peerSummary(device)} reason=$reason"
        )
    }

    private fun logPeerPending(device: WifiP2pDevice, reason: String) {
        Log.d(TAG, "P2P peer PENDING: ${peerSummary(device)} reason=$reason")
    }

    private fun peerSummary(device: WifiP2pDevice): String =
        "name=${device.deviceName} address=${device.deviceAddress} status=${device.status}"

    private fun groupSummary(group: WifiP2pGroup): String =
        "owner=${group.owner?.let(::peerSummary)} networkName=${group.networkName} " +
            "interface=${group.`interface`} clients=${group.clientList.map { it.deviceAddress }}"

    private fun sameAddress(left: String?, right: String?): Boolean =
        !left.isNullOrBlank() && !right.isNullOrBlank() && left.equals(right, ignoreCase = true)

    private fun normalizedAddress(address: String): String = address.uppercase()

    private fun localP2pIp(interfaceName: String?): String? =
        localP2pAddress(interfaceName)?.hostAddress

    private fun localP2pAddress(interfaceName: String?): Inet4Address? {
        return try {
            val interfaces = if (interfaceName.isNullOrBlank()) {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.name.startsWith("p2p") }
            } else {
                listOfNotNull(NetworkInterface.getByName(interfaceName))
            }
            interfaces.asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
        } catch (_: Throwable) {
            null
        }
    }

    private fun remoteOnP2pInterface(
        remoteAddress: InetAddress,
        localAddress: InetAddress,
        interfaceName: String?
    ): Boolean {
        if (remoteAddress.isAnyLocalAddress || remoteAddress.isLoopbackAddress) return false
        val networkInterface = interfaceName?.let {
            runCatching { NetworkInterface.getByName(it) }.getOrNull()
        } ?: return false
        val prefixLength = networkInterface.interfaceAddresses
            .firstOrNull { it.address == localAddress }
            ?.networkPrefixLength
            ?.toInt()
            ?: return false
        return sameNetwork(localAddress.address, remoteAddress.address, prefixLength)
    }

    private fun sameNetwork(left: ByteArray, right: ByteArray, prefixLength: Int): Boolean {
        if (left.size != right.size || prefixLength !in 1..(left.size * 8)) return false
        val fullBytes = prefixLength / 8
        for (index in 0 until fullBytes) {
            if (left[index] != right[index]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = 0xFF shl (8 - remainingBits) and 0xFF
        return left[fullBytes].toInt() and mask == right[fullBytes].toInt() and mask
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 P2P"
        WifiP2pManager.BUSY -> "系统 Wi-Fi P2P 忙"
        WifiP2pManager.ERROR -> "系统 Wi-Fi P2P 错误"
        else -> "未知错误 $reason"
    }

    private inline fun <reified T : Parcelable> Intent.parcelableCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? T
        }
    }

    companion object {
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3_000
        private const val CONNECT_WATCHDOG_MS = 12_000L
        private const val SOCKET_RETRY_DELAY_MS = 500L
        private const val BUSY_RETRY_DELAY_MS = 1_000L
        private const val REMOVE_GROUP_BUSY_RETRY_COUNT = 3
        private const val GROUP_REMOVAL_SETTLE_MS = 500L
        private const val GROUP_VALIDATION_RETRY_MS = 500L
        private const val GROUP_VALIDATION_RETRIES = 20
        private const val GROUP_IDENTITY_VALIDATION_RETRIES = 60
        private const val GROUP_IDENTITY_VALIDATION_TIMEOUT_MS = 30_000L
        private const val PENDING_DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val PENDING_DISCOVERY_RETRY_COUNT = 4
        private const val BUSY_STATUS = "无线占用中，正在自动复位重试..."
        private const val NO_MOTOCOM_PEER_STATUS = "发现附近 P2P 设备，但未发现 MotoCom 车友"
        private const val TAG = "MotoComP2P"
        private const val APP_ID = "MotoCom"
        private const val PROTOCOL_VERSION = "2"
        private const val SERVICE_TYPE = "_motocom._tcp"
        private const val TXT_APP_ID = "appId"
        private const val TXT_PROTOCOL_VERSION = "protocolVersion"
        private const val TXT_DEVICE_ID = "deviceId"
        private const val TXT_NICKNAME = "nickname"
        private const val TXT_DEVICE_NAME = "deviceName"
        private const val TXT_SESSION_ID = "sessionId"

        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        fun hasRequiredPermissions(context: Context): Boolean {
            return requiredPermissions().all {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}

internal fun establishWifiDirectSignalingSession(
    socket: Socket,
    establish: () -> SignalingSessionV2,
    onFailure: (Throwable) -> Unit
): SignalingSessionV2? = try {
    establish()
} catch (failure: Throwable) {
    runCatching { socket.close() }
    onFailure(failure)
    null
}

internal fun shouldReportSequentialFallback(
    busy: Boolean,
    attempt: ConnectionAttempt?
): Boolean = busy &&
    attempt?.preferredTransport == Transport.LAN &&
    attempt.channelPlan.fallbackTransport == Transport.WIFI_DIRECT
