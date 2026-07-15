package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class SignalingSessionV2Test {
    @Test
    fun requesterFirstHelloWorksForEveryProductAndPhysicalRoleCombination() {
        PhysicalSocketRole.entries.forEach { requesterPhysicalRole ->
            socketPair().use { sockets ->
                val requesterSocket = sockets.forRole(requesterPhysicalRole)
                val responderPhysicalRole = requesterPhysicalRole.opposite()
                val responderSocket = sockets.forRole(responderPhysicalRole)
                val requesterAttempt = attempt(
                    ATTEMPT_A,
                    SESSION_A,
                    DEVICE_B,
                    SESSION_B
                )
                val requester = CompletableFuture.supplyAsync {
                    establish(
                        socket = requesterSocket,
                        physicalRole = requesterPhysicalRole,
                        localDeviceId = DEVICE_A,
                        localSessionId = SESSION_A,
                        originatingAttempt = requesterAttempt
                    )
                }

                Thread.sleep(100)
                assertFalse(requester.isDone)

                val responder = CompletableFuture.supplyAsync {
                    establish(
                        socket = responderSocket,
                        physicalRole = responderPhysicalRole,
                        localDeviceId = DEVICE_B,
                        localSessionId = SESSION_B,
                        originatingAttempt = null,
                        expectedRemoteTargetLock = TargetLock(
                            DEVICE_A,
                            RuntimeSessionId(SESSION_A)
                        )
                    )
                }
                val requesterSession = requester.get(2, TimeUnit.SECONDS)
                val responderSession = responder.get(2, TimeUnit.SECONDS)
                try {
                    assertEquals(RequestRole.REQUESTER, requesterSession.requestRole)
                    assertEquals(requesterPhysicalRole, requesterSession.channel.physicalRole)
                    assertEquals(SignalingPhase.READY_TO_SEND_CONNECT_REQUEST, requesterSession.phase)
                    assertEquals(WebRtcRole.OFFERER, requesterSession.requestRole.webRtcRole)
                    assertEquals(RequestRole.RESPONDER, responderSession.requestRole)
                    assertEquals(responderPhysicalRole, responderSession.channel.physicalRole)
                    assertEquals(SignalingPhase.AWAITING_CONNECT_REQUEST, responderSession.phase)
                    assertEquals(WebRtcRole.ANSWERER, responderSession.requestRole.webRtcRole)
                    assertEquals(requesterSession.wireRequestKey, responderSession.wireRequestKey)
                    assertEquals(DEVICE_B, requesterSession.peer.deviceId)
                    assertEquals(RuntimeSessionId(SESSION_B), requesterSession.peer.runtimeSessionId)
                    assertTrue(requesterSession.peer.isDeviceIdVerified)
                    assertEquals(requesterAttempt, requesterSession.originatingAttempt)
                    assertEquals(null, responderSession.originatingAttempt)
                    assertTrue(
                        canRegisterControlChannel(
                            sessionCurrent = true,
                            currentAttempt = requesterAttempt,
                            session = requesterSession
                        )
                    )
                    assertFalse(
                        canRegisterControlChannel(
                            sessionCurrent = true,
                            currentAttempt = null,
                            session = requesterSession
                        )
                    )
                    assertTrue(
                        canRegisterControlChannel(
                            sessionCurrent = true,
                            currentAttempt = null,
                            session = responderSession
                        )
                    )
                } finally {
                    requesterSession.close()
                    responderSession.close()
                }
            }
        }
    }

    @Test
    fun simultaneousRequesterHelloChoosesOneWireRequestWithoutUsingSocketRole() {
        socketPair().use { sockets ->
            val attemptA = attempt(ATTEMPT_A, SESSION_A, DEVICE_B, SESSION_B)
            val attemptB = attempt(ATTEMPT_B, SESSION_B, DEVICE_A, SESSION_A)
            val sessionA = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.acceptor,
                    physicalRole = PhysicalSocketRole.ACCEPTOR,
                    localDeviceId = DEVICE_A,
                    localSessionId = SESSION_A,
                    originatingAttempt = attemptA
                )
            }
            val sessionB = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.opener,
                    physicalRole = PhysicalSocketRole.OPENER,
                    localDeviceId = DEVICE_B,
                    localSessionId = SESSION_B,
                    originatingAttempt = attemptB
                )
            }

            val resultA = sessionA.get(2, TimeUnit.SECONDS)
            val resultB = sessionB.get(2, TimeUnit.SECONDS)
            try {
                assertEquals(RequestRole.REQUESTER, resultA.requestRole)
                assertEquals(RequestRole.RESPONDER, resultB.requestRole)
                assertEquals(attemptA.id, resultA.wireRequestKey.attemptId)
                assertEquals(resultA.wireRequestKey, resultB.wireRequestKey)
                assertEquals(PhysicalSocketRole.ACCEPTOR, resultA.channel.physicalRole)
                assertEquals(PhysicalSocketRole.OPENER, resultB.channel.physicalRole)
            } finally {
                resultA.close()
                resultB.close()
            }
        }
    }

    @Test
    fun emptyHelloIdentityFieldsFailClosedWithoutVerifiedChannelHandoff() {
        val validFields = linkedMapOf(
            "attemptId" to ATTEMPT_A,
            "sourceDeviceId" to DEVICE_A,
            "targetDeviceId" to DEVICE_B,
            "sourceSessionId" to SESSION_A
        )

        validFields.keys.forEach { emptyField ->
            socketPair().use { sockets ->
                var establishedSession: SignalingSessionV2? = null
                val responder = CompletableFuture.supplyAsync {
                    establish(
                        socket = sockets.acceptor,
                        physicalRole = PhysicalSocketRole.ACCEPTOR,
                        localDeviceId = DEVICE_B,
                        localSessionId = SESSION_B,
                        originatingAttempt = null,
                        expectedRemoteTargetLock = TargetLock(
                            DEVICE_A,
                            RuntimeSessionId(SESSION_A)
                        )
                    ).also { establishedSession = it }
                }
                val fields = validFields.toMutableMap().apply {
                    this[emptyField] = ""
                }
                val rawHello = """
                    {
                      "protocolVersion": 2,
                      "type": "HELLO",
                      "attemptId": "${fields.getValue("attemptId")}",
                      "sourceDeviceId": "${fields.getValue("sourceDeviceId")}",
                      "targetDeviceId": "${fields.getValue("targetDeviceId")}",
                      "sourceSessionId": "${fields.getValue("sourceSessionId")}",
                      "payload": {
                        "requestRole": "REQUESTER",
                        "capabilities": []
                      }
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8)

                SignalingV2Framing.write(
                    java.io.DataOutputStream(sockets.opener.getOutputStream()),
                    rawHello
                )

                assertSignalingFailure(responder)
                assertTrue("$emptyField must close the current Socket", sockets.acceptor.isClosed)
                assertEquals("$emptyField must not create a verified session", null, establishedSession)
            }
        }
    }

    @Test
    fun wrongTargetFailsClosedOnBothEndpoints() {
        socketPair().use { sockets ->
            val requester = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.opener,
                    physicalRole = PhysicalSocketRole.OPENER,
                    localDeviceId = DEVICE_A,
                    localSessionId = SESSION_A,
                    originatingAttempt = attempt(
                        ATTEMPT_A,
                        SESSION_A,
                        DEVICE_C,
                        SESSION_B
                    )
                )
            }
            val responder = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.acceptor,
                    physicalRole = PhysicalSocketRole.ACCEPTOR,
                    localDeviceId = DEVICE_B,
                    localSessionId = SESSION_B,
                    originatingAttempt = null,
                    expectedRemoteTargetLock = TargetLock(
                        DEVICE_A,
                        RuntimeSessionId(SESSION_A)
                    )
                )
            }

            assertSignalingFailure(requester)
            assertSignalingFailure(responder)
            assertTrue(sockets.opener.isClosed)
            assertTrue(sockets.acceptor.isClosed)
        }
    }

    @Test
    fun supersededRemoteSessionFailsTheRequesterTargetLock() {
        socketPair().use { sockets ->
            val requester = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.opener,
                    physicalRole = PhysicalSocketRole.OPENER,
                    localDeviceId = DEVICE_A,
                    localSessionId = SESSION_A,
                    originatingAttempt = attempt(
                        ATTEMPT_A,
                        SESSION_A,
                        DEVICE_B,
                        SESSION_C
                    )
                )
            }
            val responder = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.acceptor,
                    physicalRole = PhysicalSocketRole.ACCEPTOR,
                    localDeviceId = DEVICE_B,
                    localSessionId = SESSION_B,
                    originatingAttempt = null,
                    expectedRemoteTargetLock = TargetLock(
                        DEVICE_A,
                        RuntimeSessionId(SESSION_A)
                    )
                )
            }

            val responderSession = responder.get(2, TimeUnit.SECONDS)
            try {
                assertSignalingFailure(requester)
                assertTrue(sockets.opener.isClosed)
            } finally {
                responderSession.close()
            }
        }
    }

    @Test
    fun thirdDeviceCannotSatisfyTheRequesterTargetLock() {
        socketPair().use { sockets ->
            val requester = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.opener,
                    physicalRole = PhysicalSocketRole.OPENER,
                    localDeviceId = DEVICE_A,
                    localSessionId = SESSION_A,
                    originatingAttempt = attempt(
                        ATTEMPT_A,
                        SESSION_A,
                        DEVICE_B,
                        SESSION_B
                    )
                )
            }
            val thirdDevice = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.acceptor,
                    physicalRole = PhysicalSocketRole.ACCEPTOR,
                    localDeviceId = DEVICE_C,
                    localSessionId = SESSION_C,
                    originatingAttempt = null,
                    expectedRemoteTargetLock = TargetLock(
                        DEVICE_A,
                        RuntimeSessionId(SESSION_A)
                    )
                )
            }

            assertSignalingFailure(requester)
            assertSignalingFailure(thirdDevice)
            assertTrue(sockets.opener.isClosed)
            assertTrue(sockets.acceptor.isClosed)
        }
    }

    @Test
    fun responderAttemptMismatchIsRejectedBeforeSessionHandoff() {
        socketPair().use { sockets ->
            val requester = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.opener,
                    physicalRole = PhysicalSocketRole.OPENER,
                    localDeviceId = DEVICE_A,
                    localSessionId = SESSION_A,
                    originatingAttempt = attempt(
                        ATTEMPT_A,
                        SESSION_A,
                        DEVICE_B,
                        SESSION_B
                    )
                )
            }
            val input = java.io.DataInputStream(sockets.acceptor.getInputStream())
            val output = java.io.DataOutputStream(sockets.acceptor.getOutputStream())
            val request = SignalingV2Codec().decode(SignalingV2Framing.read(input))
            assertEquals(ConnectionAttemptId(ATTEMPT_A), request.attemptId)
            SignalingV2Framing.write(
                output,
                SignalingV2Codec().encode(
                    SignalingEnvelopeV2(
                        attemptId = ConnectionAttemptId(ATTEMPT_B),
                        sourceDeviceId = DeviceId.parse(DEVICE_B),
                        targetDeviceId = DeviceId.parse(DEVICE_A),
                        sourceSessionId = RuntimeSessionId(SESSION_B),
                        message = SignalingMessageV2.Hello(RequestRole.RESPONDER)
                    )
                )
            )

            assertSignalingFailure(requester)
            assertTrue(sockets.opener.isClosed)
        }
    }

    @Test
    fun silentPeerTimesOutAndClosesCurrentSocket() {
        socketPair().use { sockets ->
            val requester = CompletableFuture.supplyAsync {
                establish(
                    socket = sockets.opener,
                    physicalRole = PhysicalSocketRole.OPENER,
                    localDeviceId = DEVICE_A,
                    localSessionId = SESSION_A,
                    originatingAttempt = attempt(
                        ATTEMPT_A,
                        SESSION_A,
                        DEVICE_B,
                        SESSION_B
                    )
                )
            }

            assertSignalingFailure(requester)
            assertTrue(sockets.opener.isClosed)
        }
    }

    private fun establish(
        socket: Socket,
        physicalRole: PhysicalSocketRole,
        localDeviceId: String,
        localSessionId: String,
        originatingAttempt: ConnectionAttempt?,
        expectedRemoteTargetLock: TargetLock? = originatingAttempt?.targetLock
    ) = SignalingSessionV2.establish(
        socket = socket,
        transport = Transport.LAN,
        physicalRole = physicalRole,
        openedAtElapsedMs = 10L,
        localDeviceId = localDeviceId,
        localRuntimeSessionId = RuntimeSessionId(localSessionId),
        localNickname = "Rider-${localDeviceId.first()}",
        localDeviceName = "Phone-${localDeviceId.first()}",
        originatingAttempt = originatingAttempt,
        expectedRemoteTargetLock = expectedRemoteTargetLock
    )

    private fun attempt(
        attemptId: String,
        localSessionId: String,
        remoteDeviceId: String,
        remoteSessionId: String
    ) = ConnectionAttempt(
        id = ConnectionAttemptId(attemptId),
        runtimeSessionId = RuntimeSessionId(localSessionId),
        targetLock = TargetLock(remoteDeviceId, RuntimeSessionId(remoteSessionId)),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 10_000L
    )

    private fun assertSignalingFailure(future: CompletableFuture<SignalingSessionV2>) {
        val error = assertThrows(ExecutionException::class.java) {
            future.get(2, TimeUnit.SECONDS)
        }
        assertTrue(error.cause is SignalingV2Exception)
    }

    private fun socketPair(): SocketPair = ServerSocket(0).use { server ->
        val accepted = CompletableFuture.supplyAsync { server.accept() }
        val opener = Socket("127.0.0.1", server.localPort)
        SocketPair(opener, accepted.get(2, TimeUnit.SECONDS))
    }

    private data class SocketPair(
        val opener: Socket,
        val acceptor: Socket
    ) : AutoCloseable {
        fun forRole(role: PhysicalSocketRole): Socket = when (role) {
            PhysicalSocketRole.OPENER -> opener
            PhysicalSocketRole.ACCEPTOR -> acceptor
        }

        override fun close() {
            runCatching { opener.close() }
            runCatching { acceptor.close() }
        }
    }

    private fun PhysicalSocketRole.opposite(): PhysicalSocketRole = when (this) {
        PhysicalSocketRole.OPENER -> PhysicalSocketRole.ACCEPTOR
        PhysicalSocketRole.ACCEPTOR -> PhysicalSocketRole.OPENER
    }

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val DEVICE_C = "c0000000-0000-4000-8000-000000000003"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val SESSION_B = "10000000-0000-4000-8000-000000000002"
        const val SESSION_C = "10000000-0000-4000-8000-000000000003"
        const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
        const val ATTEMPT_B = "20000000-0000-4000-8000-000000000002"
    }
}
