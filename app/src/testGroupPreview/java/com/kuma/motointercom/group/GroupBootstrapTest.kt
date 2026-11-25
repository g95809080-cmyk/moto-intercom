package com.kuma.motointercom.group

import com.kuma.motointercom.group.network.GroupWifiCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class GroupBootstrapTest {
    private fun id(value: Long) = UUID(0, value).toString()
    private val descriptor = GroupDescriptor(GroupRoomKey(id(1), id(2)), GroupAuthEndpoint(id(3), id(2)), "房主")
    private val client = GroupAuthEndpoint(id(4), id(5))
    private val code = GroupJoinCode("001234")
    private var time = 0L
    private var current = true
    private var allowed = true
    private var disclosed = 0
    private fun host() = GroupBootstrapHostSession(descriptor, code, {
        disclosed++; GroupNetworkDescriptor(GroupWifiCredentials("DIRECT-test", "super-secret"), "192.168.49.1", 8899)
    }, { allowed }, { time }, { current })
    @Test fun realPakeSurvivesNegotiatedFragmentsWithRealisticAttRoundTripBudget() = runBlocking {
        val server = host()
        fun transport(bytes: ByteArray): ByteArray {
            val assembler = com.kuma.motointercom.group.network.GroupBleAssembler()
            var result: ByteArray? = null
            com.kuma.motointercom.group.network.GroupBleChunks.split(bytes, 244).forEach {
                time += 45 // bounded simulated ATT request/response latency
                result = assembler.accept(it)
            }
            return requireNotNull(result)
        }
        val match = authenticateGroupCandidate(client, code, { time }, { current }) { bytes ->
            transport(server.respond(transport(bytes)))
        }
        assertEquals(descriptor, match.descriptor)
        assertTrue(time < GroupAuthentication.TIMEOUT_MS)
        assertEquals(1, disclosed)
        server.close()
    }
    @Test fun realPakeOverFragmentSizedRequestResponseDisclosesOnlyAfterCompleteConfirmation() = runBlocking {
        val host = host()
        val packets = mutableListOf<ByteArray>()
        val match = authenticateGroupCandidate(client, code, { time }, { current }) { bytes ->
            packets += bytes
            val response = host.respond(bytes)
            packets += response
            response
        }
        assertEquals(descriptor, match.descriptor)
        assertEquals("super-secret", match.network.credentials.passphrase)
        assertEquals(1, disclosed)
        assertEquals(10, packets.size)
        packets.forEach { packet ->
            assertFalse(packet.toString(Charsets.ISO_8859_1).contains("super-secret"))
            assertTrue(packet.size <= 8192)
        }
        host.close()
    }
    @Test fun wrongCodeAndDifferentExpectedRoomCannotDiscloseNetwork() {
        assertThrows(IOException::class.java) { runBlocking {
            val host = host()
            authenticateGroupCandidate(client, GroupJoinCode("001235"), { time }, { current }, exchange = host::respond)
        } }
        assertEquals(0, disclosed)
        assertThrows(IOException::class.java) { runBlocking {
            val host = host()
            authenticateGroupCandidate(client, code, { time }, { current }, GroupRoomKey(id(99), id(2)), host::respond)
        } }
        assertEquals(0, disclosed)
    }
    @Test fun removedDuringAuthenticationAndCancelledFinalResponseAreNotMatches() {
        assertThrows(IOException::class.java) { runBlocking {
            val host = host()
            authenticateGroupCandidate(client, code, { time }, { current }) { request ->
                if (GroupBootstrapCodec.decode(request) is GroupBootstrapMessage.PrivateRequest) allowed = false
                host.respond(request)
            }
        } }
        assertEquals(0, disclosed)
        allowed = true
        assertThrows(IOException::class.java) { runBlocking {
            val host = host()
            authenticateGroupCandidate(client, code, { time }, { current }) { request ->
                host.respond(request).also {
                    if (GroupBootstrapCodec.decode(it) is GroupBootstrapMessage.PrivateResponse) current = false
                }
            }
        } }
    }
    @Test fun deadlineAndOutOfOrderMessagesCloseExchange() {
        val host = host()
        host.respond(GroupBootstrapCodec.encode(GroupBootstrapMessage.Describe))
        time = 20_000
        assertThrows(IOException::class.java) { host.respond(GroupBootstrapCodec.encode(GroupBootstrapMessage.Describe)) }
        time = 0
        val bad = host()
        assertThrows(IOException::class.java) {
            bad.respond(GroupBootstrapCodec.encode(GroupBootstrapMessage.PrivateRequest(byteArrayOf(1))))
        }
        assertThrows(IOException::class.java) { bad.respond(GroupBootstrapCodec.encode(GroupBootstrapMessage.Describe)) }
        assertEquals(0, disclosed)
    }
    @Test fun malformedUnboundedTrailingAndUnknownPacketsNeverDecode() {
        val valid = GroupBootstrapCodec.encode(GroupBootstrapMessage.Describe)
        listOf(valid + 1, valid.copyOf(3), ByteArray(8193), valid.copyOf().also { it[4] = 99 }).forEach {
            assertThrows(Exception::class.java) { GroupBootstrapCodec.decode(it) }
        }
        assertThrows(Exception::class.java) { GroupNetworkDescriptor(GroupWifiCredentials("a", "12345678"), "evil.example", 2) }
        assertThrows(Exception::class.java) { GroupNetworkDescriptor(GroupWifiCredentials("a", "12345678"), "192.168.049.1", 2) }
    }
}
