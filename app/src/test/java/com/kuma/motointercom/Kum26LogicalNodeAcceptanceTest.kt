package com.kuma.motointercom

import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kum26LogicalNodeAcceptanceTest {
    @Test
    fun thirdDeviceFirstIsClosedWithoutResourcesAndLockedTargetStillRegisters() = runBlocking {
        val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
        val repository = RecordingPairingRepository()
        val harness = orchestrator(repository)
        var lanAdapterOpens = 0
        var wifiDirectAdapterOpens = 0

        harness.use {
            harness.start(attempt)
            assertTrue(
                openPlannedTransport(
                    attempt,
                    openLan = {
                        lanAdapterOpens += 1
                        true
                    },
                    openWifiDirect = {
                        wifiDirectAdapterOpens += 1
                        true
                    }
                )
            )
            socketPair().use { sockets ->
                val requester = establishAsync(
                    sockets.opener,
                    PhysicalSocketRole.OPENER,
                    DEVICE_A,
                    SESSION_A,
                    attempt
                )
                val thirdDevice = establishAsync(
                    sockets.acceptor,
                    PhysicalSocketRole.ACCEPTOR,
                    DEVICE_C,
                    SESSION_C,
                    null,
                    TargetLock(DEVICE_A, RuntimeSessionId(SESSION_A))
                )

                assertNull(awaitFailure(requester))
                assertNull(awaitFailure(thirdDevice))
                assertTrue(sockets.opener.isClosed)
                assertTrue(sockets.acceptor.isClosed)
                assertFalse(harness.hasPendingEffect())
                assertEquals(attempt, harness.orchestrator.currentAttempt)
                assertTrue(repository.saved.isEmpty())
                assertEquals(1, lanAdapterOpens)
                assertEquals(0, wifiDirectAdapterOpens)
            }

            establishedPair(attempt, DEVICE_B, SESSION_B).use { pair ->
                assertTrue(canRegisterControlChannel(true, attempt, pair.requester))
                assertTrue(
                    harness.orchestrator.dispatchAndAwait(
                        SessionEvent.ControlChannelVerified(
                            RUNTIME_A,
                            pair.requester.toVerifiedControlChannel()
                        )
                    )
                )
                assertTrue(harness.nextEffect() is SessionEffect.SendConnectRequest)
                assertFalse(harness.hasPendingEffect())
                assertFalse(pair.requester.isClosed)
                assertTrue(repository.saved.isEmpty())
            }
        }
    }

    @Test
    fun wrongDeviceAndSupersededRuntimeFailClosedBeforeResourceActivation() = runBlocking {
        val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
        listOf(
            DEVICE_C to SESSION_C,
            DEVICE_B to SESSION_OLD
        ).forEach { (actualDevice, actualSession) ->
            val repository = RecordingPairingRepository()
            orchestrator(repository).use { harness ->
                harness.start(attempt)
                socketPair().use { sockets ->
                    val requester = establishAsync(
                        sockets.opener,
                        PhysicalSocketRole.OPENER,
                        DEVICE_A,
                        SESSION_A,
                        attempt
                    )
                    val remote = establishAsync(
                        sockets.acceptor,
                        PhysicalSocketRole.ACCEPTOR,
                        actualDevice,
                        actualSession,
                        null,
                        TargetLock(DEVICE_A, RuntimeSessionId(SESSION_A))
                    )

                    assertNull(awaitFailure(requester))
                    assertTrue(sockets.opener.isClosed)
                    val remoteSession = awaitSessionOrNull(remote)
                    if (actualDevice == DEVICE_C) {
                        assertNull(remoteSession)
                    } else {
                        remoteSession?.let { staleSession ->
                            assertFalse(
                                canRegisterControlChannel(
                                    sessionCurrent = false,
                                    currentAttempt = null,
                                    session = staleSession
                                )
                            )
                            staleSession.close()
                            assertTrue(staleSession.isClosed)
                        }
                    }
                    assertFalse(harness.hasPendingEffect())
                    assertEquals(attempt, harness.orchestrator.currentAttempt)
                    assertTrue(repository.saved.isEmpty())
                }
            }
        }
    }

    @Test
    fun missingEmptyAndMalformedSocketIdentityNeverCreatesVerifiedResources() = runBlocking {
        val cases = listOf(
            RawIdentityCase("missing deviceId", sourceDeviceId = null),
            RawIdentityCase("missing runtimeSessionId", sourceSessionId = null),
            RawIdentityCase("empty deviceId", sourceDeviceId = ""),
            RawIdentityCase("empty runtimeSessionId", sourceSessionId = ""),
            RawIdentityCase("malformed deviceId", sourceDeviceId = "device-b"),
            RawIdentityCase("malformed runtimeSessionId", sourceSessionId = "session-b")
        )

        cases.forEach { case ->
            val repository = RecordingPairingRepository()
            val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
            orchestrator(repository).use { harness ->
                harness.start(attempt)
                socketPair().use { sockets ->
                    val responder = establishAsync(
                        sockets.acceptor,
                        PhysicalSocketRole.ACCEPTOR,
                        DEVICE_B,
                        SESSION_B,
                        null,
                        TargetLock(DEVICE_A, RuntimeSessionId(SESSION_A))
                    )
                    SignalingV2Framing.write(
                        DataOutputStream(sockets.opener.getOutputStream()),
                        rawHello(case)
                    )

                    assertNull(case.label, awaitFailure(responder))
                    assertTrue("${case.label} must close the current Socket", sockets.acceptor.isClosed)
                    assertFalse(harness.hasPendingEffect())
                    assertEquals(attempt, harness.orchestrator.currentAttempt)
                    assertTrue(repository.saved.isEmpty())
                }
            }
        }
    }

    @Test
    fun staleAcceptedActivationClosesOldSocketReleasesClaimAndKeepsCurrentAttempt() = runBlocking {
        val oldAttempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
        val newAttempt = attempt(ATTEMPT_NEW, DEVICE_B, SESSION_B)
        val repository = RecordingPairingRepository()
        val harness = OrchestratorHarness(
            SessionOrchestrator(repository, Dispatchers.Unconfined)
        )
        val resources = ResourceProbe(tunnelClaimed = true)

        establishedPair(oldAttempt, DEVICE_B, SESSION_B).use { oldPair ->
            advanceRequesterToAccepted(oldPair)
            try {
                harness.start(oldAttempt)
                assertTrue(
                    canStartWebRtc(
                        sessionCurrent = true,
                        currentAttempt = oldAttempt,
                        expectedAttempt = oldAttempt,
                        session = oldPair.requester,
                        expectedRole = WebRtcRole.OFFERER
                    )
                )

                assertTrue(
                    harness.orchestrator.dispatchAndAwait(
                        SessionEvent.AttemptTimedOut(
                            RUNTIME_A,
                            oldAttempt.id,
                            oldAttempt.deadlineElapsedRealtimeMs
                        )
                    )
                )
                assertTrue(harness.nextEffect() is SessionEffect.AbortAttemptAndResumeDiscovery)
                resources.abort(oldPair.requester)
                assertTrue(harness.orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(newAttempt)))

                assertFalse(
                    canStartWebRtc(
                        sessionCurrent = true,
                        currentAttempt = harness.orchestrator.currentAttempt,
                        expectedAttempt = oldAttempt,
                        session = oldPair.requester,
                        expectedRole = WebRtcRole.OFFERER
                    )
                )
                oldPair.requester.close()

                assertTrue(oldPair.requester.isClosed)
                assertFalse(resources.tunnelClaimed)
                assertEquals(newAttempt, harness.orchestrator.currentAttempt)
                assertTrue(repository.saved.isEmpty())
            } finally {
                harness.close()
            }
        }

        establishedPair(newAttempt, DEVICE_B, SESSION_B).use { newPair ->
            assertTrue(canRegisterControlChannel(true, newAttempt, newPair.requester))
        }
    }

    @Test
    fun timeoutOrderingsRemainMailboxOrderedAndAttemptScoped() = runBlocking {
        val peer = verifiedPeer(DEVICE_B, SESSION_B)

        orchestrator().use { harness ->
            val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
            harness.start(attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.TargetedTransportOpenFailed(
                        RUNTIME_A,
                        attempt.id,
                        Transport.LAN,
                        "adapter unavailable"
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.AbortAttemptAndResumeDiscovery)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
        }

        orchestrator().use { harness ->
            val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
            harness.start(attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertTrue(harness.nextEffect() is SessionEffect.AbortAttemptAndResumeDiscovery)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.TunnelReady(attempt, peer, Transport.LAN)
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteIdentityReceived(RUNTIME_A, attempt.id, peer)
                )
            )
        }

        orchestrator().use { harness ->
            val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
            harness.start(attempt)
            val callbackProcessed = CompletableDeferred<Boolean>()
            val timeoutProcessed = CompletableDeferred<Boolean>()
            assertTrue(
                harness.orchestrator.dispatch(
                    SessionEvent.TunnelReady(attempt, peer, Transport.LAN),
                    callbackProcessed::complete
                )
            )
            assertTrue(
                harness.orchestrator.dispatch(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        attempt.id,
                        attempt.deadlineElapsedRealtimeMs
                    ),
                    timeoutProcessed::complete
                )
            )
            assertTrue(withTimeout(1_000L) { callbackProcessed.await() })
            assertTrue(withTimeout(1_000L) { timeoutProcessed.await() })
            assertTrue(harness.nextEffect() is SessionEffect.AbortAttemptAndResumeDiscovery)
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
        }

        orchestrator().use { harness ->
            val oldAttempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
            val currentAttempt = attempt(ATTEMPT_NEW, DEVICE_B, SESSION_B)
            harness.start(oldAttempt)
            assertTrue(harness.orchestrator.dispatchAndAwait(SessionEvent.AttemptReplaced(currentAttempt)))
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.AttemptTimedOut(
                        RUNTIME_A,
                        oldAttempt.id,
                        oldAttempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertEquals(currentAttempt, harness.orchestrator.currentAttempt)
        }
    }

    @Test
    fun recoveryRetainsTargetAndPlanRejectsRolloverAndExitsDeterministically() = runBlocking {
        val attempt = attempt(ATTEMPT_A, DEVICE_B, SESSION_B)
        val harness = OrchestratorHarness(
            SessionOrchestrator(
                RecordingPairingRepository(),
                Dispatchers.Unconfined,
                elapsedRealtime = { 0L },
                attemptIdFactory = { ConnectionAttemptId(ATTEMPT_RECOVERY) }
            )
        )
        harness.use {
            harness.start(attempt)
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteIdentityReceived(
                        RUNTIME_A,
                        attempt.id,
                        verifiedPeer(DEVICE_B, SESSION_B)
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        RUNTIME_A,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        1L
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(RUNTIME_A, attempt.id)
                )
            )
            val recovering = harness.orchestrator.state.value as IntercomState.Recovering
            val recoveryAttempt = recovering.attempt

            assertEquals(ConnectionAttemptId(ATTEMPT_RECOVERY), recoveryAttempt.id)
            assertEquals(attempt.targetLock, recoveryAttempt.targetLock)
            assertEquals(attempt.channelPlan, recoveryAttempt.channelPlan)
            assertEquals(setOf(Transport.LAN), plannedDiscoveryTransports(recoveryAttempt))
            assertTrue(harness.nextEffect() is SessionEffect.RestartDiscovery)
            assertTrue(harness.nextEffect() is SessionEffect.ScheduleAttemptDeadline)
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.ConnectPresenceRequested(
                        runtimeSessionId = RUNTIME_A,
                        targetDeviceId = DEVICE_C,
                        targetSessionId = RuntimeSessionId(SESSION_C),
                        availableTransports = setOf(Transport.LAN)
                    )
                )
            )
            assertFalse(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteIdentityReceived(
                        RUNTIME_A,
                        recoveryAttempt.id,
                        verifiedPeer(DEVICE_B, SESSION_OLD)
                    )
                )
            )
            assertTrue(
                harness.orchestrator.dispatchAndAwait(
                    SessionEvent.TargetedTransportOpenFailed(
                        RUNTIME_A,
                        recoveryAttempt.id,
                        Transport.LAN,
                        "recovery adapter failed"
                    )
                )
            )
            assertTrue(harness.orchestrator.state.value is IntercomState.Discovering)
            assertEquals(
                SessionEffect.AbortAttemptAndResumeDiscovery(
                    RUNTIME_A,
                    recoveryAttempt.id
                ),
                harness.nextEffect()
            )
        }
    }

    private fun orchestrator(
        repository: RecordingPairingRepository = RecordingPairingRepository()
    ) = OrchestratorHarness(
        SessionOrchestrator(repository, Dispatchers.Unconfined)
    )

    private fun establishedPair(
        attempt: ConnectionAttempt,
        remoteDeviceId: String,
        remoteSessionId: String
    ): SessionPair {
        val sockets = socketPair()
        val requester = establishAsync(
            sockets.opener,
            PhysicalSocketRole.OPENER,
            DEVICE_A,
            SESSION_A,
            attempt
        )
        val responder = establishAsync(
            sockets.acceptor,
            PhysicalSocketRole.ACCEPTOR,
            remoteDeviceId,
            remoteSessionId,
            null,
            TargetLock(DEVICE_A, RuntimeSessionId(SESSION_A))
        )
        return SessionPair(
            sockets,
            requester.get(2, TimeUnit.SECONDS),
            responder.get(2, TimeUnit.SECONDS)
        )
    }

    private fun establishAsync(
        socket: Socket,
        physicalRole: PhysicalSocketRole,
        localDeviceId: String,
        localSessionId: String,
        originatingAttempt: ConnectionAttempt?,
        expectedRemoteTargetLock: TargetLock? = originatingAttempt?.targetLock
    ) = CompletableFuture.supplyAsync {
        SignalingSessionV2.establish(
            socket = socket,
            transport = Transport.LAN,
            physicalRole = physicalRole,
            openedAtElapsedMs = 1L,
            localDeviceId = localDeviceId,
            localRuntimeSessionId = RuntimeSessionId(localSessionId),
            localNickname = localDeviceId,
            localDeviceName = localDeviceId,
            originatingAttempt = originatingAttempt,
            expectedRemoteTargetLock = expectedRemoteTargetLock
        )
    }

    private fun awaitFailure(future: CompletableFuture<SignalingSessionV2>): SignalingSessionV2? {
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            assertTrue(error.cause is SignalingV2Exception)
            null
        }
    }

    private fun awaitSessionOrNull(
        future: CompletableFuture<SignalingSessionV2>
    ): SignalingSessionV2? = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrNull()

    private fun rawHello(case: RawIdentityCase): ByteArray {
        val fields = buildList {
            add("\"protocolVersion\":2")
            add("\"type\":\"HELLO\"")
            add("\"attemptId\":\"$ATTEMPT_A\"")
            case.sourceDeviceId?.let { add("\"sourceDeviceId\":\"$it\"") }
            add("\"targetDeviceId\":\"$DEVICE_B\"")
            case.sourceSessionId?.let { add("\"sourceSessionId\":\"$it\"") }
            add("\"payload\":{\"requestRole\":\"REQUESTER\",\"capabilities\":[]}")
        }
        return "{${fields.joinToString(",")}}".toByteArray(Charsets.UTF_8)
    }

    private fun advanceRequesterToAccepted(pair: SessionPair) {
        val requestReceived = CompletableFuture<SignalingEnvelopeV2>()
        pair.responder.startReader(requestReceived::complete) { requestReceived.completeExceptionally(it) }
        sendAndWait(pair.requester, SignalingMessageV2.ConnectRequest(RequestTrigger.USER, Transport.LAN))
        requestReceived.get(2, TimeUnit.SECONDS)

        val acceptReceived = CompletableFuture<SignalingEnvelopeV2>()
        pair.requester.startReader(acceptReceived::complete) { acceptReceived.completeExceptionally(it) }
        sendAndWait(pair.responder, SignalingMessageV2.ConnectAccept("Rider B", "Phone B"))
        acceptReceived.get(2, TimeUnit.SECONDS)
        assertEquals(SignalingPhase.ACCEPTED, pair.requester.phase)
    }

    private fun sendAndWait(session: SignalingSessionV2, message: SignalingMessageV2) {
        val result = CompletableFuture<Result<Unit>>()
        session.send(message, result::complete)
        result.get(2, TimeUnit.SECONDS).getOrThrow()
    }

    private fun attempt(id: String, targetDevice: String, targetSession: String) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = RUNTIME_A,
        targetLock = TargetLock(targetDevice, RuntimeSessionId(targetSession)),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 10_000L
    )

    private fun verifiedPeer(deviceId: String, sessionId: String) = PeerIdentity(
        deviceId = deviceId,
        runtimeSessionId = RuntimeSessionId(sessionId),
        nickname = deviceId,
        isDeviceIdVerified = true
    )

    private fun SignalingSessionV2.toVerifiedControlChannel() = VerifiedControlChannel(
        channelId = channel.channelId,
        transport = channel.transport,
        requestRole = requestRole,
        wireRequestKey = wireRequestKey,
        targetLock = targetLock,
        peer = peer,
        originatingAttempt = originatingAttempt
    )

    private fun socketPair(): SocketPair = ServerSocket(0).use { server ->
        val opener = Socket("127.0.0.1", server.localPort)
        SocketPair(opener, server.accept())
    }

    private data class RawIdentityCase(
        val label: String,
        val sourceDeviceId: String? = DEVICE_A,
        val sourceSessionId: String? = SESSION_A
    )

    private data class SocketPair(val opener: Socket, val acceptor: Socket) : AutoCloseable {
        override fun close() {
            runCatching { opener.close() }
            runCatching { acceptor.close() }
        }
    }

    private data class SessionPair(
        val sockets: SocketPair,
        val requester: SignalingSessionV2,
        val responder: SignalingSessionV2
    ) : AutoCloseable {
        override fun close() {
            requester.close()
            responder.close()
            sockets.close()
        }
    }

    private class ResourceProbe(var tunnelClaimed: Boolean = false) {
        fun abort(session: SignalingSessionV2) {
            AttemptResourceController(
                runtimeSessionId = RUNTIME_A,
                closeIntercomAndSocket = session::close,
                closeLanDiscovery = {},
                closeWifiDirect = { it() },
                closeAudioRoute = {},
                releaseTunnel = { tunnelClaimed = false },
                clearConnectionState = {},
                resumeDiscovery = {}
            ).abortAndResumeDiscovery()
        }
    }

    private class RecordingPairingRepository : PairingRepository {
        val saved = mutableListOf<PairingRecord>()

        override fun observeAll(): Flow<List<PairingRecord>> = flowOf(saved)
        override suspend fun getAll(): List<PairingRecord> = saved.toList()
        override suspend fun getByDeviceId(deviceId: String): PairingRecord? =
            saved.firstOrNull { it.remoteDeviceId == deviceId }

        override suspend fun saveConnectedPeer(record: PairingRecord) {
            saved += record
        }

        override suspend fun setPreferred(deviceId: String): Boolean = false
        override suspend fun clearPreferred() = Unit
        override suspend fun updateLastConnectedAt(
            deviceId: String,
            connectedAt: Long,
            transport: String?
        ): Boolean = false
        override suspend fun incrementFailureCount(deviceId: String): Boolean = false
        override suspend fun clearFailureCount(deviceId: String): Boolean = false
        override suspend fun forget(deviceId: String): Boolean = false
    }

    private class OrchestratorHarness(
        val orchestrator: SessionOrchestrator
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val effectQueue = Channel<SessionEffect>(Channel.UNLIMITED)

        init {
            scope.launch {
                orchestrator.effects.collect(effectQueue::send)
            }
        }

        suspend fun start(attempt: ConnectionAttempt) {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(RUNTIME_A)))
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attempt)))
        }

        suspend fun nextEffect(): SessionEffect = withTimeout(1_000L) {
            effectQueue.receive()
        }

        fun hasPendingEffect(): Boolean = effectQueue.tryReceive().isSuccess

        override fun close() {
            orchestrator.close()
            scope.cancel()
        }
    }

    companion object {
        private const val DEVICE_A = "11111111-1111-4111-8111-111111111111"
        private const val DEVICE_B = "22222222-2222-4222-8222-222222222222"
        private const val DEVICE_C = "33333333-3333-4333-8333-333333333333"
        private const val SESSION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val SESSION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val SESSION_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        private const val SESSION_OLD = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        private const val ATTEMPT_A = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
        private const val ATTEMPT_NEW = "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb"
        private const val ATTEMPT_RECOVERY = "cccccccc-1111-4111-8111-cccccccccccc"
        private val RUNTIME_A = RuntimeSessionId(SESSION_A)
    }
}
