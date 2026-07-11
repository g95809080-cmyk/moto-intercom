package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WifiDirectSignalingSocketTest {
    @Test
    fun staleSessionClosesSocketWithoutReadyCallback() {
        var active = true
        var ready = false
        val failed = CountDownLatch(1)
        val probe = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val transport = WifiDirectSignalingSocket(
            port = probe.localPort,
            readyTimeoutMillis = 1_000,
            connectTimeoutMillis = 500,
            retryDelayMillis = 10,
            isSessionCurrent = { active },
            onReady = { _, _, socket -> ready = true; socket.close() },
            onFailure = { failed.countDown() }
        )
        probe.close()
        active = false

        transport.startClient(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress())
        failed.await(2, TimeUnit.SECONDS)
        transport.close()

        assertFalse(ready)
    }

    @Test
    fun serverHandsOffOneAllowedLoopbackPeer() {
        val ready = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val server = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { true },
            { _, server, socket -> if (server) ready.countDown(); socket.close() },
            { }
        )
        val client = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { true },
            { _, _, socket -> socket.close() },
            { }
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            server.startServer(loopback) { it.isLoopbackAddress }
            client.startClient(loopback, loopback)
            assertTrue(ready.await(2, TimeUnit.SECONDS))
        } finally {
            server.close()
            client.close()
        }
    }

    @Test
    fun closeUnblocksServerWithoutFailureCallback() {
        val failed = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(
            port, 5_000, 500, 10, { true },
            { _, _, socket -> socket.close() },
            { failed.countDown() }
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            transport.startServer(loopback) { false }
            assertTrue(waitUntil(1_000) { canConnect(loopback, port) })
            transport.close()
            assertFalse(failed.await(200, TimeUnit.MILLISECONDS))
        } finally {
            transport.close()
        }
    }

    @Test
    fun sessionInvalidatedDuringAcceptDoesNotHandoff() {
        var active = true
        val ready = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { active },
            { _, _, socket -> ready.countDown(); socket.close() },
            { failed.countDown() }
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            transport.startServer(loopback) {
                active = false
                true
            }
            assertTrue(waitUntil(1_000) { canConnect(loopback, port) })
            assertFalse(ready.await(200, TimeUnit.MILLISECONDS))
            assertFalse(failed.await(200, TimeUnit.MILLISECONDS))
        } finally {
            transport.close()
        }
    }

    private fun canConnect(address: InetAddress, port: Int): Boolean =
        runCatching { Socket(address, port).use { } }.isSuccess

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
