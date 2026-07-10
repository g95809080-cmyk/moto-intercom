package com.kuma.motointercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 后台免死对讲服务。
 *
 * Activity 只做遥控器；蓝牙 SCO、Wi-Fi Direct、WebRTC 信令全部由前台服务托管，
 * 锁屏和退到后台时不跟着 Activity 一起释放。
 */
class IntercomService : Service() {

    data class LanRiderDevice(
        val id: String,
        val name: String,
        val ip: String,
        val port: Int
    )

    interface Listener {
        fun onStatusChanged(status: String, running: Boolean)
        fun onAudioSourceChanged(status: String, bluetooth: Boolean) = Unit
        fun onLanDevicesChanged(devices: List<LanRiderDevice>) = Unit
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

    private var listener: Listener? = null
    private var audioRouteController: AudioRouteController? = null
    private var wifiTunnel: WifiDirectTunnel? = null
    private var intercomManager: IntercomManager? = null
    private var lanExecutor: ExecutorService? = null
    private val lanUdpSocket = AtomicReference<DatagramSocket?>()
    private val lanServerSocket = AtomicReference<ServerSocket?>()
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdServiceName = ""
    private val lanDevices = linkedMapOf<String, LanRiderDevice>()

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
    private var lanNodeId = UUID.randomUUID().toString()
    private var activeSession: SessionGeneration.Token? = null
    private val tunnelChosen = AtomicLong(NO_SESSION_TOKEN)
    private val lanClientConnecting = AtomicLong(NO_SESSION_TOKEN)

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
        super.onDestroy()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onStatusChanged(lastStatus, running)
        listener?.onAudioSourceChanged(audioSourceStatus, audioSourceBluetooth)
        listener?.onLanDevicesChanged(lanDevicesSnapshot())
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

    fun connectToLanDevice(device: LanRiderDevice) {
        mainHandler.post { connectToLanDeviceOnMain(device) }
    }

    private fun connectToLanDeviceOnMain(device: LanRiderDevice) {
        val token = activeSession ?: return
        if (tunnelChosen.get() != NO_SESSION_TOKEN) return
        val executor = lanExecutor ?: Executors.newCachedThreadPool().also { lanExecutor = it }
        if (!claimLanClient(token)) return

        publishStatus(PEER_FOUND_STATUS)
        publishLog("正在点名连接车友：${device.name} / ${device.ip}")
        executor.execute {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(device.ip, device.port), LAN_CONNECT_TIMEOUT_MS)
                val connected = socket
                socket = null
                acceptTunnel(
                    token,
                    device.ip,
                    isServer = false,
                    signalingSocket = connected,
                    closeWifiDirect = true
                )
            } catch (t: Throwable) {
                releaseLanClient(token)
                postForSession(token) {
                    if (tunnelChosen.get() == NO_SESSION_TOKEN) handleError(t)
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: IOException) {
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
        activeSession = token
        running = true
        bluetoothReady = false
        physicalLinkReady = false
        remoteRiderName = null
        localRiderName = ""
        lanNodeId = UUID.randomUUID().toString()
        tunnelChosen.set(NO_SESSION_TOKEN)
        lanClientConnecting.set(NO_SESSION_TOKEN)
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(SEARCHING_STATUS)

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

        publishStatus(SEARCHING_STATUS)
        wifiTunnel = WifiDirectTunnel(
            context = this,
            onTunnelReady = { targetIp, isServer, socket ->
                onTunnelReady(token, targetIp, isServer, socket)
            },
            localNickname = requestedRiderName.ifBlank { "骑士" },
            onPeersChanged = {
                postForSession(token) {
                    publishLog("发现附近设备：${it.size}")
                    if (it.isNotEmpty() && !physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
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
        startLanDiscovery(token)
        startNsdDiscovery(token)
    }

    private fun onTunnelReady(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket
    ) {
        acceptTunnel(token, targetIp, isServer, signalingSocket, closeWifiDirect = false)
    }

    private fun acceptTunnel(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ): Boolean {
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
            activateTunnel(token, targetIp, isServer, signalingSocket, closeWifiDirect)
        }
        return true
    }

    private fun activateTunnel(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ) {
        stopLanDiscovery()
        if (closeWifiDirect) {
            try {
                wifiTunnel?.close()
            } catch (t: Throwable) {
                handleError(t)
            }
            wifiTunnel = null
        }

        physicalLinkReady = true
        mediaConnected = false
        publishStatus(SIGNALING_CONNECTED_STATUS)
        localRiderName = requestedRiderName.ifBlank { if (isServer) "骑士A" else "骑士B" }
        publishLog("本机骑士昵称：$localRiderName")

        intercomManager = IntercomManager(
            context = this,
            signalingSocket = signalingSocket,
            isServer = isServer,
            localRiderName = localRiderName,
            onIntercomDisconnected = { onIntercomDisconnected(token, it) },
            onConnectionStateChanged = { onConnectionStateChanged(token, it) },
            onRemoteRiderIdentified = { onRemoteRiderIdentified(token, it) },
            onAudioLevelChanged = { onAudioLevelChanged(token, it) },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also {
            publishStatus(MEDIA_INITIALIZING_STATUS)
            it.start()
        }

        updateStageStatus()
    }

    private fun onConnectionStateChanged(
        token: SessionGeneration.Token,
        state: PeerConnection.PeerConnectionState
    ) {
        postForSession(token) {
            publishLog("WebRTC 状态：$state")
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

    private fun startLanDiscovery(token: SessionGeneration.Token) {
        if (!isSessionCurrent(token)) return
        val localIp = localWifiIp() ?: return
        lanExecutor = Executors.newCachedThreadPool()
        lanExecutor?.execute { runLanTcpServer(token) }
        lanExecutor?.execute { runLanUdpListener(token, localIp) }
        lanExecutor?.execute { runLanUdpBroadcaster(token, localIp) }
    }

    private fun startNsdDiscovery(token: SessionGeneration.Token) {
        if (!isSessionCurrent(token)) return
        if (localWifiIp() == null) return
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        nsdServiceName = "MotoCom-${lanNodeId.take(8)}"
        val riderName = requestedRiderName.ifBlank { "骑士" }

        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                postForSession(token) {
                    nsdServiceName = info.serviceName
                    publishLog("局域网服务已上线：$nsdServiceName")
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                postForSession(token) { publishLog("局域网服务注册失败：$errorCode") }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                postForSession(token) { publishLog("局域网扫描启动失败：$errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                postForSession(token) {
                    if (info.serviceType != NSD_SERVICE_TYPE || info.serviceName == nsdServiceName) {
                        return@postForSession
                    }
                    resolveNsdService(token, info)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                postForSession(token) { removeLanDevice(info.serviceName) }
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = nsdServiceName
            serviceType = NSD_SERVICE_TYPE
            port = LAN_TCP_PORT
            setAttribute("id", lanNodeId)
            setAttribute("name", riderName)
        }

        try {
            val registration = nsdRegistrationListener ?: return
            val discovery = nsdDiscoveryListener ?: return
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registration)
            manager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (t: Throwable) {
            if (isSessionCurrent(token)) handleError(t)
        }
    }

    private fun resolveNsdService(token: SessionGeneration.Token, info: NsdServiceInfo) {
        if (!isSessionCurrent(token)) return
        val manager = nsdManager ?: return
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    postForSession(token) { publishLog("局域网设备解析失败：$errorCode") }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    postForSession(token) {
                        val id = serviceInfo.attributeString("id").ifBlank { serviceInfo.serviceName }
                        if (id == lanNodeId) return@postForSession
                        val ip = serviceInfo.resolvedHostAddress() ?: return@postForSession
                        val name = serviceInfo.attributeString("name").ifBlank { serviceInfo.serviceName }
                        rememberLanDevice(
                            LanRiderDevice(
                                id = id,
                                name = name,
                                ip = ip,
                                port = serviceInfo.port.takeIf { it > 0 } ?: LAN_TCP_PORT
                            )
                        )
                    }
                }
            })
        } catch (t: Throwable) {
            postForSession(token) { publishLog("局域网设备解析异常：${t.message}") }
        }
    }

    private fun runLanTcpServer(token: SessionGeneration.Token) {
        var claimedServer: ServerSocket? = null
        val claimed = try {
            sessions.claimIfCurrent(token) {
                val candidate = ServerSocket()
                try {
                    candidate.reuseAddress = true
                    candidate.bind(InetSocketAddress(LAN_TCP_PORT))
                    if (lanServerSocket.compareAndSet(null, candidate)) {
                        claimedServer = candidate
                        true
                    } else {
                        candidate.close()
                        false
                    }
                } catch (t: Throwable) {
                    candidate.close()
                    throw t
                }
            }
        } catch (t: Throwable) {
            postForSession(token) {
                if (tunnelChosen.get() == NO_SESSION_TOKEN) handleError(t)
            }
            return
        }
        if (!claimed) return
        val server = claimedServer ?: return
        try {
            while (isSessionCurrent(token) && tunnelChosen.get() == NO_SESSION_TOKEN) {
                val socket = server.accept()
                val peerIp = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName
                if (acceptTunnel(
                        token,
                        peerIp,
                        isServer = true,
                        signalingSocket = socket,
                        closeWifiDirect = true
                    )
                ) {
                    postForSession(token) { publishLog("局域网链路已接入：$peerIp") }
                    return
                }
            }
        } catch (t: Throwable) {
            postForSession(token) {
                if (tunnelChosen.get() == NO_SESSION_TOKEN) handleError(t)
            }
        } finally {
            lanServerSocket.compareAndSet(server, null)
            try {
                server.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun runLanUdpListener(token: SessionGeneration.Token, localIp: String) {
        var claimedSocket: DatagramSocket? = null
        val claimed = try {
            sessions.claimIfCurrent(token) {
                val candidate = DatagramSocket(LAN_UDP_PORT)
                try {
                    candidate.broadcast = true
                    candidate.soTimeout = LAN_RECEIVE_TIMEOUT_MS
                    if (lanUdpSocket.compareAndSet(null, candidate)) {
                        claimedSocket = candidate
                        true
                    } else {
                        candidate.close()
                        false
                    }
                } catch (t: Throwable) {
                    candidate.close()
                    throw t
                }
            }
        } catch (t: Throwable) {
            postForSession(token) {
                if (tunnelChosen.get() == NO_SESSION_TOKEN) handleError(t)
            }
            return
        }
        if (!claimed) return
        val socket = claimedSocket ?: return
        try {
            val buffer = ByteArray(2048)
            while (isSessionCurrent(token) && tunnelChosen.get() == NO_SESSION_TOKEN) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    handleLanBroadcast(token, localIp, packet)
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (t: Throwable) {
            postForSession(token) {
                if (tunnelChosen.get() == NO_SESSION_TOKEN) handleError(t)
            }
        } finally {
            lanUdpSocket.compareAndSet(socket, null)
            socket.close()
        }
    }

    private fun runLanUdpBroadcaster(token: SessionGeneration.Token, localIp: String) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val target = InetAddress.getByName("255.255.255.255")
                while (isSessionCurrent(token) && tunnelChosen.get() == NO_SESSION_TOKEN) {
                    val name = requestedRiderName.ifBlank { "骑士" }
                    val bytes = JSONObject()
                        .put("type", "MOTOCOM_HELLO")
                        .put("id", lanNodeId)
                        .put("name", name)
                        .put("ip", localIp)
                        .put("tcpPort", LAN_TCP_PORT)
                        .toString()
                        .toByteArray(StandardCharsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, target, LAN_UDP_PORT))
                    Thread.sleep(LAN_BROADCAST_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            postForSession(token) {
                if (tunnelChosen.get() == NO_SESSION_TOKEN) handleError(t)
            }
        }
    }

    private fun handleLanBroadcast(
        token: SessionGeneration.Token,
        localIp: String,
        packet: DatagramPacket
    ) {
        if (!isSessionCurrent(token)) return
        val json = try {
            JSONObject(String(packet.data, 0, packet.length, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            return
        }
        if (json.optString("type") != "MOTOCOM_HELLO") return
        if (json.optString("id") == lanNodeId) return

        val peerIp = packet.address.hostAddress ?: json.optString("ip")
        if (peerIp == localIp || peerIp.isBlank()) return

        postForSession(token) {
            publishLog("发现同一 Wi-Fi 车友：${json.optString("name")} / $peerIp")
            if (!physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
        }
        if (compareIpv4(localIp, peerIp) <= 0) return
        if (!claimLanClient(token)) return

        lanExecutor?.execute {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(
                    InetSocketAddress(peerIp, json.optInt("tcpPort", LAN_TCP_PORT)),
                    LAN_CONNECT_TIMEOUT_MS
                )
                val connected = socket
                socket = null
                acceptTunnel(
                    token,
                    peerIp,
                    isServer = false,
                    signalingSocket = connected,
                    closeWifiDirect = true
                )
            } catch (t: Throwable) {
                releaseLanClient(token)
                postForSession(token) {
                    if (tunnelChosen.get() == NO_SESSION_TOKEN) {
                        publishLog("局域网连接失败：${t.message}")
                    }
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun stopLanDiscovery() {
        try {
            lanUdpSocket.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
        try {
            lanServerSocket.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
        lanExecutor?.shutdownNow()
        lanExecutor = null
    }

    private fun stopNsdDiscovery() {
        val manager = nsdManager
        val discovery = nsdDiscoveryListener
        val registration = nsdRegistrationListener
        try {
            if (manager != null && discovery != null) manager.stopServiceDiscovery(discovery)
        } catch (_: Throwable) {
        }
        try {
            if (manager != null && registration != null) manager.unregisterService(registration)
        } catch (_: Throwable) {
        }
        nsdDiscoveryListener = null
        nsdRegistrationListener = null
        nsdManager = null
        synchronized(lanDevices) {
            lanDevices.clear()
        }
        publishLanDevices()
    }

    private fun rememberLanDevice(device: LanRiderDevice) {
        synchronized(lanDevices) {
            lanDevices[device.id] = device
        }
        publishLog("发现局域网车友：${device.name} / ${device.ip}")
        if (!physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
        publishLanDevices()
    }

    private fun removeLanDevice(id: String) {
        synchronized(lanDevices) {
            lanDevices.remove(id)
        }
        publishLanDevices()
    }

    private fun publishLanDevices() {
        val snapshot = lanDevicesSnapshot()
        dispatchOnMain { listener?.onLanDevicesChanged(snapshot) }
    }

    private fun lanDevicesSnapshot(): List<LanRiderDevice> =
        synchronized(lanDevices) { lanDevices.values.toList() }

    private fun NsdServiceInfo.attributeString(key: String): String {
        val bytes = attributes[key] ?: return ""
        return String(bytes, StandardCharsets.UTF_8).trim()
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.resolvedHostAddress(): String? =
        host?.hostAddress?.takeIf { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun localWifiIp(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun compareIpv4(left: String, right: String): Int {
        fun value(ip: String): Long =
            ip.split('.').fold(0L) { acc, part -> (acc shl 8) + part.toLong() }
        return value(left).compareTo(value(right))
    }

    private fun onIntercomDisconnected(token: SessionGeneration.Token, error: IOException) {
        postForSession(token) {
            publishLog("信令通道断开：${error.message}")
            publishStatus(SIGNAL_LOST_STATUS)
            stopIntercom()
            stopSelf()
        }
    }

    private fun onRemoteRiderIdentified(token: SessionGeneration.Token, name: String) {
        postForSession(token) {
            remoteRiderName = name
            publishLog("已识别远端骑士：$name")
            listener?.onRemoteRiderIdentified(name)
            updateStageStatus()
        }
    }

    private fun onAudioLevelChanged(token: SessionGeneration.Token, level: Float) {
        postForSession(token) { listener?.onAudioLevelChanged(level) }
    }

    private fun stopIntercom() {
        sessions.invalidate()
        activeSession = null
        running = false
        tunnelChosen.set(NO_SESSION_TOKEN)

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
        stopLanDiscovery()
        stopNsdDiscovery()

        intercomManager = null
        wifiTunnel = null
        audioRouteController = null
        bluetoothReady = false
        physicalLinkReady = false
        mediaConnected = false
        localRiderName = ""
        remoteRiderName = null
        lanClientConnecting.set(NO_SESSION_TOKEN)
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(ENDED_STATUS)
        stopForegroundCompat()
    }

    private fun isSessionCurrent(token: SessionGeneration.Token): Boolean =
        running && sessions.isCurrent(token) && activeSession == token

    private fun claimLanClient(token: SessionGeneration.Token): Boolean =
        sessions.claimIfCurrent(token) {
            lanClientConnecting.compareAndSet(NO_SESSION_TOKEN, token.value)
        }

    private fun releaseLanClient(token: SessionGeneration.Token) {
        lanClientConnecting.compareAndSet(token.value, NO_SESSION_TOKEN)
    }

    private fun dispatchOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun postForSession(token: SessionGeneration.Token, action: () -> Unit) {
        dispatchOnMain {
            if (isSessionCurrent(token)) action()
        }
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
        return (
            WifiDirectTunnel.requiredPermissions().asList() +
                RiderAudioEngine.requiredPermissions().asList() +
                AudioRouteController.requiredPermissions().asList()
            ).distinct().all {
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
        const val ACTION_START_INTERCOM = "com.kuma.motointercom.action.START_INTERCOM"
        const val ACTION_STOP_INTERCOM = "com.kuma.motointercom.action.STOP_INTERCOM"
        const val EXTRA_RIDER_NAME = "com.kuma.motointercom.extra.RIDER_NAME"
        private const val CHANNEL_ID = "intercom_status"
        private const val NOTIFICATION_ID = 2601
        private const val LAN_UDP_PORT = 8889
        private const val LAN_TCP_PORT = 8890
        private const val LAN_CONNECT_TIMEOUT_MS = 2_000
        private const val LAN_RECEIVE_TIMEOUT_MS = 1_000
        private const val LAN_BROADCAST_INTERVAL_MS = 1_000L
        private const val NSD_SERVICE_TYPE = "_motocom._tcp."
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
