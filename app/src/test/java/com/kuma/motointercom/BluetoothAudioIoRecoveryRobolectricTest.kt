package com.kuma.motointercom

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class BluetoothAudioIoRecoveryRobolectricTest {
    @Test fun platformBluetoothAloneCannotProveNativeBluetoothInputAndOutput() {
        Harness().use { h ->
            h.open().snapshot = snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC)
            tick()
            assertFalse(h.controller.routeEvidence().ready)
            tick(2_000)
            assertEquals(2, h.selections.size)
            assertTrue(h.engine.operations.takeLast(2) == listOf("suspend", "resume"))
            h.current.snapshot = snapshot()
            tick(1_000)
            assertTrue(h.controller.routeEvidence().ready)
        }
    }

    @Test fun phoneOutputOrStalePcmCannotBecomeBluetoothReady() {
        for (bad in listOf(snapshot(output = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), snapshot(fresh = false))) {
            Harness().use { h ->
                h.open().snapshot = bad
                tick(3_000)
                assertFalse(h.controller.routeEvidence().ready)
                assertEquals(2, h.selections.size)
            }
        }
    }

    @Test fun startupBeforePeerConnectionDoesNotConsumeRetriesAndRecoveryIsBounded() {
        Harness().use { h ->
            h.open().snapshot = snapshot(connected = false, fresh = false)
            tick(30_000)
            assertEquals(1, h.selections.size)
            h.current.snapshot = snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC)
            tick(30_000)
            assertEquals(3, h.selections.size)
            tick(30_000)
            assertEquals(3, h.selections.size)
            assertFalse(h.controller.routeEvidence().ready)
        }
    }

    @Test fun phoneSelectionAndLateProbeCannotForceBluetoothBackOn() {
        Harness().use { h ->
            val session = h.open().apply { holdQueries = true }
            tick()
            h.controller.updateAudioRoute(AudioRouteSelection.EARPIECE)
            session.deliver(snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC))
            tick(10_000)
            assertEquals(listOf(AudioRouteSelection.BLUETOOTH, AudioRouteSelection.EARPIECE), h.selections)
            assertEquals(AudioRouteSelection.EARPIECE, h.controller.preferredAudioRoute())
            assertTrue(h.controller.routeEvidence().ready)
        }
    }

    @Test fun delayedSessionAProbeCannotRecoverOrCertifyReplacementSessionB() {
        Harness().use { h ->
            val a = h.open().apply { holdQueries = true }
            tick()
            h.controller.closeMediaSession(a)
            val b = h.open().apply { holdQueries = true }
            tick()
            val count = h.selections.size
            a.deliver(snapshot())
            assertFalse(h.controller.routeEvidence().ready)
            a.deliver(snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC))
            assertEquals(count, h.selections.size)
            b.deliver(snapshot())
            assertTrue(h.controller.routeEvidence().ready)
        }
    }

    @Test fun focusAndPhoneInterruptionsRejectDelayedProbesAndDoNotReacquireFocus() {
        Harness().use { h ->
            val session = h.open().apply { holdQueries = true }
            tick()
            h.focus.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            session.deliver(snapshot())
            tick(20_000)
            assertFalse(h.controller.routeEvidence().ready)
            assertEquals(1, h.selections.size)
            assertEquals(1, h.focus.requests)
            h.phone.emit(PhoneCallState.OFFHOOK)
            h.focus.emit(AudioManager.AUDIOFOCUS_GAIN)
            tick(20_000)
            assertEquals(1, h.selections.size)
            assertEquals(AudioInterruptionState.PHONE_ACTIVE, h.coordinator.currentState())
        }
    }

    @Test fun closeAndUpstreamRevocationCancelFutureRecovery() {
        Harness().use { h ->
            val session = h.open().apply { snapshot = snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC) }
            tick()
            h.upstreamCurrent = false
            tick(30_000)
            assertEquals(1, h.selections.size)
            h.controller.closeMediaSession(session)
            tick(30_000)
            assertEquals(1, h.selections.size)
        }
    }

    @Test fun explicitBluetoothSelectionCanRetryAfterTheAutomaticBudgetIsSpent() {
        Harness().use { h ->
            h.open().snapshot = snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC)
            tick(30_000)
            assertEquals(3, h.selections.size)
            h.controller.updateAudioRoute(AudioRouteSelection.BLUETOOTH)
            tick(30_000)
            assertEquals(6, h.selections.size)
        }
    }

    @Test fun expiredOrFutureSameMediaSamplesCannotCertifyBluetoothOrSpendRecoveryBudget() {
        Harness().use { h ->
            val session = h.open().apply { holdQueries = true }
            tick()
            tick(2_500)
            session.deliverNext(snapshot())
            assertFalse(h.controller.routeEvidence().ready)
            assertEquals(1, h.selections.size)
            repeat(3) {
                tick(1_000)
                session.deliverNext(snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC), SystemClock.elapsedRealtime() - 2_001)
            }
            assertEquals(1, h.selections.size)
            tick(1_000)
            session.deliverNext(snapshot(), SystemClock.elapsedRealtime() + 1)
            assertFalse(h.controller.routeEvidence().ready)
            assertEquals(1, h.selections.size)
            tick(1_000)
            session.deliverNext(snapshot())
            assertTrue(h.controller.routeEvidence().ready)
        }
    }

    @Test @Config(sdk = [35], application = Application::class)
    fun activityRebindPreservesTheCurrentMediaRecoveryBudget() {
        Harness().use { h ->
            h.open().snapshot = snapshot(input = AudioDeviceInfo.TYPE_BUILTIN_MIC)
            tick(30_000)
            assertEquals(3, h.selections.size)
            val application = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(application).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            AudioRoutePreferences(application).save(AudioRouteSelection.BLUETOOTH)
            val database = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            database.set(null, null)
            val serviceOwner = Robolectric.buildService(IntercomService::class.java).create()
            val service = serviceOwner.get()
            IntercomService::class.java.getDeclaredField("audioSessionController").apply { isAccessible = true }.set(service, h.controller)
            shadowOf(application).setComponentNameAndServiceForBindService(
                ComponentName(application, IntercomService::class.java), service.onBind(Intent()))
            val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
            try {
                tick()
                val afterBind = h.selections.size
                tick(30_000)
                assertEquals("Binding granted extra automatic retries", afterBind, h.selections.size)
                activity.pause().stop().start().resume()
                tick()
                val afterRebind = h.selections.size
                tick(30_000)
                assertEquals("Background/foreground granted extra automatic retries", afterRebind, h.selections.size)
                MainActivity::class.java.getDeclaredMethod("savePreferredAudioRoute", AudioRouteSelection::class.java)
                    .apply { isAccessible = true }.invoke(activity.get(), AudioRouteSelection.BLUETOOTH)
                tick()
                val afterTap = h.selections.size
                tick(30_000)
                assertEquals("Explicit same Bluetooth tap cannot reset recovery", afterTap + 2, h.selections.size)
            } finally {
                activity.pause().stop().destroy()
                val scope = IntercomService::class.java.getDeclaredField("serviceScope").run {
                    isAccessible = true; get(service) as CoroutineScope
                }
                val stopped = CountDownLatch(1)
                scope.coroutineContext[Job]?.invokeOnCompletion { stopped.countDown() } ?: stopped.countDown()
                serviceOwner.destroy()
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                (database.get(null) as? PairingDatabase)?.close()
                database.set(null, null)
            }
        }
    }

    private class Harness : java.io.Closeable {
        val engine = Engine()
        val focus = Focus()
        val phone = Phone()
        val selections = mutableListOf<AudioRouteSelection>()
        var upstreamCurrent = true
        val route: RiderAudioRoute = object : RiderAudioRoute {
            var proof = AudioRouteEvidence(0, false)
            override fun evidence() = proof
            override fun select(selection: AudioRouteSelection) {
                selections.add(selection)
                proof = AudioRouteEvidence(proof.revision + 1, true, selection == AudioRouteSelection.BLUETOOTH)
                coordinator.onRouteReady()
            }
            override fun suspendForInterruption(restoreMode: Boolean) { proof = proof.copy(revision = proof.revision + 1, ready = false) }
            override fun close() = Unit
        }
        val coordinator: CommunicationAudioCoordinator = CommunicationAudioCoordinator(engine, route, focus, phone,
            activateRoute = { route.select(controller.preferredAudioRoute()) }, audioPrompt = object : IntercomAudioPrompt {
                override fun playResumePrompt() = Unit
                override fun close() = Unit
            })
        val controller: AudioSessionController = AudioSessionController(engine, route, audioCoordinator = coordinator)
        val current get() = engine.sessions.last()
        init { coordinator.start() }
        fun open(): Session = controller.openMediaSession(RiderMediaSessionCallbacks({}, {}, isSessionCurrent = { upstreamCurrent })) as Session
        override fun close() = controller.close()
    }
    private class Session : RiderMediaSession {
        var snapshot = snapshot()
        var holdQueries = false
        private data class Query(val sampledAt: Long, val callback: (RiderAudioIoRouteSnapshot?) -> Unit)
        private val pending = mutableListOf<Query>()
        override fun queryAudioIoRoute(callback: (RiderAudioIoRouteSnapshot?) -> Unit) {
            val sampledAt = SystemClock.elapsedRealtime()
            if (holdQueries) pending.add(Query(sampledAt, callback))
            else callback(snapshot.copy(sampledAtElapsedRealtimeMs = sampledAt))
        }
        fun deliver(value: RiderAudioIoRouteSnapshot) {
            pending.toList().also { pending.clear() }.forEach { it.callback(value.copy(sampledAtElapsedRealtimeMs = it.sampledAt)) }
        }
        fun deliverNext(value: RiderAudioIoRouteSnapshot, sampledAt: Long? = null) {
            pending.removeAt(0).let { it.callback(value.copy(sampledAtElapsedRealtimeMs = sampledAt ?: it.sampledAt)) }
        }
        override fun createOffer() = Unit
        override fun createAnswer(remoteSdpJson: String) = Unit
        override fun setRemoteAnswer(remoteSdpJson: String) = Unit
        override fun addRemoteIceCandidate(candidateJson: String) = Unit
        override fun close() = Unit
    }
    private class Engine : RiderMediaEngine {
        val sessions = mutableListOf<Session>()
        val operations = mutableListOf<String>()
        override fun updateAudioControls(controls: VersionedAudioControls) = Unit
        override fun openSession(callbacks: RiderMediaSessionCallbacks, icePolicy: MediaIcePolicy): RiderMediaSession = Session().also { sessions.add(it) }
        override fun suspendAudio() { operations.add("suspend") }
        override fun resumeAudio() { operations.add("resume") }
        override fun close() = Unit
    }
    private class Focus : IntercomAudioFocus {
        var requests = 0
        private var listener: ((Int) -> Unit)? = null
        override fun setListener(listener: (Int) -> Unit) { this.listener = listener }
        override fun request(): AudioFocusResult { ++requests; return AudioFocusResult.GRANTED }
        fun emit(value: Int) { listener?.invoke(value) }
        override fun abandon() = Unit
        override fun close() = Unit
    }
    private class Phone : IntercomPhoneState {
        private var state = PhoneCallState.IDLE
        private var listener: ((PhoneCallState) -> Unit)? = null
        override fun start(listener: (PhoneCallState) -> Unit) { this.listener = listener }
        override fun currentState() = state
        fun emit(value: PhoneCallState) { state = value; listener?.invoke(value) }
        override fun close() = Unit
    }
    companion object {
        private fun snapshot(connected: Boolean = true, fresh: Boolean = true,
            input: Int = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, output: Int = AudioDeviceInfo.TYPE_BLUETOOTH_SCO) =
            RiderAudioIoRouteSnapshot(connected, fresh, input, output, SystemClock.elapsedRealtime())
        private fun tick(ms: Long = 0) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    }
}
