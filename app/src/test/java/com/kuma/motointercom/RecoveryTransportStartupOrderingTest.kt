package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTransportStartupOrderingTest {
    @Test
    fun wifiPreferredRecoveryWaitsForAsyncWifiStartupReadiness() {
        val fixture = fixture(Transport.WIFI_DIRECT)
        val effects = mutableListOf<SessionEffect>()
        val startup = startupRouter(fixture, effects)
        var cleanupComplete = false

        val adapters = startAdapters(fixture, startup) { onReady ->
            { attempt ->
                check(cleanupComplete)
                onReady(attempt)
            }
        }

        assertTrue(adapters.wifiInstalledBeforeStart)
        assertTrue(adapters.lanInstalledBeforeStart)
        assertTrue(effects.isEmpty())

        assertFalse(
            adapters.wifi.readiness.reportServiceDiscoveryReady(null)
        )
        cleanupComplete = true
        assertTrue(
            adapters.wifi.readiness.reportServiceDiscoveryReady(
                fixture.recovering.attempt
            )
        )
        assertFalse(
            adapters.wifi.readiness.reportServiceDiscoveryReady(
                fixture.recovering.attempt
            )
        )

        assertEquals(
            listOf(
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.WIFI_DIRECT
                )
            ),
            effects
        )
    }

    @Test
    fun lanPreferredRecoveryDoesNotWaitForWifiAndDueFallbackWaitsUntilWifiReady() {
        val fixture = fixture(Transport.LAN)
        val effects = mutableListOf<SessionEffect>()
        val startup = startupRouter(fixture, effects)

        val adapters = startAdapters(fixture, startup)

        assertEquals(
            listOf(
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.LAN
                )
            ),
            effects
        )

        fixture.clock.advanceBy(3_000L)
        val due = requireNotNull(
            fixture.coordinator.handle(
                fixture.recovering,
                SessionEvent.AttemptMilestoneElapsed(fixture.milestone)
            )
        )
        assertTrue(due.accepted)
        assertTrue(due.effects.isEmpty())

        assertTrue(adapters.wifiInstalledBeforeStart)
        assertTrue(adapters.lanInstalledBeforeStart)
        assertTrue(
            adapters.wifi.readiness.reportServiceDiscoveryReady(
                fixture.recovering.attempt
            )
        )

        assertEquals(
            listOf(
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.LAN
                ),
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.WIFI_DIRECT
                )
            ),
            effects
        )
    }

    @Test
    fun wifiReadinessAtExactT3AlsoOpensTheAlreadyReadyFallbackOnce() {
        val fixture = fixture(Transport.WIFI_DIRECT)
        val effects = mutableListOf<SessionEffect>()
        val startup = startupRouter(fixture, effects)

        val adapters = startAdapters(fixture, startup)
        fixture.clock.advanceBy(3_000L)
        assertTrue(
            adapters.wifi.readiness.reportServiceDiscoveryReady(
                fixture.recovering.attempt
            )
        )

        assertEquals(
            listOf(
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.WIFI_DIRECT
                ),
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.LAN
                )
            ),
            effects
        )
        assertFalse(
            requireNotNull(
                fixture.coordinator.handle(
                    fixture.recovering,
                    SessionEvent.AttemptMilestoneElapsed(fixture.milestone)
                )
            ).accepted
        )
    }

    private fun startupRouter(
        fixture: Fixture,
        effects: MutableList<SessionEffect>
    ) = RecoveryTransportStartup(fixture.recovering.attempt) { event ->
        val decision = requireNotNull(
            fixture.coordinator.handle(fixture.recovering, event)
        )
        assertTrue(decision.accepted)
        effects += decision.effects
    }

    private fun startAdapters(
        fixture: Fixture,
        startup: RecoveryTransportStartup,
        wrapWifiReady: ((ConnectionAttempt) -> Unit) -> ((ConnectionAttempt) -> Unit) = { it }
    ): StartedAdapters {
        var installedWifi: FakeWifiAdapter? = null
        var installedLan: FakeLanAdapter? = null
        var wifiInstalledBeforeStart = false
        var lanInstalledBeforeStart = false

        startup.start(
            plannedTransports = setOf(Transport.LAN, Transport.WIFI_DIRECT),
            createWifiDirect = { onReady ->
                FakeWifiAdapter(
                    WifiDirectStartupReadiness(
                        startupAttempt = fixture.recovering.attempt,
                        clock = fixture.clock,
                        onReady = wrapWifiReady(onReady)
                    )
                )
            },
            installWifiDirect = { installedWifi = it },
            startWifiDirect = { wifiInstalledBeforeStart = installedWifi === it },
            createLan = ::FakeLanAdapter,
            installLan = { installedLan = it },
            startLan = {
                lanInstalledBeforeStart = installedLan === it
                true
            }
        )

        return StartedAdapters(
            wifi = requireNotNull(installedWifi),
            wifiInstalledBeforeStart = wifiInstalledBeforeStart,
            lanInstalledBeforeStart = lanInstalledBeforeStart
        )
    }

    private fun fixture(lastTransport: Transport): Fixture {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val attemptIds = ArrayDeque(
            listOf(
                ConnectionAttemptId("attempt-outbound"),
                ConnectionAttemptId("attempt-recovery")
            )
        )
        val coordinator = SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = attemptIds::removeFirst
        )
        val connecting = requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(RUNTIME),
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = RUNTIME,
                    targetDeviceId = TARGET_DEVICE,
                    targetSessionId = TARGET_SESSION,
                    availableTransports = setOf(Transport.LAN, Transport.WIFI_DIRECT)
                )
            )
        ).state as IntercomState.Connecting
        val recoveryDecision = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    attempt = connecting.attempt,
                    peer = PeerIdentity(
                        deviceId = TARGET_DEVICE,
                        nickname = "Rider B",
                        runtimeSessionId = TARGET_SESSION,
                        isDeviceIdVerified = true
                    ),
                    connectedAt = 1L,
                    transport = lastTransport
                ),
                SessionEvent.SignalingDisconnected(RUNTIME, connecting.attempt.id)
            )
        )
        val recovering = recoveryDecision.state as IntercomState.Recovering
        val milestone = (
            recoveryDecision.effects.single { it is SessionEffect.ScheduleAttemptMilestone }
                as SessionEffect.ScheduleAttemptMilestone
            ).milestone as AttemptMilestone.FallbackTransport
        assertEquals(MonotonicTimestamp(3_500L), milestone.scheduledAt)
        return Fixture(clock, coordinator, recovering, milestone)
    }

    private data class Fixture(
        val clock: FakeMonotonicClock,
        val coordinator: SignalingControlCoordinator,
        val recovering: IntercomState.Recovering,
        val milestone: AttemptMilestone.FallbackTransport
    )

    private data class FakeWifiAdapter(
        val readiness: WifiDirectStartupReadiness
    )

    private class FakeLanAdapter

    private data class StartedAdapters(
        val wifi: FakeWifiAdapter,
        val wifiInstalledBeforeStart: Boolean,
        val lanInstalledBeforeStart: Boolean
    )

    private companion object {
        val RUNTIME = RuntimeSessionId("runtime-current")
        val TARGET_SESSION = RuntimeSessionId("runtime-target")
        const val TARGET_DEVICE = "device-target"
    }
}
