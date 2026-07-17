package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class LegacyIdentityHandshakeTest {
    @Test
    fun matchingSocketIdentitiesAreVerifiedBidirectionally() {
        ServerSocket(0).use { server ->
            val serverPeer = CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    LegacyIdentityHandshake.exchange(
                        socket,
                        identity("device-b", "session-b", "Rider B", "Phone B"),
                        TargetLock("device-a", RuntimeSessionId("session-a"))
                    )
                }
            }

            val clientPeer = Socket("127.0.0.1", server.localPort).use { socket ->
                LegacyIdentityHandshake.exchange(
                    socket,
                    identity("device-a", "session-a", "Rider A", "Phone A"),
                    TargetLock("device-b", RuntimeSessionId("session-b"))
                )
            }

            assertEquals("device-b", clientPeer.deviceId)
            assertEquals(RuntimeSessionId("session-b"), clientPeer.runtimeSessionId)
            assertEquals("Phone B", clientPeer.deviceName)
            assertTrue(clientPeer.isDeviceIdVerified)
            assertEquals("device-a", serverPeer.get(2, TimeUnit.SECONDS).deviceId)
        }
    }

    @Test
    fun missingOrMismatchedTargetIdentityFailsClosed() {
        val invalidClaims = listOf(
            SignalingProtocol.Message.Identity("Missing Session", "device-b"),
            identity("device-b", "session-new", "Restarted Rider", "Phone B"),
            identity("device-c", "session-b", "Wrong Rider", "Phone C")
        )

        invalidClaims.forEach { claim ->
            ServerSocket(0).use { server ->
                val serverResult = CompletableFuture.supplyAsync {
                    val socket = server.accept()
                    val result = runCatching {
                        LegacyIdentityHandshake.exchange(
                            socket,
                            identity("device-a", "session-a", "Rider A", "Phone A"),
                            TargetLock("device-b", RuntimeSessionId("session-b"))
                        )
                    }
                    result to socket.isClosed
                }

                Socket("127.0.0.1", server.localPort).use { socket ->
                    writeIdentityAndReadResponse(socket, claim)
                }

                val (result, socketClosed) = serverResult.get(2, TimeUnit.SECONDS)
                assertTrue(result.exceptionOrNull() is SignalingProtocol.ProtocolException)
                assertTrue(socketClosed)
            }
        }
    }

    @Test
    fun wrongDeviceSocketDoesNotPreventTheLockedDeviceFromConnectingNext() {
        ServerSocket(0).use { server ->
            val serverPeer = CompletableFuture.supplyAsync {
                server.accept().use { wrongSocket ->
                    runCatching {
                        LegacyIdentityHandshake.exchange(
                            wrongSocket,
                            identity("device-a", "session-a", "Rider A", "Phone A"),
                            TargetLock("device-b", RuntimeSessionId("session-b"))
                        )
                    }
                }
                server.accept().use { expectedSocket ->
                    LegacyIdentityHandshake.exchange(
                        expectedSocket,
                        identity("device-a", "session-a", "Rider A", "Phone A"),
                        TargetLock("device-b", RuntimeSessionId("session-b"))
                    )
                }
            }

            Socket("127.0.0.1", server.localPort).use { wrongSocket ->
                writeIdentityAndReadResponse(
                    wrongSocket,
                    identity("device-c", "session-c", "Rider C", "Phone C")
                )
            }
            Socket("127.0.0.1", server.localPort).use { expectedSocket ->
                val peer = LegacyIdentityHandshake.exchange(
                    expectedSocket,
                    identity("device-b", "session-b", "Rider B", "Phone B"),
                    TargetLock("device-a", RuntimeSessionId("session-a"))
                )
                assertEquals("device-a", peer.deviceId)
            }

            assertEquals("device-b", serverPeer.get(2, TimeUnit.SECONDS).deviceId)
        }
    }

    @Test
    fun silentPeerIsRejectedWithinTheBoundedReadTimeout() {
        ServerSocket(0).use { server ->
            val serverResult = CompletableFuture.supplyAsync {
                server.accept().use { socket ->
                    LegacyIdentityHandshake.exchange(
                        socket,
                        identity("device-a", "session-a", "Rider A", "Phone A"),
                        TargetLock("device-b", RuntimeSessionId("session-b"))
                    )
                }
            }

            Socket("127.0.0.1", server.localPort).use {
                val error = assertThrows(ExecutionException::class.java) {
                    serverResult.get(2, TimeUnit.SECONDS)
                }
                assertTrue(error.cause is java.net.SocketTimeoutException)
            }
        }
    }

    private fun identity(
        deviceId: String,
        sessionId: String,
        name: String,
        deviceName: String
    ) = SignalingProtocol.Message.Identity(
        name = name,
        deviceId = deviceId,
        runtimeSessionId = sessionId,
        deviceName = deviceName
    )

    private fun writeIdentityAndReadResponse(
        socket: Socket,
        identity: SignalingProtocol.Message.Identity
    ) {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        val frame = protocol.encode(identity)
        DataOutputStream(socket.getOutputStream()).apply {
            writeInt(frame.size)
            write(frame)
            flush()
        }
        val input = DataInputStream(socket.getInputStream())
        val responseLength = input.readInt()
        ByteArray(responseLength).also(input::readFully)
    }
}
