package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class ConfirmationBudgetRegressionTest {
    @Test fun fallbackAndDeadlineShareOneCreationTimestamp() {
        var tick = 100L
        val clock = object : MonotonicClock {
            override fun now() = MonotonicTimestamp(tick++)
        }
        val coordinator = SignalingControlCoordinator(clock, 10_000L)
        val runtime = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        val decision = coordinator.handle(IntercomState.Discovering(runtime),
            SessionEvent.ConnectPresenceRequested(runtime, "b0000000-0000-4000-8000-000000000002",
                RuntimeSessionId("10000000-0000-4000-8000-000000000002"),
                setOf(Transport.LAN, Transport.WIFI_DIRECT), ConnectionTrigger.USER))!!
        val milestone = decision.effects.filterIsInstance<SessionEffect.ScheduleAttemptMilestone>().single().milestone
        assertEquals(coordinator.currentAttempt!!.deadlineElapsedRealtimeMs - 35_000L + 5_000L,
            milestone.scheduledAt.elapsedRealtimeMs)
    }

    @Test fun userBudgetReservesDecisionAndMediaAfterDelayedRequestDelivery() {
        val h = PairTiming(deliveryDelayMs = 9_000L)
        assertEquals(35_100L, h.attempt.deadlineElapsedRealtimeMs)
        assertEquals(24_100L, h.prompt.decisionDeadlineElapsedMs)
        assertTrue(h.attempt.deadlineElapsedRealtimeMs >= h.prompt.decisionDeadlineElapsedMs + 10_000L)
    }

    @Test fun acceptanceAfterTwelveSecondsStartsMediaWithoutRenewingRequesterDeadline() {
        val h = PairTiming(deliveryDelayMs = 9_000L)
        h.clock.advanceBy(12_000L)
        val accepted = h.b.handle(h.stateB, SessionEvent.IncomingAccepted(h.rb, h.attempt.id,
            h.cb.channelId, h.prompt.actionNonce, h.clock.now().elapsedRealtimeMs))!!
        val selection = accepted.effects.filterIsInstance<SessionEffect.SelectMediaChannel>().single()
        val selected = h.b.handle(requireNotNull(accepted.state), SessionEvent.MediaChannelSelected(
            h.rb, h.attempt.id, h.cb.wireRequestKey, h.cb.channelId, selection.cohort))!!
        assertEquals(1, selected.effects.count { it is SessionEffect.SendConnectAccept })
        val remote = h.a.handle(h.stateA, SessionEvent.RemoteConnectAccepted(h.ra, h.attempt.id,
            h.ca.channelId, h.ca.wireRequestKey))!!
        assertEquals(1, remote.effects.count { it is SessionEffect.StartWebRtc })
        assertEquals(h.attempt, h.a.currentAttempt)
        assertEquals(35_100L, h.a.currentAttempt?.deadlineElapsedRealtimeMs)
    }

    @Test fun quickAcceptanceStartsExactlyOneRequesterMediaSession() {
        val h = PairTiming()
        h.clock.advanceBy(5_000L)
        val remote = h.a.handle(h.stateA, SessionEvent.RemoteConnectAccepted(h.ra, h.attempt.id,
            h.ca.channelId, h.ca.wireRequestKey))!!
        assertEquals(1, remote.effects.count { it is SessionEffect.StartWebRtc })
    }

    @Test fun confirmationAtExactDecisionDeadlineIsRejected() {
        val h = PairTiming()
        h.clock.advanceBy(15_000L)
        val decision = h.b.handle(h.stateB, SessionEvent.IncomingAccepted(h.rb, h.attempt.id,
            h.cb.channelId, h.prompt.actionNonce, h.clock.now().elapsedRealtimeMs))!!
        assertFalse(decision.effects.any { it is SessionEffect.SendConnectAccept })
        assertFalse(decision.effects.any { it is SessionEffect.StartWebRtc })
        assertNull(h.b.currentAttempt)
    }

    @Test fun acceptAtRequesterDeadlineCannotStartMedia() {
        val h = PairTiming()
        h.clock.advanceBy(h.attempt.deadlineElapsedRealtimeMs - h.clock.now().elapsedRealtimeMs)
        val decision = h.a.handle(h.stateA, SessionEvent.RemoteConnectAccepted(h.ra, h.attempt.id,
            h.ca.channelId, h.ca.wireRequestKey))!!
        assertFalse(decision.effects.any { it is SessionEffect.StartWebRtc })
        assertFalse(h.a.isAttemptAuthorized(h.attempt))
    }

    @Test fun automaticPairingKeepsOrdinaryTenSecondBudget() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(100L))
        val coordinator = SignalingControlCoordinator(clock, 10_000L)
        val runtime = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        assertTrue(coordinator.handle(IntercomState.Discovering(runtime), SessionEvent.ConnectPresenceRequested(
            runtime, "b0000000-0000-4000-8000-000000000002",
            RuntimeSessionId("10000000-0000-4000-8000-000000000002"), setOf(Transport.LAN),
            ConnectionTrigger.AUTO_PAIRED))!!.accepted)
        assertEquals(10_100L, coordinator.currentAttempt?.deadlineElapsedRealtimeMs)
    }

    private class PairTiming(deliveryDelayMs: Long = 0L) {
        val da = "a0000000-0000-4000-8000-000000000001"
        val db = "b0000000-0000-4000-8000-000000000002"
        val ra = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        val rb = RuntimeSessionId("10000000-0000-4000-8000-000000000002")
        val clock = FakeMonotonicClock(MonotonicTimestamp(100L))
        val a = SignalingControlCoordinator(clock, 10_000L)
        val b = SignalingControlCoordinator(clock, 10_000L)
        val attempt: ConnectionAttempt
        val ca: VerifiedControlChannel
        val cb: VerifiedControlChannel
        val prompt: IncomingConfirmationPrompt
        val stateA: IntercomState
        val stateB: IntercomState
        init {
            val started = a.handle(IntercomState.Discovering(ra), SessionEvent.ConnectPresenceRequested(
                ra, db, rb, setOf(Transport.LAN), ConnectionTrigger.USER))!!
            attempt = requireNotNull(a.currentAttempt)
            val key = WireRequestKey(DeviceId.parse(da), ra, attempt.id, DeviceId.parse(db))
            ca = VerifiedControlChannel(ControlChannelId.create(), Transport.LAN, RequestRole.REQUESTER,
                key, TargetLock(db, rb), PeerIdentity(db, "B", "Phone B", rb, true), attempt)
            cb = VerifiedControlChannel(ControlChannelId.create(), Transport.LAN, RequestRole.RESPONDER,
                key, TargetLock(da, ra), PeerIdentity(da, "A", "Phone A", ra, true), null)
            val verified = a.handle(requireNotNull(started.state), SessionEvent.ControlChannelVerified(ra, ca))!!
            stateA = verified.state ?: requireNotNull(started.state)
            assertTrue(verified.effects.any { it is SessionEffect.SendConnectRequest })
            val discoveringB = IntercomState.Discovering(rb)
            assertTrue(b.handle(discoveringB, SessionEvent.ControlChannelVerified(rb, cb))!!.accepted)
            clock.advanceBy(deliveryDelayMs)
            val inbound = b.handle(discoveringB, SessionEvent.IncomingConnectRequest(rb, cb.channelId,
                key, RequestTrigger.USER, Transport.LAN, clock.now().elapsedRealtimeMs),
                IncomingRequestPolicy(false, ConfirmationAvailability(true, true)))!!
            stateB = requireNotNull(inbound.state)
            prompt = inbound.effects.filterIsInstance<SessionEffect.PublishIncomingConfirmation>().single().prompt
        }
    }
}
