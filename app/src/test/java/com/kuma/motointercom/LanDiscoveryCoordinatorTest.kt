package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun handshakeRejectsInvalidConnectionThenCompletesBidirectionalExchange() {
        ServerSocket(0).use { server ->
            val result = java.util.concurrent.CompletableFuture.supplyAsync {
                server.accept().use { first ->
                    val invalid = LanTunnelHandshake.exchangeAsServer(first, "server")
                    server.accept().use { second ->
                        invalid to LanTunnelHandshake.exchangeAsServer(second, "server")
                    }
                }
            }

            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.getOutputStream().write(byteArrayOf(0x12, 0x34, 0x56, 0x78))
            }
            val clientRemoteId = Socket("127.0.0.1", server.localPort).use { socket ->
                LanTunnelHandshake.exchangeAsClient(socket, "client", "server")
            }

            val (invalidDeviceId, validDeviceId) = result.get(2, TimeUnit.SECONDS)
            assertNull(invalidDeviceId)
            assertEquals("client", validDeviceId)
            assertEquals("server", clientRemoteId)
        }
    }

    @Test
    fun handshakeRejectsSilentConnectionWithinBoundedTime() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use {
                server.accept().use { socket ->
                    val started = System.nanoTime()
                    assertNull(LanTunnelHandshake.exchangeAsServer(socket, "server"))
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_800)
                }
            }
        }
    }

    @Test
    fun clientRejectsSocketIdentityThatDiffersFromDiscoveryTarget() {
        ServerSocket(0).use { server ->
            val serverResult = java.util.concurrent.CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    LanTunnelHandshake.exchangeAsServer(socket, "server-actual")
                }
            }

            val clientResult = Socket("127.0.0.1", server.localPort).use { socket ->
                LanTunnelHandshake.exchangeAsClient(
                    socket,
                    localNodeId = "client",
                    expectedRemoteNodeId = "server-from-discovery"
                )
            }

            assertNull(clientResult)
            assertEquals("client", serverResult.get(2, TimeUnit.SECONDS))
        }
    }
}
