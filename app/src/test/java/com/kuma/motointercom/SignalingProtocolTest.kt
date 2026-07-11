package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
