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
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private val targetMatcher: (WifiP2pDevice) -> Boolean = { true },
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    @Volatile private var running = false
    @Volatile private var connectingAddress: String? = null
    @Volatile private var tunnelStarted = false

    private var serverSocket: ServerSocket? = null
    private var signalingSocket: Socket? = null

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
                    } else {
                        resetTunnelOnly()
                        if (running) mainHandler.post { onDisconnected() }
                        if (running) discoverPeers()
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
        discoverPeers()
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (!running) return
        val m = manager ?: return
        val c = channel ?: return

        try {
            m.discoverPeers(c, action("开始搜索附近设备失败"))
        } catch (t: Throwable) {
            postError(t)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (!running) return
        if (connectingAddress == device.deviceAddress || signalingSocket != null) return

        val m = manager ?: return
        val c = channel ?: return
        connectingAddress = device.deviceAddress

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        try {
            m.connect(c, config, action("连接 ${device.deviceName} 失败") {
                connectingAddress = null
            })
        } catch (t: Throwable) {
            connectingAddress = null
            postError(t)
        }
    }

    override fun close() {
        running = false
        resetTunnelOnly()
        unregisterReceiver()
        try {
            channel?.close()
        } catch (_: Throwable) {
        }
        channel = null
        manager = null
        io.shutdownNow()
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
                discoverPeers()
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
                val peers = list.deviceList.toList()
                mainHandler.post { onPeersChanged(peers) }
                if (autoConnect && signalingSocket == null && connectingAddress == null) {
                    peers.firstOrNull(targetMatcher)?.let(::connect)
                }
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
            mainHandler.post { onDisconnected() }
            discoverPeers()
            return
        }
        if (tunnelStarted) return

        val ownerIp = info.groupOwnerAddress?.hostAddress
        if (ownerIp.isNullOrBlank()) {
            postError(IllegalStateException("未获取到组长 IP"))
            return
        }

        tunnelStarted = true
        if (info.isGroupOwner) {
            startSignalingServer()
        } else {
            connectSignalingClient(ownerIp)
        }
    }

    private fun startSignalingServer() {
        try {
            io.execute {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(signalingPort))
                    }
                    val socket = serverSocket!!.accept()
                    signalingSocket = socket
                    val peerIp = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName
                    postReady(peerIp, isServer = true, socket = socket)
                } catch (t: Throwable) {
                    if (running) postError(t)
                }
            }
        } catch (t: Throwable) {
            if (running) postError(t)
        }
    }

    private fun connectSignalingClient(groupOwnerIp: String) {
        try {
            io.execute {
                var lastError: Throwable? = null

                // 组员连接成功时，组长的 ServerSocket 可能还差几百毫秒才启动。
                repeat(CLIENT_RETRY_COUNT) {
                    if (!running) return@execute
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(groupOwnerIp, signalingPort), CONNECT_TIMEOUT_MS)
                        signalingSocket = socket
                        postReady(groupOwnerIp, isServer = false, socket = socket)
                        return@execute
                    } catch (t: Throwable) {
                        lastError = t
                        sleepQuietly(CLIENT_RETRY_DELAY_MS)
                    }
                }

                postError(IOException("连接组长信令通道失败: $groupOwnerIp:$signalingPort", lastError))
            }
        } catch (t: Throwable) {
            if (running) postError(t)
        }
    }

    private fun resetTunnelOnly() {
        tunnelStarted = false
        connectingAddress = null
        try {
            signalingSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        signalingSocket = null
        serverSocket = null
    }

    private fun action(message: String, onFailed: () -> Unit = {}) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit

            override fun onFailure(reason: Int) {
                onFailed()
                if (reason == WifiP2pManager.BUSY) {
                    postError(IllegalStateException(BUSY_STATUS))
                    resetTunnelOnly()
                    mainHandler.postDelayed({ if (running) discoverPeers() }, BUSY_RETRY_DELAY_MS)
                } else {
                    postError(IllegalStateException("$message: ${reasonText(reason)}"))
                }
            }
        }

    private fun postReady(targetIp: String, isServer: Boolean, socket: Socket) {
        mainHandler.post { onTunnelReady(targetIp, isServer, socket) }
    }

    private fun postError(t: Throwable) {
        mainHandler.post { onError(t) }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val CLIENT_RETRY_DELAY_MS = 500L
        private const val CLIENT_RETRY_COUNT = 20
        private const val BUSY_RETRY_DELAY_MS = 1_000L
        private const val BUSY_STATUS = "无线占用中，正在自动复位重试..."

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
