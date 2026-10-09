package com.kuma.motointercom

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Looper
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MainActivityRobolectricTest {
    @Test fun serviceReadinessCallbackUpdatesConnectedHomeAndIsIgnoredAfterDetach() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).setThrowInBindService(SecurityException("no binder"))
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        val connected = MainActivity::class.java.getDeclaredField("serviceConnected").apply { isAccessible = true }
        try {
            connected.setBoolean(activity, true)
            val runtime = RuntimeSessionId("runtime-ui")
            val attempt = ConnectionAttempt(ConnectionAttemptId("attempt-ui"), runtime,
                TargetLock("peer", RuntimeSessionId("remote")), ConnectionTrigger.USER,
                ChannelPlan.single(Transport.LAN), 1_000)
            activity.onIntercomStateChanged(IntercomState.Connected(attempt, PeerIdentity("peer", "rider"), 1, Transport.LAN))
            activity.onAudioReadyChanged(true)
            val readiness = MainScreen::class.java.getDeclaredField("audioReady").apply { isAccessible = true }
            assertTrue(readiness.getBoolean(screen(activity)))
            activity.onAudioReadyChanged(false)
            assertFalse(readiness.getBoolean(screen(activity)))
            connected.setBoolean(activity, false)
            activity.onAudioReadyChanged(true)
            assertFalse(readiness.getBoolean(screen(activity)))
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test
    fun skippedOnboardingPermissionResultStartsServiceOnlyAfterActivityResumes() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        // No real service binder in this test; verify only the foreground-start intent ordering.
        shadowOf(app).setThrowInBindService(SecurityException("No test service binder"))
        OnboardingPreferences(app).save(OnboardingPhase.COMPLETE)
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        (activity.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled = true
        shadowOf(activity.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager).setLocationEnabled(true)
        shadowOf(activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .setIgnoringBatteryOptimizations(activity.packageName, true)
        MainActivity::class.java.getDeclaredMethod("startIntercom").apply { isAccessible = true }.invoke(activity)
        val request = shadowOf(activity).lastRequestedPermission
        assertEquals(StartupAccessController.PERMISSIONS, request.requestCode)
        assertEquals(null, shadowOf(activity).nextStartedService)
        lifecycle.pause()
        shadowOf(app).grantPermissions(*request.requestedPermissions)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED })
        assertEquals(null, shadowOf(activity).nextStartedService)
        lifecycle.resume()
        assertNotNull(shadowOf(activity).nextStartedService)
        lifecycle.pause().stop().destroy()
    }

    private fun clickBottomNavigation(activity: MainActivity, id: Int) {
        screen(activity).root.findViewById<View>(id).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun homeText(activity: MainActivity, tag: String): String {
        val state = screen(activity)::class.java.getDeclaredField("homeUiState").apply {
            isAccessible = true
        }.get(screen(activity)) as androidx.compose.runtime.MutableState<*>
        val value = state.value as HomeScreenUiState
        return when (tag) {
            "home_audio_source" -> value.audioSourceText
            "home_bluetooth_pill" -> value.bluetoothText
            "home_status_supplemental" -> value.supplementalText.orEmpty()
            else -> error("Activity test has no Home text mapping for $tag")
        }
    }

    private fun screen(activity: MainActivity): MainScreen =
        MainActivity::class.java.getDeclaredField("screen").apply {
            isAccessible = true
        }.get(activity) as MainScreen

    private fun invokeShowPage(screen: MainScreen, route: MainRoute) =
        MainScreen::class.java.getDeclaredMethod("showPage", MainRoute::class.java).apply {
            isAccessible = true
        }.invoke(screen, route)

    private fun invokePrivate(screen: MainScreen, name: String) =
        MainScreen::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(screen)

    private fun stateValue(screen: MainScreen, fieldName: String): Any? =
        (MainScreen::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(screen) as androidx.compose.runtime.MutableState<*>).value

    private fun setPrivateString(screen: MainScreen, fieldName: String, value: String) {
        MainScreen::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(screen, value)
        }
    }

    @Test
    fun actualActorRejectionUnlocksTheBoundActivityPresenceRequest() {
        val calls = AtomicInteger()
        withPresenceAdmissionFixture({ spent -> calls.incrementAndGet(); spent }) {
                service, actor, activity, presence, spent ->
            clickAdmissionPresence(activity, presence)
            val request = pendingAdmissionRequest(activity)
            assertEquals(request, admissionField(service, "latestPresenceConnectRequest").get(service))
            assertEquals(actor.state.value.runtimeSessionId, request.runtimeSessionId)
            assertEquals(presence.deviceId, request.targetDeviceId)
            assertEquals(presence.sessionId, request.targetSessionId)
            assertFalse(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
            shadowOf(Looper.getMainLooper()).idle()
            awaitAdmissionActor(actor)
            assertEquals(1, calls.get())
            assertEquals(ConnectionAttemptTerminalOutcome.FAILED, actor.terminalOutcome(spent))
            assertTrue(actor.state.value is IntercomState.Discovering)
            assertNull(actor.currentAttempt)
            assertNull(admissionField(service, "latestPresenceConnectRequest").get(service))
            assertNull(admissionField(screen(activity), "pendingConnectRequest").get(screen(activity)))
            assertTrue(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
            assertEquals("连接未启动，请重新选择车友", discoverAdmissionState(activity).supplementalText)
        }
    }

    @Test
    fun oldSameTargetActorRejectionCannotClearTheReboundActivityRequest() {
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val calls = AtomicInteger()
        withPresenceAdmissionFixture({ spent ->
            val (entered, release) = when (calls.incrementAndGet()) {
                1 -> enteredA to releaseA
                2 -> enteredB to releaseB
                else -> error("unexpected Presence admission")
            }
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS)) { "Presence factory gate timed out" }
            spent
        }) { service, actor, activity, presence, _ ->
            try {
                clickAdmissionPresence(activity, presence)
                val requestA = pendingAdmissionRequest(activity)
                awaitAdmissionGate(enteredA)
                stopAdmissionActivity(activity)
                bindAdmissionService(activity, service)
                clickAdmissionPresence(activity, presence)
                val requestB = pendingAdmissionRequest(activity)
                assertEquals(requestA.runtimeSessionId, requestB.runtimeSessionId)
                assertEquals(requestA.targetDeviceId, requestB.targetDeviceId)
                assertEquals(requestA.targetSessionId, requestB.targetSessionId)
                assertFalse(requestA.requestId == requestB.requestId)
                releaseA.countDown()
                awaitAdmissionGate(enteredB)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(requestB, pendingAdmissionRequest(activity))
                assertEquals(requestB, admissionField(service, "latestPresenceConnectRequest").get(service))
                assertFalse(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
                assertFalse(discoverAdmissionState(activity).supplementalText == "连接未启动，请重新选择车友")
                releaseB.countDown()
                awaitAdmissionActor(actor)
                assertTrue(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
                assertNull(admissionField(screen(activity), "pendingConnectRequest").get(screen(activity)))
                assertTrue(actor.state.value is IntercomState.Discovering)
                assertNull(actor.currentAttempt)
            } finally {
                releaseA.countDown()
                releaseB.countDown()
            }
        }
    }

    @Test
    fun admittedAttemptThatFinishesBeforeMainReceiptReportsItsActualTerminalOutcome() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptId = ConnectionAttemptId("presence-receipt-finished")
        withPresenceAdmissionFixture({
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS)) { "Presence factory gate timed out" }
            attemptId
        }) { service, actor, activity, presence, _ ->
            val admissions = mutableListOf<Pair<PresenceConnectRequest, Boolean>>()
            service.setListener(object : IntercomService.Listener by activity {
                override fun onPresenceConnectAdmission(request: PresenceConnectRequest, accepted: Boolean) {
                    admissions += request to accepted
                    activity.onPresenceConnectAdmission(request, accepted)
                }
            })
            try {
                clickAdmissionPresence(activity, presence)
                val request = pendingAdmissionRequest(activity)
                awaitAdmissionGate(entered)
                release.countDown()
                awaitAdmissionActor(actor, deliverOnMain = false)
                val actual = requireNotNull(actor.currentAttempt)
                assertEquals(attemptId, actual.id)
                assertEquals(request.runtimeSessionId, actual.runtimeSessionId)
                assertEquals(TargetLock(request.targetDeviceId, request.targetSessionId), actual.targetLock)
                assertNull(actor.terminalOutcome(attemptId))
                assertTrue(admissions.isEmpty())
                assertEquals(request, pendingAdmissionRequest(activity))
                runBlocking {
                    withTimeout(5_000L) {
                        assertTrue(actor.dispatchAndAwait(SessionEvent.TargetedTransportOpenFailed(
                            request.runtimeSessionId, attemptId, Transport.LAN, "finish before Main receipt"
                        )))
                    }
                }
                awaitAdmissionActor(actor, deliverOnMain = false)
                assertEquals(ConnectionAttemptTerminalOutcome.FAILED, actor.terminalOutcome(attemptId))
                assertEquals(IntercomState.Discovering(request.runtimeSessionId), actor.state.value)
                assertNull(actor.currentAttempt)
                assertTrue(admissions.isEmpty())
                assertEquals(request, admissionField(service, "latestPresenceConnectRequest").get(service))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(request to false), admissions)
                assertNull(admissionField(service, "latestPresenceConnectRequest").get(service))
                assertNull(admissionField(screen(activity), "pendingConnectRequest").get(screen(activity)))
                assertFalse(admissionField(screen(activity), "discoverConnectAwaitingState").getBoolean(screen(activity)))
                invokeShowPage(screen(activity), MainRoute.DISCOVER)
                assertTrue(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun oldAutoPairedActorRejectionCannotClearTheNewSameTargetGeneration() {
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val calls = AtomicInteger()
        val attemptB = ConnectionAttemptId("presence-auto-generation-b")
        withPresenceAdmissionFixture({ spent ->
            val first = when (calls.incrementAndGet()) {
                1 -> true
                2 -> false
                else -> error("unexpected auto Presence admission")
            }
            (if (first) enteredA else enteredB).countDown()
            check((if (first) releaseA else releaseB).await(5L, TimeUnit.SECONDS)) {
                "Auto Presence factory gate timed out"
            }
            if (first) spent else attemptB
        }) { service, actor, activity, presence, _ ->
            val preferred = presence.copy(
                deviceId = "a0000000-0000-4000-8000-000000000070",
                sessionId = RuntimeSessionId("10000000-0000-4000-8000-000000000070"),
                pairing = requireNotNull(pairedPresence().pairing).copy(
                remoteDeviceId = "a0000000-0000-4000-8000-000000000070", isPreferred = true
            ))
            val snapshot = PresenceSnapshot(listOf(preferred), nextExpiryElapsedRealtimeMs = null)
            val key = PreferredAutoConnectTargetKey(
                requireNotNull(preferred.deviceId), requireNotNull(preferred.sessionId)
            )
            val source = FreshDiscoverySource(Transport.LAN)
            fun publishFresh() {
                publishAdmissionSnapshot(service, snapshot)
                val candidate = DiscoveryCandidate(Transport.LAN, "admission-endpoint", "127.0.0.1", 1234,
                    DiscoveryIdentityClaim(preferred.deviceId, preferred.sessionId, preferred.nickname, preferred.deviceName, 2))
                val receipt = requireNotNull(source.capture(DiscoveryObservationKind.LAN_UDP, android.os.SystemClock.elapsedRealtime()))
                val observed = requireNotNull(source.accept(receipt, candidate) { true }).second
                val globalAdmission = admissionField(service, "discoveryPresenceAdmission").get(service) as DiscoveryPresenceAdmission
                globalAdmission.replaceCached(Transport.LAN, listOf(candidate))
                assertTrue(globalAdmission.admit(observed))
                IntercomService::class.java.getDeclaredMethod("maybeAutoConnectPreferred", PresenceSnapshot::class.java,
                    FreshDiscoveryObservation::class.java).apply { isAccessible = true }.invoke(service, snapshot, observed)
            }
            try {
                publishFresh()
                awaitAdmissionGate(enteredA)
                val generationA = admissionField(service, "autoConnectRequestGeneration").getLong(service)
                assertEquals(key, admissionField(service, "autoConnectTargetKey").get(service))
                publishFresh()
                val generationB = admissionField(service, "autoConnectRequestGeneration").getLong(service)
                assertTrue(generationB > generationA)
                releaseA.countDown()
                awaitAdmissionGate(enteredB)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, calls.get())
                assertEquals(generationB, admissionField(service, "autoConnectRequestGeneration").getLong(service))
                assertEquals(key, admissionField(service, "autoConnectTargetKey").get(service))
                assertTrue(actor.state.value is IntercomState.Discovering)
                assertNull(actor.currentAttempt)
                releaseB.countDown()
                awaitAdmissionActor(actor, deliverOnMain = false)
                val actualB = requireNotNull(actor.currentAttempt)
                assertEquals(attemptB, actualB.id)
                assertEquals(ConnectionTrigger.AUTO_PAIRED, actualB.trigger)
                assertEquals(TargetLock(key.deviceId, key.sessionId), actualB.targetLock)
                assertEquals(actor.state.value.runtimeSessionId, actualB.runtimeSessionId)
                assertEquals(key, admissionField(service, "autoConnectTargetKey").get(service))
                assertFalse(admissionField(screen(activity), "discoverConnectAwaitingState").getBoolean(screen(activity)))
            } finally {
                releaseA.countDown()
                releaseB.countDown()
            }
        }
    }

    @Test
    fun cleanupCancelsOldTokenBeforeSameRuntimeRestartAndLateFreshAcceptanceCannotStealRequestB() =
        assertRevokedFreshAttemptCannotStealReplacement(differentTarget = false)

    @Test
    fun revokedFreshAttemptCannotStealDifferentTargetAfterSameRuntimeRestart() =
        assertRevokedFreshAttemptCannotStealReplacement(differentTarget = true)

    private fun assertRevokedFreshAttemptCannotStealReplacement(differentTarget: Boolean) {
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val calls = AtomicInteger()
        val runtime = RuntimeSessionId.create()
        val attemptA = ConnectionAttemptId("revoked-token-a-fresh")
        val attemptB = ConnectionAttemptId("current-token-b-fresh")
        withPresenceAdmissionFixture(manualFactory = {
            val (entered, release) = when (calls.incrementAndGet()) {
                1 -> enteredA to releaseA
                2 -> enteredB to releaseB
                else -> error("unexpected Presence admission")
            }
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS)) { "Presence factory gate timed out" }
            if (calls.get() == 1) attemptA else attemptB
        }, runtime = runtime) { service, actor, activity, presence, _ ->
            val effects = recordAdmissionEffects(actor)
            val sessions = admissionField(service, "sessions").get(service) as SessionGeneration
            val tokenA = admissionField(service, "activeSession").get(service) as SessionGeneration.Token
            val originalOwner = admissionField(service, "wifiTunnelCloseOwner").get(service)
            val cleanup = mutableListOf<() -> Unit>()
            val localDeviceId = java.util.UUID.randomUUID().toString()
            val tunnelA = WifiDirectTunnel(service, { _, _ -> }, localDeviceId = localDeviceId,
                localDeviceName = "admission fixture", sessionId = runtime)
            val pendingClose = PendingCloseOwner<WifiDirectTunnel> { resource, complete ->
                resource.close { cleanup += complete }
            }
            val admissions = mutableListOf<Pair<PresenceConnectRequest, Boolean>>()
            service.setListener(object : IntercomService.Listener by activity {
                override fun onPresenceConnectAdmission(request: PresenceConnectRequest, accepted: Boolean) {
                    admissions += request to accepted
                    activity.onPresenceConnectAdmission(request, accepted)
                }
            })
            admissionField(service, "localDeviceId").set(service, localDeviceId)
            admissionField(service, "wifiTunnel").set(service, tunnelA)
            admissionField(service, "wifiTunnelCloseOwner").set(service, pendingClose)
            try {
                clickAdmissionPresence(activity, presence)
                val requestA = pendingAdmissionRequest(activity)
                awaitAdmissionGate(enteredA)
                assertTrue(abortAdmissionResources(service, runtime))
                assertEquals(listOf(requestA to false), admissions)
                assertNull(admissionField(screen(activity), "pendingConnectRequest").get(screen(activity)))
                assertFalse(admissionField(screen(activity), "discoverConnectAwaitingState").getBoolean(screen(activity)))
                assertNull(admissionField(service, "activeSession").get(service))
                assertFalse(sessions.isCurrent(tokenA))
                assertEquals(runtime.value, admissionField(service, "activeRuntimeSessionId").get(service))
                assertTrue(pendingClose.hasPending)
                assertEquals(1, cleanup.size)
                cleanup.single().invoke()
                shadowOf(Looper.getMainLooper()).idle()
                val tokenB = admissionField(service, "activeSession").get(service) as SessionGeneration.Token
                assertFalse(tokenA == tokenB)
                assertTrue(sessions.isCurrent(tokenB))
                assertEquals(runtime.value, admissionField(service, "activeRuntimeSessionId").get(service))
                assertEquals(IntercomState.Discovering(runtime), actor.state.value)
                assertFalse(pendingClose.hasPending)
                admissionField(service, "wifiTunnelCloseOwner").set(service, originalOwner)
                val candidate = presence.candidates.single { it.transport == Transport.LAN }
                val aggregator = admissionField(service, "presenceAggregator").get(service) as PresenceAggregator
                val identityB = if (differentTarget) DiscoveryIdentityClaim(
                    java.util.UUID.randomUUID().toString(), RuntimeSessionId.create(), "Rider B", "Phone B", 2
                ) else DiscoveryIdentityClaim(presence.deviceId, presence.sessionId,
                    presence.nickname, presence.deviceName, presence.protocolVersion)
                val restored = aggregator.replaceCandidates(Transport.LAN, listOf(DiscoveryCandidate(
                    candidate.transport, candidate.endpointId, candidate.address, candidate.port,
                    identityB
                )))
                publishAdmissionSnapshot(service, restored)
                val selectedB = restored.presences.single()
                assertTrue(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
                clickAdmissionPresence(activity, selectedB)
                val requestB = pendingAdmissionRequest(activity)
                assertEquals(requestA.runtimeSessionId, requestB.runtimeSessionId)
                assertEquals(differentTarget, requestA.targetDeviceId != requestB.targetDeviceId)
                assertEquals(differentTarget, requestA.targetSessionId != requestB.targetSessionId)
                assertFalse(requestA.requestId == requestB.requestId)
                releaseA.countDown()
                awaitAdmissionGate(enteredB)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, calls.get())
                assertEquals(listOf(requestA to false), admissions)
                assertEquals(requestB, pendingAdmissionRequest(activity))
                assertEquals(requestB, admissionField(service, "latestPresenceConnectRequest").get(service))
                assertFalse(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
                assertTrue(sessions.isCurrent(tokenB))
                assertEquals(IntercomState.Discovering(runtime), actor.state.value)
                assertNull(actor.currentAttempt)
                assertNull(actor.terminalOutcome(attemptA))
                assertTrue(effects.filterIsInstance<SessionEffect.OpenTargetedTransport>().isEmpty())
                releaseB.countDown()
                awaitAdmissionActor(actor, deliverOnMain = false)
                val actualB = requireNotNull(actor.currentAttempt)
                assertEquals(attemptB, actualB.id)
                assertEquals(TargetLock(requestB.targetDeviceId, requestB.targetSessionId), actualB.targetLock)
                assertTrue(actor.state.value is IntercomState.Connecting)
                assertEquals(listOf(requestA to false), admissions)
                assertEquals(requestB, admissionField(service, "latestPresenceConnectRequest").get(service))
                assertEquals(requestB, pendingAdmissionRequest(activity))
                assertEquals(listOf(attemptB), effects.filterIsInstance<SessionEffect.OpenTargetedTransport>()
                    .map { it.attempt.id })
            } finally {
                releaseA.countDown()
                releaseB.countDown()
                cleanup.toList().forEach { it() }
                admissionField(service, "wifiTunnelCloseOwner").set(service, originalOwner)
            }
        }
    }

    @Test
    fun revokedAdoptedAttemptCancelsAfterCleanupAndCannotOpenOrBlockFreshTargetB() {
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val actorPaused = CountDownLatch(1)
        val resumeActor = CountDownLatch(1)
        val calls = AtomicInteger()
        val runtime = RuntimeSessionId.create()
        val attemptA = ConnectionAttemptId("adopted-revoked-a-fresh")
        val attemptB = ConnectionAttemptId("adopted-replacement-b-fresh")
        withPresenceAdmissionFixture(manualFactory = {
            val index = calls.incrementAndGet()
            val (entered, release) = when (index) {
                1 -> enteredA to releaseA
                2 -> enteredB to releaseB
                else -> error("unexpected Presence admission")
            }
            entered.countDown()
            check(release.await(5L, TimeUnit.SECONDS)) { "Presence factory gate timed out" }
            if (index == 1) attemptA else attemptB
        }, runtime = runtime) { service, actor, activity, presence, _ ->
            val effects = recordAdmissionEffects(actor)
            val events = recordAdmissionEvents(actor)
            val sessions = admissionField(service, "sessions").get(service) as SessionGeneration
            val tokenA = admissionField(service, "activeSession").get(service) as SessionGeneration.Token
            val originalOwner = admissionField(service, "wifiTunnelCloseOwner").get(service)
            val cleanup = mutableListOf<() -> Unit>()
            val localDeviceId = java.util.UUID.randomUUID().toString()
            val tunnelA = WifiDirectTunnel(service, { _, _ -> }, localDeviceId = localDeviceId,
                localDeviceName = "adopted admission fixture", sessionId = runtime)
            val pendingClose = PendingCloseOwner<WifiDirectTunnel> { resource, complete ->
                resource.close { cleanup += complete }
            }
            val admissions = mutableListOf<Pair<PresenceConnectRequest, Boolean>>()
            var requestA: PresenceConnectRequest? = null
            var revokedOpen: SessionEffect.OpenTargetedTransport? = null
            var probedWhileTokenCurrent = false
            service.setListener(object : IntercomService.Listener by activity {
                override fun onPresenceConnectAdmission(request: PresenceConnectRequest, accepted: Boolean) {
                    admissions += request to accepted
                    if (!accepted && request == requestA) {
                        val oldOpen = requireNotNull(revokedOpen)
                        // Probe while the token and actor owner are current: only revocation can reject Open.
                        assertTrue(sessions.isCurrent(tokenA))
                        assertEquals(tokenA, admissionField(service, "activeSession").get(service))
                        assertEquals(oldOpen.attempt, actor.currentAttempt)
                        assertNull(actor.terminalOutcome(attemptA))
                        assertFalse(actor.isAttemptAuthorized(oldOpen.attempt))
                        assertNull(admissionField(service, "lanDiscovery").get(service))
                        deliverAdmissionEffect(service, oldOpen)
                        assertFalse(events.filterIsInstance<SessionEvent.TargetedTransportOpenFailed>()
                            .any { it.attemptId == attemptA })
                        probedWhileTokenCurrent = true
                    }
                    activity.onPresenceConnectAdmission(request, accepted)
                }
            })
            admissionField(service, "localDeviceId").set(service, localDeviceId)
            admissionField(service, "wifiTunnel").set(service, tunnelA)
            admissionField(service, "wifiTunnelCloseOwner").set(service, pendingClose)
            try {
                clickAdmissionPresence(activity, presence)
                requestA = pendingAdmissionRequest(activity)
                val exactRequestA = requireNotNull(requestA)
                awaitAdmissionGate(enteredA)
                releaseA.countDown()
                awaitAdmissionActor(actor, deliverOnMain = false)
                val actualA = requireNotNull(actor.currentAttempt)
                assertEquals(attemptA, actualA.id)
                assertEquals(TargetLock(exactRequestA.targetDeviceId, exactRequestA.targetSessionId), actualA.targetLock)
                assertEquals(IntercomState.Connecting(actualA), actor.state.value)
                assertTrue(actor.isAttemptAuthorized(actualA))
                revokedOpen = effects.filterIsInstance<SessionEffect.OpenTargetedTransport>()
                    .single { it.attempt == actualA }
                assertTrue(admissions.isEmpty())
                assertEquals(exactRequestA, pendingAdmissionRequest(activity))

                // Pause the real actor after adoption/effect production, outside every owner lock.
                assertTrue(actor.dispatch(SessionEvent.AutomaticReconnectChanged(true)) { accepted ->
                    check(accepted)
                    actorPaused.countDown()
                    check(resumeActor.await(5L, TimeUnit.SECONDS)) { "Actor cancellation gate timed out" }
                })
                assertTrue("Actor did not pause", actorPaused.await(5L, TimeUnit.SECONDS))
                assertTrue(abortAdmissionResources(service, runtime))
                assertTrue(probedWhileTokenCurrent)
                assertEquals(listOf(exactRequestA to false), admissions)
                assertNull(admissionField(screen(activity), "pendingConnectRequest").get(screen(activity)))
                assertFalse(admissionField(screen(activity), "discoverConnectAwaitingState").getBoolean(screen(activity)))
                assertFalse(sessions.isCurrent(tokenA))
                assertNull(admissionField(service, "activeSession").get(service))
                val canceled = events.filterIsInstance<SessionEvent.PresenceConnectCanceled>().single()
                assertEquals(actualA, canceled.attempt)
                assertEquals(exactRequestA, canceled.admission.request)
                assertTrue(canceled.admission.isRevoked)
                assertEquals(actualA, canceled.admission.adoptedAttempt)
                assertTrue(canceled.resourcesAlreadyClosing)
                assertEquals(1, cleanup.size)
                assertTrue(pendingClose.hasPending)

                val coordinator = requireNotNull(admissionField(service, "recoveryCleanupCoordinator").get(service))
                val cleanupRecord = requireNotNull(admissionField(coordinator, "active").get(coordinator))
                val originalCleanup = admissionField(cleanupRecord, "request").get(cleanupRecord) as RecoveryCleanupRequest
                assertEquals(0L, originalCleanup.restartDelayMillis)
                cleanup.single().invoke()
                shadowOf(Looper.getMainLooper()).idle()
                // Physical cleanup completes before exact cancellation reaches the actor.
                assertFalse(pendingClose.hasPending)
                assertTrue(admissionField(cleanupRecord, "cleanupComplete").getBoolean(cleanupRecord))
                assertNull(admissionField(cleanupRecord, "restartCallback").get(cleanupRecord))
                assertEquals(originalCleanup, admissionField(cleanupRecord, "request").get(cleanupRecord))
                assertEquals(actualA, actor.currentAttempt)
                assertNull(actor.terminalOutcome(attemptA))
                assertNull(admissionField(service, "activeSession").get(service))
                assertEquals(listOf(exactRequestA to false), admissions)
                assertFalse(events.filterIsInstance<SessionEvent.TargetedTransportOpenFailed>()
                    .any { it.attemptId == attemptA })

                resumeActor.countDown()
                awaitAdmissionActor(actor, deliverOnMain = false)
                assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, actor.terminalOutcome(attemptA))
                assertNull(actor.currentAttempt)
                assertEquals(IntercomState.Discovering(runtime), actor.state.value)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(originalCleanup, admissionField(cleanupRecord, "request").get(cleanupRecord))
                val tokenB = admissionField(service, "activeSession").get(service) as? SessionGeneration.Token
                assertNotNull("Completed cleanup was not rechecked after actual cancellation", tokenB)
                assertTrue(sessions.isCurrent(requireNotNull(tokenB)))
                assertFalse(tokenA == tokenB)
                assertEquals(runtime.value, admissionField(service, "activeRuntimeSessionId").get(service))
                assertEquals(listOf(exactRequestA to false), admissions)
                admissionField(service, "wifiTunnelCloseOwner").set(service, originalOwner)

                val candidate = presence.candidates.single { it.transport == Transport.LAN }
                val aggregator = admissionField(service, "presenceAggregator").get(service) as PresenceAggregator
                val restored = aggregator.replaceCandidates(Transport.LAN, listOf(DiscoveryCandidate(
                    candidate.transport, candidate.endpointId, candidate.address, candidate.port,
                    DiscoveryIdentityClaim(java.util.UUID.randomUUID().toString(), RuntimeSessionId.create(),
                        "Rider B", "Phone B", 2)
                )))
                publishAdmissionSnapshot(service, restored)
                invokeShowPage(screen(activity), MainRoute.DISCOVER)
                assertTrue(discoverAdmissionState(activity).presentation.cards.single().connectEnabled)
                clickAdmissionPresence(activity, restored.presences.single())
                val requestB = pendingAdmissionRequest(activity)
                assertEquals(exactRequestA.runtimeSessionId, requestB.runtimeSessionId)
                assertFalse(exactRequestA.targetDeviceId == requestB.targetDeviceId)
                assertFalse(exactRequestA.targetSessionId == requestB.targetSessionId)
                assertFalse(exactRequestA.requestId == requestB.requestId)
                awaitAdmissionGate(enteredB)
                assertEquals(requestB, pendingAdmissionRequest(activity))
                assertEquals(requestB, admissionField(service, "latestPresenceConnectRequest").get(service))
                assertEquals(listOf(exactRequestA to false), admissions)
                assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, actor.terminalOutcome(attemptA))
                assertNull(actor.currentAttempt)
                releaseB.countDown()
                awaitAdmissionActor(actor, deliverOnMain = false)
                val actualB = requireNotNull(actor.currentAttempt)
                assertEquals(attemptB, actualB.id)
                assertEquals(TargetLock(requestB.targetDeviceId, requestB.targetSessionId), actualB.targetLock)
                assertEquals(ConnectionTrigger.USER, actualB.trigger)
                assertTrue(actor.isAttemptAuthorized(actualB))
                assertFalse(actor.isAttemptAuthorized(actualA))
                assertEquals(IntercomState.Connecting(actualB), actor.state.value)
                assertEquals(listOf(attemptA, attemptB), effects.filterIsInstance<SessionEffect.OpenTargetedTransport>()
                    .map { it.attempt.id })
                assertEquals(2, calls.get())
            } finally {
                revokedOpen = null
                requestA = null
                releaseA.countDown()
                resumeActor.countDown()
                releaseB.countDown()
                cleanup.toList().forEach { it() }
                admissionField(service, "wifiTunnelCloseOwner").set(service, originalOwner)
            }
        }
    }

    @Test
    fun activityOwnsXmlRoutesAndRestoresUiStateBeforeServiceStart() {
        val firstController = Robolectric.buildActivity(MainActivity::class.java).create()
        val first = firstController.get()

        clickBottomNavigation(first, R.id.bottom_nav_settings_button)
        setPrivateString(screen(first), "settingsNicknameDraft", "Activity Draft")
        invokePrivate(screen(first), "renderSettings")

        val savedState = Bundle()
        firstController.saveInstanceState(savedState)
        firstController.destroy()

        val recreated = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
            .get()

        assertNotNull(recreated.findViewById<View>(R.id.settings_scroll))
        assertEquals(
            "Activity Draft",
            (stateValue(screen(recreated), "settingsUiState") as SettingsScreenUiState).nickname
        )
        assertTrue(recreated.findViewById<View>(R.id.home_scroll) == null)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun manualDiscoveryRefreshWithoutABoundServiceReportsUnavailable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val mainScreen = screen(activity)
        mainScreen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-unbound-refresh")),
            canStart = true
        )
        invokeShowPage(mainScreen, MainRoute.DISCOVER)
        val refresh = MainScreen::class.java
            .getDeclaredField("onRequestDiscoveryRefresh")
            .apply { isAccessible = true }
            .get(mainScreen) as () -> Unit

        refresh()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            SERVICE_UNAVAILABLE_STATUS,
            (stateValue(mainScreen, "discoverUiState") as DiscoverScreenUiState).supplementalText
        )
        controller.destroy()
    }

    @Test
    fun activityLoadsPersistedVoxSettingsBeforeAnyServiceReplay() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val preferences = AudioControlPreferences(application)
        preferences.saveVoxSettings(
            AudioControlSettings(voxEnabled = false, voxSensitivity = 70)
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        val home = stateValue(screen(activity), "homeUiState") as HomeScreenUiState
        assertEquals("DISABLED", home.voxText)
        assertEquals(70, home.voxSensitivity)
        assertFalse(home.voxEnabled)

        controller.destroy()
        preferences.saveVoxSettings(AudioControlSettings())
    }

    @Test
    fun activityLoadsAndPersistsAutomaticReconnectBeforeServiceReplay() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val preferences = application.getSharedPreferences("moto_intercom", android.content.Context.MODE_PRIVATE)
        preferences.edit().putBoolean("automatic_reconnect_enabled", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val mainScreen = screen(activity)

        assertFalse(
            MainActivity::class.java.getDeclaredField("automaticReconnectEnabled").apply {
                isAccessible = true
            }.getBoolean(activity)
        )
        invokeShowPage(mainScreen, MainRoute.SETTINGS)
        assertFalse(
            (stateValue(mainScreen, "settingsUiState") as SettingsScreenUiState)
                .automaticReconnectEnabled
        )

        @Suppress("UNCHECKED_CAST")
        val callback = MainScreen::class.java.getDeclaredField("onAutomaticReconnectChanged").apply {
            isAccessible = true
        }.get(mainScreen) as (Boolean) -> Unit
        callback(true)

        assertTrue(preferences.getBoolean("automatic_reconnect_enabled", false))
        controller.destroy()
    }

    @Test
    fun feedbackUsesAnExplicitUserChooserWithoutAttachingSessionLogs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()

        MainActivity::class.java.getDeclaredMethod("sendFeedback", String::class.java).apply {
            isAccessible = true
        }.invoke(activity, "1.1.0")

        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            ?: error("feedback chooser is missing its send Intent")
        assertEquals(Intent.ACTION_SEND, payload.action)
        assertEquals("text/plain", payload.type)
        assertTrue(payload.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().contains("MotoCom"))
        val expectedBody = activity.getString(
            R.string.feedback_body,
            "1.1.0",
            "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        )
        assertEquals(expectedBody, payload.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(null, payload.data)
        payload.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                val item = clipData.getItemAt(index)
                assertEquals(null, item.uri)
                assertFalse(item.text?.toString().orEmpty().contains("session log", ignoreCase = true))
            }
        }
        assertEquals(
            0,
            payload.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
        )
    }

    @Test
    fun activityOwnsAndPersistsPreferredAudioRouteBeforeServiceReplay() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val preferences = AudioRoutePreferences(application)
        preferences.save(AudioRouteSelection.EARPIECE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        assertEquals(
            AudioRouteSelection.EARPIECE,
            MainScreen::class.java.getDeclaredField("preferredAudioRoute").apply {
                isAccessible = true
            }.get(screen(activity))
        )

        MainActivity::class.java.getDeclaredMethod(
            "savePreferredAudioRoute",
            AudioRouteSelection::class.java
        ).apply { isAccessible = true }.invoke(activity, AudioRouteSelection.SPEAKER)

        assertEquals(AudioRouteSelection.SPEAKER, AudioRoutePreferences(application).load())
        assertEquals(
            AudioRouteSelection.SPEAKER,
            MainScreen::class.java.getDeclaredField("preferredAudioRoute").apply {
                isAccessible = true
            }.get(screen(activity))
        )

        controller.destroy()
        preferences.save(AudioRouteSelection.BLUETOOTH)
    }

    @Test
    fun processRestartDoesNotRestoreSavedRouteFromAnotherProcess() {
        val savedState = Bundle().apply {
            putString("main_route", MainRoute.SETTINGS.name)
            putString("process_session_token", "previous-process")
        }

        val recreated = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
            .get()

        assertNotNull(recreated.findViewById<View>(R.id.home_scroll))
        assertTrue(recreated.findViewById<View>(R.id.settings_scroll) == null)
    }

    @Test
    fun recreatedActivityCanRenderServiceReplayedIncomingConfirmation() {
        val savedState = Bundle()
        val firstController = Robolectric.buildActivity(MainActivity::class.java).create()
        firstController.saveInstanceState(savedState)
        firstController.destroy()

        val recreatedController = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
        val recreated = recreatedController.get()
        setPrivateBoolean(recreated, "serviceConnected", true)

        recreated.onIncomingConfirmation(incomingPrompt("recreated-nonce", "Recreated Rider"))
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("replayed incoming dialog was not shown")
        assertTrue(dialog.isShowing)
        assertEquals(
            recreated.getString(R.string.incoming_confirmation_title, "Recreated Rider"),
            shadowOf(dialog).title
        )
        dialog.dismiss()
        recreatedController.destroy()
    }

    @Test
    fun incomingConfirmationDismissesPairingManagementBeforeTakingPriority() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val mainScreen = screen(activity)
        val presence = pairedPresence()
        invokeShowPage(mainScreen, MainRoute.DISCOVER)
        mainScreen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-pairing-priority")),
            canStart = true
        )
        mainScreen.setPresences(listOf(presence))
        MainScreen::class.java.getDeclaredMethod(
            "showPairingManagement",
            RiderPresence::class.java
        ).apply { isAccessible = true }.invoke(mainScreen, presence)
        val pairingDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("pairing management dialog was not shown")
        assertTrue(pairingDialog.isShowing)

        showIncomingConfirmation(activity, incomingPrompt("pairing-priority", "Incoming Rider"))
        val incomingDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming confirmation dialog was not shown")

        assertFalse(pairingDialog.isShowing)
        assertTrue(incomingDialog.isShowing)
        assertEquals(
            activity.getString(R.string.incoming_confirmation_title, "Incoming Rider"),
            shadowOf(incomingDialog).title
        )
        incomingDialog.dismiss()
        controller.destroy()
    }

    @Test
    fun replacedIncomingDialogCannotActOnTheCurrentRequest() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        showIncomingConfirmation(activity, incomingPrompt("old-nonce", "Old Rider"))
        val oldDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("first incoming dialog was not shown")

        showIncomingConfirmation(activity, incomingPrompt("new-nonce", "New Rider"))
        val currentDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("replacement incoming dialog was not shown")

        assertFalse(oldDialog.isShowing)
        oldDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(currentDialog.isShowing)
        assertEquals(
            activity.getString(R.string.incoming_confirmation_title, "New Rider"),
            shadowOf(currentDialog).title
        )

        currentDialog.dismiss()
        controller.destroy()
    }

    @Test
    fun incomingConfirmationSupersedesHelpDialog() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        invokePrivate(screen(activity), "showHelpDialog")
        val help = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("help dialog was not shown")
        assertTrue(help.isShowing)

        showIncomingConfirmation(activity, incomingPrompt("incoming-nonce", "Incoming Rider"))
        val incoming = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming dialog was not shown")

        assertFalse(help.isShowing)
        assertTrue(incoming.isShowing)
        assertEquals(
            activity.getString(R.string.incoming_confirmation_title, "Incoming Rider"),
            shadowOf(incoming).title
        )

        incoming.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(incoming.isShowing)
        assertNotNull(activity.findViewById<View>(R.id.home_scroll))
        controller.destroy()
    }

    @Test
    fun incomingConfirmationClosesWhenServiceStateLeavesConfirmation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "serviceConnected", true)

        showIncomingConfirmation(activity, incomingPrompt("state-close-nonce", "Incoming Rider"))
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming dialog was not shown")
        assertTrue(dialog.isShowing)

        activity.onIntercomStateChanged(IntercomState.Offline)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        controller.destroy()
    }

    @Test
    fun serviceReplayedStatusAndPeerDoNotBecomeCurrentSessionLogs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "serviceConnected", true)
        setPrivateBoolean(activity, "replayingServiceSnapshot", true)

        activity.onStatusChanged("回放的服务状态", running = true)
        activity.onRemoteRiderIdentified("回放的远端骑士")
        shadowOf(Looper.getMainLooper()).idle()

        clickBottomNavigation(activity, R.id.bottom_nav_settings_button)
        invokeShowPage(screen(activity), MainRoute.LOGS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            activity.getString(R.string.logs_empty),
            (stateValue(screen(activity), "logsUiState") as LogsScreenUiState).logText
        )

        setPrivateBoolean(activity, "replayingServiceSnapshot", false)
        activity.onStatusChanged("实时服务状态", running = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            (stateValue(screen(activity), "logsUiState") as LogsScreenUiState).logText
                .toString()
                .contains("实时服务状态")
        )
        controller.destroy()
    }

    @Test
    fun serviceDisconnectClearsStaleAudioBluetoothAndPresenceFacts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "bindingRegistered", true)
        setPrivateBoolean(activity, "serviceConnected", true)

        activity.onAudioSourceChanged("当前音频源：蓝牙耳机 (Helmet)", bluetooth = true)
        activity.onPresencesChanged(stalePresence())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            BLUETOOTH_PERMISSION_UNAVAILABLE,
            homeText(activity, "home_audio_source")
        )

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "当前音频源：待机",
            homeText(activity, "home_audio_source")
        )
        assertFalse(
            homeText(activity, "home_bluetooth_pill")
                .contains(BLUETOOTH_CONNECTED_TEXT)
        )

        clickBottomNavigation(activity, R.id.bottom_nav_discover_button)
        assertEquals(
            0,
            (stateValue(screen(activity), "discoverUiState") as DiscoverScreenUiState)
                .presentation
                .cards
                .size
        )
        controller.destroy()
    }

    @Test
    fun serviceDisconnectBeforeRebindConnectionDoesNotOverwriteUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "bindingRegistered", true)
        setPrivateBoolean(activity, "serviceConnected", false)
        setPrivateBoolean(activity, "serviceConnected", true)
        activity.onStatusChanged("保留当前状态", running = false)
        setPrivateBoolean(activity, "serviceConnected", false)
        shadowOf(Looper.getMainLooper()).idle()

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )

        assertEquals(
            "保留当前状态",
            homeText(activity, "home_status_supplemental")
        )
        controller.destroy()
    }

    @Test
    fun lateServiceDisconnectAfterActivityStopDoesNotOverwriteUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        controller.stop()
        setPrivateBoolean(activity, "serviceConnected", true)
        activity.onStatusChanged("保留当前状态", running = false)
        setPrivateBoolean(activity, "serviceConnected", false)
        shadowOf(Looper.getMainLooper()).idle()
        val before = homeText(activity, "home_status_supplemental")

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )

        assertEquals(
            before,
            homeText(activity, "home_status_supplemental")
        )
        controller.destroy()
    }

    @Test
    fun serviceAudioReadinessIsReplayedAndRevokedOnInterruptionAndUnbind() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val serviceController = Robolectric.buildService(IntercomService::class.java).create()
        val service = serviceController.get()
        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        val publish = IntercomService::class.java.getDeclaredMethod("publishAudioReady", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val ready = MainScreen::class.java.getDeclaredField("audioReady").apply { isAccessible = true }
        val attempt = ConnectionAttemptFixture.create(MonotonicClock { MonotonicTimestamp(0) })
        val connected = IntercomState.Connected(attempt,
            PeerIdentity(attempt.targetDeviceId, "Rider", runtimeSessionId = attempt.targetLock.expectedRemoteSessionId),
            0, Transport.LAN)
        try {
            publish.invoke(service, true)
            setPrivateBoolean(activity, "bindingRegistered", true)
            connection.onServiceConnected(ComponentName(activity, IntercomService::class.java), service.onBind(Intent()))
            // Offline snapshots must never display readiness even when the service has a stale true value.
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ready.getBoolean(screen(activity)))
            val orchestrator = IntercomService::class.java.getDeclaredField("orchestrator").run {
                isAccessible = true; get(service) as SessionOrchestrator
            }
            @Suppress("UNCHECKED_CAST")
            val state = SessionOrchestrator::class.java.getDeclaredField("mutableState").run {
                isAccessible = true; get(orchestrator) as kotlinx.coroutines.flow.MutableStateFlow<IntercomState>
            }
            state.value = connected
            val productState = MainScreen::class.java.getDeclaredField("productState").apply { isAccessible = true }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
            while (productState.get(screen(activity)) != connected && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertEquals(connected, productState.get(screen(activity)))
            publish.invoke(service, false)
            publish.invoke(service, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ready.getBoolean(screen(activity)))
            IntercomService::class.java.getDeclaredMethod("onAudioInterruptionChanged", AudioInterruptionState::class.java)
                .apply { isAccessible = true }.invoke(service, AudioInterruptionState.PHONE_RINGING)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ready.getBoolean(screen(activity)))
            IntercomService::class.java.getDeclaredMethod("onAudioInterruptionChanged", AudioInterruptionState::class.java)
                .apply { isAccessible = true }.invoke(service, AudioInterruptionState.NORMAL)
            publish.invoke(service, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ready.getBoolean(screen(activity)))
            connection.onServiceDisconnected(ComponentName(activity, IntercomService::class.java))
            assertFalse(ready.getBoolean(screen(activity)))
            activity.onAudioReadyChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ready.getBoolean(screen(activity)))
        } finally {
            controller.destroy()
            val scope = admissionField(service, "serviceScope").get(service) as CoroutineScope
            val stopped = CountDownLatch(1)
            scope.coroutineContext[Job]?.invokeOnCompletion { stopped.countDown() } ?: stopped.countDown()
            serviceController.destroy()
            assertTrue("Service scope did not stop", stopped.await(5L, TimeUnit.SECONDS))
            val databaseField = PairingDatabase::class.java.getDeclaredField("instance").apply {
                isAccessible = true
            }
            (databaseField.get(null) as? PairingDatabase)?.close()
            databaseField.set(null, null)
        }
    }

    private fun showIncomingConfirmation(
        activity: MainActivity,
        prompt: IncomingConfirmationPrompt
    ) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "showIncomingConfirmation",
            IncomingConfirmationPrompt::class.java
        )
        method.isAccessible = true
        method.invoke(activity, prompt)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setPrivateBoolean(activity: MainActivity, fieldName: String, value: Boolean) {
        MainActivity::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            setBoolean(activity, value)
        }
    }

    private fun incomingPrompt(
        nonce: String,
        riderName: String
    ): IncomingConfirmationPrompt = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("incoming-runtime-$nonce"),
        attemptId = ConnectionAttemptId("incoming-attempt-$nonce"),
        channelId = ControlChannelId.create(),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "device-$nonce",
            nickname = riderName,
            deviceName = "Test Device",
            runtimeSessionId = RuntimeSessionId("peer-runtime-$nonce"),
            isDeviceIdVerified = false
        ),
        decisionDeadlineElapsedMs = 60_000L,
        surface = ConfirmationSurface.IN_APP
    )

    private fun stalePresence(): List<RiderPresence> = listOf(
        RiderPresence(
            deviceId = "stale-device",
            sessionId = RuntimeSessionId("stale-session"),
            nickname = "Stale Rider",
            deviceName = "Stale Phone",
            protocolVersion = 2,
            lastSeenElapsedRealtimeMs = 1L,
            candidates = listOf(
                PresenceTransportCandidate(
                    transport = Transport.LAN,
                    endpointId = "stale-endpoint",
                    address = "127.0.0.1",
                    port = 1234,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = true
                )
            ),
            pairing = null
        )
    )

    private fun withPresenceAdmissionFixture(
        manualFactory: (ConnectionAttemptId) -> ConnectionAttemptId,
        runtime: RuntimeSessionId = RuntimeSessionId("presence-admission-runtime"),
        block: (IntercomService, SessionOrchestrator, MainActivity, RiderPresence, ConnectionAttemptId) -> Unit
    ) {
        val databaseField = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        var ownership: Any? = null
        try {
            val actor = admissionField(service, "orchestrator").get(service) as SessionOrchestrator
            val control = requireNotNull(admissionField(actor, "signalingControl").get(actor))
            val spent = ConnectionAttemptId("presence-admission-spent")
            val remote = RuntimeSessionId("presence-admission-remote")
            var generated = 0
            val factory: () -> ConnectionAttemptId = {
                if (generated++ == 0) spent else manualFactory(spent)
            }
            admissionField(control, "attemptIdFactory").set(control, factory)
            runBlocking {
                withTimeout(5_000L) {
                    assertTrue(actor.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime)))
                    assertTrue(actor.dispatchAndAwait(SessionEvent.ConnectPresenceRequested(
                        runtime, "admission-device", remote, setOf(Transport.LAN)
                    )))
                    assertTrue(actor.dispatchAndAwait(SessionEvent.TargetedTransportOpenFailed(
                        runtime, spent, Transport.LAN, "seed terminal attempt"
                    )))
                }
            }
            assertEquals(ConnectionAttemptTerminalOutcome.FAILED, actor.terminalOutcome(spent))
            assertEquals(IntercomState.Discovering(runtime), actor.state.value)
            shadowOf(Looper.getMainLooper()).idle()
            val aggregator = admissionField(service, "presenceAggregator").get(service) as PresenceAggregator
            val presence = aggregator.replaceCandidates(Transport.LAN, listOf(DiscoveryCandidate(
                Transport.LAN, "admission-endpoint", "127.0.0.1", 1234,
                DiscoveryIdentityClaim("admission-device", remote, "Admission Rider", "Test Phone", 2)
            ))).presences.single()
            val sessions = admissionField(service, "sessions").get(service) as SessionGeneration
            ownership = requireNotNull(LegacyRuntimeOwnership.acquire())
            admissionField(service, "legacyOwnership").set(service, ownership)
            admissionField(service, "activeSession").set(service, sessions.start())
            admissionField(service, "activeRuntimeSessionId").set(service, runtime.value)
            admissionField(service, "running").set(service, true)
            val activityController = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = activityController.get()
            try {
                bindAdmissionService(activity, service)
                invokeShowPage(screen(activity), MainRoute.DISCOVER)
                shadowOf(Looper.getMainLooper()).idle()
                block(service, actor, activity, presence, spent)
            } finally {
                stopAdmissionActivity(activity)
                activityController.destroy()
            }
        } finally {
            val scope = admissionField(service, "serviceScope").get(service) as CoroutineScope
            val stopped = CountDownLatch(1)
            scope.coroutineContext[Job]?.invokeOnCompletion { stopped.countDown() } ?: stopped.countDown()
            try {
                controller.destroy()
                assertTrue("Service scope did not stop", stopped.await(5L, TimeUnit.SECONDS))
            } finally {
                ownership?.let(LegacyRuntimeOwnership::release)
                (databaseField.get(null) as? PairingDatabase)?.close()
                databaseField.set(null, null)
            }
        }
    }

    private fun bindAdmissionService(activity: MainActivity, service: IntercomService) {
        setPrivateBoolean(activity, "bindingRegistered", true)
        val connection = admissionField(activity, "serviceConnection").get(activity) as ServiceConnection
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(activity, IntercomService::class.java), service.onBind(Intent())
        )
        assertTrue(activity.bindService(Intent(activity, IntercomService::class.java), connection, android.content.Context.BIND_AUTO_CREATE))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stopAdmissionActivity(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(activity)
    }

    private fun clickAdmissionPresence(activity: MainActivity, presence: RiderPresence) {
        MainScreen::class.java.getDeclaredMethod("connectFromDiscover", RiderPresence::class.java).apply {
            isAccessible = true
        }.invoke(screen(activity), presence)
    }

    private fun pendingAdmissionRequest(activity: MainActivity): PresenceConnectRequest =
        admissionField(screen(activity), "pendingConnectRequest").get(screen(activity)) as PresenceConnectRequest

    private fun discoverAdmissionState(activity: MainActivity): DiscoverScreenUiState =
        stateValue(screen(activity), "discoverUiState") as DiscoverScreenUiState

    private fun awaitAdmissionActor(actor: SessionOrchestrator, deliverOnMain: Boolean = true) {
        runBlocking {
            withTimeout(5_000L) { assertTrue(actor.dispatchAndAwait(SessionEvent.AutomaticReconnectChanged(true))) }
        }
        if (deliverOnMain) shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitAdmissionGate(gate: CountDownLatch) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (gate.await(10L, TimeUnit.MILLISECONDS)) return
        }
        error("Presence actor did not reach its gate")
    }

    private fun abortAdmissionResources(service: IntercomService, runtime: RuntimeSessionId): Boolean {
        val method = IntercomService::class.java.declaredMethods.single {
            it.name.startsWith("abortResourcesAndResumeDiscovery") && it.parameterCount == 6
        }.apply { isAccessible = true }
        return method.invoke(service, runtime.value, null, 0L, null, "Refreshing discovery", null) as Boolean
    }

    private fun publishAdmissionSnapshot(service: IntercomService, snapshot: PresenceSnapshot) {
        IntercomService::class.java.getDeclaredMethod("publishPresenceSnapshot", PresenceSnapshot::class.java)
            .apply { isAccessible = true }.invoke(service, snapshot)
    }

    private fun recordAdmissionEffects(actor: SessionOrchestrator): List<SessionEffect> {
        val observed = java.util.concurrent.CopyOnWriteArrayList<SessionEffect>()
        @Suppress("UNCHECKED_CAST")
        val channel = admissionField(actor, "effectChannel").get(actor) as kotlinx.coroutines.channels.Channel<SessionEffect>
        val recording = object : kotlinx.coroutines.channels.Channel<SessionEffect> by channel {
            override suspend fun send(element: SessionEffect) {
                observed += element
                channel.send(element)
            }
        }
        admissionField(actor, "effectChannel").set(actor, recording)
        return observed
    }

    private fun recordAdmissionEvents(actor: SessionOrchestrator): List<SessionEvent> {
        val observed = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
        @Suppress("UNCHECKED_CAST")
        val channel = admissionField(actor, "events").get(actor) as kotlinx.coroutines.channels.Channel<Any>
        val recording = object : kotlinx.coroutines.channels.Channel<Any> by channel {
            override fun trySend(element: Any): kotlinx.coroutines.channels.ChannelResult<Unit> {
                val result = channel.trySend(element)
                if (result.isSuccess) observed += admissionField(element, "event").get(element) as SessionEvent
                return result
            }
        }
        admissionField(actor, "events").set(actor, recording)
        return observed
    }

    private fun deliverAdmissionEffect(service: IntercomService, effect: SessionEffect) {
        IntercomService::class.java.getDeclaredMethod("handleSessionEffect", SessionEffect::class.java)
            .apply { isAccessible = true }.invoke(service, effect)
    }

    private fun admissionField(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun pairedPresence(): RiderPresence = RiderPresence(
        deviceId = "paired-device",
        sessionId = RuntimeSessionId("paired-session"),
        nickname = "Paired Rider",
        deviceName = "Paired Phone",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = Transport.LAN,
                endpointId = "paired-endpoint",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = PairingRecord(
            remoteDeviceId = "paired-device",
            remoteNickname = "Paired Rider",
            deviceName = "Paired Phone",
            localAlias = "Paired Rider",
            shortCode = "1234",
            pairedAt = 1L,
            lastConnectedAt = 2L,
            isPreferred = false,
            lastTransport = "LAN",
            failureCount = 0
        )
    )
}
