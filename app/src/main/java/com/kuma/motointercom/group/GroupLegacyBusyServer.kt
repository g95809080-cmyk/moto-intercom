package com.kuma.motointercom.group

import com.kuma.motointercom.*
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Passive rejection only: no discovery, network provisioning, pairing or media ownership. */
internal class GroupLegacyBusyServer(private val endpoint: GroupAuthEndpoint,
    private val nickname: String, private val ports: List<Int> = listOf(8888, 8890)) : Closeable {
    private val closed = AtomicBoolean(false)
    private val servers = mutableListOf<ServerSocket>()
    private val sockets = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val permits = Semaphore(2)
    private val budget = com.kuma.motointercom.group.network.GroupBleBudget(::groupNowMs)
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { task -> Thread(task, "group-legacy-busy").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val timer = Executors.newSingleThreadScheduledExecutor()
    fun start() {
        try {
            ports.forEach { port ->
                val server = ServerSocket()
                try { server.reuseAddress = true; server.bind(InetSocketAddress(port)) }
                catch (error: Exception) { server.close(); throw error }
                servers += server
                Thread({
                    while (!closed.get()) {
                        val socket = try { server.accept() } catch (_: Exception) { break }
                        if (closed.get() || !budget.acquire(socket.inetAddress.hostAddress.orEmpty(), 2 - permits.availablePermits()) || !permits.tryAcquire()) {
                            runCatching { socket.close() }; continue
                        }
                        sockets += socket
                        try {
                            val deadline = timer.schedule({ runCatching { socket.close() } }, 3, TimeUnit.SECONDS)
                            workers.execute {
                                try { if (!closed.get()) respond(socket) } catch (_: Exception) { }
                                finally { deadline.cancel(false); sockets.remove(socket); permits.release(); runCatching { socket.close() } }
                            }
                        } catch (_: Exception) { sockets.remove(socket); permits.release(); runCatching { socket.close() } }
                    }
                }, "group-busy-accept-$port").apply { isDaemon = true; start() }
            }
        } catch (error: Exception) { close(); throw error }
    }
    internal fun respond(socket: Socket) {
        socket.soTimeout = 1_000
        val codec = SignalingV2Codec()
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val hello = codec.decode(SignalingV2Framing.read(input))
        require(hello.targetDeviceId.value == endpoint.deviceId && hello.sourceDeviceId.value != endpoint.deviceId)
        require((hello.message as? SignalingMessageV2.Hello)?.requestRole == RequestRole.REQUESTER)
        fun reply(message: SignalingMessageV2) = SignalingV2Framing.write(output, codec.encode(SignalingEnvelopeV2(
            attemptId = hello.attemptId, sourceDeviceId = DeviceId.parse(endpoint.deviceId), targetDeviceId = hello.sourceDeviceId,
            sourceSessionId = RuntimeSessionId(endpoint.runtimeId), message = message)))
        reply(SignalingMessageV2.Hello(RequestRole.RESPONDER, nickname, "MotoCom"))
        val request = codec.decode(SignalingV2Framing.read(input))
        require(request.attemptId == hello.attemptId && request.sourceDeviceId == hello.sourceDeviceId &&
            request.targetDeviceId == hello.targetDeviceId && request.sourceSessionId == hello.sourceSessionId &&
            request.message is SignalingMessageV2.ConnectRequest)
        reply(SignalingMessageV2.Busy(BusyReason.parse("BUSY"), null))
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        servers.forEach { runCatching { it.close() } }; servers.clear()
        sockets.toList().forEach { runCatching { it.close() } }; sockets.clear()
        workers.shutdownNow(); timer.shutdownNow()
    }
}
