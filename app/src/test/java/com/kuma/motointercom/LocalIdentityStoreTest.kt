package com.kuma.motointercom

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalIdentityStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun returnsPersistedDeviceId() = withStore { store ->
        val first = store.getOrCreateDeviceId()
        val second = store.getOrCreateDeviceId()

        UUID.fromString(first)
        assertEquals(first, second)
    }

    @Test
    fun concurrentCallsReturnOneDeviceId() = withStore { store ->
        val ids = coroutineScope {
            List(20) { async(Dispatchers.Default) { store.getOrCreateDeviceId() } }.awaitAll()
        }

        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun reopeningDataStoreReadsTheSameDeviceId() = runBlocking {
        val file = File(temporaryFolder.root, "reopened_local_identity.preferences_pb")
        val first = withStore(file) { it.getOrCreateDeviceId() }
        val afterProcessRestart = withStore(file) { it.getOrCreateDeviceId() }

        assertEquals(first, afterProcessRestart)
    }

    private fun withStore(block: suspend (LocalIdentityStore) -> Unit) = runBlocking {
        withStore(File(temporaryFolder.root, "local_identity.preferences_pb"), block)
    }

    private suspend fun <T> withStore(
        file: File,
        block: suspend (LocalIdentityStore) -> T
    ): T {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return try {
            block(DataStoreLocalIdentityStore(dataStore))
        } finally {
            job.cancelAndJoin()
        }
    }
}
