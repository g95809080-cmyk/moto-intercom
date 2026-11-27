package com.kuma.motointercom.group

import com.kuma.motointercom.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GroupLegacyBusyServerTest {
    @Test fun realLegacyHelloRequestGetsBusyWithoutGroupOrMediaAdmission() {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val server = GroupLegacyBusyServer(endpoint, "房主", emptyList())
        val listener = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val done = worker.submit { listener.accept().use(server::respond) }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 3_000
                val codec = SignalingV2Codec()
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                val hello = SignalingEnvelopeV2(attemptId = ConnectionAttemptId.create(), sourceDeviceId = DeviceId.parse(UUID.randomUUID().toString()),
                    targetDeviceId = DeviceId.parse(endpoint.deviceId), sourceSessionId = RuntimeSessionId.create(),
                    message = SignalingMessageV2.Hello(RequestRole.REQUESTER, "车友"))
                SignalingV2Framing.write(output, codec.encode(hello))
                val reply = codec.decode(SignalingV2Framing.read(input))
                assertEquals(RequestRole.RESPONDER, (reply.message as SignalingMessageV2.Hello).requestRole)
                SignalingV2Framing.write(output, codec.encode(hello.copy(message = SignalingMessageV2.ConnectRequest(RequestTrigger.USER))))
                val busy = codec.decode(SignalingV2Framing.read(input))
                assertTrue(busy.message is SignalingMessageV2.Busy)
                assertEquals(hello.attemptId, busy.attemptId)
                assertEquals(hello.sourceDeviceId, busy.targetDeviceId)
            }
            done.get(3, TimeUnit.SECONDS)
        } finally { server.close(); listener.close(); worker.shutdownNow() }
    }
    @Test fun groupOwnerRemainsUntilExactCleanupOwnerReleasesIt() {
        val token = GroupRuntimeOwnership.acquire()!!
        try {
            GroupRuntimeOwnership.release(Any())
            assertTrue(GroupRuntimeOwnership.hasOwner()); assertNull(GroupRuntimeOwnership.acquire())
        } finally { GroupRuntimeOwnership.release(token) }
        assertFalse(GroupRuntimeOwnership.hasOwner())
    }
}
