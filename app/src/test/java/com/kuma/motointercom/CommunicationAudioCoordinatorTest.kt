package com.kuma.motointercom

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommunicationAudioCoordinatorTest {
    @Test
    fun phoneCallSuspendsBothDirectionsAndResumesOnlyAfterRouteReady() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        assertEquals(1, harness.focus.requestCount)
        assertEquals(1, harness.activateRouteCount)
        assertEquals(1, harness.engine.suspendCount)

        harness.coordinator.onRouteReady()
        assertEquals(listOf("suspend", "resume"), harness.engine.audioOperations)

        harness.phone.emit(PhoneCallState.RINGING)
        assertEquals(AudioInterruptionState.PHONE_RINGING, harness.states.last())
        assertEquals(listOf("suspend"), harness.engine.audioOperations.takeLast(1))
        assertEquals(1, harness.route.suspendCount)
        assertEquals(1, harness.focus.abandonCount)

        harness.phone.emit(PhoneCallState.OFFHOOK)
        assertEquals(AudioInterruptionState.PHONE_ACTIVE, harness.states.last())
        assertEquals(2, harness.engine.suspendCount)

        harness.phone.emit(PhoneCallState.IDLE)
        assertEquals(AudioInterruptionState.RESUMING, harness.states.last())
        assertEquals(2, harness.focus.requestCount)
        assertEquals(2, harness.activateRouteCount)
        assertEquals(1, harness.engine.resumeCount)

        harness.coordinator.onRouteReady()
        assertEquals(2, harness.engine.resumeCount)
        assertEquals(1, harness.prompt.playCount)
        assertEquals(AudioInterruptionState.NORMAL, harness.states.last())
        harness.coordinator.close()
    }

    @Test
    fun communicationFocusLossSuspendsAndGainWaitsForRouteBeforeResume() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()

        harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(AudioInterruptionState.FOCUS_LOST, harness.states.last())
        assertEquals(2, harness.engine.suspendCount)

        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(AudioInterruptionState.RESUMING, harness.states.last())
        assertEquals(2, harness.focus.requestCount)
        assertEquals(2, harness.activateRouteCount)
        assertEquals(1, harness.engine.resumeCount)

        harness.coordinator.onRouteReady()
        assertEquals(2, harness.engine.resumeCount)
        harness.coordinator.close()
    }

    @Test
    fun phoneStateKeepsPhonePriorityWhenFocusLossArrivesAfterCallState() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()

        harness.phone.emit(PhoneCallState.OFFHOOK)
        harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(AudioInterruptionState.PHONE_ACTIVE, harness.states.last())
    }

    @Test
    fun duckFocusLossDoesNotSuspendIntercom() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()
        val suspendCount = harness.engine.suspendCount

        harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(AudioInterruptionState.NORMAL, harness.states.last())
        assertEquals(suspendCount, harness.engine.suspendCount)
    }

    @Test
    fun stoppingMediaPreventsLateFocusGainFromResumingAudio() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()
        harness.coordinator.endMediaSession()

        val resumeCount = harness.engine.resumeCount
        val activateCount = harness.activateRouteCount
        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(resumeCount, harness.engine.resumeCount)
        assertEquals(activateCount, harness.activateRouteCount)
        assertFalse(harness.coordinator.isPhoneCallActive())
        harness.coordinator.close()
    }

    private class Harness {
        val engine = FakeEngine()
        val route = FakeRoute()
        val focus = FakeFocus()
        val phone = FakePhone()
        val prompt = FakePrompt()
        val states = mutableListOf<AudioInterruptionState>()
        var activateRouteCount = 0
        val coordinator = CommunicationAudioCoordinator(
            engine = engine,
            route = route,
            audioFocus = focus,
            phoneState = phone,
            activateRoute = { activateRouteCount++ },
            audioPrompt = prompt,
            onStateChanged = states::add
        )
    }

    private class FakeEngine : RiderMediaEngine {
        val audioOperations = mutableListOf<String>()
        var suspendCount = 0
        var resumeCount = 0

        override fun updateAudioControls(controls: VersionedAudioControls) = Unit

        override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession =
            error("not used")

        override fun suspendAudio() {
            suspendCount++
            audioOperations += "suspend"
        }

        override fun resumeAudio() {
            resumeCount++
            audioOperations += "resume"
        }

        override fun close() = Unit
    }

    private class FakeRoute : RiderAudioRoute {
        var suspendCount = 0

        override fun select(selection: AudioRouteSelection) = Unit

        override fun suspendForInterruption(restoreMode: Boolean) {
            suspendCount++
        }

        override fun close() = Unit
    }

    private class FakeFocus : IntercomAudioFocus {
        private var listener: ((Int) -> Unit)? = null
        var requestCount = 0
        var abandonCount = 0

        override fun setListener(listener: (Int) -> Unit) {
            this.listener = listener
        }

        override fun request(): AudioFocusResult {
            requestCount++
            return AudioFocusResult.GRANTED
        }

        override fun abandon() {
            abandonCount++
        }

        fun emit(change: Int) {
            listener?.invoke(change)
        }

        override fun close() = Unit
    }

    private class FakePhone : IntercomPhoneState {
        private var listener: ((PhoneCallState) -> Unit)? = null
        private var state = PhoneCallState.IDLE

        override fun start(listener: (PhoneCallState) -> Unit) {
            this.listener = listener
        }

        override fun currentState(): PhoneCallState = state

        fun emit(next: PhoneCallState) {
            state = next
            listener?.invoke(next)
        }

        override fun close() = Unit
    }

    private class FakePrompt : IntercomAudioPrompt {
        var playCount = 0

        override fun playResumePrompt() {
            playCount++
        }

        override fun close() = Unit
    }
}
