package com.kuma.motointercom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class LanDiscoveryCoordinator(
    context: Context,
    private val token: SessionGeneration.Token,
    private val isSessionCurrent: (SessionGeneration.Token) -> Boolean,
    private val nodeId: String,
    private val runtimeSessionId: RuntimeSessionId,
    private val riderName: String,
    private val deviceName: String,
    private val protocolVersion: Int,
    private val onDevicesChanged: (List<LanRiderDevice>) -> Unit,
    private val onTunnelReady: (
        String,
        Boolean,
        PeerIdentity,
        ConnectionAttempt,
        Socket
    ) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) : Closeable {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val lifecycleLock = Any()
    private val udpSocket = AtomicReference<DatagramSocket?>()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val targetAttempt = AtomicReference<ConnectionAttempt?>()
    private val clientConnecting = AtomicBoolean(false)
    private val deviceRegistry = LanDiscoveryDeviceRegistry()

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdServiceName = ""

    fun start() {
        if (!isActive()) return
        val localIp = localWifiIp() ?: return
        startLanDiscovery(localIp)
        startNsdDiscovery()
    }

    private fun startLanDiscovery(localIp: String) {
        if (!isActive()) return
        executor.execute { runLanTcpServer() }
        executor.execute { runLanUdpListener(localIp) }
        executor.execute { runLanUdpBroadcaster(localIp) }
    }

    fun connect(attempt: ConnectionAttempt): Boolean {
        if (!isActive() || attempt.channelPlan.transport != Transport.LAN) return false
        targetAttempt.set(attempt)
        connectTargetIfAvailable()
        return true
    }

    private fun connectTargetIfAvailable() {
        val attempt = targetAttempt.get() ?: return
        val device = deviceRegistry.find(attempt.targetLock) ?: return
        if (!clientConnecting.compareAndSet(false, true)) return
        log("正在点名连接车友：${device.name} / ${device.ip}")
        try {
            executor.execute {
                connect(
                    device.ip,
                    device.port,
                    attempt.targetDeviceId,
                    attempt,
                    reportFailure = true
                )
            }
        } catch (t: Throwable) {
            clientConnecting.set(false)
            error(t)
        }
    }

    private fun startNsdDiscovery() {
        if (!isActive()) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        nsdServiceName = "MotoCom-${nodeId.take(8)}-${runtimeSessionId.value.take(8)}"

        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (!isActive()) return
                nsdServiceName = info.serviceName
                log("局域网服务已上线：$nsdServiceName")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log("局域网服务注册失败：$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("局域网扫描启动失败：$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!isActive() || info.serviceType != NSD_SERVICE_TYPE || info.serviceName == nsdServiceName) {
                    return
                }
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
            setAttribute("id", nodeId)
            setAttribute("sessionId", runtimeSessionId.value)
            setAttribute("name", riderName)
            setAttribute("deviceName", deviceName)
            setAttribute("protocolVersion", protocolVersion.toString())
        }

        try {
            val registration = nsdRegistrationListener ?: return
            val discovery = nsdDiscoveryListener ?: return
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registration)
            manager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (t: Throwable) {
            error(t)
        }
    }

    private fun resolveNsdService(info: NsdServiceInfo) {
        if (!isActive()) return
        val manager = nsdManager ?: return
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    log("局域网设备解析失败：$errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!isActive()) return
                    val deviceId = serviceInfo.attributeString("id").takeIf(String::isNotBlank)
                    if (deviceId == nodeId) return
                    val ip = serviceInfo.resolvedHostAddress() ?: return
                    val name = serviceInfo.attributeString("name").ifBlank { serviceInfo.serviceName }
                    rememberLanDevice(
                        serviceInfo.serviceName,
                        LanRiderDevice(
                            discoveryEndpointId = serviceInfo.serviceName,
                            deviceId = deviceId,
                            sessionId = serviceInfo.attributeString("sessionId")
                                .takeIf(String::isNotBlank)
                                ?.let(::RuntimeSessionId),
                            name = name,
                            deviceName = serviceInfo.attributeString("deviceName"),
                            protocolVersion = serviceInfo.attributeString("protocolVersion")
                                .toIntOrNull()
                                ?: 0,
                            ip = ip,
                            port = serviceInfo.port.takeIf { it > 0 } ?: LAN_TCP_PORT
                        )
                    )
                }
            })
        } catch (t: Throwable) {
            log("局域网设备解析异常：${t.message}")
        }
    }

    private fun runLanTcpServer() {
        var localServer: ServerSocket? = null
        var acceptedSocket: Socket? = null
        try {
            val candidate = createServerSocket() ?: return
            localServer = candidate

            while (isActive()) {
                val socket = candidate.accept()
                acceptedSocket = socket
                val attempt = targetAttempt.get()
                if (attempt == null) {
                    log("Ignored LAN tunnel without an active Target Lock")
                    closeQuietly(socket)
                    acceptedSocket = null
                    continue
                }
                val remoteDeviceId = LanTunnelHandshake.exchangeAsServer(socket, nodeId)
                if (
                    !attempt.acceptsLanPreflightDevice(remoteDeviceId) ||
                    targetAttempt.get() != attempt
                ) {
                    log("Ignored invalid or wrong-target LAN tunnel connection")
                    closeQuietly(socket)
                    acceptedSocket = null
                    continue
                }
                val verifiedPeer = try {
                    LegacyIdentityHandshake.exchange(socket, localIdentity(), attempt.targetLock)
                } catch (t: Throwable) {
                    log("Rejected LAN socket with incomplete or mismatched IDENTITY: ${t.message}")
                    closeQuietly(socket)
                    acceptedSocket = null
                    continue
                }
                if (targetAttempt.get() != attempt) {
                    closeQuietly(socket)
                    acceptedSocket = null
                    continue
                }
                val peerIp = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName
                if (
                    handoff(
                        peerIp,
                        server = true,
                        verifiedPeer,
                        attempt,
                        socket
                    )
                ) {
                    acceptedSocket = null
                    return
                }
                acceptedSocket = null
            }
        } catch (t: Throwable) {
            error(t)
        } finally {
            closeQuietly(acceptedSocket)
            localServer?.let { serverSocket.compareAndSet(it, null) }
            closeQuietly(localServer)
        }
    }

    private fun runLanUdpListener(localIp: String) {
        var localSocket: DatagramSocket? = null
        try {
            val candidate = createUdpSocket() ?: return
            localSocket = candidate

            val buffer = ByteArray(2048)
            while (isActive()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    candidate.receive(packet)
                    handleLanBroadcast(localIp, packet)
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (t: Throwable) {
            error(t)
        } finally {
            localSocket?.let { udpSocket.compareAndSet(it, null) }
            closeQuietly(localSocket)
        }
    }

    private fun runLanUdpBroadcaster(localIp: String) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val target = InetAddress.getByName("255.255.255.255")
                while (isActive()) {
                    val bytes = JSONObject()
                        .put("type", "MOTOCOM_HELLO")
                        .put("id", nodeId)
                        .put("sessionId", runtimeSessionId.value)
                        .put("name", riderName)
                        .put("deviceName", deviceName)
                        .put("protocolVersion", protocolVersion)
                        .put("ip", localIp)
                        .put("tcpPort", LAN_TCP_PORT)
                        .toString()
                        .toByteArray(StandardCharsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, target, LAN_UDP_PORT))
                    Thread.sleep(LAN_BROADCAST_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            error(t)
        }
    }

    private fun handleLanBroadcast(localIp: String, packet: DatagramPacket) {
        if (!isActive()) return
        val json = try {
            JSONObject(String(packet.data, 0, packet.length, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            return
        }
        if (json.optString("type") != "MOTOCOM_HELLO" || json.optString("id") == nodeId) return

        val peerIp = packet.address.hostAddress ?: json.optString("ip")
        if (peerIp == localIp || peerIp.isBlank()) return

        log("发现同一 Wi-Fi 车友：${json.optString("name")} / $peerIp")
    }

    private fun connect(
        ip: String,
        port: Int,
        remoteDeviceId: String,
        attempt: ConnectionAttempt,
        reportFailure: Boolean
    ) {
        var socket: Socket? = null
        try {
            if (!isActive() || targetAttempt.get() != attempt) return
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), LAN_CONNECT_TIMEOUT_MS)
            val preliminaryRemoteDeviceId = LanTunnelHandshake.exchangeAsClient(
                socket,
                localNodeId = nodeId,
                expectedRemoteNodeId = remoteDeviceId
            )
            if (
                !attempt.acceptsLanPreflightDevice(preliminaryRemoteDeviceId) ||
                targetAttempt.get() != attempt
            ) {
                clientConnecting.set(false)
                log("LAN socket identity mismatch for $ip")
                return
            }
            val verifiedPeer = LegacyIdentityHandshake.exchange(
                socket,
                localIdentity(),
                attempt.targetLock
            )
            if (targetAttempt.get() != attempt) {
                clientConnecting.set(false)
                return
            }
            val connected = socket
            if (
                handoff(
                    ip,
                    server = false,
                    verifiedPeer,
                    attempt,
                    connected
                )
            ) {
                socket = null
            } else {
                clientConnecting.set(false)
            }
        } catch (t: Throwable) {
            clientConnecting.set(false)
            if (reportFailure) error(t) else log("局域网连接失败：${t.message}")
        } finally {
            closeQuietly(socket)
        }
    }

    private fun rememberLanDevice(serviceName: String, device: LanRiderDevice) {
        if (!isActive()) return
        val snapshot = deviceRegistry.remember(serviceName, device)
        log("发现局域网车友：${device.name} / ${device.ip}")
        publishLanDevices(snapshot)
        connectTargetIfAvailable()
    }

    private fun removeLanDevice(serviceName: String) {
        if (!isActive()) return
        val snapshot = deviceRegistry.remove(serviceName)
        publishLanDevices(snapshot)
    }

    private fun publishLanDevices(snapshot: List<LanRiderDevice>) {
        if (isActive()) onDevicesChanged(snapshot)
    }

    private fun NsdServiceInfo.attributeString(key: String): String {
        val bytes = attributes[key] ?: return ""
        return String(bytes, StandardCharsets.UTF_8).trim()
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.resolvedHostAddress(): String? =
        host?.hostAddress?.takeIf { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun localWifiIp(): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun isActive(): Boolean = !closed.get() && isSessionCurrent(token)

    private fun handoff(
        ip: String,
        server: Boolean,
        peer: PeerIdentity,
        attempt: ConnectionAttempt,
        socket: Socket
    ): Boolean {
        if (!isActive() || targetAttempt.get() != attempt || !peer.isVerifiedFor(attempt.targetLock)) {
            closeQuietly(socket)
            return false
        }
        return try {
            onTunnelReady(
                ip,
                server,
                peer,
                attempt,
                socket
            )
            true
        } catch (t: Throwable) {
            closeQuietly(socket)
            error(t)
            false
        }
    }

    private fun log(message: String) {
        if (isActive()) onLog(message)
    }

    private fun error(t: Throwable) {
        if (isActive()) onError(t)
    }

    private fun localIdentity() = SignalingProtocol.Message.Identity(
        name = riderName,
        deviceId = nodeId,
        runtimeSessionId = runtimeSessionId.value,
        deviceName = deviceName
    )

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            closeQuietly(udpSocket.getAndSet(null))
            closeQuietly(serverSocket.getAndSet(null))
        }
        stopNsdDiscovery()
        executor.shutdownNow()
        targetAttempt.set(null)
        deviceRegistry.clear()
        onDevicesChanged(emptyList())
    }

    private fun createServerSocket(): ServerSocket? = synchronized(lifecycleLock) {
        if (!isActive()) return@synchronized null
        val candidate = ServerSocket()
        try {
            candidate.reuseAddress = true
            candidate.bind(InetSocketAddress(LAN_TCP_PORT))
            if (serverSocket.compareAndSet(null, candidate)) candidate else {
                closeQuietly(candidate)
                null
            }
        } catch (t: Throwable) {
            closeQuietly(candidate)
            throw t
        }
    }

    private fun createUdpSocket(): DatagramSocket? = synchronized(lifecycleLock) {
        if (!isActive()) return@synchronized null
        val candidate = DatagramSocket(LAN_UDP_PORT)
        try {
            candidate.broadcast = true
            candidate.soTimeout = LAN_RECEIVE_TIMEOUT_MS
            if (udpSocket.compareAndSet(null, candidate)) candidate else {
                closeQuietly(candidate)
                null
            }
        } catch (t: Throwable) {
            closeQuietly(candidate)
            throw t
        }
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
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val LAN_UDP_PORT = 8889
        private const val LAN_TCP_PORT = 8890
        private const val LAN_CONNECT_TIMEOUT_MS = 2_000
        private const val LAN_RECEIVE_TIMEOUT_MS = 1_000
        private const val LAN_BROADCAST_INTERVAL_MS = 1_000L
        private const val NSD_SERVICE_TYPE = "_motocom._tcp."

    }
}

internal object LanTunnelHandshake {
    private const val MAGIC = 0x4D54434D // MTCM
    private const val VERSION = 2
    private const val MAX_NODE_ID_BYTES = 128
    private const val READ_TIMEOUT_MS = 1_000

    fun exchangeAsClient(
        socket: Socket,
        localNodeId: String,
        expectedRemoteNodeId: String
    ): String? = exchange(socket) { input, output ->
        write(output, localNodeId)
        read(input, localNodeId)?.takeIf { it == expectedRemoteNodeId.trim() }
    }

    fun exchangeAsServer(socket: Socket, localNodeId: String): String? =
        exchange(socket) { input, output ->
            val remoteNodeId = read(input, localNodeId) ?: return@exchange null
            write(output, localNodeId)
            remoteNodeId
        }

    private fun <T> exchange(
        socket: Socket,
        action: (DataInputStream, DataOutputStream) -> T?
    ): T? {
        val previousTimeout = socket.soTimeout
        return try {
            socket.soTimeout = READ_TIMEOUT_MS
            action(
                DataInputStream(socket.getInputStream()),
                DataOutputStream(socket.getOutputStream())
            )
        } catch (_: IOException) {
            null
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
    }

    private fun write(output: DataOutputStream, nodeId: String) {
        val id = nodeId.trim().toByteArray(StandardCharsets.UTF_8)
        require(id.isNotEmpty() && id.size <= MAX_NODE_ID_BYTES) { "invalid LAN node id" }
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.writeByte(id.size)
        output.write(id)
        output.flush()
    }

    private fun read(input: DataInputStream, localNodeId: String): String? {
        if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null
        val size = input.readUnsignedByte()
        if (size !in 1..MAX_NODE_ID_BYTES) return null
        val id = ByteArray(size).also(input::readFully)
            .toString(StandardCharsets.UTF_8)
            .trim()
        return id.takeIf { it.isNotEmpty() && it != localNodeId.trim() }
    }
}
