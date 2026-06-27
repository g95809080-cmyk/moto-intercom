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
import java.util.concurrent.atomic.AtomicBoolean

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

    private var listener: Listener? = null
    private var audioRouteController: AudioRouteController? = null
    private var wifiTunnel: WifiDirectTunnel? = null
    private var intercomManager: IntercomManager? = null
    private var lanExecutor: ExecutorService? = null
    private var lanUdpSocket: DatagramSocket? = null
    private var lanServerSocket: ServerSocket? = null
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
    private val tunnelChosen = AtomicBoolean(false)
    private val lanClientConnecting = AtomicBoolean(false)

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
                return START_STICKY
            }
            ACTION_STOP_INTERCOM -> {
                stopIntercom()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
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
        requestedRiderName = riderName.trim()
        if (hasRequiredRuntimePermissions()) startIntercom() else publishStatus("缺少必要权限，无法启动摩声")
    }

    fun requestStop() {
        stopIntercom()
        stopSelf()
    }

    fun connectToLanDevice(device: LanRiderDevice) {
        if (!running || tunnelChosen.get()) return
        val executor = lanExecutor ?: Executors.newCachedThreadPool().also { lanExecutor = it }
        if (!lanClientConnecting.compareAndSet(false, true)) return

        publishStatus(PEER_FOUND_STATUS)
        publishLog("正在点名连接车友：${device.name} / ${device.ip}")
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(device.ip, device.port), LAN_CONNECT_TIMEOUT_MS)
                acceptTunnel(device.ip, isServer = false, signalingSocket = socket, closeWifiDirect = true)
            } catch (t: Throwable) {
                lanClientConnecting.set(false)
                if (running && !tunnelChosen.get()) handleError(t)
            }
        }
    }

    private fun startIntercom() {
        if (running) {
            publishStatus(lastStatus)
            return
        }

        running = true
        bluetoothReady = false
        physicalLinkReady = false
        remoteRiderName = null
        localRiderName = ""
        lanNodeId = UUID.randomUUID().toString()
        tunnelChosen.set(false)
        lanClientConnecting.set(false)
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(SEARCHING_STATUS)

        audioRouteController = AudioRouteController(
            context = this,
            onScoConnected = { deviceName ->
                bluetoothReady = true
                publishAudioSource("当前音频源：蓝牙耳机 ($deviceName)", bluetooth = true)
                publishToast("头盔蓝牙已连线，对讲音频已就绪")
                updateStageStatus()
            },
            onScoDisconnected = {
                bluetoothReady = false
                publishToast(BLUETOOTH_RETRY_STATUS)
                publishLog(BLUETOOTH_RETRY_STATUS)
                updateStageStatus()
            },
            onSpeakerFallback = { noBluetooth ->
                bluetoothReady = false
                publishAudioSource(AUDIO_SPEAKER_STATUS, bluetooth = false)
                if (noBluetooth) publishToast("未检测到头盔蓝牙，已切换至手机外放")
                updateStageStatus()
            },
            onError = ::handleError
        ).also { it.switchToBluetoothSco() }

        publishStatus(SEARCHING_STATUS)
        wifiTunnel = WifiDirectTunnel(
            context = this,
            onTunnelReady = ::onTunnelReady,
            onPeersChanged = {
                publishLog("发现附近设备：${it.size}")
                if (it.isNotEmpty() && !physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
            },
            onDisconnected = { publishStatus(SIGNAL_LOST_STATUS) },
            onError = ::handleError
        ).also { it.start() }
        startLanDiscovery()
        startNsdDiscovery()
    }

    private fun onTunnelReady(targetIp: String, isServer: Boolean, signalingSocket: Socket) {
        if (!acceptTunnel(targetIp, isServer, signalingSocket, closeWifiDirect = false)) return
    }

    private fun acceptTunnel(
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ): Boolean {
        if (!tunnelChosen.compareAndSet(false, true)) {
            try {
                signalingSocket.close()
            } catch (_: Throwable) {
            }
            return false
        }

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
            onIntercomDisconnected = ::onIntercomDisconnected,
            onConnectionStateChanged = ::onConnectionStateChanged,
            onRemoteRiderIdentified = ::onRemoteRiderIdentified,
            onAudioLevelChanged = ::onAudioLevelChanged,
            onError = ::handleError
        ).also {
            publishStatus(MEDIA_INITIALIZING_STATUS)
            it.start()
        }

        updateStageStatus()
        return true
    }

    private fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
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

    private fun startLanDiscovery() {
        val localIp = localWifiIp() ?: return
        lanExecutor = Executors.newCachedThreadPool()
        lanExecutor?.execute { runLanTcpServer() }
        lanExecutor?.execute { runLanUdpListener(localIp) }
        lanExecutor?.execute { runLanUdpBroadcaster(localIp) }
    }

    private fun startNsdDiscovery() {
        if (localWifiIp() == null) return
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        nsdServiceName = "MotoCom-${lanNodeId.take(8)}"
        val riderName = requestedRiderName.ifBlank { "骑士" }

        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                nsdServiceName = info.serviceName
                publishLog("局域网服务已上线：$nsdServiceName")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                publishLog("局域网服务注册失败：$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                publishLog("局域网扫描启动失败：$errorCode")

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != NSD_SERVICE_TYPE || info.serviceName == nsdServiceName) return
                resolveNsdService(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                removeLanDevice(info.serviceName)
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
            handleError(t)
        }
    }

    private fun resolveNsdService(info: NsdServiceInfo) {
        val manager = nsdManager ?: return
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    publishLog("局域网设备解析失败：$errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val id = serviceInfo.attributeString("id").ifBlank { serviceInfo.serviceName }
                    if (id == lanNodeId) return
                    val ip = serviceInfo.resolvedHostAddress() ?: return
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
            })
        } catch (t: Throwable) {
            if (running) publishLog("局域网设备解析异常：${t.message}")
        }
    }

    private fun runLanTcpServer() {
        try {
            lanServerSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LAN_TCP_PORT))
            }
            while (running && !tunnelChosen.get()) {
                val socket = lanServerSocket?.accept() ?: return
                val peerIp = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName
                if (acceptTunnel(peerIp, isServer = true, signalingSocket = socket, closeWifiDirect = true)) {
                    publishLog("局域网链路已接入：$peerIp")
                    return
                }
            }
        } catch (t: Throwable) {
            if (running && !tunnelChosen.get()) handleError(t)
        }
    }

    private fun runLanUdpListener(localIp: String) {
        try {
            lanUdpSocket = DatagramSocket(LAN_UDP_PORT).apply {
                broadcast = true
                soTimeout = LAN_RECEIVE_TIMEOUT_MS
            }
            val buffer = ByteArray(2048)
            while (running && !tunnelChosen.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    lanUdpSocket?.receive(packet)
                    handleLanBroadcast(localIp, packet)
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (t: Throwable) {
            if (running && !tunnelChosen.get()) handleError(t)
        }
    }

    private fun runLanUdpBroadcaster(localIp: String) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val target = InetAddress.getByName("255.255.255.255")
                while (running && !tunnelChosen.get()) {
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
            if (running && !tunnelChosen.get()) handleError(t)
        }
    }

    private fun handleLanBroadcast(localIp: String, packet: DatagramPacket) {
        val json = try {
            JSONObject(String(packet.data, 0, packet.length, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            return
        }
        if (json.optString("type") != "MOTOCOM_HELLO") return
        if (json.optString("id") == lanNodeId) return

        val peerIp = packet.address.hostAddress ?: json.optString("ip")
        if (peerIp == localIp || peerIp.isBlank()) return

        publishLog("发现同一 Wi-Fi 车友：${json.optString("name")} / $peerIp")
        if (!physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
        if (compareIpv4(localIp, peerIp) <= 0) return
        if (!lanClientConnecting.compareAndSet(false, true)) return

        lanExecutor?.execute {
            try {
                val socket = Socket()
                socket.connect(
                    InetSocketAddress(peerIp, json.optInt("tcpPort", LAN_TCP_PORT)),
                    LAN_CONNECT_TIMEOUT_MS
                )
                acceptTunnel(peerIp, isServer = false, signalingSocket = socket, closeWifiDirect = true)
            } catch (t: Throwable) {
                lanClientConnecting.set(false)
                if (running && !tunnelChosen.get()) publishLog("局域网连接失败：${t.message}")
            }
        }
    }

    private fun stopLanDiscovery() {
        try {
            lanUdpSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            lanServerSocket?.close()
        } catch (_: Throwable) {
        }
        lanUdpSocket = null
        lanServerSocket = null
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
        mainHandler.post { listener?.onLanDevicesChanged(snapshot) }
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

    private fun onIntercomDisconnected(error: IOException) {
        publishLog("信令通道断开：${error.message}")
        publishStatus(SIGNAL_LOST_STATUS)
        stopIntercom()
    }

    private fun onRemoteRiderIdentified(name: String) {
        remoteRiderName = name
        publishLog("已识别远端骑士：$name")
        mainHandler.post { listener?.onRemoteRiderIdentified(name) }
        updateStageStatus()
    }

    private fun onAudioLevelChanged(level: Float) {
        mainHandler.post { listener?.onAudioLevelChanged(level) }
    }

    private fun stopIntercom() {
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
        running = false
        localRiderName = ""
        remoteRiderName = null
        tunnelChosen.set(false)
        lanClientConnecting.set(false)
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(ENDED_STATUS)
        stopForegroundCompat()
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
        lastStatus = status
        mainHandler.post { listener?.onStatusChanged(status, running) }
        updateNotification()
    }

    private fun publishAudioSource(status: String, bluetooth: Boolean) {
        audioSourceStatus = status
        audioSourceBluetooth = bluetooth
        mainHandler.post { listener?.onAudioSourceChanged(status, bluetooth) }
    }

    private fun publishLog(message: String) {
        mainHandler.post { listener?.onLog(message) }
    }

    private fun publishToast(message: String) {
        mainHandler.post { listener?.onToast(message) }
    }

    private fun handleError(t: Throwable) {
        val message = t.message ?: t.javaClass.simpleName
        mainHandler.post { listener?.onError(message) }
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
