package com.kuma.motointercom

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gme.TMG.ITMGContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class GmeAudioLifecycleTest {
    private class Engine : GmeEngine {
        val calls=mutableListOf<String>()
        var level=20; var volume=100; var capturing=false; var playing=false; var sending=false
        var failVolume=false; var failUninit=false
        var failPause=false;var throwDeviceQuery=false
        override fun initialize(appId: Int,openId: String): Int { calls.add("init"); return 0 }
        override fun enter(ticket: GmeTicket): Int { calls.add("enter"); return 0 }
        override fun poll()=0
        override fun anchor(): Int { calls.add("anchor"); return 0 }
        override fun mic(enabled: Boolean): Int { capturing=enabled; calls.add("mic/$enabled"); return 0 }
        override fun speaker(enabled: Boolean): Int { playing=enabled; calls.add("speaker/$enabled"); return 0 }
        override fun audioSend(enabled: Boolean): Int { sending=enabled; calls.add("send/$enabled"); return 0 }
        override fun micLevel()=level
        override fun micVolume(volume: Int): Int { this.volume=volume; calls.add("volume/$volume"); return if(failVolume)1 else 0 }
        override fun pause(): Int { calls.add("pause"); return if(failPause)1 else 0 }
        override fun audioDevicesDisabled(): Boolean {
            if(throwDeviceQuery)error("Device query unavailable")
            return !capturing && !playing
        }
        override fun resume(): Int { calls.add("resume"); return 0 }
        override fun exit(): Int { calls.add("exit"); return 0 }
        override fun uninitialize(): Int { calls.add("uninit"); return if(failUninit)1 else 0 }
    }
    private class Fixture(vox: Boolean=true,muted: Boolean=false,allowance: Long=14_400_000) : AutoCloseable {
        val engine=Engine()
        val states=mutableListOf<CloudIntercomState>(); val voxStates=mutableListOf<VoxRuntimeState>()
        var verified=true; var routeRequests=0; var closed=0; var factories=0
        var idleWitness=true
        val now=System.currentTimeMillis()
        val approved=ApprovedCloudRide.from(testRide(now,RideState.CLOUD_PENDING).copy(cloudAllowanceMs=allowance),"a","车友",now-1,now)!!
        val ticket=GmeTicket(1400802321,"ride_test",1,"123456789","10001","10002",byteArrayOf(1,2,3),now/1000+300)
        val session: GmeCloudSession
        init {
            session=GmeCloudSession(ApplicationProvider.getApplicationContext(),GmeTicketSource { ticket },states::add,
                {closed++},{},VersionedAudioControls(1,AudioControlSettings(muted=muted,voxEnabled=vox)),
                onVoxState={_,s->voxStates.add(s)},onRouteRequired={routeRequests++; if(verified) resume()},
                engineFactory={_,_->factories++; engine},audioIdleWitness={idleWitness})
        }
        private fun resume()=session.resumeAudio()
        fun start(authorized: Boolean=true) {
            if(authorized) session.resumeAudio()
            session.start(approved); idle(1)
        }
        fun entered() { session.OnEvent(ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_ENTER_ROOM,Intent().putExtra("result",0)); idle(1) }
        fun idle(ms: Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        override fun close() { session.stop(); idle(2_100) }
    }
    @Test fun handoverPauseKeepsTheRoomAndRequiresTwoDeviceIdleObservations() {
        Fixture().use {f ->
            f.start();f.entered()
            val result=mutableListOf<Boolean>()
            f.session.suspendForHandover(result::add)
            assertTrue(result.isEmpty());f.idle(20);assertEquals(listOf(true),result)
            assertFalse(f.engine.capturing);assertFalse(f.engine.playing)
            assertFalse(f.engine.calls.any {it=="exit" || it=="uninit"})
            f.session.resumeAudio();assertTrue(f.engine.capturing);assertTrue(f.engine.playing)
        }
    }
    @Test fun unknownStopOrFailedPauseCannotCloseTheRoomAndCanResumeCloud() {
        Fixture().use {f ->
            f.start();f.entered();f.idleWitness=false
            val result=mutableListOf<Boolean>();f.session.suspendForHandover(result::add);f.idle(250)
            assertEquals(listOf(false),result);assertEquals(0,f.closed)
            assertFalse(f.engine.calls.any {it=="exit" || it=="uninit"})
            f.session.resumeAudio();assertTrue(f.engine.capturing)
            f.engine.failPause=true;result.clear();f.session.suspendForHandover(result::add)
            assertEquals(listOf(false),result);assertEquals(0,f.closed)
            f.engine.failPause=false;f.session.resumeAudio();assertTrue(f.engine.capturing)
        }
    }
    @Test fun delayedDeviceQueryFailureCompletesFalseWithoutCrashingTheMainThread() {
        Fixture().use {f ->
            f.start();f.entered();f.idleWitness=false
            val result=mutableListOf<Boolean>();f.session.suspendForHandover(result::add)
            f.engine.throwDeviceQuery=true;f.idle(20)
            assertEquals(listOf(false),result);assertEquals(0,f.closed)
        }
    }
    @Test fun initializationWaitsForFocusAndRouteAuthorization() {
        Fixture().use { f ->
            f.start(authorized=false); assertEquals(0,f.factories)
            f.session.resumeAudio(); f.idle(1); assertEquals(1,f.factories)
            f.verified=false; f.entered()
            assertFalse(f.engine.capturing); assertFalse(f.engine.playing)
            assertEquals(1,f.routeRequests)
            f.session.resumeAudio()
            assertTrue(f.engine.capturing); assertTrue(f.engine.playing)
            assertFalse(f.engine.sending); assertEquals(100,f.engine.volume)
        }
    }
    @Test fun voxTogglesAudioSendWithoutChangingCaptureGainOrDevice() {
        Fixture().use { f ->
            f.start(); f.entered(); f.idle(600)
            assertTrue(f.engine.capturing); assertFalse(f.engine.sending); assertEquals(100,f.engine.volume)
            val micCalls=f.engine.calls.count { it.startsWith("mic/") }
            f.engine.level=90; f.idle(100)
            assertTrue(f.engine.sending); assertEquals(100,f.engine.volume)
            assertTrue(f.voxStates.contains(VoxRuntimeState.OPEN))
            f.engine.level=0; f.idle(1_000)
            assertFalse(f.engine.sending); assertEquals(100,f.engine.volume)
            assertEquals(micCalls,f.engine.calls.count { it.startsWith("mic/") })
        }
    }
    @Test fun preparationMuteAndVoxChangesKeepPlaybackButNeverOpenTheMic() {
        Fixture(muted=true).use { f ->
            f.start(); f.entered()
            assertFalse(f.engine.capturing); assertTrue(f.engine.playing)
            assertFalse(f.engine.sending); assertEquals(100,f.engine.volume)
            f.session.updateAudioControls(VersionedAudioControls(2,AudioControlSettings(muted=true,voxEnabled=false)))
            f.engine.level=100; f.idle(700)
            assertFalse(f.engine.capturing); assertFalse(f.engine.sending); assertEquals(100,f.engine.volume)
            assertEquals(VoxRuntimeState.MUTED,f.voxStates.last())
        }
    }
    @Test fun interruptionKeepsRoomAndReconnectCannotBypassRouteOrMute() {
        Fixture(vox=false).use { f ->
            f.start(); f.entered(); assertTrue(f.engine.sending); assertEquals(100,f.engine.volume)
            f.verified=false; f.session.suspendAudio()
            val before=f.engine.calls.size
            f.session.setMuted(true)
            f.session.OnEvent(ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_RECONNECT_SUCCESS,null)
            f.idle(100)
            assertFalse(f.engine.capturing); assertFalse(f.engine.playing)
            assertFalse(f.engine.calls.drop(before).contains("exit"))
            assertFalse(f.engine.calls.drop(before).contains("mic/true"))
            f.session.resumeAudio()
            assertFalse(f.engine.capturing); assertTrue(f.engine.playing)
            assertFalse(f.engine.sending); assertEquals(100,f.engine.volume)
        }
    }
    @Test fun stopRejectsLateUnmuteResumeEnterAndReconnect() {
        Fixture(vox=false).use { f ->
            f.start(); f.entered(); f.session.stop()
            val before=f.engine.calls.size
            f.session.setMuted(false); f.session.resumeAudio()
            f.session.OnEvent(ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_RECONNECT_SUCCESS,null)
            f.entered(); f.idle(2_100)
            assertFalse(f.engine.calls.drop(before).contains("mic/true"))
            assertFalse(f.engine.calls.drop(before).contains("speaker/true"))
            assertEquals(1,f.closed); assertTrue(f.engine.calls.contains("uninit"))
        }
    }
    @Test fun noPeerDeadlineAndCloudDeadlineAlsoRunWhileAudioIsUnavailable() {
        Fixture(allowance=1_000).use { f ->
            f.start(authorized=false); f.idle(1_400)
            assertEquals(0,f.factories); assertEquals(1,f.closed)
            assertTrue(f.states.any { it is CloudIntercomState.Failed })
        }
        Fixture().use { f ->
            f.start(); f.entered(); f.session.suspendAudio(); f.idle(60_400)
            assertTrue(f.engine.calls.contains("exit"));assertTrue(f.states.any { it is CloudIntercomState.ResumeAvailable })
            f.idle(2_100);assertEquals(1,f.closed)
        }
    }
    @Test fun failedSdkGainOrUninitIsReportedWithoutClaimingReleaseSuccess() {
        Fixture().use { f ->
            f.start()
            f.engine.failVolume=true; f.engine.failUninit=true
            f.entered()
            f.idle(1_000)
            assertTrue(f.states.any { it is CloudIntercomState.Failed })
            assertEquals(1,f.closed); assertFalse(f.session.nativeReleaseSucceeded)
        }
    }
    @Test fun reconnectionSetupFailureCannotOverwriteFailedWithAHealthyRoomState() {
        Fixture().use { f ->
            f.start(); f.entered(); f.engine.failVolume=true
            f.session.OnEvent(ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_RECONNECT_SUCCESS,null)
            assertTrue(f.states.last() is CloudIntercomState.Failed)
        }
    }
    @Test fun callDuringRoomEntryFreezesOnlyTheEntryTimeoutBudget() {
        Fixture().use { f ->
            f.start(); f.session.suspendAudio(); f.idle(25_000)
            assertEquals(0,f.closed); assertFalse(f.states.any { it is CloudIntercomState.Failed })
            f.session.resumeAudio(); f.idle(19_000)
            assertEquals(0,f.closed)
            f.entered(); assertTrue(f.engine.capturing)
        }
    }
}
