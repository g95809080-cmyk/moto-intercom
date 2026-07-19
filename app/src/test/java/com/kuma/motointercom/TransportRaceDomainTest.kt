package com.kuma.motointercom

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRaceDomainTest {
    @Test
    fun channelPlanPreservesPreferredFallbackOrderAndImmutableSnapshot() {
        val plan = ChannelPlan.race(
            preferredTransport = Transport.LAN,
            fallbackTransport = Transport.WIFI_DIRECT
        )

        assertEquals(Transport.LAN, plan.preferredTransport)
        assertEquals(Transport.WIFI_DIRECT, plan.fallbackTransport)
        assertEquals(
            linkedSetOf(Transport.LAN, Transport.WIFI_DIRECT),
            plan.plannedTransports
        )
        assertTrue(Transport.LAN in plan)
        assertTrue(Transport.WIFI_DIRECT in plan)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.plannedTransports as MutableSet<Transport>).clear()
        }
        assertEquals(
            linkedSetOf(Transport.LAN, Transport.WIFI_DIRECT),
            plan.plannedTransports
        )
    }

    @Test
    fun singlePlanHasNoFallbackAndRaceRejectsDuplicateTransport() {
        val single = ChannelPlan.single(Transport.WIFI_DIRECT)

        assertEquals(Transport.WIFI_DIRECT, single.preferredTransport)
        assertNull(single.fallbackTransport)
        assertEquals(setOf(Transport.WIFI_DIRECT), single.plannedTransports)
        assertThrows(IllegalArgumentException::class.java) {
            ChannelPlan.race(Transport.LAN, Transport.LAN)
        }
    }

    @Test
    fun plannedDiscoveryIncludesBothRaceTransports() {
        val attempt = attempt(
            id = "race",
            plan = ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT)
        )

        assertEquals(
            linkedSetOf(Transport.LAN, Transport.WIFI_DIRECT),
            plannedDiscoveryTransports(attempt)
        )
    }

    @Test
    fun sequentialFallbackClassifierRequiresBusyLanPreferredP2pFallback() {
        val eligible = attempt(
            id = "eligible",
            plan = ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT)
        )
        val reversed = attempt(
            id = "reversed",
            plan = ChannelPlan.race(Transport.WIFI_DIRECT, Transport.LAN)
        )
        val single = attempt(
            id = "single",
            plan = ChannelPlan.single(Transport.WIFI_DIRECT)
        )

        assertTrue(shouldReportSequentialFallback(busy = true, eligible))
        assertFalse(shouldReportSequentialFallback(busy = false, eligible))
        assertFalse(shouldReportSequentialFallback(busy = true, reversed))
        assertFalse(shouldReportSequentialFallback(busy = true, single))
        assertFalse(shouldReportSequentialFallback(busy = true, attempt = null))
    }

    @Test
    fun retirePlannedTransportRoutesOnlyTheExactPlannedTransport() {
        val attempt = attempt(
            id = "retire",
            plan = ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT)
        )
        val retired = mutableListOf<Pair<Transport, ConnectionAttempt>>()

        assertTrue(
            retirePlannedTransport(
                attempt,
                Transport.LAN,
                retireLan = { retired += Transport.LAN to it },
                retireWifiDirect = { retired += Transport.WIFI_DIRECT to it }
            )
        )
        assertEquals(listOf(Transport.LAN to attempt), retired)

        retired.clear()
        assertTrue(
            retirePlannedTransport(
                attempt,
                Transport.WIFI_DIRECT,
                retireLan = { retired += Transport.LAN to it },
                retireWifiDirect = { retired += Transport.WIFI_DIRECT to it }
            )
        )
        assertEquals(listOf(Transport.WIFI_DIRECT to attempt), retired)

        retired.clear()
        val single = attempt.copy(channelPlan = ChannelPlan.single(Transport.LAN))
        assertFalse(
            retirePlannedTransport(
                single,
                Transport.WIFI_DIRECT,
                retireLan = { retired += Transport.LAN to it },
                retireWifiDirect = { retired += Transport.WIFI_DIRECT to it }
            )
        )
        assertTrue(retired.isEmpty())
    }

    @Test
    fun schedulerKeepsFallbackAndOptimizationMilestonesConcurrent() {
        var now = 1_000L
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val elapsed = mutableListOf<AttemptMilestone>()
        val scheduler = AttemptMilestoneScheduler(
            elapsedRealtime = { now },
            postDelayed = { callback, delay -> posted += callback to delay },
            removeCallbacks = {},
            onElapsed = elapsed::add
        )
        val attempt = attempt("race")
        val fallback = AttemptMilestone.FallbackTransport(
            attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(6_000L)
        )
        val optimization = AttemptMilestone.MediaOptimization(
            attempt,
            wireKey(attempt),
            MonotonicTimestamp(7_000L)
        )

        scheduler.schedule(fallback)
        scheduler.schedule(optimization)

        assertEquals(listOf(5_000L, 6_000L), posted.map { it.second })
        posted[0].first.run()
        posted[1].first.run()
        assertEquals(listOf(fallback, optimization), elapsed)
    }

    @Test
    fun replacementAndCancellationInvalidateQueuedMilestones() {
        val posted = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        val elapsed = mutableListOf<AttemptMilestone>()
        val scheduler = AttemptMilestoneScheduler(
            elapsedRealtime = { 1_000L },
            postDelayed = { callback, _ -> posted += callback },
            removeCallbacks = removed::add,
            onElapsed = elapsed::add
        )
        val attemptA = attempt("attempt-a")
        val attemptB = attempt("attempt-b")
        val first = AttemptMilestone.FallbackTransport(
            attemptA,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(6_000L)
        )
        val replacement = AttemptMilestone.FallbackTransport(
            attemptB,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(6_000L)
        )
        val optimization = AttemptMilestone.MediaOptimization(
            attemptB,
            wireKey(attemptB),
            MonotonicTimestamp(7_000L)
        )

        scheduler.schedule(first)
        scheduler.schedule(replacement)
        scheduler.schedule(optimization)
        scheduler.cancel(attemptB)

        assertEquals(3, removed.size)
        posted.forEach(Runnable::run)
        assertTrue(elapsed.isEmpty())
    }

    @Test
    fun duplicateMilestoneDoesNotMoveExactTime() {
        var now = 1_000L
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val removed = mutableListOf<Runnable>()
        val scheduler = AttemptMilestoneScheduler(
            elapsedRealtime = { now },
            postDelayed = { callback, delay -> posted += callback to delay },
            removeCallbacks = removed::add,
            onElapsed = {}
        )
        val attempt = attempt("race")
        val milestone = AttemptMilestone.FallbackTransport(
            attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(6_000L)
        )

        scheduler.schedule(milestone)
        now = 2_000L
        scheduler.schedule(milestone)

        assertEquals(listOf(5_000L), posted.map { it.second })
        assertTrue(removed.isEmpty())
    }

    @Test
    fun runtimeCancellationDoesNotCancelAnotherRuntime() {
        val posted = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        val elapsed = mutableListOf<AttemptMilestone>()
        val scheduler = AttemptMilestoneScheduler(
            elapsedRealtime = { 1_000L },
            postDelayed = { callback, _ -> posted += callback },
            removeCallbacks = removed::add,
            onElapsed = elapsed::add
        )
        val current = attempt("current")
        val otherRuntime = RuntimeSessionId(UUID.nameUUIDFromBytes("other-runtime".toByteArray()).toString())
        val other = attempt("other").copy(runtimeSessionId = otherRuntime)
        val currentMilestone = AttemptMilestone.FallbackTransport(
            current,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(6_000L)
        )
        val otherMilestone = AttemptMilestone.MediaOptimization(
            other,
            wireKey(other),
            MonotonicTimestamp(7_000L)
        )

        scheduler.schedule(currentMilestone)
        scheduler.schedule(otherMilestone)
        scheduler.cancelRuntime(otherRuntime)
        posted.forEach(Runnable::run)

        assertEquals(listOf(currentMilestone), elapsed)
        assertFalse(otherMilestone in elapsed)
        assertEquals(1, removed.size)
    }

    private fun attempt(
        id: String,
        plan: ChannelPlan = ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT)
    ): ConnectionAttempt = ConnectionAttempt(
        id = ConnectionAttemptId(UUID.nameUUIDFromBytes(id.toByteArray()).toString()),
        runtimeSessionId = RuntimeSessionId(
            UUID.nameUUIDFromBytes("runtime-current".toByteArray()).toString()
        ),
        targetLock = TargetLock(
            UUID.nameUUIDFromBytes("peer-b".toByteArray()).toString(),
            RuntimeSessionId(UUID.nameUUIDFromBytes("peer-runtime".toByteArray()).toString())
        ),
        trigger = ConnectionTrigger.USER,
        channelPlan = plan,
        deadlineElapsedRealtimeMs = 11_000L
    )

    private fun wireKey(attempt: ConnectionAttempt): WireRequestKey = WireRequestKey(
        requesterDeviceId = DeviceId.parse(
            UUID.nameUUIDFromBytes("local-device".toByteArray()).toString()
        ),
        requesterSessionId = attempt.runtimeSessionId,
        responderDeviceId = DeviceId.parse(attempt.targetDeviceId),
        attemptId = attempt.id
    )
}
