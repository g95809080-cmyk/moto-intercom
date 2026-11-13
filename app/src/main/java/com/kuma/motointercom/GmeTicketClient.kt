package com.kuma.motointercom

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

internal data class GmeTicket(
    val appId: Int,
    val rideId: String,
    val revision: Long,
    val roomId: String,
    val openId: String,
    val peerOpenId: String,
    val authBuffer: ByteArray,
    val expiresAtEpochSeconds: Long
) {
    override fun toString(): String = "GmeTicket(rideId=$rideId, revision=$revision, expiresAt=$expiresAtEpochSeconds)"
}

internal fun interface GmeTicketSource {
    suspend fun fetch(ride: ApprovedCloudRide): GmeTicket
}

internal class GmeTicketException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class GmeTicketClient(private val controlPlane: SignedControlPlaneTransport) : GmeTicketSource {
    override suspend fun fetch(ride: ApprovedCloudRide): GmeTicket =
        parseTicket(controlPlane.post("/v1/rides/${ride.rideId}/ticket", "{}"), ride)

    private fun parseTicket(payload: String, ride: ApprovedCloudRide): GmeTicket {
        try {
            val root = JsonParser.parseString(payload).asJsonObject
            if (root.has("error")) throw GmeTicketException("云端暂时无法签发进房票据")
            val ticket = GmeTicket(
                appId = root.get("appId").asInt,
                rideId = root.requiredString("rideId"),
                revision = root.get("revision").asLong,
                roomId = root.requiredString("roomId"),
                openId = root.requiredString("openId"),
                peerOpenId = root.requiredString("peerOpenId"),
                authBuffer = Base64.decode(root.requiredString("authBuffer"), Base64.DEFAULT),
                expiresAtEpochSeconds = root.get("expiresAt").asLong
            )
            val now = System.currentTimeMillis() / 1_000
            if (ticket.appId != 1400802321 || ticket.rideId != ride.rideId || ticket.roomId != ride.roomId ||
                ticket.revision < ride.revision || ticket.openId == ticket.peerOpenId ||
                !ticket.openId.matches(Regex("[A-Za-z0-9_-]{1,127}")) ||
                !ticket.peerOpenId.matches(Regex("[A-Za-z0-9_-]{1,127}")) ||
                ticket.authBuffer.isEmpty() || ticket.authBuffer.size > 1024 ||
                ticket.expiresAtEpochSeconds <= now + 30 || ticket.expiresAtEpochSeconds > now + 360) {
                throw GmeTicketException("云端进房票据无效或已过期")
            }
            return ticket
        } catch (error: GmeTicketException) {
            throw error
        } catch (_: Exception) {
            // Response bodies and decoder error details may contain authentication data.
            throw GmeTicketException("云端进房票据格式错误")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name).asString.takeIf { it.isNotBlank() } ?: throw GmeTicketException("云端进房票据字段缺失")
}
