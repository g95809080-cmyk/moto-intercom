package com.kuma.motointercom

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GmeTicketClientTest {
    private val now = System.currentTimeMillis()
    private fun approved() = ApprovedCloudRide.from(
        RideSession("ride_test", "pair_test", listOf("a", "b"), RideState.CLOUD_PENDING,
            "123456789", 4, false, CloudEntryReason.INVITE_ACCEPTED, mapOf("a" to now, "b" to now), cloudStartedAt = now),
        "a", "车友", now - 1, now
    )!!

    private fun payload() = JsonObject().apply {
        addProperty("appId", 1400802321)
        addProperty("rideId", "ride_test")
        addProperty("revision", 4)
        addProperty("roomId", "123456789")
        addProperty("openId", "10001")
        addProperty("peerOpenId", "10002")
        addProperty("authBuffer", "AQID")
        addProperty("expiresAt", now / 1_000 + 300)
    }

    @Test fun fetchUsesTheApprovedRideEndpointAndRedactsTicketString() = runBlocking {
        val client = GmeTicketClient(object : SignedControlPlaneTransport {
            override suspend fun get(path: String): String = error("Unused in ticket tests")
            override suspend fun post(path: String, body: String): String {
                assertEquals("/v1/rides/ride_test/ticket", path)
                assertEquals("{}", body)
                return payload().toString()
            }
        })
        val ticket = client.fetch(approved())
        assertArrayEquals(byteArrayOf(1, 2, 3), ticket.authBuffer)
        assertFalse(ticket.toString().contains("AQID"))
        assertFalse(ticket.toString().contains("[1, 2, 3]"))
    }

    @Test fun staleRevisionWrongRoomWrongRideAndExpiredTicketsAreRejected() {
        val invalid = listOf(
            payload().apply { addProperty("roomId", "other") },
            payload().apply { addProperty("rideId", "ride_other") },
            payload().apply { addProperty("revision", 3) },
            payload().apply { addProperty("expiresAt", now / 1_000 - 1) },
            payload().apply { addProperty("peerOpenId", "10001") }
        )
        invalid.forEach { response ->
            val client = GmeTicketClient(object : SignedControlPlaneTransport {
            override suspend fun get(path: String): String = error("Unused in ticket tests")
                override suspend fun post(path: String, body: String) = response.toString()
            })
            assertThrows(GmeTicketException::class.java) { runBlocking { client.fetch(approved()) } }
        }
    }

    @Test fun transportFailureCannotEchoAuthenticationDataIntoUiOrDiagnostics() {
        val states = mutableListOf<CloudIntercomState>()
        val logs = mutableListOf<String>()
        var closes = 0
        val session = GmeCloudSession(
            context = ApplicationProvider.getApplicationContext(),
            ticketSource = GmeTicketSource { throw java.io.IOException("private-test-auth-data") },
            onState = states::add,
            onClosed = { closes++ },
            onLog = logs::add
        )
        session.setMuted(true)
        session.start(approved())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertTrue(states.any { it is CloudIntercomState.Failed })
        assertEquals(1, closes)
        assertFalse((states.toString() + logs.toString()).contains("private-test-auth-data"))
    }
}
