package com.kuma.motointercom

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_peers")
data class PairingRecord(
    @PrimaryKey val remoteDeviceId: String,
    val remoteNickname: String,
    val deviceName: String,
    val localAlias: String,
    val shortCode: String,
    val pairedAt: Long,
    val lastConnectedAt: Long,
    val isPreferred: Boolean,
    val lastTransport: String?,
    val failureCount: Int
)
