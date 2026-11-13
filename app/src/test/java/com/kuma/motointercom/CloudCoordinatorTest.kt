package com.kuma.motointercom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CloudCoordinatorTest {
    private val now=1_800_000_000_000L
    @Test fun switchingSnapshotAfterProcessRecreationOnlyOffersManualResume()=runTest {
        val api=Api(now=now);api.active=testRide(now,RideState.CLOUD_ACTIVE).copy(state=RideState.SWITCHING_LOCAL,
            localSwitch=LocalSwitch("switch_test","a".repeat(64),"ARMED",listOf("a","b"),emptyList(),now+8_000))
        val starts=mutableListOf<ApprovedCloudRide>();val co=coordinator(api,Socket(),store(),this,starts=starts)
        try {co.start();runCurrent();assertTrue(starts.isEmpty());assertTrue(co.state.value is CloudIntercomState.ResumeAvailable)} finally {co.close()}
    }
    @Test fun endingACommittedLocalRideStopsItsMediaEvenWithoutACloudJoinedMarker()=runTest {
        val api=Api(now=now);api.active=testRide(now,RideState.CLOUD_PENDING)
        var stops=0;val starts=mutableListOf<ApprovedCloudRide>()
        val co=DefaultCloudSessionCoordinator(api,Socket(),store(),{now-1},{true},{true},{false},{it},
            {starts.add(it);true},{stops++},serverNow={now},wallNow={now},dispatcher=StandardTestDispatcher(testScheduler),hasLocalMedia={true})
        try {
            co.start();runCurrent();co.resumeManually("ride_test")
            api.active=api.active!!.copy(state=RideState.LOCAL_ACTIVE,everLocalActive=true,
                localSwitch=LocalSwitch("switch_test","a".repeat(64),"COMMITTED",listOf("a","b"),listOf("a","b"),now+8_000))
            co.acceptLocalSnapshot(api.active!!);assertTrue(co.state.value is CloudIntercomState.LocalConnected)
            co.cancelCurrent();assertEquals(1,stops);assertEquals(CloudIntercomState.Idle,co.state.value)
        } finally {co.close()}
    }
    @Test fun peerAbsenceClosesMediaWithoutEndingRideOrAutomaticallyRejoining()=runTest {
        val api=Api(now=now);api.active=testRide(now,RideState.CLOUD_PENDING)
        val starts=mutableListOf<ApprovedCloudRide>();val co=coordinator(api,Socket(),store(),this,starts=starts)
        try {
            co.start();runCurrent();co.resumeManually("ride_test");assertEquals(1,starts.size)
            co.mediaStateChanged(CloudIntercomState.ResumeAvailable("ride_test","车友"));runCurrent()
            co.refresh();assertEquals(1,starts.size);assertEquals(0,api.endings)
            assertTrue(co.state.value is CloudIntercomState.ResumeAvailable)
            co.resumeManually("ride_test");assertEquals(2,starts.size)
        } finally {co.close()}
    }
    private class Socket : CloudEventSocket {
        var connections=0
        lateinit var event: (String)->Unit
        override fun connect(token: CloudSocketToken,onOpen: ()->Unit,onEvent: (String)->Unit,onDisconnected: ()->Unit) { connections++; event=onEvent; onOpen() }
        override fun close() {}
    }
    private class Api(override val deviceId: String="a", val now: Long) : CloudControlPlane {
        var active: RideSession?=null
        var invitations=emptyList<CloudInvitation>()
        var starts=0; var snapshots=0; var endings=0
        val replies=mutableListOf<String>()
        var startGate: CompletableDeferred<Unit>?=null
        var acceptGate: CompletableDeferred<Unit>?=null
        var tokenGate: CompletableDeferred<Unit>?=null
        var fallbacks=0
        var failRideAfterConfirmation=false
        var failNextRide=false
        var endGate: CompletableDeferred<Unit>?=null
        var busyWhileActive=false
        var nextRideId="ride_test"
        override suspend fun registerDevice() {}
        override suspend fun registerPair(proof: PairingProof) {}
        override suspend fun revokePair(pairId: String) {}
        override suspend fun snapshot(): CloudSnapshot { snapshots++; return CloudSnapshot(active?.takeUnless { it.state==RideState.ENDED },invitations,emptyList()) }
        override suspend fun startRide(pairId: String,requestId: String): RideSession {
            starts++; startGate?.await()
            if(busyWhileActive && active?.state!=null && active?.state!=RideState.ENDED) throw ControlPlaneException(409,"BUSY")
            return testRide(now).copy(rideId=nextRideId).also { active=it }
        }
        override suspend fun invite(rideId: String): CloudInvitation {
            active=active!!.copy(state=RideState.INVITE_PENDING,inviteId="invite_test",inviteExpiresAt=now+30_000)
            return CloudInvitation("invite_test",rideId,"a","b",now+30_000)
        }
        override suspend fun respond(invite: CloudInvitation,response: String): RideSession {
            replies.add(response); if(response=="ACCEPT") acceptGate?.await()
            return testRide(now,if(response=="ACCEPT") RideState.CLOUD_PENDING else RideState.ENDED).also { active=it; invitations=emptyList() }
        }
        override suspend fun ride(rideId: String): RideSession {
            if(failNextRide) { failNextRide=false; throw ControlPlaneException(0,"NETWORK_ERROR") }
            return active!!
        }
        override suspend fun end(rideId: String) {
            endings++; endGate?.await()
            if(active?.rideId==rideId) active=active?.copy(state=RideState.ENDED)
            invitations=emptyList()
        }
        override suspend fun heartbeat(rideId: String?) = true
        override suspend fun socketToken(): CloudSocketToken { tokenGate?.await(); return CloudSocketToken("x".repeat(43),now+60_000) }
        override suspend fun cloudStatus(rideId: String,connected: Boolean) {}
        override suspend fun extend(rideId: String) = active!!
        override suspend fun localEstablished(rideId: String) {
            active=active!!.copy(state=RideState.LOCAL_ACTIVE,everLocalActive=true)
            if(failRideAfterConfirmation) { failRideAfterConfirmation=false; failNextRide=true }
        }
        override suspend fun fallback(rideId: String): RideSession {
            fallbacks++
            return active!!.copy(state=RideState.CLOUD_PENDING,cloudReason=CloudEntryReason.LOCAL_FAILURE,cloudStartedAt=now,revision=3).also { active=it }
        }
    }
    private fun coordinator(api: Api,socket: Socket,store: CloudSessionStore,scope: TestScope,
        busy: ()->Boolean={false},starts: MutableList<ApprovedCloudRide> = mutableListOf(),
        stops: ()->Unit={}) = DefaultCloudSessionCoordinator(api,socket,store,{now-1},{true},{true},busy,{it},
        { starts.add(it); true },stops,serverNow={now+scope.testScheduler.currentTime},wallNow={now+scope.testScheduler.currentTime},
        dispatcher=StandardTestDispatcher(scope.testScheduler))
    private fun store(local: String="a")=MemoryCloudStore(listOf(CloudPairRecord(if(local=="a")"b" else "a",testPair(now=now),true)))

    @Test fun startCancelledWhileHttpIsInFlightNeverStartsMediaAndEndsLateRide() = runTest {
        val api=Api(now=now); api.startGate=CompletableDeferred()
        val starts=mutableListOf<ApprovedCloudRide>(); val store=store()
        val co=coordinator(api,Socket(),store,this,starts=starts)
        try {
            co.start(); runCurrent()
            val job=launch { co.invite("pair_test") }; runCurrent()
            assertEquals(1,api.starts)
            co.cancelCurrent(); api.startGate!!.complete(Unit); runCurrent(); job.join()
            assertTrue(starts.isEmpty()); assertTrue(api.endings>=1)
            assertFalse(co.state.value.ownsCloudSession); assertNull(co.ride.value)
        } finally { co.close() }
    }
    @Test fun cancelledAcceptancePersistsEndBeforeLateAcceptResponseAndNeverJoins() = runTest {
        val api=Api("b",now); api.acceptGate=CompletableDeferred()
        api.invitations=listOf(CloudInvitation("invite_test","ride_test","a","b",now+30_000))
        val starts=mutableListOf<ApprovedCloudRide>(); val co=coordinator(api,Socket(),store("b"),this,starts=starts)
        try {
            co.start(); runCurrent(); assertNotNull(co.incoming.value)
            val job=launch { co.respond("invite_test",true) }; runCurrent()
            co.cancelCurrent(); assertTrue(api.endings>=1)
            api.acceptGate!!.complete(Unit); runCurrent(); job.join()
            assertTrue(starts.isEmpty()); assertTrue(api.endings>=2)
        } finally { co.close() }
    }
    @Test fun restartExposesManualResumeAndOnlyExplicitActionCanStartMedia() = runTest {
        val api=Api(now=now); api.active=testRide(now,RideState.CLOUD_PENDING)
        val starts=mutableListOf<ApprovedCloudRide>(); val co=coordinator(api,Socket(),store(),this,starts=starts)
        try {
            co.start(); runCurrent()
            assertTrue(co.state.value is CloudIntercomState.ResumeAvailable); assertTrue(starts.isEmpty())
            co.refresh(); assertTrue(starts.isEmpty())
            co.resumeManually("ride_test")
            assertEquals(1,starts.size)
        } finally { co.close() }
    }
    @Test fun busyAndDeclinedInvitationsCannotReachMedia() = runTest {
        for (busy in listOf(true,false)) {
            val api=Api("b",now)
            api.invitations=listOf(CloudInvitation("invite_test","ride_test","a","b",now+30_000))
            val starts=mutableListOf<ApprovedCloudRide>(); val co=coordinator(api,Socket(),store("b"),this,{busy},starts)
            try {
                co.start(); runCurrent()
                if(!busy) co.respond("invite_test",false)
                assertEquals(listOf(if(busy)"BUSY" else "DECLINE"),api.replies)
                assertTrue(starts.isEmpty()); assertNull(co.incoming.value)
            } finally { co.close() }
        }
    }
    @Test fun inviteTimeoutEndsRideAndEqualRevisionDistinctEventsStillRefresh() = runTest {
        val api=Api(now=now); val socket=Socket(); val starts=mutableListOf<ApprovedCloudRide>()
        val co=coordinator(api,socket,store(),this,starts=starts)
        try {
            co.start(); runCurrent(); co.invite("pair_test")
            val before=api.snapshots
            socket.event("""{"eventId":"event_1","rideId":"ride_test","revision":2}"""); runCurrent()
            socket.event("""{"eventId":"event_2","rideId":"ride_test","revision":2}"""); runCurrent()
            socket.event("""{"eventId":"event_2","rideId":"ride_test","revision":2}"""); runCurrent()
            assertEquals(before+2,api.snapshots)
            advanceTimeBy(30_001); runCurrent()
            assertTrue(api.endings>=1); assertTrue(starts.isEmpty())
            assertFalse(co.state.value.ownsCloudSession)
        } finally { co.close() }
    }
    @Test fun remoteEndStopsMediaOnSignedSnapshotEvenWithoutAnActiveRide() = runTest {
        val api=Api(now=now); api.active=testRide(now,RideState.CLOUD_PENDING)
        var stops=0
        val co=coordinator(api,Socket(),store(),this,stops={stops++})
        try {
            co.start(); runCurrent(); co.resumeManually("ride_test")
            api.active=api.active!!.copy(state=RideState.ENDED,revision=4)
            co.refresh()
            assertEquals(1,stops); assertNull(co.ride.value); assertEquals(CloudIntercomState.Idle,co.state.value)
        } finally { co.close() }
    }
    @Test fun cancellationInvalidatesQueuedAcceptanceAndItsPrompt() = runTest {
        val api=Api("b",now)
        api.invitations=listOf(CloudInvitation("invite_test","ride_test","a","b",now+30_000))
        val starts=mutableListOf<ApprovedCloudRide>(); val co=coordinator(api,Socket(),store("b"),this,starts=starts)
        try {
            co.start(); runCurrent(); assertNotNull(co.incoming.value)
            co.cancelCurrent(); co.respond("invite_test",true)
            assertNull(co.incoming.value); assertTrue(api.replies.isEmpty()); assertTrue(starts.isEmpty())
        } finally { co.close() }
    }
    @Test fun aLateSocketTokenCannotReconnectAfterWifiPolicyRevokesIt() = runTest {
        val api=Api(now=now); api.tokenGate=CompletableDeferred()
        val socket=Socket(); var wifi=true
        val co=DefaultCloudSessionCoordinator(api,socket,store(),{now-1},{true},{wifi},{false},{it},
            {error("No media")},{},serverNow={now},dispatcher=StandardTestDispatcher(testScheduler))
        try {
            co.start(); runCurrent(); assertEquals(0,socket.connections)
            wifi=false; co.policyChanged(); api.tokenGate!!.complete(Unit); runCurrent()
            assertFalse(co.online.value); assertEquals(0,socket.connections)
        } finally { co.close() }
    }
    @Test fun inviteTimeoutDoesNotCancelItsOwnDurableEndWhenStorageSuspends() = runTest {
        val api=Api(now=now); val delegate=store()
        val suspendedStore=object : CloudSessionStore by delegate {
            override suspend fun cancelStarts() { delay(1); delegate.cancelStarts() }
            override suspend fun queueEnd(rideId: String) { delay(1); delegate.queueEnd(rideId) }
        }
        val co=coordinator(api,Socket(),suspendedStore,this)
        try {
            co.start(); runCurrent(); co.invite("pair_test")
            advanceTimeBy(30_010); runCurrent()
            assertEquals(1,api.endings); assertNull(co.ride.value)
            assertFalse(co.state.value.ownsCloudSession)
        } finally { co.close() }
    }
    @Test fun onlyAnEstablishedLiveLocalLeaseCanRequestFallback() = runTest {
        val api=Api(now=now); val starts=mutableListOf<ApprovedCloudRide>()
        val co=DefaultCloudSessionCoordinator(api,Socket(),store(),{now-1},{true},{true},{true},{it},
            {starts.add(it);true},{},serverNow={now},wallNow={now},dispatcher=StandardTestDispatcher(testScheduler),
            canReplaceLocal={it.reason==CloudEntryReason.LOCAL_FAILURE})
        try {
            co.start(); runCurrent()
            co.establishedLocalLost("unknown"); assertEquals(0,api.fallbacks)
            co.attachEstablishedLocal("b","lease_test") {true}
            assertEquals(RideState.LOCAL_ACTIVE,co.ride.value!!.state)
            assertEquals(CloudIntercomState.Idle,co.state.value); assertTrue(starts.isEmpty())
            co.establishedLocalLost("lease_test")
            assertEquals(1,api.fallbacks); assertEquals(1,starts.size)
            assertEquals(CloudEntryReason.LOCAL_FAILURE,starts.single().reason)
        } finally { co.close() }
    }
    @Test fun cancelledHandoverCannotStartAfterNativeReleaseFinallyCompletes() = runTest {
        val api=Api(now=now); val released=CompletableDeferred<Unit>(); var joined=0; var requested=0
        lateinit var co: DefaultCloudSessionCoordinator
        co=DefaultCloudSessionCoordinator(api,Socket(),store(),{now-1},{true},{true},{true},{it},
            {approved ->
                requested++; val current=co.mediaAdmissionGuard(approved.rideId)
                released.await()
                if(current()) { joined++;true } else false
            },{},serverNow={now},wallNow={now},dispatcher=StandardTestDispatcher(testScheduler),canReplaceLocal={true})
        try {
            co.start(); runCurrent(); co.attachEstablishedLocal("b","lease_test") {true}
            val loss=launch { co.establishedLocalLost("lease_test") }; runCurrent()
            assertEquals(1,requested); assertEquals(0,joined)
            co.cancelCurrent(); released.complete(Unit); runCurrent(); loss.join()
            assertEquals(0,joined); assertFalse(co.state.value.ownsCloudSession)
        } finally { co.close() }
    }
    @Test fun userEndInvalidatesTheLocalLeaseAndLateFailureCannotReopenIt() = runTest {
        val api=Api(now=now); var live=true; val starts=mutableListOf<ApprovedCloudRide>()
        val co=coordinator(api,Socket(),store(),this,starts=starts)
        try {
            co.start(); runCurrent(); co.attachEstablishedLocal("b","lease_test") {live}
            live=false; co.cancelCurrent(); co.establishedLocalLost("lease_test")
            assertEquals(0,api.fallbacks); assertTrue(starts.isEmpty()); assertNull(co.ride.value)
        } finally { co.close() }
    }
    @Test fun partialConfirmationRetryCanFollowThePeersAlreadyApprovedFallback() = runTest {
        val api=Api(now=now); val starts=mutableListOf<ApprovedCloudRide>()
        val co=coordinator(api,Socket(),store(),this,starts=starts)
        try {
            co.start(); runCurrent(); api.failRideAfterConfirmation=true
            assertTrue(runCatching { co.attachEstablishedLocal("b","lease_test") {true} }.isFailure)
            api.active=api.active!!.copy(state=RideState.CLOUD_PENDING,cloudReason=CloudEntryReason.LOCAL_FAILURE,cloudStartedAt=now,revision=3)
            co.attachEstablishedLocal("b","lease_test") {true}
            assertEquals(1,api.starts); assertEquals(1,starts.size)
        } finally { co.close() }
    }
    @Test fun endedPartialConfirmationCannotCreateANewRideFromTheSameLease() = runTest {
        val api=Api(now=now); val co=coordinator(api,Socket(),store(),this)
        try {
            co.start(); runCurrent(); api.failRideAfterConfirmation=true
            assertTrue(runCatching { co.attachEstablishedLocal("b","lease_test") {true} }.isFailure)
            api.active=api.active!!.copy(state=RideState.ENDED)
            co.attachEstablishedLocal("b","lease_test") {true}
            co.attachEstablishedLocal("b","lease_test") {true}
            assertEquals(1,api.starts); assertNull(co.ride.value)
        } finally { co.close() }
    }
    @Test fun newLeaseCannotReauthorizeAnOldRideWhoseEndIsStillInFlight() = runTest {
        val api=Api(now=now); api.busyWhileActive=true
        val co=coordinator(api,Socket(),store(),this)
        try {
            co.start(); runCurrent(); co.attachEstablishedLocal("b","old_lease") {true}
            api.endGate=CompletableDeferred()
            val ending=launch { co.cancelCurrent() }; runCurrent()
            assertTrue(runCatching { co.attachEstablishedLocal("b","new_lease") {true} }.isFailure)
            assertFalse(co.knowsLocalLease("new_lease")); assertNull(co.ride.value)
            co.establishedLocalLost("new_lease"); assertEquals(0,api.fallbacks)
            api.endGate!!.complete(Unit); runCurrent(); ending.join()
            api.nextRideId="ride_new"
            co.attachEstablishedLocal("b","new_lease") {true}
            assertEquals("ride_new",co.ride.value!!.rideId)
        } finally { co.close() }
    }
    @Test fun delayedEndedPersistenceCannotClearANewerLocalRide() = runTest {
        val api=Api(now=now); val backing=store(); val clearGate=CompletableDeferred<Unit>()
        var blockClear=false
        val persistence=object : CloudSessionStore by backing {
            override suspend fun clearRide(rideId: String) {
                if(blockClear && rideId=="ride_test") clearGate.await()
                backing.clearRide(rideId)
            }
        }
        val co=coordinator(api,Socket(),persistence,this)
        try {
            co.start(); runCurrent(); co.attachEstablishedLocal("b","old_lease") {true}
            api.active=api.active!!.copy(state=RideState.ENDED); blockClear=true
            val oldRefresh=launch { co.refresh() }; runCurrent()
            api.nextRideId="ride_new"
            co.attachEstablishedLocal("b","new_lease") {true}
            clearGate.complete(Unit); runCurrent(); oldRefresh.join()
            assertEquals("ride_new",co.ride.value!!.rideId)
            assertEquals("ride_new",backing.savedRide()!!.rideId)
            assertEquals(CloudIntercomState.Idle,co.state.value)
        } finally { co.close() }
    }
    @Test fun cancellingFallbackRetiresItsSuccessfulLocalLease() = runTest {
        val api=Api(now=now); val co=coordinator(api,Socket(),store(),this)
        try {
            co.start(); runCurrent(); co.attachEstablishedLocal("b","lease_test") {true}
            co.establishedLocalLost("lease_test"); co.cancelCurrent()
            co.attachEstablishedLocal("b","lease_test") {true}
            co.establishedLocalLost("lease_test")
            assertEquals(1,api.starts); assertEquals(1,api.fallbacks); assertNull(co.ride.value)
        } finally { co.close() }
    }
}
