package com.kuma.motointercom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOrchestratorTest {
    @Test
    fun persistsPairingOnlyAfterWebRtcConnected() {
        val repository = RecordingPairingRepository()
        val orchestrator = SessionOrchestrator(repository, Dispatchers.Unconfined)
        val runtime = RuntimeSessionId("runtime-current")
        val attempt = ConnectionAttemptId("attempt-current")
        val peer = PeerIdentity("peer-a", "Rider A", "Phone A")
        try {
            assertTrue(orchestrator.dispatch(SessionEvent.RuntimeStarted(runtime)))
            assertTrue(
                orchestrator.dispatch(
                    SessionEvent.ConnectRequested(runtime, attempt, peer.deviceId)
                )
            )
            assertTrue(repository.saved.isEmpty())

            assertTrue(
                orchestrator.dispatch(
                    SessionEvent.WebRtcConnected(runtime, attempt, peer, 100L, "LAN")
                )
            )

            assertEquals(listOf("peer-a"), repository.saved.map(PairingRecord::remoteDeviceId))
        } finally {
            orchestrator.close()
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
}
