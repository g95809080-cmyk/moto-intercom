package com.kuma.motointercom

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap
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
    private val onControlChannelReady: (SignalingSessionV2, PendingSocketLease) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    initialTargetAttempt: ConnectionAttempt? = null,
    private val monotonicClock: MonotonicClock = MonotonicClock {
        MonotonicTimestamp(SystemClock.elapsedRealtime())
    },
    private val onFreshObservation: (FreshDiscoveryObservation) -> Unit = {},
    private val onTargetedConnectFailure: (ConnectionAttempt, Throwable) -> Unit = { _, failure -> onError(failure) }
) : Closeable {
    private val context = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val lifecycleLock = Any()
    private val udpSocket = AtomicReference<DatagramSocket?>()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val targetedClientSocket = AtomicReference<Socket?>()
    private val pendingSockets = ConcurrentHashMap<PendingSocketLease, Long>()
    @Volatile private var pendingSocketGeneration = 0L
    private val ingressAttempt = LanAttemptLease(initialTargetAttempt)
    private val targetAttempt = LanAttemptLease()
    private val clientConnectAttempt = LanAttemptLease()
    private val failedConnectAttempt = LanAttemptLease()
    private val retryPause = RecoveryAttemptPause()
    private val deviceRegistry = LanDiscoveryDeviceRegistry()
    val observationSource = FreshDiscoverySource(Transport.LAN)
    private val observedSessions = DiscoverySessionTracker()

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdServiceName = ""

    fun start(): Boolean {
        if (!isActive()) return false
        val localIp = localWifiIp() ?: return false
        startLanDiscovery(localIp)
        startNsdDiscovery()
        return true
    }

    fun probeDiscovery() {
        if (!isActive()) return
        observationSource.advanceEpoch()
        stopNsdDiscovery()
        startNsdDiscovery()
    }

    private fun startLanDiscovery(localIp: String) {
        if (!isActive()) return
        executor.execute { runLanTcpServer() }
        executor.execute { runLanUdpListener(localIp) }
        executor.execute { runLanUdpBroadcaster(localIp) }
    }

    fun restrictIngress(attempt: ConnectionAttempt): Boolean {
        val retired = synchronized(lifecycleLock) {
            if (!isActive() || Transport.LAN !in attempt.channelPlan ||
                attempt.remainingMillis(monotonicClock) <= 0L
            ) return false
            val retired = if (ingressAttempt.current != attempt) retirePendingLocked() else emptyList()
            ingressAttempt.bind(attempt)
            retired
        }
        retired.forEach { it.close() }
        return true
    }

    fun connect(attempt: ConnectionAttempt): Boolean {
        if (retryPause.isPrepared) {
            if (
                !retryPause.resumeExact(
                    attempt,
                    targetAttempt.current,
                    ingressAttempt.current,
                    monotonicClock
                )
            ) return false
            connectTargetIfAvailable()
            return true
        }
        if (!restrictIngress(attempt)) return false
        retryPause.clear()
        targetAttempt.bind(attempt)
        connectTargetIfAvailable()
        return true
    }

    fun prepareRetry(attempt: ConnectionAttempt): Boolean {
        val retired = synchronized(lifecycleLock) {
            val previous = targetAttempt.current ?: ingressAttempt.current ?: return false
            if (
                !isActive() ||
                !attempt.canReuseDiscoveryAdapterFrom(previous, Transport.LAN, monotonicClock)
            ) {
                return false
            }
            retryPause.prepare(attempt)
            targetedClientSocket.set(null)
            clientConnectAttempt.clear()
            failedConnectAttempt.clear()
            ingressAttempt.bind(attempt)
            targetAttempt.bind(attempt)
            retirePendingLocked()
        }
        retired.forEach { it.close() }
        return true
    }

    fun retainPassiveIngress(completedAttempt: ConnectionAttempt) {
        val retired = synchronized(lifecycleLock) {
            val releasedIngress = ingressAttempt.release(completedAttempt)
            val releasedTarget = targetAttempt.release(completedAttempt)
            if (releasedTarget) {
                targetedClientSocket.set(null)
                clientConnectAttempt.release(completedAttempt)
                failedConnectAttempt.release(completedAttempt)
                retryPause.clear()
            }
            if (releasedIngress || releasedTarget) retirePendingLocked() else emptyList()
        }
        retired.forEach { it.close() }
    }

    private fun connectTargetIfAvailable() {
        if (retryPause.isPrepared) return
        val attempt = targetAttempt.current ?: return
        if (!isAttemptCurrent(attempt) || failedConnectAttempt.current == attempt) return
        val device = deviceRegistry.find(attempt.targetLock) ?: return
        val claimed = synchronized(lifecycleLock) {
            isAttemptCurrent(attempt) && failedConnectAttempt.current != attempt &&
                clientConnectAttempt.tryBind(attempt)
        }
        if (!claimed) return
        log("正在点名连接车友：${device.name} / ${device.ip}")
        try {
            executor.execute {
                connect(
                    device.ip,
                    device.port,
                    attempt,
                    reportFailure = true
                )
            }
        } catch (t: Throwable) {
            val report = markConnectFailed(attempt)
            clientConnectAttempt.release(attempt)
            if (report && isAttemptCurrent(attempt)) {
                onTargetedConnectFailure(attempt, connectFailure(t, attempt, device.ip, device.port, "SUBMIT", "unbound"))
            }
        }
    }

    private fun startNsdDiscovery() {
        if (!isActive()) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val sourceEpoch = observationSource.advanceEpoch()
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
                val receipt = observationSource.capture(DiscoveryObservationKind.LAN_NSD,
                    monotonicClock.now().elapsedRealtimeMs, sourceEpoch, info.serviceName) ?: return
                resolveNsdService(info, receipt)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (!observationSource.invalidateKey(DiscoveryObservationKind.LAN_NSD, info.serviceName, sourceEpoch)) return
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

    private fun resolveNsdService(info: NsdServiceInfo, receipt: FreshDiscoveryReceipt) {
        if (!isActive() || !observationSource.isCurrentEpoch(receipt.sourceEpoch)) return
        val manager = nsdManager ?: return
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    log("局域网设备解析失败：$errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!isActive() || !observationSource.isCurrentEpoch(receipt.sourceEpoch)) return
                    val deviceId = serviceInfo.attributeString("id").takeIf(String::isNotBlank)
                    if (deviceId == nodeId) return
                    val ip = serviceInfo.resolvedHostAddress() ?: return
                    val name = serviceInfo.attributeString("name").ifBlank { serviceInfo.serviceName }
                    rememberObservedLanDevice(
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
                        ),
                        receipt
                    )
                }
            })
        } catch (t: Throwable) {
            log("局域网设备解析异常：${t.message}")
        }
    }

    private fun runLanTcpServer() {
        var localServer: ServerSocket? = null
        var acceptedLease: PendingSocketLease? = null
        try {
            val candidate = createServerSocket() ?: return
            localServer = candidate

            while (isActive()) {
                val socket = candidate.accept()
                val attempt = ingressAttempt.current
                val lease = registerPending(socket, attempt) ?: continue
                acceptedLease = lease
                val session = try {
                    SignalingSessionV2.establish(
                        socket = socket,
                        transport = Transport.LAN,
                        physicalRole = PhysicalSocketRole.ACCEPTOR,
                        openedAtElapsedMs = monotonicClock.now().elapsedRealtimeMs,
                        localDeviceId = nodeId,
                        localRuntimeSessionId = runtimeSessionId,
                        localNickname = riderName,
                        localDeviceName = deviceName,
                        originatingAttempt = attempt,
                        monotonicClock = monotonicClock,
                        pendingSocketLease = lease
                    )
                } catch (t: Throwable) {
                    log("Rejected LAN v2 HELLO: ${t.message}")
                    lease.close()
                    acceptedLease = null
                    continue
                }
                if (!isAttemptCurrentOrPassive(attempt)) {
                    lease.close()
                    acceptedLease = null
                    continue
                }
                handoff(session, attempt, lease)
                acceptedLease = null
            }
        } catch (t: Throwable) {
            error(t)
        } finally {
            closeQuietly(acceptedLease)
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
                    val receipt = observationSource.capture(DiscoveryObservationKind.LAN_UDP,
                        monotonicClock.now().elapsedRealtimeMs) ?: continue
                    expireLanBroadcastDevices()
                    handleLanBroadcast(localIp, packet, receipt)
                } catch (_: SocketTimeoutException) {
                    expireLanBroadcastDevices()
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

    private fun handleLanBroadcast(localIp: String, packet: DatagramPacket, receipt: FreshDiscoveryReceipt) {
        if (!isActive()) return
        val json = runCatching {
            JSONObject(String(packet.data, 0, packet.length, StandardCharsets.UTF_8))
        }.getOrNull() ?: return
        val device = lanBroadcastDeviceOrNull(
            hello = LanBroadcastHello(
                type = json.optString("type"),
                deviceId = json.optString("id"),
                sessionId = json.optString("sessionId"),
                name = json.optString("name"),
                deviceName = json.optString("deviceName"),
                protocolVersion = json.optInt("protocolVersion", 0),
                tcpPort = json.optInt("tcpPort", 0)
            ),
            sourceAddress = packet.address.hostAddress.orEmpty(),
            localIp = localIp,
            localDeviceId = nodeId
        ) ?: return
        log("发现同一 Wi-Fi 车友：${device.name} / ${device.ip}")
        rememberObservedLanDevice(
            serviceName = device.discoveryEndpointId,
            device = device,
            receipt = receipt,
            expiresAtElapsedRealtimeMs =
                receipt.receivedAtElapsedRealtimeMs + LAN_BROADCAST_RETENTION_MS
        )
    }

    private fun connect(
        ip: String,
        port: Int,
        attempt: ConnectionAttempt,
        reportFailure: Boolean
    ) {
        var socket: Socket? = null
        var lease: PendingSocketLease? = null
        var handedOff = false
        var stage = "WIFI_BIND"
        var route = "unbound"
        var failure: Throwable? = null
        try {
            if (!isAttemptCurrent(attempt)) {
                clientConnectAttempt.release(attempt)
                return
            }
            val connectTimeoutMillis = attempt.boundedTimeoutMillis(
                monotonicClock,
                LAN_CONNECT_TIMEOUT_MS.toLong()
            )
            if (connectTimeoutMillis <= 0L) {
                clientConnectAttempt.release(attempt)
                return
            }
            val candidate = Socket()
            socket = candidate
            val pending = registerPending(candidate, attempt) ?: return
            lease = pending
            if (!installTargetedClientSocket(attempt, candidate)) {
                clientConnectAttempt.release(attempt)
                return
            }
            val (network, selectedRoute) = resolveWifiSocketRoute()
            route = selectedRoute
            network.bindSocket(candidate)
            log("LAN socket route: attempt=${attempt.id.value} target=$ip:$port $route")
            if (!isAttemptCurrent(attempt)) {
                clientConnectAttempt.release(attempt)
                return
            }
            val remainingTimeoutMillis = attempt.boundedTimeoutMillis(monotonicClock, connectTimeoutMillis)
            if (remainingTimeoutMillis <= 0L) {
                clientConnectAttempt.release(attempt)
                return
            }
            stage = "TCP"
            candidate.connect(InetSocketAddress(ip, port), remainingTimeoutMillis.toInt())
            log("LAN TCP connected: attempt=${attempt.id.value} local=${candidate.localSocketAddress} target=$ip:$port")
            stage = "HELLO"
            val connected = candidate
            val session = SignalingSessionV2.establish(
                socket = connected,
                transport = Transport.LAN,
                physicalRole = PhysicalSocketRole.OPENER,
                openedAtElapsedMs = monotonicClock.now().elapsedRealtimeMs,
                localDeviceId = nodeId,
                localRuntimeSessionId = runtimeSessionId,
                localNickname = riderName,
                localDeviceName = deviceName,
                originatingAttempt = attempt,
                monotonicClock = monotonicClock,
                pendingSocketLease = pending
            )
            if (!isAttemptCurrent(attempt)) {
                clientConnectAttempt.release(attempt)
                pending.close()
                return
            }
            if (handoff(session, attempt, pending)) {
                targetedClientSocket.compareAndSet(candidate, null)
                socket = null
                handedOff = true
            } else {
                clientConnectAttempt.release(attempt)
            }
        } catch (t: Throwable) {
            if (reportFailure && markConnectFailed(attempt)) {
                failure = connectFailure(t, attempt, ip, port, stage, route)
            } else {
                log("LAN connection completion ignored: attempt=${attempt.id.value} stage=$stage target=$ip:$port: ${t.message}")
            }
            clientConnectAttempt.release(attempt)
        } finally {
            socket?.let { targetedClientSocket.compareAndSet(it, null) }
            if (!handedOff) {
                lease?.close()
                closeQuietly(socket)
            }
        }
        failure?.let { if (isAttemptCurrent(attempt)) onTargetedConnectFailure(attempt, it) }
    }

    // A failed opener stays retired until the actor ends or replaces the attempt.
    private fun markConnectFailed(attempt: ConnectionAttempt): Boolean = synchronized(lifecycleLock) {
        if (!isAttemptCurrent(attempt) || failedConnectAttempt.current == attempt) false
        else {
            failedConnectAttempt.bind(attempt)
            true
        }
    }

    private fun connectFailure(
        cause: Throwable, attempt: ConnectionAttempt, ip: String, port: Int, stage: String, route: String
    ) = IOException("LAN open failed: stage=$stage runtime=${attempt.runtimeSessionId.value} " +
        "attempt=${attempt.id.value} target=$ip:$port $route: ${cause.message ?: cause.javaClass.simpleName}", cause)

    private fun resolveWifiSocketRoute(): Pair<Network, String> {
        val localIp = localWifiIp() ?: throw IOException("Local Wi-Fi IPv4 unavailable")
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IOException("ConnectivityManager unavailable for LAN")
        for (network in connectivity.allNetworks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val link = connectivity.getLinkProperties(network) ?: continue
            if (link.linkAddresses.none { it.address.hostAddress == localIp }) continue
            // Local-only Wi-Fi does not need internet validation.
            return network to "network=$network interface=${link.interfaceName ?: "unknown"} local=$localIp"
        }
        throw IOException("No matching Wi-Fi network for local=$localIp")
    }

    private fun installTargetedClientSocket(
        attempt: ConnectionAttempt,
        candidate: Socket
    ): Boolean = synchronized(lifecycleLock) {
        if (!isAttemptCurrent(attempt)) return@synchronized false
        targetedClientSocket.set(candidate)
        true
    }

    private fun rememberObservedLanDevice(
        serviceName: String,
        device: LanRiderDevice,
        receipt: FreshDiscoveryReceipt,
        expiresAtElapsedRealtimeMs: Long? = null
    ) {
        if (!isActive()) return
        val candidate = runCatching { DiscoveryCandidate(Transport.LAN, device.discoveryEndpointId,
            device.ip, device.port, DiscoveryIdentityClaim(device.deviceId, device.sessionId,
                device.name, device.deviceName, device.protocolVersion)) }.getOrNull() ?: return
        val (snapshot, observation) = observationSource.accept(receipt, candidate) {
            if (observedSessions.register(candidate.identity) == DiscoverySessionRegistration.SUPERSEDED) null
            else deviceRegistry.remember(serviceName, device, expiresAtElapsedRealtimeMs)
        } ?: return
        if (!isActive() || !observationSource.isCurrent(observation)) return
        log("发现局域网车友：${device.name} / ${device.ip}")
        publishLanDevices(snapshot)
        if (isActive() && observationSource.isCurrent(observation)) onFreshObservation(observation)
        connectTargetIfAvailable()
    }

    private fun expireLanBroadcastDevices() {
        if (!isActive()) return
        deviceRegistry.expire(monotonicClock.now().elapsedRealtimeMs)
            ?.let(::publishLanDevices)
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
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun isActive(): Boolean = !closed.get() && isSessionCurrent(token)

    private fun handoff(
        session: SignalingSessionV2,
        expectedAttempt: ConnectionAttempt?,
        lease: PendingSocketLease
    ): Boolean {
        val generation = pendingSockets[lease]
        if (
            generation == null ||
            !isActive() ||
            !isAttemptCurrentOrPassive(expectedAttempt) ||
            !session.peer.isVerifiedFor(session.targetLock)
        ) {
            lease.close()
            return false
        }
        if (!lease.prepareAdmission({
                pendingSocketGeneration == generation && isAttemptCurrentOrPassive(expectedAttempt)
            })
        ) {
            lease.close()
            return false
        }
        return try {
            onControlChannelReady(session, lease)
            true
        } catch (t: Throwable) {
            lease.close()
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

    private fun isAttemptCurrent(attempt: ConnectionAttempt): Boolean =
        isActive() &&
            attempt.canRunTargetedWork(
                targetAttempt.current,
                retryPause,
                monotonicClock
            )

    private fun isAttemptCurrentOrPassive(attempt: ConnectionAttempt?): Boolean =
        if (attempt == null) isActive() && targetAttempt.current == null
        else isAttemptCurrent(attempt)

    override fun close() {
        observationSource.close()
        val resources = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            val sockets = listOfNotNull<Closeable>(
                udpSocket.getAndSet(null), serverSocket.getAndSet(null),
                targetedClientSocket.getAndSet(null)
            )
            sockets + retirePendingLocked()
        }
        resources.forEach(::closeQuietly)
        stopNsdDiscovery()
        executor.shutdownNow()
        ingressAttempt.clear()
        targetAttempt.clear()
        clientConnectAttempt.clear()
        failedConnectAttempt.clear()
        retryPause.clear()
        deviceRegistry.clear()
        observedSessions.clear()
        onDevicesChanged(emptyList())
    }

    private fun createServerSocket(): ServerSocket? {
        if (!isActive()) return null
        val candidate = ServerSocket()
        try {
            candidate.reuseAddress = true
            candidate.bind(InetSocketAddress(LAN_TCP_PORT))
            val installed = synchronized(lifecycleLock) {
                isActive() && serverSocket.compareAndSet(null, candidate)
            }
            return if (installed) {
                log("LAN TCP listener ready: local=${candidate.localSocketAddress} runtime=${runtimeSessionId.value}")
                candidate
            } else {
                closeQuietly(candidate)
                null
            }
        } catch (t: Throwable) {
            closeQuietly(candidate)
            throw t
        }
    }

    private fun createUdpSocket(): DatagramSocket? {
        if (!isActive()) return null
        val candidate = DatagramSocket(LAN_UDP_PORT)
        try {
            candidate.broadcast = true
            candidate.soTimeout = LAN_RECEIVE_TIMEOUT_MS
            val installed = synchronized(lifecycleLock) {
                isActive() && udpSocket.compareAndSet(null, candidate)
            }
            return if (installed) candidate else {
                closeQuietly(candidate)
                null
            }
        } catch (t: Throwable) {
            closeQuietly(candidate)
            throw t
        }
    }

    private fun registerPending(socket: Socket, attempt: ConnectionAttempt?): PendingSocketLease? {
        val lease = synchronized(lifecycleLock) {
            if (!isAttemptCurrentOrPassive(attempt)) null else PendingSocketLease(
                socket, attempt,
                attempt?.deadlineElapsedRealtimeMs ?: Math.addExact(
                    monotonicClock.now().elapsedRealtimeMs,
                    PendingSocketLease.PASSIVE_ADMISSION_TIMEOUT_MS
                ),
                monotonicClock,
                onReleased = { pendingSockets.remove(it) }
            ).also { pendingSockets[it] = pendingSocketGeneration }
        }
        if (lease == null) closeQuietly(socket) else lease.armAdmissionDeadline()
        return lease
    }

    // The caller only holds the adapter lock while detaching ownership, never while closing I/O.
    private fun retirePendingLocked(): List<PendingSocketLease> {
        pendingSocketGeneration += 1
        return pendingSockets.keys.toTypedArray().toList().also { pendingSockets.clear() }
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
        private const val LAN_BROADCAST_RETENTION_MS = LAN_BROADCAST_INTERVAL_MS * 3
        private const val NSD_SERVICE_TYPE = "_motocom._tcp."

    }
}

internal data class LanBroadcastHello(
    val type: String,
    val deviceId: String,
    val sessionId: String,
    val name: String,
    val deviceName: String,
    val protocolVersion: Int,
    val tcpPort: Int
)

internal fun lanBroadcastDeviceOrNull(
    hello: LanBroadcastHello,
    sourceAddress: String,
    localIp: String,
    localDeviceId: String
): LanRiderDevice? {
    if (hello.type != "MOTOCOM_HELLO") return null
    val deviceId = hello.deviceId.trim()
    val sessionId = hello.sessionId.trim()
    val peerIp = sourceAddress.trim()
    val hasValidIdentity = runCatching {
        DeviceId.parse(deviceId)
        requireCanonicalUuid(sessionId, "sessionId")
    }.isSuccess
    if (
        !hasValidIdentity ||
        deviceId == localDeviceId ||
        peerIp.isEmpty() ||
        peerIp == localIp ||
        hello.tcpPort !in 1..65535
    ) {
        return null
    }
    val endpointId = "udp:$deviceId:$sessionId"
    return LanRiderDevice(
        discoveryEndpointId = endpointId,
        deviceId = deviceId,
        sessionId = RuntimeSessionId(sessionId),
        name = hello.name.ifBlank { endpointId },
        deviceName = hello.deviceName,
        protocolVersion = hello.protocolVersion.coerceAtLeast(0),
        ip = peerIp,
        port = hello.tcpPort
    )
}

internal class LanAttemptLease(initialAttempt: ConnectionAttempt? = null) {
    private val attempt = AtomicReference(initialAttempt)

    val current: ConnectionAttempt?
        get() = attempt.get()

    fun bind(value: ConnectionAttempt) {
        attempt.set(value)
    }

    fun tryBind(value: ConnectionAttempt): Boolean =
        attempt.compareAndSet(null, value)

    fun release(expected: ConnectionAttempt): Boolean {
        while (true) {
            val current = attempt.get() ?: return false
            if (current != expected) return false
            if (attempt.compareAndSet(current, null)) return true
        }
    }

    fun clear() {
        attempt.set(null)
    }
}
