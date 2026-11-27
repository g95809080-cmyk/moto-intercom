package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.group.GroupChannel
import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.GroupRole
import com.kuma.motointercom.group.GroupTransport
import com.kuma.motointercom.group.network.supportsGroupWifiP2pPreset
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** Zero-dependency HTTPS/TLS 1.2+ (or loopback dev HTTP) client for the R4 cloud control plane. */
internal class HttpGroupCloudClient(
    rawBaseUrl: String,
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val connectTimeoutMs: Int = 8_000,
    private val shortReadTimeoutMs: Int = 10_000,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val entitlementTokenProvider: () -> String? = { null },
    private val deviceIdProvider: () -> String? = { null }
) : GroupCloudApi {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }
    private val baseUrl = GroupCloudUrlPolicy.normalizeConfiguredUrl(rawBaseUrl)
    private val closed = AtomicBoolean(false)
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    override fun createRoom(
        deviceId: String,
        runtimeId: String,
        nickname: String,
        requireApproval: Boolean
    ): CloudAdmittedSession {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("runtimeId", runtimeId)
            put("nickname", nickname)
            put("requireApproval", requireApproval)
        }
        return GroupCloudCodec.parseAdmittedSession(request("POST", "/v1/rooms", null, body))
    }

    override fun createHybridRoom(
        deviceId: String,
        runtimeId: String,
        nickname: String,
        requireApproval: Boolean
    ): CloudAdmittedSession {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("runtimeId", runtimeId)
            put("nickname", nickname)
            put("requireApproval", requireApproval)
            put("mode", "HYBRID")
            put("supportsFastP2p", supportsGroupWifiP2pPreset())
        }
        return GroupCloudCodec.parseAdmittedSession(request("POST", "/v1/rooms", null, body))
    }

    override fun submitJoinRequest(
        inviteCode: GroupCloudInviteCode,
        deviceId: String,
        runtimeId: String,
        nickname: String
    ): CloudJoinSubmission = submitJoinRequest(inviteCode, deviceId, runtimeId, nickname, GroupJoinReceipt.create())

    override fun submitJoinRequest(
        inviteCode: GroupCloudInviteCode,
        deviceId: String,
        runtimeId: String,
        nickname: String,
        receipt: GroupJoinReceipt
    ): CloudJoinSubmission {
        val body = JSONObject().apply {
            put("joinSecret", receipt.secret)
            put("inviteCode", inviteCode.value)
            put("supportsFastP2p", supportsGroupWifiP2pPreset())
            put("deviceId", deviceId)
            put("runtimeId", runtimeId)
            put("nickname", nickname)
        }
        return GroupCloudCodec.parseJoinSubmission(request("POST", "/v1/rooms/join-requests", null, body))
    }

    override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int): CloudJoinPollResult {
        val path = "/v1/join-requests/${encode(requestId)}?waitMs=${waitMs.coerceIn(0, 20_000)}"
        return GroupCloudCodec.parseJoinPollResult(
            request("GET", path, requestSecret, null, readTimeoutMs = waitMs.coerceAtLeast(0) + shortReadTimeoutMs)
        )
    }

    override fun cancelJoinRequest(requestId: String, requestSecret: String) {
        request("DELETE", "/v1/join-requests/${encode(requestId)}", requestSecret, null)
    }

    override fun approveJoinRequest(
        token: String,
        roomId: String,
        requestId: String,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/join-requests/${encode(requestId)}/approve", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun rejectJoinRequest(
        token: String,
        roomId: String,
        requestId: String,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/join-requests/${encode(requestId)}/reject", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun rotateInvite(token: String, roomId: String, expectedRevision: Long): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/invites", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun setRequireApproval(
        token: String,
        roomId: String,
        requireApproval: Boolean,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject()
            .put("requireApproval", requireApproval)
            .put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/approval-policy", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun transferOwner(
        token: String,
        roomId: String,
        targetDeviceId: String,
        alsoLeave: Boolean,
        expectedRevision: Long
    ): CloudTransferResult {
        val body = JSONObject()
            .put("targetDeviceId", targetDeviceId)
            .put("alsoLeave", alsoLeave)
            .put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/owner-transfer", token, body)
        val snap = if (res.has("snapshot") && !res.isNull("snapshot")) {
            GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
        } else null
        return CloudTransferResult(left = res.optBoolean("left", false), snapshot = snap)
    }

    override fun removeMember(
        token: String,
        roomId: String,
        targetDeviceId: String,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/members/${encode(targetDeviceId)}/remove", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun unblockMember(
        token: String,
        roomId: String,
        targetDeviceId: String,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/unblock/${encode(targetDeviceId)}", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun leaveRoom(token: String, roomId: String): Boolean {
        val res = request("POST", "/v1/rooms/${encode(roomId)}/leave", token, JSONObject())
        return res.optBoolean("ended", false)
    }

    override fun endRoom(token: String, roomId: String, expectedRevision: Long) {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        request("POST", "/v1/rooms/${encode(roomId)}/end", token, body)
    }

    override fun resumeMember(token: String, roomId: String): CloudResumeResult {
        val res = request("POST", "/v1/rooms/${encode(roomId)}/resume", token, JSONObject())
        val snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
        val rtc = GroupCloudCodec.parseOptionalRtcToken(res)
        if (snapshot.view.mode == GroupMode.NETWORK) {
            requireNotNull(rtc) { "网络模式恢复响应缺少 RTC 凭证" }
        }
        return CloudResumeResult(
            resumed = res.optBoolean("resumed", false),
            latestSeq = res.optLong("latestSeq", 0L),
            snapshot = snapshot,
            rtc = rtc
        )
    }

    override fun refreshRtcToken(token: String, roomId: String): CloudRtcToken {
        val res = request("POST", "/v1/rooms/${encode(roomId)}/rtc-token", token, JSONObject())
        return GroupCloudCodec.parseRtcToken(res.getJSONObject("rtc"))
    }

    override fun updateAudioAvailability(token: String, roomId: String, available: Boolean): GroupCloudSnapshotPayload {
        val res = request("POST", "/v1/rooms/${encode(roomId)}/audio", token, JSONObject().put("available", available))
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
        val path = "/v1/rooms/${encode(roomId)}/events?after=${afterSeq.coerceAtLeast(0L)}&waitMs=${waitMs.coerceIn(0, 20_000)}"
        return GroupCloudCodec.parseEventBatch(
            request("GET", path, token, null, readTimeoutMs = waitMs.coerceAtLeast(0) + shortReadTimeoutMs)
        )
    }

    override fun ackEvents(token: String, roomId: String, sequence: Long) {
        request("POST", "/v1/rooms/${encode(roomId)}/ack", token, JSONObject().put("sequence", sequence))
    }

    override fun redeemActivationCode(
        code: String,
        deviceId: String,
        appVersion: String
    ): GroupEntitlementRedeemResult {
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .put("appVersion", appVersion)
        val res = request("POST", "/v1/entitlements/redeem", null, body)
        return GroupCloudCodec.parseEntitlementRedeemResult(res)
    }

    override fun fetchEntitlementStatus(): GroupEntitlementSummary {
        val res = request("GET", "/v1/entitlements/me", null, null)
        return GroupCloudCodec.parseEntitlementSummary(res.getJSONObject("entitlement"))
    }

    override fun setRole(
        token: String,
        roomId: String,
        targetDeviceId: String,
        role: GroupRole,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject()
            .put("targetDeviceId", targetDeviceId)
            .put("role", role.name)
            .put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/roles", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun setRiderIncludesFormation(
        token: String,
        roomId: String,
        included: Boolean,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject()
            .put("riderIncludesFormation", included)
            .put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/rider-policy", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun switchChannel(
        token: String,
        roomId: String,
        channel: GroupChannel,
        expectedRevision: Long
    ): CloudChannelActionResult {
        val label = when (channel) {
            GroupChannel.Public -> "PUBLIC"
            GroupChannel.Rider -> "RIDER"
            is GroupChannel.Private -> throw IllegalArgumentException("私聊必须通过邀请建立")
        }
        val body = JSONObject()
            .put("channel", label)
            .put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/channel", token, body)
        return CloudChannelActionResult(
            snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot")),
            rtc = GroupCloudCodec.parseOptionalRtcToken(res)
        )
    }

    override fun requestPrivate(
        token: String,
        roomId: String,
        targetDeviceId: String,
        expectedRevision: Long
    ): CloudPrivateRequestResult {
        val body = JSONObject()
            .put("targetDeviceId", targetDeviceId)
            .put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/private-invites", token, body)
        return CloudPrivateRequestResult(
            inviteId = res.getString("inviteId"),
            snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
        )
    }

    override fun acceptPrivate(
        token: String,
        roomId: String,
        inviteId: String,
        expectedRevision: Long
    ): CloudChannelActionResult {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/private-invites/${encode(inviteId)}/accept", token, body)
        return CloudChannelActionResult(
            snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot")),
            rtc = GroupCloudCodec.parseOptionalRtcToken(res)
        )
    }

    override fun declinePrivate(
        token: String,
        roomId: String,
        inviteId: String,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/private-invites/${encode(inviteId)}/decline", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun cancelPrivate(
        token: String,
        roomId: String,
        inviteId: String,
        expectedRevision: Long
    ): GroupCloudSnapshotPayload {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/private-invites/${encode(inviteId)}/cancel", token, body)
        return GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
    }

    override fun closePrivate(
        token: String,
        roomId: String,
        expectedRevision: Long
    ): CloudChannelActionResult {
        val body = JSONObject().put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/private/close", token, body)
        return CloudChannelActionResult(
            snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot")),
            rtc = GroupCloudCodec.parseOptionalRtcToken(res)
        )
    }

    override fun switchTransport(
        token: String,
        roomId: String,
        target: GroupTransport,
        reason: String,
        expectedRevision: Long?,
        expectedTransportEpoch: Long
    ): CloudTransportSwitchResult {
        val body = JSONObject()
            .put("targetTransport", target.name)
            .put("reason", reason)
            .put("expectedTransportEpoch", expectedTransportEpoch)
        if (expectedRevision != null) body.put("expectedRevision", expectedRevision)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/transport/switch", token, body)
        return CloudTransportSwitchResult(
            switched = res.optBoolean("switched", false),
            snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot")),
            rtc = GroupCloudCodec.parseOptionalRtcToken(res)
        )
    }

    override fun reportLocalStatus(
        token: String,
        roomId: String,
        transportEpoch: Long,
        localReady: Boolean,
        localReachable: Boolean,
        localProbeActive: Boolean,
        localRssiDbm: Int?,
        localControlReady: Boolean,
        localHostAddress: String?,
        localFrequencyMhz: Int?,
        preconnectFailed: Boolean
    ): CloudLocalStatusResult {
        val body = JSONObject()
            .put("transportEpoch", transportEpoch)
            .put("localReady", localReady)
            .put("localReachable", localReachable)
            .put("localProbeActive", localProbeActive)
            .put("localControlReady", localControlReady)
            .put("preconnectFailed", preconnectFailed)
        if (localRssiDbm != null) body.put("localRssiDbm", localRssiDbm)
        if (!localHostAddress.isNullOrBlank()) body.put("localHostAddress", localHostAddress)
        if (localFrequencyMhz != null) body.put("localFrequencyMhz", localFrequencyMhz)
        val res = request("POST", "/v1/rooms/${encode(roomId)}/transport/local-status", token, body)
        return CloudLocalStatusResult(
            switched = res.optBoolean("switched", false),
            snapshot = GroupCloudCodec.parseSnapshot(res.getJSONObject("snapshot"))
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = synchronized(activeConnections) { activeConnections.toList() }
        snapshot.forEach { conn -> runCatching { conn.disconnect() } }
        activeConnections.clear()
    }

    private fun request(
        method: String,
        path: String,
        bearerToken: String?,
        body: JSONObject?,
        readTimeoutMs: Int = shortReadTimeoutMs
    ): JSONObject {
        if (closed.get()) throw GroupCloudException(0, "CLIENT_CLOSED", "网络客户端已关闭")
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            val entitlementToken = entitlementTokenProvider()?.trim()
            if (!entitlementToken.isNullOrBlank()) {
                setRequestProperty("X-Moto-Entitlement", entitlementToken)
            }
            val deviceId = deviceIdProvider()?.trim()
            if (!deviceId.isNullOrBlank()) {
                setRequestProperty("X-Moto-Device-Id", deviceId)
            }
            if (this is HttpsURLConnection && sslSocketFactory != null) {
                this.sslSocketFactory = sslSocketFactory
            }
        }
        activeConnections += connection
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Content-Length", bytes.size.toString())
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val isSuccess = status in 200..299
            val bodyLimit = if (isSuccess) maxResponseBytes else minOf(maxResponseBytes, MAX_ERROR_RESPONSE_BYTES)
            val declaredLength = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
            if (declaredLength != null && declaredLength > bodyLimit) {
                throw GroupCloudException(0, "RESPONSE_TOO_LARGE", "服务端响应过大，请检查服务地址")
            }
            val stream = if (isSuccess) connection.inputStream else connection.errorStream
            val rawText = stream?.use { readBoundedUtf8(it, bodyLimit) }.orEmpty()
            val parsed = if (rawText.isNotBlank()) runCatching { JSONObject(rawText) }.getOrNull() else null
            if (status in 200..299) {
                return parsed ?: JSONObject()
            }
            val code = parsed?.optString("code", "HTTP_$status").orEmpty().ifBlank { "HTTP_$status" }
            val message = parsed?.optString("message", defaultErrorMessage(status, code)).orEmpty()
                .ifBlank { defaultErrorMessage(status, code) }
            val endedReason = parsed?.optString("endedReason", "")?.ifBlank { null }
            throw GroupCloudException(status, code, message, endedReason)
        } catch (error: GroupCloudException) {
            throw error
        } catch (error: IOException) {
            if (closed.get()) throw GroupCloudException(0, "CLIENT_CLOSED", "网络客户端已关闭")
            throw GroupCloudException(0, "NETWORK_ERROR", "网络连接中断，请检查网络或服务地址")
        } finally {
            activeConnections.remove(connection)
            runCatching { connection.disconnect() }
        }
    }

    private fun defaultErrorMessage(status: Int, code: String): String = when (code) {
        "INVALID_INVITE" -> "邀请码无效或已过期"
        "ROOM_FULL" -> "房间已满（最多 50 人）"
        "REMOVED" -> "你已被移出该房间，需群主解除限制"
        "OWNER_TRANSFER_REQUIRED" -> "离开前必须先转让群主或解散全队"
        "REVISION_CONFLICT" -> "房间状态已更新，请重试"
        "ROOM_ENDED" -> "网络房间已结束"
        "UNAUTHORIZED" -> "会话已失效，请重新加入"
        "TRANSPORT_CONFLICT" -> "传输状态已更新，请重试"
        "UNSUPPORTED_IN_MODE" -> "当前模式不支持该操作"
        "ENTITLEMENT_REQUIRED" -> "请先输入激活码开通云端对讲权益"
        "ENTITLEMENT_INVALID", "ENTITLEMENT_REVOKED" -> "激活凭证已失效，请重新输入激活码"
        "ENTITLEMENT_DEVICE_MISMATCH" -> "激活凭证与当前设备不匹配，请重新输入激活码"
        "QUOTA_EXHAUSTED", "ENTITLEMENT_EXPIRED" -> "云端对讲额度已用尽或过期，请续码或改用本地模式"
        "GLOBAL_QUOTA_EXHAUSTED" -> "本月云端测试总免费额度已用尽，请使用本地直连模式"
        else -> if (status in 500..599) "云端服务暂时不可用，请稍后重试" else "云端请求失败（$status）"
    }

    private fun readBoundedUtf8(stream: InputStream, limitBytes: Int): String {
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limitBytes) {
                throw GroupCloudException(0, "RESPONSE_TOO_LARGE", "服务端响应过大，请检查服务地址")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    companion object {
        private const val DEFAULT_MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_ERROR_RESPONSE_BYTES = 64 * 1024
    }
}
