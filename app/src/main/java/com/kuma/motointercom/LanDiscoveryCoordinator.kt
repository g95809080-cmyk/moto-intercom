package com.kuma.motointercom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.Closeable
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
    private val riderName: String,
    private val onDevicesChanged: (List<LanRiderDevice>) -> Unit,
    private val onTunnelReady: (String, Boolean, Socket) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) : Closeable {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val udpSocket = AtomicReference<DatagramSocket?>()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val clientConnecting = AtomicBoolean(false)
    private val devices = linkedMapOf<String, LanRiderDevice>()

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdServiceName = ""

    fun start() {
        if (!isActive()) return
        startLanDiscovery()
        startNsdDiscovery()
    }

    private fun startLanDiscovery() {
        if (!isActive()) return
        val localIp = localWifiIp() ?: return
        executor.execute { runLanTcpServer() }
        executor.execute { runLanUdpListener(localIp) }
        executor.execute { runLanUdpBroadcaster(localIp) }
    }

    fun connect(device: LanRiderDevice) {
        if (!isActive() || !clientConnecting.compareAndSet(false, true)) return
        log("正在点名连接车友：${device.name} / ${device.ip}")
        try {
            executor.execute { connect(device.ip, device.port, reportFailure = true) }
        } catch (t: Throwable) {
            clientConnecting.set(false)
            error(t)
        }
    }

    internal fun devicesSnapshot(): List<LanRiderDevice> =
        synchronized(devices) { devices.values.toList() }

    private fun startNsdDiscovery() {
        if (!isActive()) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        nsdServiceName = "MotoCom-${nodeId.take(8)}"

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
                if (isActive()) removeLanDevice(info.serviceName)
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = nsdServiceName
            serviceType = NSD_SERVICE_TYPE
            port = LAN_TCP_PORT
            setAttribute("id", nodeId)
            setAttribute("name", riderName)
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
                    val id = serviceInfo.attributeString("id").ifBlank { serviceInfo.serviceName }
                    if (id == nodeId) return
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
            log("局域网设备解析异常：${t.message}")
        }
    }

    private fun runLanTcpServer() {
        var localServer: ServerSocket? = null
        var acceptedSocket: Socket? = null
        try {
            if (!isActive()) return
            val candidate = ServerSocket()
            localServer = candidate
            candidate.reuseAddress = true
            candidate.bind(InetSocketAddress(LAN_TCP_PORT))
            if (!isActive() || !serverSocket.compareAndSet(null, candidate)) return

            while (isActive()) {
                val socket = candidate.accept()
                acceptedSocket = socket
                val peerIp = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName
                acceptedSocket = null
                handoff(peerIp, server = true, socket)
                return
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
            if (!isActive()) return
            val candidate = DatagramSocket(LAN_UDP_PORT)
            localSocket = candidate
            candidate.broadcast = true
            candidate.soTimeout = LAN_RECEIVE_TIMEOUT_MS
            if (!isActive() || !udpSocket.compareAndSet(null, candidate)) return

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
                        .put("name", riderName)
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
        if (!shouldInitiateClient(localIp, peerIp) || !clientConnecting.compareAndSet(false, true)) return
        try {
            executor.execute {
                connect(peerIp, json.optInt("tcpPort", LAN_TCP_PORT), reportFailure = false)
            }
        } catch (t: Throwable) {
            clientConnecting.set(false)
            error(t)
        }
    }

    private fun connect(ip: String, port: Int, reportFailure: Boolean) {
        var socket: Socket? = null
        try {
            if (!isActive()) return
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), LAN_CONNECT_TIMEOUT_MS)
            val connected = socket
            socket = null
            handoff(ip, server = false, connected)
        } catch (t: Throwable) {
            clientConnecting.set(false)
            if (reportFailure) error(t) else log("局域网连接失败：${t.message}")
        } finally {
            closeQuietly(socket)
        }
    }

    private fun rememberLanDevice(device: LanRiderDevice) {
        if (!isActive()) return
        synchronized(devices) { devices[device.id] = device }
        log("发现局域网车友：${device.name} / ${device.ip}")
        publishLanDevices()
    }

    private fun removeLanDevice(id: String) {
        if (!isActive()) return
        synchronized(devices) { devices.remove(id) }
        publishLanDevices()
    }

    private fun publishLanDevices() {
        if (isActive()) onDevicesChanged(devicesSnapshot())
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

    private fun handoff(ip: String, server: Boolean, socket: Socket) {
        if (!isActive()) {
            closeQuietly(socket)
            return
        }
        onTunnelReady(ip, server, socket)
    }

    private fun log(message: String) {
        if (isActive()) onLog(message)
    }

    private fun error(t: Throwable) {
        if (isActive()) onError(t)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(udpSocket.getAndSet(null))
        closeQuietly(serverSocket.getAndSet(null))
        stopNsdDiscovery()
        executor.shutdownNow()
        synchronized(devices) { devices.clear() }
        onDevicesChanged(emptyList())
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

        internal fun shouldInitiateClient(localIp: String, peerIp: String): Boolean =
            ipv4Value(localIp) > ipv4Value(peerIp)

        private fun ipv4Value(ip: String): Long =
            ip.split('.').fold(0L) { value, part -> (value shl 8) + part.toLong() }
    }
}
