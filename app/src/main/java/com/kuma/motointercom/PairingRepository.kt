package com.kuma.motointercom

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.Flow

interface PairingRepository {
    fun observeAll(): Flow<List<PairingRecord>>
    suspend fun getAll(): List<PairingRecord>
    suspend fun getByDeviceId(deviceId: String): PairingRecord?
    suspend fun saveConnectedPeer(record: PairingRecord)
    suspend fun setPreferred(deviceId: String): Boolean
    suspend fun clearPreferred()
    suspend fun updateLastConnectedAt(deviceId: String, connectedAt: Long, transport: String?): Boolean
    suspend fun incrementFailureCount(deviceId: String): Boolean
    suspend fun clearFailureCount(deviceId: String): Boolean
    suspend fun forget(deviceId: String): Boolean
}

internal class RoomPairingRepository(
    private val dao: PairingDao
) : PairingRepository {
    override fun observeAll(): Flow<List<PairingRecord>> = dao.observeAll()

    override suspend fun getAll(): List<PairingRecord> = dao.getAll()

    override suspend fun getByDeviceId(deviceId: String): PairingRecord? =
        dao.getByDeviceId(deviceId.trim())

    override suspend fun saveConnectedPeer(record: PairingRecord) {
        val deviceId = record.remoteDeviceId.trim()
        require(deviceId.isNotBlank()) { "Remote device ID must not be blank" }
        dao.saveConnected(
            record.copy(
                remoteDeviceId = deviceId,
                remoteNickname = record.remoteNickname.trim(),
                deviceName = record.deviceName.trim(),
                localAlias = record.localAlias.trim(),
                shortCode = record.shortCode.ifBlank { shortCode(deviceId) },
                isPreferred = false,
                failureCount = record.failureCount.coerceAtLeast(0)
            )
        )
    }

    override suspend fun setPreferred(deviceId: String): Boolean =
        dao.setPreferred(deviceId.trim())

    override suspend fun clearPreferred() = dao.clearPreferred()

    override suspend fun updateLastConnectedAt(
        deviceId: String,
        connectedAt: Long,
        transport: String?
    ): Boolean = dao.updateLastConnectedAt(deviceId.trim(), connectedAt, transport) == 1

    override suspend fun incrementFailureCount(deviceId: String): Boolean =
        dao.incrementFailureCount(deviceId.trim()) == 1

    override suspend fun clearFailureCount(deviceId: String): Boolean =
        dao.clearFailureCount(deviceId.trim()) == 1

    override suspend fun forget(deviceId: String): Boolean =
        dao.forget(deviceId.trim()) == 1

    private fun shortCode(deviceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
            .take(2)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            .uppercase(Locale.ROOT)
}
