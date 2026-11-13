package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CloudSignalingTest {
    @Test fun cloudFramesRoundTripInsideThePinnedEnvelope() {
        val proof=testPair()
        val codec=SignalingV2Codec()
        val id=ConnectionAttemptId(UUID.randomUUID().toString())
        val a=DeviceId.create(); val b=DeviceId.create(); val session=RuntimeSessionId(UUID.randomUUID().toString())
        val offer=SignalingMessageV2.CloudPairingOffer(proof.transcript.pairId,proof.transcript.publicKeyA,
            UUID.randomUUID().toString(),proof.transcript.createdAt,proof.transcript.expiresAt)
        for(message in listOf(offer,SignalingMessageV2.CloudPairingProof(proof.transcript,proof.signatureA))) {
            val envelope=SignalingEnvelopeV2(attemptId=id,sourceDeviceId=a,targetDeviceId=b,sourceSessionId=session,message=message)
            assertEquals(envelope,codec.decode(codec.encode(envelope)))
        }
    }
    @Test fun cloudProofCannotAdvanceOrBypassTheLocalAcceptancePhase() {
        val phase=SignalingPhaseMachine(RequestRole.REQUESTER)
        val offer=SignalingMessageV2.CloudPairingOffer("pair_test","public",UUID.randomUUID().toString(),1,2)
        assertThrows(SignalingV2Exception::class.java) { phase.onFrame(FrameDirection.OUTBOUND,offer) }
        phase.onFrame(FrameDirection.OUTBOUND,SignalingMessageV2.Hello(RequestRole.REQUESTER))
        phase.onFrame(FrameDirection.INBOUND,SignalingMessageV2.Hello(RequestRole.RESPONDER,capabilities=setOf(CLOUD_PAIRING_CAPABILITY)))
        phase.onFrame(FrameDirection.OUTBOUND,SignalingMessageV2.ConnectRequest(RequestTrigger.USER))
        assertThrows(SignalingV2Exception::class.java) { phase.onFrame(FrameDirection.OUTBOUND,offer) }
        phase.onFrame(FrameDirection.INBOUND,SignalingMessageV2.ConnectAccept("车友","Phone"))
        val before=phase.phase
        phase.onFrame(FrameDirection.OUTBOUND,offer); phase.onFrame(FrameDirection.INBOUND,offer)
        assertEquals(before,phase.phase)
        phase.close()
        assertThrows(SignalingV2Exception::class.java) { phase.onFrame(FrameDirection.INBOUND,offer) }
    }
}
