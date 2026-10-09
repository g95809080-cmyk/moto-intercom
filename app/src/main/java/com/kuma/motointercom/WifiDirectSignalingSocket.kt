package com.kuma.motointercom

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

internal class WifiDirectSignalingSocket(
    private val port: Int,
    private val readyTimeoutMillis: Long,
    private val connectTimeoutMillis: Int,
    private val retryDelayMillis: Long,
    private val isSessionCurrent: () -> Boolean,
    private val onReady: (String, PhysicalSocketRole, PendingSocketLease) -> Unit,
    private val onFailure: (IOException) -> Unit,
    private val clock: MonotonicClock = MonotonicClock {
        MonotonicTimestamp(System.nanoTime() / 1_000_000L)
    },
    private val attemptContext: AttemptTaskContext? = null
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val io = Executors.newCachedThreadPool()
    private val lifecycleLock = Any()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val connectingSocket = AtomicReference<PendingSocketLease?>()
    private val pendingSockets = Collections.newSetFromMap(ConcurrentHashMap<PendingSocketLease, Boolean>())

    fun startServer(localAddress: InetAddress, remoteAllowed: (InetAddress) -> Boolean) {
        execute("signaling server failed") {
            val deadline = readyDeadline()
            var server: ServerSocket? = null
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(localAddress, port))
                }
                if (!publishServer(server)) return@execute

                while (isUsable()) {
                    val remaining = remainingUntil(deadline)
                    if (remaining <= 0L) break
                    server.soTimeout = min(ACCEPT_POLL_MILLIS.toLong(), remaining)
                        .coerceAtLeast(1L)
                        .toInt()
                    try {
                        val socket = server.accept()
                        val lease = registerPending(socket, passiveAdmissionDeadline())
                            ?: return@execute
                        if (isUsable() && remoteAllowed(socket.inetAddress)) {
                            handoff(lease, PhysicalSocketRole.ACCEPTOR)
                            return@execute
                        }
                        lease.close()
                    } catch (_: SocketTimeoutException) {
                    }
                }
                if (isUsable()) fail(IOException("signaling accept timeout"))
            } catch (t: Throwable) {
                fail(t.asIo("signaling server failed"))
            } finally {
                server?.let {
                    serverSocket.compareAndSet(it, null)
                    runCatching { it.close() }
                }
            }
        }
    }

    fun startClient(localAddress: InetAddress, remoteAddress: InetAddress) {
        execute("signaling client failed") {
            val deadline = readyDeadline()
            var last = IOException("signaling connect timeout")
            while (isUsable()) {
                val remaining = remainingUntil(deadline)
                if (remaining <= 0L) break
                val candidate = Socket()
                val lease = registerPending(candidate, clientAdmissionDeadline(deadline))
                    ?: return@execute
                var handedOff = false
                try {
                    if (!publishConnecting(lease)) return@execute
                    candidate.bind(InetSocketAddress(localAddress, 0))
                    val connectRemaining = remainingUntil(deadline)
                    if (connectRemaining <= 0L) return@execute
                    val connectTimeout = min(connectTimeoutMillis.toLong(), connectRemaining)
                        .coerceAtLeast(1L)
                        .toInt()
                    candidate.connect(InetSocketAddress(remoteAddress, port), connectTimeout)
                    if (!isUsable()) return@execute
                    connectingSocket.compareAndSet(lease, null)
                    handedOff = true
                    handoff(lease, PhysicalSocketRole.OPENER)
                    return@execute
                } catch (t: Throwable) {
                    last = t.asIo("signaling client failed")
                } finally {
                    connectingSocket.compareAndSet(lease, null)
                    if (!handedOff) lease.close()
                }

                try {
                    val retryDelay = min(retryDelayMillis, remainingUntil(deadline))
                    if (retryDelay <= 0L) break
                    Thread.sleep(retryDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            if (isUsable()) fail(last)
        }
    }

    private fun execute(message: String, block: () -> Unit) {
        if (!isUsable()) return
        try {
            io.execute(block)
        } catch (t: Throwable) {
            fail(t.asIo(message))
        }
    }

    private fun publishServer(socket: ServerSocket): Boolean {
        if (!isUsable() || !serverSocket.compareAndSet(null, socket) || !isUsable()) {
            runCatching { socket.close() }
            serverSocket.compareAndSet(socket, null)
            return false
        }
        return true
    }

    private fun publishConnecting(lease: PendingSocketLease): Boolean {
        if (!isUsable() || !connectingSocket.compareAndSet(null, lease) || !isUsable()) {
            lease.close()
            connectingSocket.compareAndSet(lease, null)
            return false
        }
        return true
    }

    private fun handoff(lease: PendingSocketLease, physicalRole: PhysicalSocketRole) {
        val socket = lease.socket
        val accepted = synchronized(lifecycleLock) {
            if (!isUsable() || !socket.isConnected || socket.isClosed ||
                !terminal.compareAndSet(false, true) || !isUsable()
            ) false else true
        }
        if (!accepted) {
            lease.close()
            return
        }
        try {
            onReady(socket.inetAddress.hostAddress.orEmpty(), physicalRole, lease)
        } catch (t: Throwable) {
            lease.close()
            notifyFailure(t.asIo("signaling handoff failed"))
        }
    }

    private fun registerPending(socket: Socket, deadline: Long): PendingSocketLease? {
        val lease = synchronized(lifecycleLock) {
            if (!isUsable()) null else PendingSocketLease(
                socket, attemptContext?.attempt, deadline, clock,
                onReleased = {
                    pendingSockets.remove(it)
                    if (it.currentStage == PendingSocketLease.Stage.CLOSED && terminal.get() && isUsable()) {
                        notifyFailure(IOException("pending signaling Socket closed before Service admission"))
                    }
                }
            ).also { pendingSockets.add(it) }
        }
        if (lease == null) runCatching { socket.close() }
        else lease.armAdmissionDeadline()
        return lease
    }

    private fun passiveAdmissionDeadline(): Long = attemptContext?.attempt?.deadlineElapsedRealtimeMs
        ?: Math.addExact(clock.now().elapsedRealtimeMs, PendingSocketLease.PASSIVE_ADMISSION_TIMEOUT_MS)

    private fun clientAdmissionDeadline(readyDeadline: Long): Long =
        attemptContext?.attempt?.deadlineElapsedRealtimeMs
            ?: Math.addExact(readyDeadline, PendingSocketLease.PASSIVE_ADMISSION_TIMEOUT_MS)

    private fun readyDeadline(): Long {
        val now = clock.now().elapsedRealtimeMs
        val localDeadline = Math.addExact(now, readyTimeoutMillis)
        return minOf(localDeadline, attemptContext?.attempt?.deadlineElapsedRealtimeMs ?: localDeadline)
    }

    private fun remainingUntil(deadline: Long): Long =
        (deadline - clock.now().elapsedRealtimeMs).coerceAtLeast(0L)

    private fun isUsable(): Boolean =
        !closed.get() &&
            isSessionCurrent() &&
            (attemptContext == null || attemptContext.attempt.remainingMillis(clock) > 0L)

    private fun fail(error: IOException) {
        val report = synchronized(lifecycleLock) { isUsable() && terminal.compareAndSet(false, true) }
        if (report) notifyFailure(error)
    }

    private fun notifyFailure(error: IOException) {
        if (isUsable() && failureReported.compareAndSet(false, true)) onFailure(error)
    }

    private fun Throwable.asIo(message: String): IOException =
        this as? IOException ?: IOException(message, this)

    override fun close() {
        val resources = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            connectingSocket.set(null)
            serverSocket.getAndSet(null) to pendingSockets.toTypedArray().toList()
        }
        resources.first?.let { runCatching { it.close() } }
        resources.second.forEach { it.close() }
        io.shutdownNow()
    }

    private companion object {
        const val ACCEPT_POLL_MILLIS = 500
    }
}
