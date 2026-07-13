package com.kuma.motointercom

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
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

    private fun withStore(block: suspend (LocalIdentityStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(temporaryFolder.root, "local_identity.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            block(DataStoreLocalIdentityStore(dataStore))
        } finally {
            scope.cancel()
        }
    }
}
