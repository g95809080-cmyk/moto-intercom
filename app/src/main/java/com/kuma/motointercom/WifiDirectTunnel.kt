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
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID

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
class WifiDirectTunnel(
    context: Context,
    private val onTunnelReady: (targetIp: String, isServer: Boolean, signalingSocket: Socket) -> Unit,
    private val signalingPort: Int = 8888,
    private val autoConnect: Boolean = true,
    private val localNickname: String = "骑士",
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit = {},
    private val onDiscoveryStatus: (String) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {

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
    private val peerDevices = linkedMapOf<String, WifiP2pDevice>()
    private var pendingRetryAttempt = 0
    private var pendingRetryGeneration = 0
    private var pendingRetryScheduled = false
    private var connectWatchdogGeneration = 0
    private var serviceDiscoveryReady = false
    private val sessionId = UUID.randomUUID().toString()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        postError(IllegalStateException("Wi-Fi Direct 未开启"))
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
                        resetTunnelOnly()
                        state = State.DISCOVERING
                        if (running) mainHandler.post { onDisconnected() }
                        if (running && !removingGroup) discoverPeers()
                    }
                }
            }
        }
    }

    fun start() {
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
        if (!running) return
        if (!serviceDiscoveryReady) {
            Log.d(TAG, "discoverServices skipped: service request not ready")
            return
        }
        val m = manager ?: return
        val c = channel ?: return

        try {
            val peers = peerRegistry.snapshot()
            Log.d(TAG, "discoverServices start pending=${peers.pending.size} accepted=${peers.accepted.size}")
            m.discoverServices(
                c,
                discoverAction(
                    "开始搜索 MotoCom 服务失败",
                    onFailed = { Log.w(TAG, "discoverServices failure") },
                    onSuccess = { Log.d(TAG, "discoverServices success") }
                )
            )
        } catch (t: Throwable) {
            postError(t)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (!running) return
        val address = normalizedAddress(device.deviceAddress)
        if (!peerRegistry.isAccepted(address)) {
            logPeer(device, accepted = false, reason = "未发布 MotoCom DNS-SD 身份")
            postDiscoveryStatus(NO_MOTOCOM_PEER_STATUS)
            return
        }
        if (connectingAddress == address || state == State.SIGNALING_READY) return

        val m = manager ?: return
        val c = channel ?: return
        connectingAddress = address
        startConnectWatchdog(address)

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        try {
            cancelPendingRetry()
            Log.d(TAG, "connect start selectedPeer=${peerSummary(device)}")
            state = State.P2P_CONNECTING
            m.connect(
                c,
                config,
                action("连接 ${device.deviceName} 失败", onFailed = {
                    cancelConnectWatchdog()
                    connectingAddress = null
                    state = State.DISCOVERING
                })
            )
        } catch (t: Throwable) {
            cancelConnectWatchdog()
            connectingAddress = null
            state = State.DISCOVERING
            postError(t)
        }
    }

    override fun close() {
        state = State.CLOSED
        running = false
        resetTunnelOnly()
        unregisterReceiver()
        try {
            serviceRequest?.let { request -> manager?.removeServiceRequest(channel, request, null) }
            manager?.clearLocalServices(channel, null)
        } catch (_: Throwable) {
        }
        serviceRequest = null
        serviceDiscoveryReady = false
        peerRegistry.reset()
        peerDevices.clear()
        cancelPendingRetry()
        cancelConnectWatchdog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                channel?.close()
            } catch (_: Throwable) {
            }
        }
        channel = null
        manager = null
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
                initP2p()
                clearUntrustedGroupBeforeDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearUntrustedGroupBeforeDiscovery() {
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.requestGroupInfo(c) { group ->
                if (!running) return@requestGroupInfo
                if (group == null) {
                    cancelPendingConnectThenDiscover()
                } else {
                    Log.w(TAG, "启动时发现未验证 P2P group，主动清理: ${groupSummary(group)}")
                    removeGroupAndRediscover("启动时存在未验证 P2P group")
                }
            }
        } catch (t: Throwable) {
            postError(t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelPendingConnectThenDiscover() {
        val m = manager ?: return
        val c = channel ?: return
        removingGroup = true
        val finish: () -> Unit = {
            removingGroup = false
            mainHandler.postDelayed(
                { if (running) setupServiceDiscovery() },
                GROUP_REMOVAL_SETTLE_MS
            )
            Unit
        }
        try {
            m.cancelConnect(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = finish()
                override fun onFailure(reason: Int) = finish()
            })
        } catch (_: Throwable) {
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupServiceDiscovery() {
        if (!running) return
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
            TXT_NICKNAME to localNickname.ifBlank { "骑士" },
            TXT_SESSION_ID to sessionId
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "$SERVICE_INSTANCE_PREFIX-${sessionId.take(8)}",
            SERVICE_TYPE,
            record
        )
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        serviceRequest = request

        try {
            m.clearLocalServices(c, setupAction("清理本机 P2P 服务失败", onSuccess = {
                m.addLocalService(c, serviceInfo, setupAction("发布 MotoCom P2P 服务失败", onSuccess = {
                    Log.d(TAG, "local service publish success: $record")
                    m.clearServiceRequests(c, setupAction("清理 P2P 服务请求失败", onSuccess = {
                        m.addServiceRequest(c, request, setupAction("添加 MotoCom 服务请求失败", onSuccess = {
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

        acceptPeer(
            device,
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
        val validInstance = instanceName.startsWith("$SERVICE_INSTANCE_PREFIX-")
        val validType = registrationType.startsWith("$SERVICE_TYPE.")
        if (!validInstance || !validType) {
            logPeer(
                device,
                accepted = false,
                reason = "DNS-SD 服务标识不匹配 instance=$instanceName type=$registrationType"
            )
            return
        }

        // 部分小米系统不回调 TXT；精确服务类型和实例前缀仍是 App 专属身份。
        acceptPeer(device, "MotoCom DNS-SD 服务校验通过 instance=$instanceName type=$registrationType")
    }

    private fun acceptPeer(device: WifiP2pDevice, reason: String) {
        if (device.deviceAddress.isBlank()) return

        val address = normalizedAddress(device.deviceAddress)
        peerDevices[address] = device
        val wasPending = address in peerRegistry.snapshot().pending
        val snapshot = peerRegistry.accept(address)
        Log.d(TAG, "peer accepted: ${peerSummary(device)} reason=$reason pendingBefore=$wasPending")
        if (wasPending) {
            Log.d(TAG, "pending -> accepted: ${peerSummary(device)} pending=${snapshot.pending.size}")
        }
        logPeer(device, accepted = true, reason = reason)
        val selectedPeer = snapshot.selected?.let(peerDevices::get)
        if (snapshot.selected == address) {
            Log.d(TAG, "selectedPeer=${peerSummary(device)}")
        }
        mainHandler.post { onPeersChanged(snapshot.accepted.mapNotNull(peerDevices::get)) }
        if (autoConnect && connectingAddress == null && state != State.SIGNALING_READY) {
            cancelPendingRetry()
            selectedPeer?.let {
                Log.d(TAG, "accepted peer triggers connect: ${peerSummary(it)}")
                connect(it)
            }
        }
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
                val selectedPeer = snapshot.selected?.let(peerDevices::get)
                val motoComPeers = snapshot.accepted.mapNotNull(peerDevices::get)
                selectedPeer?.let { Log.d(TAG, "selectedPeer=${peerSummary(it)}") }
                mainHandler.post { onPeersChanged(motoComPeers) }
                if (current.isNotEmpty() && motoComPeers.isEmpty()) {
                    postDiscoveryStatus(NO_MOTOCOM_PEER_STATUS)
                }
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

        try {
            m.requestConnectionInfo(c) { info -> handleConnectionInfo(info) }
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!running) return
        if (!info.groupFormed) {
            resetTunnelOnly()
            state = State.DISCOVERING
            mainHandler.post { onDisconnected() }
            discoverPeers()
            return
        }
        if (tunnelStarted || validatingGroup) return

        validatingGroup = true
        requestValidatedGroup(info, attempt = 0)
    }

    @SuppressLint("MissingPermission")
    private fun requestValidatedGroup(info: WifiP2pInfo, attempt: Int) {
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.requestGroupInfo(c) { group ->
                if (!running) return@requestGroupInfo
                if (group == null) {
                    if (attempt < GROUP_VALIDATION_RETRIES) {
                        mainHandler.postDelayed(
                            { requestValidatedGroup(info, attempt + 1) },
                            GROUP_VALIDATION_RETRY_MS
                        )
                    } else {
                        rejectCurrentGroup("groupFormed=true 但 requestGroupInfo 为空")
                    }
                    return@requestGroupInfo
                }
                validateGroup(info, group, attempt)
            }
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun validateGroup(info: WifiP2pInfo, group: WifiP2pGroup, attempt: Int) {
        val target = peerRegistry.snapshot().selected?.let(peerDevices::get)
        val targetAddress = target?.deviceAddress
        val ownerDeviceAddress = group.owner?.deviceAddress
        val clientAddresses = group.clientList.map { it.deviceAddress }
        val targetMatches = if (info.isGroupOwner) {
            targetAddress != null && clientAddresses.size == 1 &&
                sameAddress(clientAddresses.single(), targetAddress)
        } else {
            targetAddress != null && sameAddress(ownerDeviceAddress, targetAddress)
        }

        Log.d(
            TAG,
            "校验 P2P group: selectedPeer=${target?.let(::peerSummary)} " +
                "groupOwner=${group.owner?.let(::peerSummary)} networkName=${group.networkName} " +
                "interface=${group.`interface`} clients=$clientAddresses " +
                "localP2pIp=${localP2pIp(group.`interface`)} isGroupOwner=${info.isGroupOwner} " +
                "targetMatches=$targetMatches attempt=$attempt"
        )

        if (!targetMatches && attempt < GROUP_VALIDATION_RETRIES) {
            mainHandler.postDelayed(
                { requestValidatedGroup(info, attempt + 1) },
                GROUP_VALIDATION_RETRY_MS
            )
            return
        }
        if (!targetMatches) {
            validatingGroup = false
            rejectCurrentGroup(
                "group owner/client 与 selectedPeer 不匹配: owner=$ownerDeviceAddress " +
                    "clients=$clientAddresses target=$targetAddress networkName=${group.networkName}"
            )
            return
        }

        val ownerAddress = info.groupOwnerAddress
        if (ownerAddress == null) {
            postError(IllegalStateException("未获取到组长 IP"))
            removeGroupAndRediscover("P2P group 没有组长 IP")
            return
        }

        val localAddress = localP2pAddress(group.`interface`)
        if (localAddress == null) {
            postError(IllegalStateException("未获取到本机 P2P 接口 IP"))
            removeGroupAndRediscover("P2P 接口没有可用 IPv4 地址")
            return
        }

        validatingGroup = false
        tunnelStarted = true
        state = State.GROUP_READY
        Log.d(
            TAG,
            "MotoCom P2P group 校验通过: groupOwner=$ownerAddress networkName=${group.networkName} " +
                "interface=${group.`interface`} localP2pIp=${localAddress.hostAddress} " +
                "remoteTargetIp=${ownerAddress.hostAddress}"
        )
        startSocketTransport(info, group, localAddress, ownerAddress, targetAddress)
    }

    private fun rejectCurrentGroup(reason: String) {
        Log.w(TAG, "检测到外部/错误 P2P group，禁止启动 TCP 并清理: $reason")
        postDiscoveryStatus("检测到非 MotoCom P2P 组，正在清理并重新搜索")
        removeGroupAndRediscover(reason)
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRediscover(reason: String) {
        removeGroupAndRediscover(reason, attempt = 1)
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRediscover(reason: String, attempt: Int) {
        if (removingGroup) return
        val m = manager ?: return recoverAfterGroupRemovalFailure()
        val c = channel ?: return recoverAfterGroupRemovalFailure()
        removingGroup = true
        resetTunnelOnly()
        cancelConnectWatchdog()
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "已清理误连 P2P group: $reason")
                    finishGroupRemoval()
                }

                override fun onFailure(code: Int) {
                    Log.w(TAG, "removeGroup 失败: reason=$reason code=${reasonText(code)}")
                    removingGroup = false
                    if (!running) return
                    if (code == WifiP2pManager.BUSY) {
                        if (attempt < REMOVE_GROUP_BUSY_RETRY_COUNT) {
                            Log.w(
                                TAG,
                                "removeGroup BUSY retry attempt=${attempt + 1}/$REMOVE_GROUP_BUSY_RETRY_COUNT " +
                                    "reason=$reason"
                            )
                            mainHandler.postDelayed(
                                { if (running) removeGroupAndRediscover(reason, attempt + 1) },
                                BUSY_RETRY_DELAY_MS
                            )
                        } else {
                            Log.w(TAG, "removeGroup BUSY exhausted, rediscover anyway: reason=$reason")
                            resetDiscoveryCandidates()
                            state = State.DISCOVERING
                            mainHandler.postDelayed(
                                { if (running) setupServiceDiscovery() },
                                GROUP_REMOVAL_SETTLE_MS
                            )
                        }
                        return
                    }

                    postError(IllegalStateException("清理错误 P2P group 失败: ${reasonText(code)}"))
                    recoverAfterGroupRemovalFailure()
                }
            })
        } catch (t: Throwable) {
            removingGroup = false
            postError(t)
            recoverAfterGroupRemovalFailure()
        }
    }

    private fun recoverAfterGroupRemovalFailure() {
        removingGroup = false
        if (!running) return
        connectingAddress = null
        resetDiscoveryCandidates()
        state = State.DISCOVERING
        mainHandler.postDelayed(
            { if (running) clearUntrustedGroupBeforeDiscovery() },
            BUSY_RETRY_DELAY_MS
        )
    }

    private fun finishGroupRemoval() {
        removingGroup = false
        if (!running) return
        connectingAddress = null
        resetDiscoveryCandidates()
        state = State.DISCOVERING
        mainHandler.postDelayed(
            { if (running) setupServiceDiscovery() },
            GROUP_REMOVAL_SETTLE_MS
        )
    }

    private fun startSocketTransport(
        info: WifiP2pInfo,
        group: WifiP2pGroup,
        localAddress: InetAddress,
        ownerAddress: InetAddress,
        selectedAddress: String?
    ) {
        socketTransport?.close()
        val generation = ++socketTransportGeneration
        val transport = WifiDirectSignalingSocket(
            port = signalingPort,
            readyTimeoutMillis = CONNECT_WATCHDOG_MS,
            connectTimeoutMillis = SOCKET_CONNECT_TIMEOUT_MS,
            retryDelayMillis = SOCKET_RETRY_DELAY_MS,
            isSessionCurrent = {
                running && generation == socketTransportGeneration && state == State.GROUP_READY
            },
            onReady = { ip, server, socket ->
                postTransportReady(generation, ip, server, socket)
            },
            onFailure = { error -> postTransportFailure(generation, error) }
        )
        socketTransport = transport

        if (info.isGroupOwner) {
            val hasOnlySelectedClient = group.clientList.size == 1 &&
                sameAddress(group.clientList.single().deviceAddress, selectedAddress)
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

    private fun resetTunnelOnly() {
        tunnelStarted = false
        validatingGroup = false
        connectingAddress = null
        cancelConnectWatchdog()
        socketTransportGeneration++
        socketTransport?.close()
        socketTransport = null
    }

    private fun resetDiscoveryCandidates() {
        cancelPendingRetry()
        serviceDiscoveryReady = false
        peerRegistry.reset()
        peerDevices.clear()
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

    private fun startConnectWatchdog(peerAddress: String) {
        val generation = ++connectWatchdogGeneration
        mainHandler.postDelayed({
            if (!running || generation != connectWatchdogGeneration) return@postDelayed
            if (state == State.SIGNALING_READY || connectingAddress != peerAddress) {
                return@postDelayed
            }

            Log.w(TAG, "P2P connect timeout: peer=$peerAddress")
            connectingAddress = null
            peerRegistry.reset()
            peerDevices.clear()
            serviceDiscoveryReady = false
            cancelPendingRetry()
            Log.w(TAG, "P2P connect timeout cleanup: peer=$peerAddress")
            Log.d(TAG, "reset discovery candidates after connect timeout")
            removeGroupAndRediscover("P2P connect timeout")
        }, CONNECT_WATCHDOG_MS)
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogGeneration++
    }

    private fun setupAction(
        message: String,
        onFailed: () -> Unit = {},
        onSuccess: () -> Unit = {}
    ) = action(
        message = message,
        onFailed = onFailed,
        onSuccess = onSuccess,
        onBusy = {
            Log.w(TAG, "setup BUSY retry setupServiceDiscovery")
            resetTunnelOnly()
            mainHandler.postDelayed({ if (running) setupServiceDiscovery() }, BUSY_RETRY_DELAY_MS)
        }
    )

    private fun discoverAction(
        message: String,
        onFailed: () -> Unit = {},
        onSuccess: () -> Unit = {}
    ) = action(
        message = message,
        onFailed = onFailed,
        onSuccess = onSuccess,
        onBusy = {
            Log.w(TAG, "discover BUSY retry discoverServices")
            resetTunnelOnly()
            mainHandler.postDelayed({ if (running) discoverPeers() }, BUSY_RETRY_DELAY_MS)
        }
    )

    private fun action(
        message: String,
        onFailed: () -> Unit = {},
        onSuccess: () -> Unit = {},
        onBusy: () -> Unit = {
            resetTunnelOnly()
            mainHandler.postDelayed({ if (running) discoverPeers() }, BUSY_RETRY_DELAY_MS)
        }
    ) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onSuccess()

            override fun onFailure(reason: Int) {
                onFailed()
                if (reason == WifiP2pManager.BUSY) {
                    postError(IllegalStateException(BUSY_STATUS))
                    onBusy()
                } else {
                    postError(IllegalStateException("$message: ${reasonText(reason)}"))
                }
            }
        }

    private fun postTransportReady(
        generation: Int,
        targetIp: String,
        isServer: Boolean,
        socket: Socket
    ) {
        mainHandler.post {
            if (!isTransportCurrent(generation) || !socket.isConnected || socket.isClosed) {
                runCatching { socket.close() }
                return@post
            }

            state = State.SIGNALING_READY
            connectingAddress = null
            cancelConnectWatchdog()
            try {
                onTunnelReady(targetIp, isServer, socket)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                postError(t)
                removeGroupAndRediscover("signaling socket handoff failure")
            }
        }
    }

    private fun postTransportFailure(generation: Int, error: IOException) {
        mainHandler.post {
            if (!isTransportCurrent(generation)) return@post
            postError(error)
            removeGroupAndRediscover("signaling socket failure")
        }
    }

    private fun isTransportCurrent(generation: Int): Boolean =
        running && generation == socketTransportGeneration &&
            socketTransport != null && state == State.GROUP_READY

    private fun postError(t: Throwable) {
        mainHandler.post { onError(t) }
    }

    private fun postDiscoveryStatus(message: String) {
        mainHandler.post { onDiscoveryStatus(message) }
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
        private const val PENDING_DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val PENDING_DISCOVERY_RETRY_COUNT = 4
        private const val BUSY_STATUS = "无线占用中，正在自动复位重试..."
        private const val NO_MOTOCOM_PEER_STATUS = "发现附近 P2P 设备，但未发现 MotoCom 车友"
        private const val TAG = "MotoComP2P"
        private const val APP_ID = "MotoCom"
        private const val PROTOCOL_VERSION = "1"
        private const val SERVICE_INSTANCE_PREFIX = "MotoCom"
        private const val SERVICE_TYPE = "_motocom._tcp"
        private const val TXT_APP_ID = "appId"
        private const val TXT_PROTOCOL_VERSION = "protocolVersion"
        private const val TXT_NICKNAME = "nickname"
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
