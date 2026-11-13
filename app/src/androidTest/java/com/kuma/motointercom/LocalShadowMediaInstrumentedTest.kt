package com.kuma.motointercom

import android.Manifest
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Real pinned WebRTC/Android audio, synthetic PCM, no GME network call.
@RunWith(AndroidJUnit4::class)
class LocalShadowMediaInstrumentedTest {
    @Test fun connectedShadowDoesNotRecordOrPlayUntilGrantedAndPauseWaitsForNativeRelease() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val failures=LinkedBlockingQueue<Throwable>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val mode=context.getSystemService(AudioManager::class.java).mode
            val controls=VersionedAudioControls(1,AudioControlSettings(muted=true,voxEnabled=false))
            val a=RiderAudioEngine(context,{failures.offer(it)},initialAudioControls=controls,initialAudioIoEnabled=false,opusDtxEnabled=false)
            val b=RiderAudioEngine(context,{failures.offer(it)},initialAudioControls=controls,initialAudioIoEnabled=false,opusDtxEnabled=false)
            val modules=RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").apply {isAccessible=true}
            try {
                lateinit var offerer: RiderMediaSession;lateinit var answerer: RiderMediaSession
                val connected=CountDownLatch(2)
                offerer=a.openSession(RiderMediaSessionCallbacks({answerer.createAnswer(it)},{answerer.addRemoteIceCandidate(it)},
                    {if(it==PeerConnection.PeerConnectionState.CONNECTED)connected.countDown()},onError={failures.offer(it)},isSessionCurrent={true}))
                answerer=b.openSession(RiderMediaSessionCallbacks({offerer.setRemoteAnswer(it)},{offerer.addRemoteIceCandidate(it)},
                    {if(it==PeerConnection.PeerConnectionState.CONNECTED)connected.countDown()},onError={failures.offer(it)},isSessionCurrent={true}))
                offerer.createOffer();assertTrue(connected.await(15,TimeUnit.SECONDS))
                val am=modules.get(a) as JavaAudioDeviceModule;val bm=modules.get(b) as JavaAudioDeviceModule
                assertTrue(NativeCaptureDiagnostics.recordingStopped(am));assertTrue(NativeCaptureDiagnostics.recordingStopped(bm))
                assertEquals(mode,context.getSystemService(AudioManager::class.java).mode)
                for(module in listOf(am,bm)) {
                    val input=module.javaClass.getDeclaredField("audioInput").run {isAccessible=true;get(module)}
                    val field=input.javaClass.getField("motoCaptureCallback")
                    val original=field.get(input) as AudioRecordDataCallback
                    field.set(input,AudioRecordDataCallback {format,channels,rate,frame ->
                        for(index in 0 until frame.capacity()/2) {
                            val value=(kotlin.math.sin(index/channels*2*Math.PI*440/rate)*6_000).toInt()
                            frame.put(index*2,value.toByte());frame.put(index*2+1,(value shr 8).toByte())
                        }
                        original.onAudioDataRecorded(format,channels,rate,frame)
                    })
                }
                a.resumeAudio();b.resumeAudio()
                healthy(offerer);healthy(answerer)
                val paused=LinkedBlockingQueue<Boolean>();a.suspendForHandover {paused.offer(it)}
                assertEquals(true,paused.poll(5,TimeUnit.SECONDS));assertTrue(NativeCaptureDiagnostics.recordingStopped(am))
                a.resumeAudio();healthy(offerer)
                assertTrue(failures.isEmpty())
            } finally {
                val released=LinkedBlockingQueue<Boolean>();a.close();b.close()
                a.whenReleased {released.offer(it)};b.whenReleased {released.offer(it)}
                assertEquals(true,released.poll(5,TimeUnit.SECONDS));assertEquals(true,released.poll(5,TimeUnit.SECONDS))
            }
        }
    }
    private fun healthy(session: RiderMediaSession) {
        val deadline=SystemClock.elapsedRealtime()+10_000
        val fresh=LocalActivationEvidence()
        while(SystemClock.elapsedRealtime()<deadline) {
            val sampled=LinkedBlockingQueue<RiderMediaEvidence>()
            session.queryEvidence {if(it!=null)sampled.offer(it)}
            val value=sampled.poll(200,TimeUnit.MILLISECONDS)
            if(fresh.sample(value))return
        }
        error("No fresh native PCM/RTP/playout evidence")
    }
}
