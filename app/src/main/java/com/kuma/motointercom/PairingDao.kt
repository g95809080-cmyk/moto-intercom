package com.kuma.motointercom

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class PairingDao {
    @Query(
        "SELECT * FROM paired_peers " +
            "ORDER BY isPreferred DESC, lastConnectedAt DESC, remoteNickname COLLATE NOCASE"
    )
    abstract fun observeAll(): Flow<List<PairingRecord>>

    @Query(
        "SELECT * FROM paired_peers " +
            "ORDER BY isPreferred DESC, lastConnectedAt DESC, remoteNickname COLLATE NOCASE"
    )
    abstract suspend fun getAll(): List<PairingRecord>

    @Query("SELECT * FROM paired_peers WHERE remoteDeviceId = :deviceId LIMIT 1")
    abstract suspend fun getByDeviceId(deviceId: String): PairingRecord?

    @Upsert
    protected abstract suspend fun upsert(record: PairingRecord)

    @Query("SELECT EXISTS(SELECT 1 FROM paired_peers WHERE remoteDeviceId = :deviceId)")
    protected abstract suspend fun exists(deviceId: String): Boolean

    @Query("UPDATE paired_peers SET isPreferred = 0 WHERE isPreferred = 1")
    protected abstract suspend fun clearPreferredInternal()

    @Query("UPDATE paired_peers SET isPreferred = 1 WHERE remoteDeviceId = :deviceId")
    protected abstract suspend fun markPreferredInternal(deviceId: String): Int

    @Query(
        "UPDATE paired_peers SET lastConnectedAt = :connectedAt, lastTransport = :transport " +
            "WHERE remoteDeviceId = :deviceId"
    )
    abstract suspend fun updateLastConnectedAt(
        deviceId: String,
        connectedAt: Long,
        transport: String?
    ): Int

    @Query(
        "UPDATE paired_peers SET failureCount = failureCount + 1 " +
            "WHERE remoteDeviceId = :deviceId"
    )
    abstract suspend fun incrementFailureCount(deviceId: String): Int

    @Query("UPDATE paired_peers SET failureCount = 0 WHERE remoteDeviceId = :deviceId")
    abstract suspend fun clearFailureCount(deviceId: String): Int

    @Query("DELETE FROM paired_peers WHERE remoteDeviceId = :deviceId")
    abstract suspend fun forget(deviceId: String): Int

    @Transaction
    open suspend fun saveConnected(record: PairingRecord) {
        val existing = getByDeviceId(record.remoteDeviceId)
        upsert(
            record.copy(
                localAlias = existing?.localAlias ?: record.localAlias,
                pairedAt = existing?.pairedAt ?: record.pairedAt,
                isPreferred = existing?.isPreferred ?: false,
                failureCount = existing?.failureCount ?: record.failureCount
            )
        )
    }

    @Transaction
    open suspend fun setPreferred(deviceId: String): Boolean {
        if (!exists(deviceId)) return false
        clearPreferredInternal()
        return markPreferredInternal(deviceId) == 1
    }

    @Transaction
    open suspend fun clearPreferred() {
        clearPreferredInternal()
    }
}
