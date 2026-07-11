package com.kuma.motointercom

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class WifiDirectSignalingSocket(
    private val port: Int,
    private val readyTimeoutMillis: Long,
    private val connectTimeoutMillis: Int,
    private val retryDelayMillis: Long,
    private val isSessionCurrent: () -> Boolean,
    private val onReady: (String, Boolean, Socket) -> Unit,
    private val onFailure: (IOException) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val io = Executors.newCachedThreadPool()
    private val lifecycleLock = Any()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val connectingSocket = AtomicReference<Socket?>()

    fun startServer(localAddress: InetAddress, remoteAllowed: (InetAddress) -> Boolean) {
        execute("signaling server failed") {
            val deadline = System.currentTimeMillis() + readyTimeoutMillis
            var server: ServerSocket? = null
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = ACCEPT_POLL_MILLIS
                    bind(InetSocketAddress(localAddress, port))
                }
                if (!publishServer(server)) return@execute

                while (isUsable() && System.currentTimeMillis() < deadline) {
                    try {
                        val socket = server.accept()
                        if (isUsable() && remoteAllowed(socket.inetAddress)) {
                            handoff(socket, isServer = true)
                            return@execute
                        }
                        socket.close()
                    } catch (_: SocketTimeoutException) {
                    }
                }
                fail(IOException("signaling accept timeout"))
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
            val deadline = System.currentTimeMillis() + readyTimeoutMillis
            var last = IOException("signaling connect timeout")
            while (isUsable() && System.currentTimeMillis() < deadline) {
                val candidate = Socket()
                var socket: Socket? = candidate
                try {
                    if (!publishConnecting(candidate)) return@execute
                    candidate.bind(InetSocketAddress(localAddress, 0))
                    candidate.connect(InetSocketAddress(remoteAddress, port), connectTimeoutMillis)
                    connectingSocket.compareAndSet(candidate, null)
                    val connected = candidate
                    socket = null
                    handoff(connected, isServer = false)
                    return@execute
                } catch (t: Throwable) {
                    last = t.asIo("signaling client failed")
                } finally {
                    socket?.let {
                        connectingSocket.compareAndSet(it, null)
                        runCatching { it.close() }
                    }
                }

                try {
                    Thread.sleep(retryDelayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            fail(last)
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

    private fun publishConnecting(socket: Socket): Boolean {
        if (!isUsable() || !connectingSocket.compareAndSet(null, socket) || !isUsable()) {
            runCatching { socket.close() }
            connectingSocket.compareAndSet(socket, null)
            return false
        }
        return true
    }

    private fun handoff(socket: Socket, isServer: Boolean) {
        synchronized(lifecycleLock) {
            if (!isUsable() || !socket.isConnected || socket.isClosed ||
                !terminal.compareAndSet(false, true) || !isUsable()
            ) {
                socket.close()
                return
            }
            try {
                onReady(socket.inetAddress.hostAddress.orEmpty(), isServer, socket)
            } catch (t: Throwable) {
                socket.close()
                if (isUsable()) onFailure(t.asIo("signaling handoff failed"))
            }
        }
    }

    private fun isUsable(): Boolean = !closed.get() && isSessionCurrent()

    private fun fail(error: IOException) {
        synchronized(lifecycleLock) {
            if (isUsable() && terminal.compareAndSet(false, true)) onFailure(error)
        }
    }

    private fun Throwable.asIo(message: String): IOException =
        this as? IOException ?: IOException(message, this)

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
            connectingSocket.getAndSet(null)?.let { runCatching { it.close() } }
        }
        io.shutdownNow()
    }

    private companion object {
        const val ACCEPT_POLL_MILLIS = 500
    }
}
