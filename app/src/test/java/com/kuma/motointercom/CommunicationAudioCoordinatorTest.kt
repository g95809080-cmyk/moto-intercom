package com.kuma.motointercom

import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommunicationAudioCoordinatorTest {
    @Test
    fun mediaStartedDuringAnActivePhoneCallWaitsForCallEndAndRouteReady() {
        val harness = Harness(initialPhoneState = PhoneCallState.OFFHOOK)
        harness.coordinator.start()
        assertEquals(AudioInterruptionState.PHONE_ACTIVE, harness.states.last())

        harness.coordinator.beginMediaSession()
        assertEquals(0, harness.focus.requestCount)
        assertEquals(1, harness.engine.suspendCount)
        assertEquals(0, harness.engine.resumeCount)

        harness.phone.emit(PhoneCallState.IDLE)
        assertEquals(AudioInterruptionState.RESUMING, harness.states.last())
        assertEquals(1, harness.focus.requestCount)
        assertEquals(2, harness.engine.suspendCount)
        assertEquals(0, harness.engine.resumeCount)

        harness.coordinator.onRouteReady()
        assertEquals(AudioInterruptionState.NORMAL, harness.states.last())
        assertEquals(1, harness.engine.resumeCount)
        harness.coordinator.close()
    }

    @Test
    fun duplicatePhoneCallbacksDoNotRepeatAudioTeardown() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()

        harness.phone.emit(PhoneCallState.RINGING)
        harness.phone.emit(PhoneCallState.RINGING)
        harness.phone.emit(PhoneCallState.OFFHOOK)
        harness.phone.emit(PhoneCallState.OFFHOOK)

        assertEquals(AudioInterruptionState.PHONE_ACTIVE, harness.states.last())
        assertEquals(2, harness.engine.suspendCount)
        assertEquals(1, harness.route.suspendCount)
        assertEquals(1, harness.focus.abandonCount)
        harness.coordinator.close()
    }

    @Test
    fun focusChangesDuringPhoneCallKeepPhonePriorityWithoutNewAudioOperations() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()
        harness.phone.emit(PhoneCallState.OFFHOOK)
        val suspendCount = harness.engine.suspendCount
        val resumeCount = harness.engine.resumeCount
        val requestCount = harness.focus.requestCount

        harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(AudioInterruptionState.PHONE_ACTIVE, harness.states.last())
        assertEquals(suspendCount, harness.engine.suspendCount)
        assertEquals(resumeCount, harness.engine.resumeCount)
        assertEquals(requestCount, harness.focus.requestCount)
        harness.coordinator.close()
    }

    @Test
    fun delayedFocusWaitsForSystemGainWithoutReRequestingOrResumingEarly() {
        val harness = Harness(
            focusResults = listOf(AudioFocusResult.DELAYED, AudioFocusResult.GRANTED)
        )
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()

        assertEquals(AudioInterruptionState.RESUMING, harness.states.last())
        assertEquals(1, harness.focus.requestCount)
        assertEquals(0, harness.activateRouteCount)
        assertEquals(1, harness.engine.suspendCount)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        harness.coordinator.onRouteReady()
        assertEquals(1, harness.focus.requestCount)
        assertEquals(0, harness.engine.resumeCount)
        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, harness.focus.requestCount)
        assertEquals(1, harness.activateRouteCount)
        assertEquals(2, harness.engine.suspendCount)
        assertEquals(0, harness.engine.resumeCount)

        harness.coordinator.onRouteReady()
        assertEquals(1, harness.engine.resumeCount)
        assertEquals(AudioInterruptionState.NORMAL, harness.states.last())
        harness.coordinator.close()
    }

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
        assertEquals(0, harness.focus.abandonCount)
        assertEquals(listOf(true), harness.route.restoreModes)

        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(AudioInterruptionState.RESUMING, harness.states.last())
        assertEquals(1, harness.focus.requestCount)
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
    fun permanentFocusLossReleasesRouteAndDoesNotResumeOnStaleGain() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()
        val suspendCount = harness.engine.suspendCount

        harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(AudioInterruptionState.FOCUS_LOST, harness.states.last())
        assertEquals(suspendCount + 1, harness.engine.suspendCount)
        assertEquals(1, harness.focus.abandonCount)
        assertEquals(listOf(true), harness.route.restoreModes)
        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)
        harness.coordinator.onRouteReady()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, harness.engine.resumeCount)
        assertEquals(1, harness.focus.requestCount)
        harness.coordinator.close()
    }

    @Test
    fun stoppingMediaPreventsLateFocusGainFromResumingAudio() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()
        harness.coordinator.endMediaSession()
        assertEquals(listOf(true), harness.route.restoreModes)

        val resumeCount = harness.engine.resumeCount
        val activateCount = harness.activateRouteCount
        harness.focus.emit(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(resumeCount, harness.engine.resumeCount)
        assertEquals(activateCount, harness.activateRouteCount)
        assertFalse(harness.coordinator.isPhoneCallActive())
        harness.coordinator.close()
    }

    @Test
    fun routeInvalidationSuspendsUntilVerificationWithoutRequestingFocusAgain() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.onRouteReady()

        harness.coordinator.reapplyPreferredRoute()
        assertEquals(AudioInterruptionState.RESUMING, harness.states.last())
        assertEquals(1, harness.focus.requestCount)
        assertEquals(2, harness.activateRouteCount)
        assertEquals(listOf("suspend", "resume", "suspend"), harness.engine.audioOperations)
        harness.coordinator.reapplyPreferredRoute()
        assertEquals(2, harness.activateRouteCount)

        harness.coordinator.onRouteReady()
        assertEquals(AudioInterruptionState.NORMAL, harness.states.last())
        assertEquals(2, harness.engine.resumeCount)
        harness.coordinator.close()
    }

    @Test
    fun explicitSelectionCanReplaceAnUnverifiedRouteWithoutReacquiringFocus() {
        val harness = Harness()
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.reapplyPreferredRoute()
        assertEquals(1, harness.activateRouteCount)
        harness.coordinator.reapplyPreferredRoute(force = true)
        assertEquals(2, harness.activateRouteCount)
        assertEquals(1, harness.focus.requestCount)
        assertEquals(0, harness.engine.resumeCount)
        harness.coordinator.onRouteReady()
        assertEquals(1, harness.engine.resumeCount)
        harness.coordinator.close()
    }

    @Test
    fun routeEventsCannotRevivePhoneOrFocusInterruptions() {
        for (interruption in listOf("phone", "transient", "permanent")) {
            val harness = Harness()
            harness.coordinator.start()
            harness.coordinator.beginMediaSession()
            harness.coordinator.onRouteReady()
            when (interruption) {
                "phone" -> harness.phone.emit(PhoneCallState.OFFHOOK)
                "transient" -> harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                else -> harness.focus.emit(AudioManager.AUDIOFOCUS_LOSS)
            }
            val operations = harness.engine.audioOperations.toList()
            val state = harness.states.last()
            harness.coordinator.reapplyPreferredRoute()
            harness.coordinator.reapplyPreferredRoute(force = true)
            harness.coordinator.onRouteReady()
            assertEquals(interruption, state, harness.states.last())
            assertEquals(interruption, operations, harness.engine.audioOperations)
            assertEquals(1, harness.activateRouteCount)
            assertEquals(1, harness.focus.requestCount)
            harness.coordinator.close()
        }
    }

    @Test
    fun routeEventsCannotBypassDelayedFocus() {
        val harness = Harness(focusResults = listOf(AudioFocusResult.DELAYED))
        harness.coordinator.start()
        harness.coordinator.beginMediaSession()
        harness.coordinator.reapplyPreferredRoute(force = true)
        harness.coordinator.onRouteReady()
        assertEquals(0, harness.activateRouteCount)
        assertEquals(0, harness.engine.resumeCount)
        assertEquals(1, harness.focus.requestCount)
        harness.coordinator.close()
    }

    private class Harness(
        initialPhoneState: PhoneCallState = PhoneCallState.IDLE,
        focusResults: List<AudioFocusResult> = emptyList()
    ) {
        val engine = FakeEngine()
        val route = FakeRoute()
        val focus = FakeFocus(focusResults.toMutableList())
        val phone = FakePhone(initialPhoneState)
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
        val restoreModes = mutableListOf<Boolean>()

        override fun select(selection: AudioRouteSelection) = Unit

        override fun suspendForInterruption(restoreMode: Boolean) {
            suspendCount++
            restoreModes += restoreMode
        }

        override fun close() = Unit
    }

    private class FakeFocus(
        private val results: MutableList<AudioFocusResult>
    ) : IntercomAudioFocus {
        private var listener: ((Int) -> Unit)? = null
        var requestCount = 0
        var abandonCount = 0

        override fun setListener(listener: (Int) -> Unit) {
            this.listener = listener
        }

        override fun request(): AudioFocusResult {
            requestCount++
            return results.removeFirstOrNull() ?: AudioFocusResult.GRANTED
        }

        override fun abandon() {
            abandonCount++
        }

        fun emit(change: Int) {
            listener?.invoke(change)
        }

        override fun close() = Unit
    }

    private class FakePhone(
        private var state: PhoneCallState
    ) : IntercomPhoneState {
        private var listener: ((PhoneCallState) -> Unit)? = null

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
