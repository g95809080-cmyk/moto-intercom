package com.kuma.motointercom

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ShadowFrameCodecTest {
    private val a=TestDeviceSigner();private val b=TestDeviceSigner()
    private fun codec(first: Boolean,binding: String="a".repeat(64))=if(first)ShadowFrameCodec(binding,a,b.publicKeyBase64(),true)
        else ShadowFrameCodec(binding,b,a.publicKeyBase64(),false)
    private fun authenticate(a: ShadowFrameCodec,b: ShadowFrameCodec) {
        val ah=a.encode("HELLO");val bh=b.encode("HELLO");a.receive(bh);b.receive(ah)
        val aa=a.encode("AUTH");val ba=b.encode("AUTH");a.receive(ba);b.receive(aa)
    }
    @Test fun bothFreshChallengesBindTheSameProbeAndAllMediaFramesAreSignedAndOrdered() {
        val x=codec(true);val y=codec(false);authenticate(x,y)
        assertEquals(x.probeId,y.probeId);assertTrue(x.authenticated);assertTrue(y.authenticated)
        val frame=x.encode("SDP","{\"type\":\"offer\"}")
        assertEquals("SDP" to "{\"type\":\"offer\"}",y.receive(frame))
        assertTrue(runCatching {y.receive(frame)}.isFailure)
        val changed=JsonParser.parseString(x.encode("CANDIDATE","candidate-original")).asJsonObject
        changed.getAsJsonObject("body").addProperty("data","attacker-candidate")
        assertTrue(runCatching {y.receive(changed.toString())}.isFailure)
    }
    @Test fun oldProofCannotAuthenticateANewNonceEvenIfOuterBindingIsReused() {
        val oldA=codec(true);val oldB=codec(false)
        val ah=oldA.encode("HELLO");val bh=oldB.encode("HELLO");oldA.receive(bh);oldB.receive(ah)
        val oldAuth=oldB.encode("AUTH")
        val freshA=codec(true);freshA.encode("HELLO");freshA.receive(bh)
        assertTrue(runCatching {freshA.receive(oldAuth)}.isFailure)
        assertFalse(freshA.authenticated)
    }
    @Test fun wrongKeyRideOrRuntimeBindingAndUnauthenticatedMediaAreRejected() {
        val x=codec(true);val outsider=ShadowFrameCodec("a".repeat(64),TestDeviceSigner(),a.publicKeyBase64(),false)
        assertTrue(runCatching {x.receive(outsider.encode("HELLO"))}.isFailure)
        assertTrue(runCatching {x.receive(codec(false,"b".repeat(64)).encode("HELLO"))}.isFailure)
        assertTrue(runCatching {x.encode("GO","switch_fake")}.isFailure)
    }
    @Test fun shadowPurposeCannotEnterNormalConnectNegotiation() {
        val p=SignalingPhaseMachine(RequestRole.REQUESTER)
        p.onFrame(FrameDirection.OUTBOUND,SignalingMessageV2.Hello(RequestRole.REQUESTER))
        p.onFrame(FrameDirection.INBOUND,SignalingMessageV2.Hello(RequestRole.RESPONDER))
        p.onFrame(FrameDirection.OUTBOUND,SignalingMessageV2.CloudShadow("test"))
        assertTrue(runCatching {p.onFrame(FrameDirection.OUTBOUND,SignalingMessageV2.ConnectRequest(RequestTrigger.USER))}.isFailure)
    }
}
