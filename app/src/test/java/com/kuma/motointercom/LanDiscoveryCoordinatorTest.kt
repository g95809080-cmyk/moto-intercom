package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class LanDiscoveryCoordinatorTest {
    @Test
    fun higherIpv4AddressInitiatesClient() {
        assertTrue(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.20", "192.168.1.10"))
        assertFalse(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.10", "192.168.1.20"))
        assertFalse(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.10", "192.168.1.10"))
    }

    @Test
    fun handshakeRejectsInvalidConnectionThenAcceptsMotoComPeer() {
        ServerSocket(0).use { server ->
            val result = java.util.concurrent.CompletableFuture.supplyAsync {
                server.accept().use { first ->
                    val invalid = LanTunnelHandshake.read(first, "server")
                    server.accept().use { second ->
                        invalid to LanTunnelHandshake.read(second, "server")
                    }
                }
            }

            Socket("127.0.0.1", server.localPort).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(0x12345678)
                    flush()
                }
            }
            Socket("127.0.0.1", server.localPort).use { socket ->
                LanTunnelHandshake.write(socket, "client")
            }

            val (invalidAccepted, validAccepted) = result.get(2, TimeUnit.SECONDS)
            assertFalse(invalidAccepted)
            assertTrue(validAccepted)
        }
    }

    @Test
    fun handshakeRejectsSilentConnectionWithinBoundedTime() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use {
                server.accept().use { socket ->
                    val started = System.nanoTime()
                    assertFalse(LanTunnelHandshake.read(socket, "server"))
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_800)
                }
            }
        }
    }
}
