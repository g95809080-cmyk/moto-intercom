package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCleanupCoordinatorTest {
    @Test
    fun completedCleanupReschedulesForTheLatestRequest() {
        val tasks = TaskQueue()
        val restarted = mutableListOf<RecoveryCleanupRequest>()
        val coordinator = RecoveryCleanupCoordinator(
            postDelayed = tasks::post,
            removeCallbacks = tasks::remove,
            restart = {
                restarted += it
                true
            }
        )
        val first = request("attempt-a", delayMillis = 0L)
        val second = request("attempt-b", delayMillis = 1_500L)

        val token = coordinator.start(first)
        coordinator.complete(token)
        val staleCallback = tasks.single().callback
        assertTrue(coordinator.updateIfActive(second))

        staleCallback.run()
        assertTrue(restarted.isEmpty())
        assertEquals(1_500L, tasks.single().delayMillis)
        tasks.runNext()
        assertEquals(listOf(second), restarted)
    }

    @Test
    fun delayedCleanupReconcilesThreeDeadlinesAndCompletesExactReset() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val ids = ArrayDeque(
            listOf("outbound", "recovery-1", "recovery-2", "recovery-3")
                .map(::ConnectionAttemptId)
        )
        val signaling = SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = ids::removeFirst
        )
        val runtime = RuntimeSessionId("runtime-current")
        val remoteRuntime = RuntimeSessionId("runtime-remote")
        val connecting = requireNotNull(
            signaling.handle(
                IntercomState.Discovering(runtime),
                SessionEvent.ConnectPresenceRequested(
                    runtime,
                    "peer-b",
                    remoteRuntime,
                    setOf(Transport.LAN)
                )
            )
        ).state as IntercomState.Connecting
        var state: IntercomState = IntercomState.Connected(
            attempt = connecting.attempt,
            peer = PeerIdentity(
                deviceId = "peer-b",
                nickname = "Rider B",
                runtimeSessionId = remoteRuntime,
                isDeviceIdVerified = true
            ),
            connectedAt = 1L,
            transport = Transport.LAN
        )
        val deadlineTasks = TaskQueue()
        val restartTasks = TaskQueue()
        val closeOrder = mutableListOf<String>()
        val adapterOrder = mutableListOf<String>()
        val restartRequests = mutableListOf<RecoveryCleanupRequest>()
        var wifiCloseCompletion: (() -> Unit)? = null
        lateinit var processEffects: (List<SessionEffect>) -> Unit
        lateinit var cleanup: RecoveryCleanupCoordinator
        lateinit var deadlineScheduler: AttemptDeadlineScheduler

        fun apply(event: SessionEvent): SignalingControlDecision {
            val previous = state
            val decision = requireNotNull(signaling.handle(previous, event))
            assertTrue(decision.accepted)
            state = decision.state ?: previous
            signaling.onProductTransition(previous, state, event)
            processEffects(decision.effects)
            return decision
        }

        cleanup = RecoveryCleanupCoordinator(
            postDelayed = restartTasks::post,
            removeCallbacks = restartTasks::remove,
            restart = { request ->
                if (
                    !canRestartRecoveryAttempt(
                        request.nextAttempt,
                        signaling.currentAttempt,
                        clock.now()
                    )
                ) {
                    return@RecoveryCleanupCoordinator false
                }
                restartRequests += request
                val startup = RecoveryTransportStartup(request.nextAttempt) { event -> apply(event) }
                startup.start(
                    plannedTransports = plannedDiscoveryTransports(request.nextAttempt),
                    createWifiDirect = {
                        adapterOrder += "wifi-create"
                        "wifi"
                    },
                    installWifiDirect = { adapterOrder += "wifi-install:$it" },
                    startWifiDirect = { adapterOrder += "wifi-start:$it" },
                    createLan = {
                        adapterOrder += "lan-create"
                        "lan"
                    },
                    installLan = { adapterOrder += "lan-install:$it" },
                    startLan = {
                        adapterOrder += "lan-start:$it"
                        true
                    }
                )
                request.resetEffect?.let { effect ->
                    assertTrue(
                        canExecuteResetWirelessEnvironmentEffect(
                            effect,
                            state,
                            signaling.currentAttempt,
                            signaling.activeAttempt,
                            signaling.pendingInboundRequest
                        )
                    )
                    val event = SessionEvent.ResetCompleted(
                        effect.runtimeSessionId,
                        effect.failedAttemptId
                    )
                    val previous = state
                    state = requireNotNull(reduceIntercomState(previous, event)).state
                    signaling.onProductTransition(previous, state, event)
                }
                true
            }
        )

        fun submit(request: RecoveryCleanupRequest) {
            if (cleanup.updateIfActive(request)) return
            val token = cleanup.start(request)
            AttemptResourceController(
                runtimeSessionId = request.runtimeSessionId,
                closeIntercomAndSocket = { closeOrder += "intercom-and-socket" },
                closeLanDiscovery = { closeOrder += "lan" },
                closeWifiDirect = { complete ->
                    closeOrder += "wifi"
                    wifiCloseCompletion = complete
                },
                clearMediaLocator = { closeOrder += "media" },
                clearConnectionState = { closeOrder += "state" },
                resumeDiscovery = { cleanup.complete(token) }
            ).abortAndResumeDiscovery()
        }

        deadlineScheduler = AttemptDeadlineScheduler(
            elapsedRealtime = { clock.now().elapsedRealtimeMs },
            postDelayed = deadlineTasks::post,
            removeCallbacks = deadlineTasks::remove,
            onTimedOut = { attempt ->
                apply(
                    SessionEvent.AttemptTimedOut(
                        attempt.runtimeSessionId,
                        attempt.id,
                        attempt.deadlineElapsedRealtimeMs
                    )
                )
            }
        )

        processEffects = { effects ->
            effects.forEach { effect ->
                when (effect) {
                    is SessionEffect.RestartDiscovery -> submit(
                        RecoveryCleanupRequest(
                            effect.runtimeSessionId,
                            effect.attempt,
                            effect.restartDelayMillis
                        )
                    )
                    is SessionEffect.ScheduleAttemptDeadline ->
                        deadlineScheduler.schedule(effect.attempt)
                    is SessionEffect.ResetWirelessEnvironment -> submit(
                        RecoveryCleanupRequest(
                            effect.runtimeSessionId,
                            nextAttempt = null,
                            restartDelayMillis = 0L,
                            resetEffect = effect
                        )
                    )
                    else -> Unit
                }
            }
        }

        apply(SessionEvent.SignalingDisconnected(runtime, connecting.attempt.id))
        assertEquals(listOf("media", "intercom-and-socket", "lan", "state", "wifi"), closeOrder)
        assertTrue(restartRequests.isEmpty())

        repeat(3) { deadlineTasks.runNext { clock.advanceBy(it) } }
        val resetting = state as IntercomState.Resetting
        assertEquals(3, resetting.consecutiveFinalFailures)
        assertTrue(restartRequests.isEmpty())

        requireNotNull(wifiCloseCompletion).invoke()
        assertEquals(0L, restartTasks.single().delayMillis)
        restartTasks.runNext()

        assertEquals(IntercomState.Discovering(runtime), state)
        assertEquals(1, restartRequests.size)
        assertEquals(resetting.failedAttemptId, restartRequests.single().resetEffect?.failedAttemptId)
        assertEquals(
            listOf(
                "wifi-create",
                "wifi-install:wifi",
                "wifi-start:wifi",
                "lan-create",
                "lan-install:lan",
                "lan-start:lan"
            ),
            adapterOrder
        )
        assertFalse(deadlineTasks.hasTasks())
    }

    private fun request(id: String, delayMillis: Long): RecoveryCleanupRequest =
        RecoveryCleanupRequest(
            runtimeSessionId = RuntimeSessionId("runtime-current"),
            nextAttempt = ConnectionAttemptFixture.create(
                FakeMonotonicClock(MonotonicTimestamp(1L)),
                id = ConnectionAttemptId(id),
                runtimeSessionId = RuntimeSessionId("runtime-current")
            ),
            restartDelayMillis = delayMillis
        )

    private class TaskQueue {
        data class Task(val callback: Runnable, val delayMillis: Long)

        private val tasks = ArrayDeque<Task>()

        fun post(callback: Runnable, delayMillis: Long) {
            tasks += Task(callback, delayMillis)
        }

        fun remove(callback: Runnable) {
            tasks.removeAll { it.callback === callback }
        }

        fun single(): Task = tasks.single()

        fun hasTasks(): Boolean = tasks.isNotEmpty()

        fun runNext(beforeRun: (Long) -> Unit = {}) {
            val task = tasks.removeFirst()
            beforeRun(task.delayMillis)
            task.callback.run()
        }
    }
}
