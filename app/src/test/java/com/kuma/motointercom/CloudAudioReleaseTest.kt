package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class CloudAudioReleaseTest {
    @Test fun audioOwnerWaitsForBothNativeProducerAndRouteRestore() {
        var native: ((Boolean)->Unit)?=null
        var route: ((Boolean)->Unit)?=null
        val engine=object : RiderMediaEngine {
            override fun updateAudioControls(controls: VersionedAudioControls) {}
            override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession=error("No WebRTC session")
            override fun close() {}
            override fun whenReleased(callback: (Boolean)->Unit) { native=callback }
        }
        val routing=object : RiderAudioRoute {
            override fun select(selection: AudioRouteSelection) {}
            override fun close() {}
            override fun whenReleased(callback: (Boolean)->Unit) { route=callback }
        }
        val controller=AudioSessionController(engine,routing)
        val results=mutableListOf<Boolean>()
        controller.closeWhenReleased(results::add)
        assertTrue(results.isEmpty())
        native!!(true); assertTrue(results.isEmpty())
        route!!(true); assertEquals(listOf(true),results)
    }
    @Test fun failedReleaseStaysFailedAndCallbacksRunOnlyOnce() {
        val barrier=ResourceReleaseBarrier(); val results=mutableListOf<Boolean>()
        barrier.whenReleased(results::add); barrier.complete(false); barrier.complete(true)
        barrier.whenReleased(results::add)
        assertEquals(listOf(false,false),results)
    }
    @Test fun repeatedCloseCannotTurnAnEarlierFailureIntoSuccess() {
        val engine=object : RiderMediaEngine {
            override fun updateAudioControls(controls: VersionedAudioControls) {}
            override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession=error("Unused")
            override fun close() { error("test close failure") }
        }
        val route=object : RiderAudioRoute {
            override fun select(selection: AudioRouteSelection) {}
            override fun close() {}
        }
        val controller=AudioSessionController(engine,route)
        val results=mutableListOf<Boolean>()
        controller.closeWhenReleased(results::add); controller.closeWhenReleased(results::add)
        assertEquals(listOf(false,false),results)
    }
}
