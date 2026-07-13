package com.kuma.motointercom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOrchestratorTest {
    private val runtime = RuntimeSessionId("runtime-current")

    @Test
    fun connectedThenImmediateDisconnectCannotRemainStaleConnected() = runBlocking {
        val orchestrator = orchestrator()
        val attempt = attempt("attempt-current", "peer-a", Transport.LAN)
        val peer = PeerIdentity("peer-a", "Rider A", "Phone A")
        val recovery = recovery("attempt-recovery")
        try {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime)))
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attempt)))
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteIdentityReceived(runtime, attempt.id, peer)
                )
            )

            assertTrue(
                orchestrator.dispatch(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        100L,
                        recovery
                    )
                )
            )
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attempt.id,
                        WebRtcConnectionState.DISCONNECTED,
                        101L,
                        recovery
                    )
                )
            )

            assertTrue(orchestrator.state.value is IntercomState.Recovering)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun connectedCallbackAfterStopIsIgnored() = runBlocking {
        val orchestrator = orchestrator()
        val attempt = attempt("attempt-current", "peer-a", Transport.LAN)
        try {
            orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime))
            orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attempt))
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.StopRequested(runtime)))

            assertFalse(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        100L,
                        recovery("attempt-recovery")
                    )
                )
            )
            assertTrue(orchestrator.state.value is IntercomState.Stopping)

            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStopped(runtime)))
            assertEquals(IntercomState.Offline, orchestrator.state.value)
            assertFalse(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        101L,
                        recovery("attempt-recovery-after-offline")
                    )
                )
            )
            assertEquals(IntercomState.Offline, orchestrator.state.value)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun replacingAttemptInvalidatesOldCallbacksAndAllowsAnotherConnection() = runBlocking {
        val orchestrator = orchestrator()
        val attemptA = attempt("attempt-a", "peer-a", Transport.LAN)
        val attemptB = attempt("attempt-b", "peer-b", Transport.WIFI_DIRECT)
        val attemptC = attempt("attempt-c", "peer-c", Transport.LAN)
        try {
            orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime))
            orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attemptA))
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.AttemptReplaced(attemptB)))

            assertFalse(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attemptA.id,
                        WebRtcConnectionState.CONNECTED,
                        1L,
                        recovery("recovery-a")
                    )
                )
            )
            assertFalse(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attemptA.id,
                        WebRtcConnectionState.FAILED,
                        2L,
                        recovery("recovery-a-2")
                    )
                )
            )
            assertEquals(attemptB, orchestrator.currentAttempt)

            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        attemptB.id,
                        WebRtcConnectionState.FAILED,
                        3L,
                        recovery("recovery-b")
                    )
                )
            )
            assertTrue(orchestrator.state.value is IntercomState.Discovering)
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.ConnectRequested(attemptC)))
            assertEquals(attemptC, orchestrator.currentAttempt)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun p2pVerifiedIdentityIsPersistedOnlyAfterConnected() = runBlocking {
        val repository = RecordingPairingRepository()
        val orchestrator = orchestrator(repository)
        val provisional = provisionalAttempt("attempt-p2p")
        val peer = PeerIdentity(
            deviceId = "peer-p2p",
            nickname = "P2P Rider",
            deviceName = "Phone P2P",
            runtimeSessionId = RuntimeSessionId("remote-runtime"),
            isDeviceIdVerified = true
        )
        try {
            orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime))
            orchestrator.dispatchAndAwait(
                SessionEvent.TunnelReady(provisional, null, Transport.WIFI_DIRECT)
            )
            orchestrator.dispatchAndAwait(
                SessionEvent.RemoteIdentityReceived(runtime, provisional.id, peer)
            )
            assertTrue(repository.saved.isEmpty())

            orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    provisional.id,
                    WebRtcConnectionState.CONNECTED,
                    100L,
                    recovery("recovery-p2p")
                )
            )

            assertEquals(listOf("peer-p2p"), repository.saved.map(PairingRecord::remoteDeviceId))
            assertEquals("WIFI_DIRECT", repository.saved.single().lastTransport)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun p2pUnknownIdentityIsNotPersistedAndIsLogged() = runBlocking {
        val repository = RecordingPairingRepository()
        val logs = mutableListOf<String>()
        val orchestrator = orchestrator(repository, logs::add)
        val provisional = provisionalAttempt("attempt-unknown")
        try {
            orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime))
            orchestrator.dispatchAndAwait(
                SessionEvent.TunnelReady(provisional, null, Transport.WIFI_DIRECT)
            )
            orchestrator.dispatchAndAwait(
                SessionEvent.RemoteIdentityReceived(
                    runtime,
                    provisional.id,
                    PeerIdentity(null, "Legacy Rider")
                )
            )
            orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    provisional.id,
                    WebRtcConnectionState.CONNECTED,
                    100L,
                    recovery("recovery-unknown")
                )
            )

            assertTrue(repository.saved.isEmpty())
            assertTrue(logs.any { it.contains("deviceId is unknown") })
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun p2pTargetFromDiscoveryIsNotPersistedWithoutSocketIdentityConfirmation() = runBlocking {
        val repository = RecordingPairingRepository()
        val orchestrator = orchestrator(repository)
        val unverified = ConnectionAttempt(
            id = ConnectionAttemptId("attempt-unverified"),
            runtimeSessionId = runtime,
            targetDeviceId = "peer-from-discovery",
            trigger = ConnectionTrigger.AUTO_DISCOVERY,
            preferredTransport = Transport.WIFI_DIRECT,
            deadlineElapsedRealtimeMs = 10_000L
        )
        try {
            orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime))
            orchestrator.dispatchAndAwait(
                SessionEvent.TunnelReady(unverified, null, Transport.WIFI_DIRECT)
            )
            orchestrator.dispatchAndAwait(
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    unverified.id,
                    WebRtcConnectionState.CONNECTED,
                    100L,
                    recovery("recovery-unverified")
                )
            )

            assertTrue(repository.saved.isEmpty())
        } finally {
            orchestrator.close()
        }
    }

    private fun orchestrator(
        repository: RecordingPairingRepository = RecordingPairingRepository(),
        onLog: (String) -> Unit = {}
    ) = SessionOrchestrator(repository, Dispatchers.Unconfined, onLog = onLog)

    private fun attempt(id: String, target: String, transport: Transport) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = runtime,
        targetDeviceId = target,
        trigger = ConnectionTrigger.USER,
        preferredTransport = transport,
        deadlineElapsedRealtimeMs = 10_000L
    )

    private fun provisionalAttempt(id: String) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = runtime,
        targetDeviceId = null,
        trigger = ConnectionTrigger.LEGACY_PROVISIONAL,
        preferredTransport = Transport.WIFI_DIRECT,
        deadlineElapsedRealtimeMs = 10_000L
    )

    private fun recovery(id: String) = RecoveryAttemptSpec(
        id = ConnectionAttemptId(id),
        deadlineElapsedRealtimeMs = 20_000L
    )

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
}
