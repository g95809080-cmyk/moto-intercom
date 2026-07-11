package com.kuma.motointercom

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SignalingProtocolTest {
    @Test
    fun decodesIdentityThenExpectedOffer() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertEquals(
            SignalingProtocol.Message.Identity("Rider"),
            protocol.decode("""{"type":"IDENTITY","name":" Rider "}""".toByteArray())
        )
        val message = protocol.decode(
            """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
        )
        assertEquals(SignalingProtocol.Message.Offer("""{"type":"OFFER","sdp":"v=0"}"""), message)
    }

    @Test
    fun rejectsOfferBeforeIdentity() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(
                """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
            )
        }
    }

    @Test
    fun rejectsCandidate257() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        protocol.decode("""{"type":"IDENTITY","name":"A"}""".toByteArray())
        protocol.decode(
            """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
        )
        val candidate =
            """{"type":"CANDIDATE","candidate":{"sdpMid":"0","sdpMLineIndex":0,"candidate":"c"}}"""
                .toByteArray()
        repeat(256) { protocol.decode(candidate) }

        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(candidate)
        }
    }

    @Test
    fun rejectsIdentityOver64CodePoints() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        val name = "骑".repeat(65)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode("""{"type":"IDENTITY","name":"$name"}""".toByteArray())
        }
    }

    @Test
    fun rejectsFrameOver128KiB() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(ByteArray(SignalingProtocol.MAX_FRAME_BYTES + 1) { 'x'.code.toByte() })
        }
    }

    @Test
    fun rejectsCandidateOver4KiB() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        protocol.decode("""{"type":"IDENTITY","name":"A"}""".toByteArray())
        protocol.decode(
            """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
        )
        val longCandidate = "c".repeat(SignalingProtocol.MAX_CANDIDATE_BYTES + 1)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(
                """{"type":"CANDIDATE","candidate":{"candidate":"$longCandidate"}}""".toByteArray()
            )
        }
    }

    @Test
    fun preservesOutgoingSdpStringAndCandidateObjectWireShapes() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        val sdp = """{"type":"OFFER","sdp":"v=0"}"""
        val encodedSdp = JsonParser.parseString(
            String(protocol.encode(SignalingProtocol.Message.Offer(sdp)), StandardCharsets.UTF_8)
        ).asJsonObject
        assertTrue(encodedSdp.get("sdp").isJsonPrimitive)
        assertEquals(sdp, encodedSdp.get("sdp").asString)

        val candidate = """{"sdpMid":"0","sdpMLineIndex":0,"candidate":"c"}"""
        val encodedCandidate = JsonParser.parseString(
            String(
                protocol.encode(SignalingProtocol.Message.Candidate(candidate)),
                StandardCharsets.UTF_8
            )
        ).asJsonObject
        assertTrue(encodedCandidate.get("candidate").isJsonObject)
        assertEquals(candidate, encodedCandidate.get("candidate").toString())
    }

    @Test
    fun rejectsOversizedOutgoingPayloads() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.encode(SignalingProtocol.Message.Identity("骑".repeat(65)))
        }
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.encode(
                SignalingProtocol.Message.Offer("s".repeat(SignalingProtocol.MAX_SDP_BYTES + 1))
            )
        }
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.encode(
                SignalingProtocol.Message.Candidate(
                    """{"candidate":"${"c".repeat(SignalingProtocol.MAX_CANDIDATE_BYTES)}"}"""
                )
            )
        }
    }

    @Test
    fun rejectsOutgoingCandidate257() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        val candidate = SignalingProtocol.Message.Candidate("""{"candidate":"c"}""")
        repeat(256) { protocol.encode(candidate) }

        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.encode(candidate)
        }
    }
}
