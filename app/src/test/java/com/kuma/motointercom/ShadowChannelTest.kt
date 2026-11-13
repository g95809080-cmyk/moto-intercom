package com.kuma.motointercom

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class ShadowChannelTest {
    @Test fun realV2SocketsAuthenticateAndDeliverOnlySignedShadowMessages() {
        val a="11111111-1111-4111-8111-111111111111";val b="22222222-2222-4222-8222-222222222222"
        val ar=RuntimeSessionId.create();val br=RuntimeSessionId.create()
        val clock=MonotonicClock {MonotonicTimestamp(System.nanoTime()/1_000_000)}
        val attempt=ConnectionAttempt(ConnectionAttemptId.create(),ar,TargetLock(b,br),ConnectionTrigger.RECOVERY,ChannelPlan.single(Transport.LAN),clock.now().elapsedRealtimeMs+10_000)
        val pool=Executors.newFixedThreadPool(2)
        try {
        ServerSocket(0).use {server ->
            Socket("127.0.0.1",server.localPort).use {client ->
                server.accept().use {accepted ->
                    val af=pool.submit<SignalingSessionV2> {SignalingSessionV2.establish(client,Transport.LAN,PhysicalSocketRole.OPENER,
                        clock.now().elapsedRealtimeMs,a,ar,"a","test",attempt,monotonicClock=clock,localCapabilities=setOf(CLOUD_SHADOW_CAPABILITY))}
                    val bf=pool.submit<SignalingSessionV2> {SignalingSessionV2.establish(accepted,Transport.LAN,PhysicalSocketRole.ACCEPTOR,
                        clock.now().elapsedRealtimeMs,b,br,"b","test",null,monotonicClock=clock,localCapabilities=setOf(CLOUD_SHADOW_CAPABILITY))}
                    val sa=af.get(3,TimeUnit.SECONDS);val sb=bf.get(3,TimeUnit.SECONDS)
                    val ak=TestDeviceSigner();val bk=TestDeviceSigner();val now=System.currentTimeMillis()
                    val transcript=PairingTranscript(1,"pair_shadow",a,b,ak.publicKeyBase64(),bk.publicKeyBase64(),now,UUID.randomUUID().toString(),now+300_000)
                    val proof=PairingProof(transcript,ak.sign(transcript.bytes()),bk.sign(transcript.bytes()))
                    val ride=testRide(now,RideState.CLOUD_ACTIVE).copy(pairId="pair_shadow",members=listOf(a,b),lastHeartbeatAt=mapOf(a to now,b to now))
                    var authenticated=0;var failures=0;val messages=mutableListOf<Pair<String,String>>()
                    val x=ShadowChannel(sa,ride,ak,proof,{authenticated++},{_,_->},{failures++})
                    val y=ShadowChannel(sb,ride,bk,proof,{authenticated++},{k,d->messages.add(k to d)},{failures++})
                    try {
                        x.start();y.start();until {authenticated==2}
                        assertEquals(x.probeId,y.probeId)
                        x.send("SDP","{\"type\":\"offer\"}");until {messages.size==1}
                        assertEquals("SDP" to "{\"type\":\"offer\"}",messages.single())
                        sa.send(SignalingMessageV2.CloudShadow("{\"body\":{},\"signature\":\"invalid\"}"))
                        until {failures>0};assertEquals(1,messages.size)
                    } finally {x.close();y.close();sa.close();sb.close()}
                }
            }
        }
        } finally {pool.shutdownNow()}
    }
    private fun until(condition: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue(condition())
    }
}
