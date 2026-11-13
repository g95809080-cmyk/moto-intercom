package com.kuma.motointercom

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Exercises Android focus/routing with a silent target, not a real GME call.
@RunWith(AndroidJUnit4::class)
class CloudAudioPlatformInstrumentedTest {
    @Test fun speakerRouteIsVerifiedBeforeAudioAndRestoredBeforeRelease() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE))
        val manager=context.getSystemService(AudioManager::class.java)
        val initialMode=manager.mode
        val ready=CountDownLatch(1); val released=CountDownLatch(1)
        val resumes=AtomicInteger(); val suspends=AtomicInteger()
        val target=object : CommunicationAudioTarget {
            override fun suspendAudio() { suspends.incrementAndGet() }
            override fun resumeAudio() { resumes.incrementAndGet(); ready.countDown() }
        }
        var controller: CloudAudioSessionController?=null
        var closedSafely=false
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync {
                    controller=CloudAudioSessionController.create(context,target,AudioRouteSelection.SPEAKER,
                        {},{},{},{},{},{},{})
                    controller!!.start()
                }
                assertTrue("Android route was not verified",ready.await(10,TimeUnit.SECONDS))
                assertTrue(resumes.get()>0); assertTrue(suspends.get()>0)
                assertEquals(AudioManager.MODE_IN_COMMUNICATION,manager.mode)
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,manager.communicationDevice?.type)
                instrumentation.runOnMainSync {
                    controller!!.closeWhenReleased { success -> closedSafely=success; released.countDown() }
                }
                assertTrue("Route release did not complete",released.await(10,TimeUnit.SECONDS))
                assertTrue(closedSafely); assertEquals(initialMode,manager.mode)
            } finally { instrumentation.runOnMainSync { controller?.close() } }
        }
    }
}
