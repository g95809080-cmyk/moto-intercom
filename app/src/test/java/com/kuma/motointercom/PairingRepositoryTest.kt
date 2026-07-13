package com.kuma.motointercom

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PairingRepositoryTest {
    private lateinit var database: PairingDatabase
    private lateinit var repository: PairingRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PairingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomPairingRepository(database.pairingDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun supportsCreateReadUpdateFailureCountersAndForget() = runBlocking {
        repository.saveConnectedPeer(record("peer-a", connectedAt = 100L))

        val created = requireNotNull(repository.getByDeviceId("peer-a"))
        assertEquals(4, created.shortCode.length)
        assertTrue(repository.updateLastConnectedAt("peer-a", 200L, "LAN"))
        assertTrue(repository.incrementFailureCount("peer-a"))
        assertEquals(1, repository.getByDeviceId("peer-a")?.failureCount)
        assertTrue(repository.clearFailureCount("peer-a"))
        assertEquals(0, repository.getByDeviceId("peer-a")?.failureCount)
        assertTrue(repository.forget("peer-a"))
        assertNull(repository.getByDeviceId("peer-a"))
    }

    @Test
    fun keepsAtMostOnePreferredPeer() = runBlocking {
        repository.saveConnectedPeer(record("peer-a"))
        repository.saveConnectedPeer(record("peer-b"))

        assertTrue(repository.setPreferred("peer-a"))
        assertTrue(repository.setPreferred("peer-b"))

        val records = repository.getAll()
        assertEquals(1, records.count(PairingRecord::isPreferred))
        assertEquals("peer-b", records.single(PairingRecord::isPreferred).remoteDeviceId)
    }

    @Test
    fun unknownPreferredPeerDoesNotClearExistingPreference() = runBlocking {
        repository.saveConnectedPeer(record("peer-a"))
        assertTrue(repository.setPreferred("peer-a"))

        assertFalse(repository.setPreferred("missing"))
        assertTrue(repository.getByDeviceId("peer-a")?.isPreferred == true)
    }

    @Test
    fun forgettingPreferredPeerClearsItsPreferenceWithTheRecord() = runBlocking {
        repository.saveConnectedPeer(record("peer-a"))
        repository.saveConnectedPeer(record("peer-b"))
        repository.setPreferred("peer-a")

        repository.forget("peer-a")

        assertNull(repository.getByDeviceId("peer-a"))
        assertFalse(repository.getAll().any(PairingRecord::isPreferred))
    }

    private fun record(deviceId: String, connectedAt: Long = 10L) = PairingRecord(
        remoteDeviceId = deviceId,
        remoteNickname = "Rider $deviceId",
        deviceName = "Phone $deviceId",
        localAlias = "",
        shortCode = "",
        pairedAt = 1L,
        lastConnectedAt = connectedAt,
        isPreferred = false,
        lastTransport = null,
        failureCount = 0
    )
}
