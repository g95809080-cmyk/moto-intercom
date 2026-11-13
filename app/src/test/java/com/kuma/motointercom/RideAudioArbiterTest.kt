package com.kuma.motointercom

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideAudioArbiterTest {
    private class Target : CommunicationAudioTarget {
        var active=false;var resumes=0;var hold=false;var stopped: ((Boolean)->Unit)?=null
        override fun suspendAudio() {if(!hold)active=false}
        override fun resumeAudio() {active=true;resumes++}
        override fun whenAudioApplied(enabled: Boolean,callback: (Boolean)->Unit) {
            if(!enabled && hold) stopped=callback else callback(if(enabled)active else !active)
        }
        fun release(success: Boolean=true) {if(success)active=false;stopped!!(success)}
    }
    @Test fun newSourceWaitsForNativeStopAndVerifiedRouteWithoutResumingTheOldSource()=runTest {
        val cloud=Target();val local=Target();val a=RideAudioArbiter({it()},{testScheduler.currentTime})
        a.cloud(cloud);a.local(local);a.resumeAudio();cloud.hold=true
        var routes=0
        val result=async {a.select(RideAudioSource.LOCAL,{routes++;a.suspendAudio()})}
        runCurrent();assertTrue(cloud.active);assertFalse(local.active);assertEquals(0,routes)
        a.resumeAudio();assertEquals(1,cloud.resumes)
        cloud.release();runCurrent();assertFalse(cloud.active);assertFalse(local.active);assertEquals(1,routes)
        a.resumeAudio();runCurrent();assertTrue(result.await());assertTrue(local.active);assertFalse(cloud.active)
        a.close();assertFalse(local.active)
    }
    @Test fun phoneOrFocusRevisionRevokesAnOlderStopFence()=runTest {
        val cloud=Target();val local=Target();val a=RideAudioArbiter({it()},{testScheduler.currentTime})
        a.cloud(cloud);a.local(local);a.resumeAudio();cloud.hold=true
        val result=async {a.select(RideAudioSource.LOCAL,{error("Stale fence must not select a route")})}
        runCurrent();a.suspendAudio();a.resumeAudio();cloud.release();runCurrent()
        assertFalse(result.await());assertEquals(0,local.resumes);assertEquals(RideAudioSource.CLOUD,a.source)
        a.close()
    }
    @Test fun failedNativeReleaseNeverGrantsLocalAudio()=runTest {
        val cloud=Target();val local=Target();val a=RideAudioArbiter({it()},{testScheduler.currentTime})
        a.cloud(cloud);a.local(local);a.resumeAudio();cloud.hold=true
        val result=async {a.select(RideAudioSource.LOCAL,{error("No route on failed stop")})}
        runCurrent();cloud.release(false);runCurrent()
        assertFalse(result.await());assertTrue(cloud.active);assertFalse(local.active);a.close()
    }
    @Test fun entireSwitchHasOneDeadlineAndLateRouteCannotActivateTimedOutLocalAudio()=runTest {
        val cloud=Target();val local=Target();val a=RideAudioArbiter({it()},{testScheduler.currentTime})
        a.cloud(cloud);a.local(local);a.resumeAudio();cloud.hold=true
        val result=async {a.select(RideAudioSource.LOCAL,{a.suspendAudio()},300)}
        runCurrent();advanceTimeBy(200);cloud.release();runCurrent()
        advanceTimeBy(101);runCurrent();assertFalse(result.await())
        a.resumeAudio();assertFalse(local.active);assertEquals(0,local.resumes)
        a.localReleased(local,true)
        val rollback=async {a.select(RideAudioSource.CLOUD,{a.suspendAudio();a.resumeAudio()})}
        runCurrent();assertTrue(rollback.await());assertTrue(cloud.active);a.close()
    }
    @Test fun closingDuringQueuedStopRejectsLateGrants()=runTest {
        val cloud=Target();val local=Target();val a=RideAudioArbiter({it()},{testScheduler.currentTime})
        a.cloud(cloud);a.local(local);a.resumeAudio();cloud.hold=true
        val result=async {a.select(RideAudioSource.LOCAL,{error("Closed")})}
        runCurrent();a.close();cloud.release();runCurrent();a.resumeAudio()
        assertFalse(result.await());assertFalse(local.active)
    }
    @Test fun releasingSelectedTargetCannotLetACloudResumeCompleteItsLocalWaiter()=runTest {
        val cloud=Target();val local=Target();val a=RideAudioArbiter({it()},{testScheduler.currentTime})
        a.cloud(cloud);a.local(local);a.resumeAudio()
        val result=async {a.select(RideAudioSource.LOCAL,{a.suspendAudio()})}
        runCurrent();a.localReleased(local,true);a.resumeAudio();runCurrent()
        assertFalse(result.await());assertEquals(RideAudioSource.CLOUD,a.source);assertTrue(cloud.active);a.close()
    }
    @Test fun synchronousRouteWorkCannotExceedTheSingleSwitchDeadline()=runTest {
        val cloud=Target();val local=Target();var clock=0L;val a=RideAudioArbiter({it()},{clock})
        a.cloud(cloud);a.local(local);a.resumeAudio()
        assertFalse(a.select(RideAudioSource.LOCAL,{clock=301;a.suspendAudio();a.resumeAudio()}))
        assertFalse(local.active);a.close()
    }
}
