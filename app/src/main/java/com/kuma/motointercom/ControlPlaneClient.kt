package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ControlPlaneException(val status: Int, val code: String) : IOException(messageFor(code)) {
    companion object {
        private fun messageFor(code: String): String = when (code) {
            "PEER_OFFLINE" -> "这位车友当前不在线"
            "BUSY", "PEER_BUSY" -> "车友正在对讲，请稍后再邀请"
            "INVITE_EXPIRED", "INVITE_TIMEOUT" -> "邀请已过期"
            "RIDE_ENDED" -> "本次骑行已结束"
            "CLOUD_LIMIT" -> "本次云端时长已达上限，可手动延长"
            "PAIR_REVOKED", "PAIR_NOT_AUTHORIZED", "PAIR_KEY_MISMATCH", "DEVICE_KEY_MISMATCH" -> "云端配对已失效，请重新配对"
            "LOCAL_STATE_UNAVAILABLE" -> "云端本地记录无法读取"
            "WIFI_REQUIRED" -> "已设置仅 Wi-Fi，当前不能使用云端"
            "CONSENT_REQUIRED" -> "请先阅读并同意云端隐私说明"
            "SIGNATURE_EXPIRED" -> "手机时间与服务端不一致，请校准系统时间"
            else -> "云端服务暂时无法连接，请稍后重试"
        }
    }
}

internal class ControlPlaneClient(
    baseUrl: String,
    val deviceId: String,
    private val signer: DeviceSigner,
    private val client: OkHttpClient = cloudHttpClient(),
    private val allowed: () -> Boolean = { true },
    private val wifiAllowed: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    allowLoopbackForTests: Boolean = false
) : SignedControlPlaneTransport {
    private val base = baseUrl.toHttpUrl().also {
        require(it.encodedPath == "/" && it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty())
        require(it.isHttps || (allowLoopbackForTests && it.host in setOf("localhost", "127.0.0.1")))
        require(cloudId(deviceId))
    }

    override suspend fun post(path: String, body: String): String = request("POST", path, body)
    override suspend fun get(path: String): String = request("GET", path, "")

    suspend fun ownPublicKey(): String = withContext(Dispatchers.IO) { signer.publicKeyBase64() }

    private suspend fun request(method: String, path: String, body: String): String {
        require(path.matches(Regex("/v1/[A-Za-z0-9_/-]+")) && !path.contains("//") && !path.contains(".."))
        val cleanup = path.endsWith("/end") || path.endsWith("/revoke")
        if (!allowed() && !cleanup) throw ControlPlaneException(0, "CONSENT_REQUIRED")
        if (!wifiAllowed() && !cleanup) throw ControlPlaneException(0, "WIFI_REQUIRED")
        val request = withContext(Dispatchers.IO) {
            val timestamp = now().toString(); val nonce = UUID.randomUUID().toString()
            val signature = signer.sign(CloudCrypto.canonicalRequest(method, path, body, timestamp, nonce))
            Request.Builder().url(base.newBuilder().encodedPath(path).build())
                .header("X-Device-Id", deviceId).header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce).header("X-Signature", signature)
                .header("Accept", "application/json").header("Cache-Control", "no-store")
                .method(method, if (method == "POST") body.toRequestBody("application/json; charset=utf-8".toMediaType()) else null).build()
        }
        if (!allowed() && !cleanup) throw ControlPlaneException(0, "CONSENT_REQUIRED")
        if (!wifiAllowed() && !cleanup) throw ControlPlaneException(0, "WIFI_REQUIRED")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ControlPlaneException(0, "NETWORK_ERROR"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val source = it.body.source()
                            // Do not read/log an unbounded error body or keep credentials in exceptions.
                            if (source.request(65_537)) throw ControlPlaneException(it.code, "RESPONSE_TOO_LARGE")
                            val text = source.readUtf8()
                            if (!it.isSuccessful) {
                                val code = runCatching { JsonParser.parseString(text).asJsonObject["error"].asString }
                                    .getOrNull()?.takeIf { c -> c.matches(Regex("[A-Z_]{1,64}")) } ?: "HTTP_ERROR"
                                throw ControlPlaneException(it.code, code)
                            }
                            if (continuation.isActive) continuation.resume(text)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error as? ControlPlaneException ?: ControlPlaneException(0, "INVALID_RESPONSE"))
                        }
                    }
                }
            })
        }
    }
}

internal fun cloudHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

internal data class CloudInvitation(val inviteId: String, val rideId: String, val fromDeviceId: String, val toDeviceId: String, val expiresAt: Long)
internal data class CloudInvitePrompt(val invitation: CloudInvitation, val peerName: String, val actionNonce: String,
    val expiresAtLocal: Long = invitation.expiresAt)
internal data class CloudPeerPresence(val pairId: String, val deviceId: String, val online: Boolean)
internal data class CloudSnapshot(val activeRide: RideSession?, val invitations: List<CloudInvitation>, val peers: List<CloudPeerPresence>)
internal data class CloudSocketToken(val value: String, val expiresAt: Long) {
    override fun toString(): String = "CloudSocketToken(expiresAt=$expiresAt)"
}

internal interface CloudControlPlane {
    val deviceId: String
    suspend fun registerDevice()
    suspend fun registerPair(proof: PairingProof)
    suspend fun revokePair(pairId: String)
    suspend fun snapshot(): CloudSnapshot
    suspend fun startRide(pairId: String, requestId: String): RideSession
    suspend fun invite(rideId: String): CloudInvitation
    suspend fun respond(invite: CloudInvitation, response: String): RideSession
    suspend fun ride(rideId: String): RideSession
    suspend fun end(rideId: String)
    suspend fun heartbeat(rideId: String?): Boolean
    suspend fun socketToken(): CloudSocketToken
    suspend fun cloudStatus(rideId: String, connected: Boolean)
    suspend fun extend(rideId: String): RideSession
    suspend fun localEstablished(rideId: String)
    suspend fun fallback(rideId: String): RideSession
    suspend fun localProbe(rideId: String,probeId: String,ready: Boolean): RideSession = throw ControlPlaneException(0,"LOCAL_SWITCH_UNSUPPORTED")
    suspend fun localSwitch(rideId: String,switchId: String,probeId: String,stage: String): RideSession = throw ControlPlaneException(0,"LOCAL_SWITCH_UNSUPPORTED")
}

internal class HttpCloudControlPlane(private val transport: ControlPlaneClient, private val wallClock: () -> Long = System::currentTimeMillis) : CloudControlPlane {
    override val deviceId: String get() = transport.deviceId
    @Volatile private var offset = 0L
    fun serverNow(): Long = wallClock() + offset
    private fun json(vararg values: Pair<String, String>): String = JsonObject().apply { values.forEach { (k,v) -> addProperty(k,v) } }.toString()
    private fun objectOf(text: String): JsonObject = try { JsonParser.parseString(text).asJsonObject }
        catch (_: Exception) { throw ControlPlaneException(0, "INVALID_RESPONSE") }
    override suspend fun registerDevice() { transport.post("/v1/devices/register", json("publicKey" to transport.ownPublicKey())) }
    override suspend fun registerPair(proof: PairingProof) { transport.post("/v1/pairs/register", proof.json().toString()) }
    override suspend fun revokePair(pairId: String) { require(cloudId(pairId)); transport.post("/v1/pairs/$pairId/revoke", "{}") }
    override suspend fun snapshot(): CloudSnapshot {
        val r = objectOf(transport.get("/v1/state"))
        return CloudSnapshot(r["activeRide"]?.takeUnless { it.isJsonNull }?.let { CloudJson.ride(it.asJsonObject) },
            r.getAsJsonArray("invitations").map { CloudJson.invite(it.asJsonObject) },
            r.getAsJsonArray("peers").map { it.asJsonObject.let { p -> CloudPeerPresence(p["pairId"].asString, p["deviceId"].asString, p["online"].asBoolean) } })
    }
    override suspend fun startRide(pairId: String, requestId: String): RideSession = CloudJson.ride(objectOf(transport.post("/v1/rides/start", json("pairId" to pairId, "requestId" to requestId))))
    override suspend fun invite(rideId: String): CloudInvitation = CloudJson.invite(objectOf(command(rideId,"invite"))["invitation"].asJsonObject)
    override suspend fun respond(invite: CloudInvitation, response: String): RideSession = CloudJson.ride(objectOf(command(invite.rideId, "invite/respond", json("inviteId" to invite.inviteId, "response" to response))))
    override suspend fun ride(rideId: String): RideSession { require(cloudId(rideId)); return CloudJson.ride(objectOf(transport.get("/v1/rides/$rideId"))) }
    override suspend fun end(rideId: String) { command(rideId, "end") }
    override suspend fun heartbeat(rideId: String?): Boolean {
        val before = wallClock(); val r = objectOf(transport.post("/v1/heartbeat", if (rideId == null) "{}" else json("rideId" to rideId)))
        offset = r["serverTime"].asLong - (before + (wallClock() - before) / 2)
        return r["connectionAlive"].asBoolean
    }
    override suspend fun socketToken(): CloudSocketToken {
        val before = wallClock(); val r = objectOf(transport.post("/v1/ws-token", "{}"))
        val expires = r["expiresAt"].asLong; offset = expires - 60_000 - (before + (wallClock() - before) / 2)
        return CloudSocketToken(r["token"].asString.also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }, expires)
    }
    override suspend fun cloudStatus(rideId: String, connected: Boolean) { command(rideId,"cloud-status",json("status" to if (connected) "CONNECTED" else "FAILED")) }
    override suspend fun extend(rideId: String): RideSession = CloudJson.ride(objectOf(command(rideId,"extend")))
    override suspend fun localEstablished(rideId: String) { command(rideId,"local-established") }
    override suspend fun fallback(rideId: String): RideSession = CloudJson.ride(objectOf(command(rideId,"fallback",json("reason" to "TRANSPORT_FAILURE"))))
    override suspend fun localProbe(rideId: String,probeId: String,ready: Boolean): RideSession =
        CloudJson.ride(objectOf(command(rideId,"local-probe",JsonObject().apply {addProperty("probeId",probeId);addProperty("ready",ready)}.toString())))
    override suspend fun localSwitch(rideId: String,switchId: String,probeId: String,stage: String): RideSession =
        CloudJson.ride(objectOf(command(rideId,"local-switch",json("switchId" to switchId,"probeId" to probeId,"stage" to stage))))
    private suspend fun command(rideId: String, action: String, body: String = "{}"): String {
        require(cloudId(rideId)); return transport.post("/v1/rides/$rideId/$action", body)
    }
}

internal object CloudJson {
    fun ride(r: JsonObject): RideSession = try {
        val members = r.getAsJsonArray("members").map { it.asString }
        require(members.size == 2 && members.distinct().size == 2 && members.all(::cloudId))
        val heartbeats = r.getAsJsonObject("lastHeartbeatAt").entrySet().associate { it.key to it.value.asLong }
        require(heartbeats.keys == members.toSet())
        RideSession(r["rideId"].asString.also { require(cloudId(it)) }, r["pairId"].asString.also { require(cloudId(it)) },
            members, RideState.valueOf(r["state"].asString), r["roomId"].asString, r["revision"].asLong,
            r["everLocalActive"].asBoolean, r["cloudReason"]?.takeUnless { it.isJsonNull }?.let { CloudEntryReason.valueOf(it.asString) },
            heartbeats, r["cloudStartedAt"]?.takeUnless { it.isJsonNull }?.asLong, r["cloudUsedMs"].asLong, r["cloudAllowanceMs"].asLong,
            r["inviteId"]?.takeUnless { it.isJsonNull }?.asString, r["inviteExpiresAt"]?.takeUnless { it.isJsonNull }?.asLong,
            r["localSwitch"]?.takeUnless {it.isJsonNull}?.asJsonObject?.let { s ->
                val switchId=s["switchId"].asString; val probeId=s["probeId"].asString; val phase=s["phase"].asString
                val prepared=s.getAsJsonArray("prepared").map {it.asString}; val completed=s.getAsJsonArray("completed").map {it.asString}
                require(cloudId(switchId) && probeId.matches(Regex("[a-f0-9]{64}")) && phase in setOf("PREPARING","ARMED","COMMITTED","FAILED"))
                require(prepared.distinct()==prepared && completed.distinct()==completed && (prepared+completed).all {it in members})
                LocalSwitch(switchId,probeId,phase,prepared,completed,s["expiresAt"].asLong)
            },r["localSwitchAttempts"]?.asInt?.also {require(it in 0..3)} ?: 0)
    } catch (_: Exception) { throw ControlPlaneException(0,"INVALID_RESPONSE") }
    fun invite(r: JsonObject): CloudInvitation = try {
        CloudInvitation(r["inviteId"].asString, r["rideId"].asString, r["fromDeviceId"].asString, r["toDeviceId"].asString, r["expiresAt"].asLong)
            .also { require(listOf(it.inviteId,it.rideId,it.fromDeviceId,it.toDeviceId).all(::cloudId) && it.fromDeviceId != it.toDeviceId) }
    } catch (_: Exception) { throw ControlPlaneException(0,"INVALID_RESPONSE") }
}
