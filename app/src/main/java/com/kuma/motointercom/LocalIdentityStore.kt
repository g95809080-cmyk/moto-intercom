package com.kuma.motointercom

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.localIdentityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_identity"
)

interface LocalIdentityStore {
    suspend fun getOrCreateDeviceId(): String
    suspend fun getNickname(): String
    suspend fun updateNickname(value: String)
}

class DataStoreLocalIdentityStore internal constructor(
    private val dataStore: DataStore<Preferences>
) : LocalIdentityStore {
    constructor(context: Context) : this(context.applicationContext.localIdentityDataStore)

    override suspend fun getOrCreateDeviceId(): String {
        val updated = dataStore.edit { preferences ->
            if (preferences[DEVICE_ID].isNullOrBlank()) {
                preferences[DEVICE_ID] = UUID.randomUUID().toString()
            }
        }
        return checkNotNull(updated[DEVICE_ID]) { "Device ID was not persisted" }
    }

    override suspend fun getNickname(): String =
        dataStore.data.first()[NICKNAME].orEmpty()

    override suspend fun updateNickname(value: String) {
        val normalized = value.trim()
        dataStore.edit { preferences ->
            if (normalized.isBlank()) {
                preferences.remove(NICKNAME)
            } else {
                preferences[NICKNAME] = normalized
            }
        }
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val NICKNAME = stringPreferencesKey("nickname")
    }
}
