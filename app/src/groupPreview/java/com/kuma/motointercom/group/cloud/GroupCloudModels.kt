package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.group.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal sealed interface GroupCloudStartRequest {
    val serverUrl: String
    val mode: GroupMode

    data class Create(
        override val serverUrl: String,
        val requireApproval: Boolean = true,
        override val mode: GroupMode = GroupMode.NETWORK
    ) : GroupCloudStartRequest

    data class Join(
        override val serverUrl: String,
        val inviteCode: GroupCloudInviteCode,
        override val mode: GroupMode = GroupMode.NETWORK
    ) : GroupCloudStartRequest
}

internal class GroupCloudInviteCode(raw: String) {
    val value: String

    init {
        val cleaned = raw.trim().replace(Regex("[\\s-]+"), "")
        require(cleaned.length == LENGTH && cleaned.all { it in '0'..'9' }) { "邀请码格式无效" }
        value = cleaned
    }

    override fun toString() = "GroupCloudInviteCode(REDACTED)"
    override fun equals(other: Any?) = other is GroupCloudInviteCode && value == other.value
    override fun hashCode() = value.hashCode()

    companion object {
        const val LENGTH = 6
        fun isValid(raw: String): Boolean {
            val cleaned = raw.trim().replace(Regex("[\\s-]+"), "")
            return cleaned.length == LENGTH && cleaned.all { it in '0'..'9' }
        }
    }
}

internal object GroupCloudUrlPolicy {
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]", "10.0.2.2")

    fun normalizeAndValidate(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "请输入云端服务地址" }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("云端服务地址格式无效")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("云端服务地址缺少协议头（https://）")
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("云端服务地址缺少主机名")
        require(uri.userInfo == null) { "云端服务地址不能包含账号信息" }
        require(uri.query == null && uri.fragment == null) { "云端服务地址不能包含查询参数或锚点" }
        when (scheme) {
            "https" -> Unit
            "http" -> require(host in LOOPBACK_HOSTS) { "非本机调试地址必须使用 HTTPS" }
            else -> throw IllegalArgumentException("仅支持 https://（或本机调试 http://127.0.0.1）")
        }
        return trimmed
    }
}

internal data class CloudRtcToken(
    val appId: String,
    val rtcRoomId: String,
    val rtcUserId: String,
    val rtcToken: String,
    val expiresAtMs: Long
) {
    init {
        require(appId.isNotBlank() && rtcRoomId.isNotBlank() && rtcUserId.isNotBlank() && rtcToken.isNotBlank())
        require(expiresAtMs > 0)
    }

    fun isUsable(nowMs: Long): Boolean = expiresAtMs - nowMs >= MIN_VALIDITY_MS
    fun needsRefresh(nowMs: Long): Boolean = expiresAtMs - nowMs <= REFRESH_MARGIN_MS

    override fun toString() = "CloudRtcToken(room=${rtcRoomId.takeLast(8)}, user=${rtcUserId.takeLast(8)})"

    companion object {
        const val MIN_VALIDITY_MS = 5_000L
        const val REFRESH_MARGIN_MS = 30_000L
    }
}

internal enum class GroupHybridRecoveryStage {
    IDLE,
    PROBING,
    PRECONNECTING
}

internal data class GroupHybridTransportPayload(
    val active: GroupTransport,
    val epoch: Long,
    val reason: String,
    val localHostDeviceId: String,
    val localRoomInstanceId: String,
    val localCode: String?,
    val changedAtMs: Long,
    val cooldownUntilMs: Long,
    val localP2pSsid: String? = null,
    val localP2pPassphrase: String? = null,
    val localControlPort: Int = 8899,
    val localHostAddress: String? = null,
    val localFrequencyMhz: Int? = null,
    val recoveryStage: GroupHybridRecoveryStage = GroupHybridRecoveryStage.IDLE,
    val handoverFromEpoch: Long? = null
) {
    init {
        require(epoch > 0L)
        require(localRoomInstanceId.isNotBlank())
        require(localControlPort in 1..65535) { "无效的本地控制端口" }
        if (localCode != null) {
            require(localCode.length == 6 && localCode.all { it in '0'..'9' }) { "无效的本地认证码" }
        }
        if (localP2pSsid != null) {
            require(localP2pSsid.matches(Regex("^DIRECT-[0-9A-Za-z]{2}-.{1,22}$"))) { "无效的 P2P SSID" }
        }
        if (localP2pPassphrase != null) {
            require(localP2pPassphrase.length in 8..63 && localP2pPassphrase.all { it.code in 32..126 }) { "无效的 P2P 口令" }
        }
        if (localFrequencyMhz != null) {
            require(localFrequencyMhz in 2400..6000) { "无效的 P2P 频点" }
        }
        if (handoverFromEpoch != null) {
            require(handoverFromEpoch > 0L) { "无效的预连交接代际" }
        }
    }

    override fun toString() =
        "GroupHybridTransportPayload(active=$active, epoch=$epoch, stage=$recoveryStage, reason=$reason, host=${localHostDeviceId.takeLast(8)})"
}

internal class GroupCloudRoomView(
    override val key: GroupRoomKey,
    val revision: Long,
    override val ownerId: String,
    override val ended: Boolean,
    members: List<GroupMember>,
    override val maxMembers: Int = GroupRoomView.NETWORK_MAX_MEMBERS,
    override val mode: GroupMode = GroupMode.NETWORK,
    override val hostDeviceId: String? = null,
    override val riderIncludesFormation: Boolean = false,
    privateInvites: List<GroupPrivateInvite> = emptyList(),
    links: List<GroupLink> = emptyList()
) : GroupRoomView {
    private val memberValues = members.toList()
    private val inviteValues = privateInvites.toList()
    private val linkValues = links.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    override val members: List<GroupMember> get() = memberValues.toList()
    override val privateInvites: List<GroupPrivateInvite> get() = inviteValues.toList()
    override val links: List<GroupLink> get() = linkValues.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }

    init {
        require(revision > 0)
        require(key.instanceId.isNotBlank() && key.hostRuntimeId.isNotBlank())
        require(mode == GroupMode.NETWORK || mode == GroupMode.HYBRID)
        val modeCap = if (mode == GroupMode.HYBRID) GroupRoom.MAX_MEMBERS else GroupRoomView.NETWORK_MAX_MEMBERS
        require(maxMembers in 2..modeCap)
        require(memberValues.size <= maxMembers)
        require(memberValues.map { it.lease.deviceId }.distinct().size == memberValues.size)
        if (mode == GroupMode.NETWORK) {
            require(hostDeviceId == null)
            require(!riderIncludesFormation && inviteValues.isEmpty() && linkValues.isEmpty())
        }
        if (ended) {
            require(memberValues.isEmpty() && inviteValues.isEmpty() && linkValues.isEmpty())
        } else {
            require(ownerId.isNotBlank())
            val owners = memberValues.filter { it.role == GroupRole.OWNER }
            require(owners.size == 1 && owners.single().lease.deviceId == ownerId)
        }
        if (mode == GroupMode.HYBRID) {
            require(memberValues.count { it.role == GroupRole.HEAD } <= 1)
            require(memberValues.count { it.role == GroupRole.TAIL } <= 1)
        }
        memberValues.forEach { member ->
            require(member.lease.deviceId.isNotBlank() && member.lease.runtimeId.isNotBlank())
            require(member.lease.incarnation > 0 && member.lease.controlGeneration > 0)
            require(member.status != GroupMemberStatus.WAITING)
            if (mode == GroupMode.NETWORK) {
                require(member.channel == GroupChannel.Public)
                require(member.role == GroupRole.OWNER || member.role == GroupRole.MEMBER)
            } else if (member.channel == GroupChannel.Rider) {
                require(canEnterRider(member.role))
            }
            require((member.status == GroupMemberStatus.RESERVED) == (member.reservedUntilMs != null))
            require(member.status == GroupMemberStatus.ADMITTED || !member.audioAvailable)
            require((member.lease.deviceId == ownerId) == (member.role == GroupRole.OWNER))
        }
        if (mode == GroupMode.HYBRID) {
            memberValues.filter { it.channel is GroupChannel.Private }.groupBy { it.channel }.forEach { (_, group) ->
                require(group.size == 2)
            }
        }
    }

    fun withLocalLinks(
        newLinks: List<GroupLink>,
        localAudioAvailability: Map<String, Boolean> = emptyMap(),
        activeHostDeviceId: String? = hostDeviceId
    ): GroupCloudRoomView = GroupCloudRoomView(
        key = key,
        revision = revision,
        ownerId = ownerId,
        ended = ended,
        members = memberValues.map { member ->
            val overrideAudio = localAudioAvailability[member.lease.deviceId]
            if (overrideAudio != null && member.status == GroupMemberStatus.ADMITTED) {
                member.copy(audioAvailable = overrideAudio)
            } else member
        },
        maxMembers = maxMembers,
        mode = mode,
        hostDeviceId = if (ended) null else activeHostDeviceId,
        riderIncludesFormation = riderIncludesFormation,
        privateInvites = inviteValues,
        links = if (ended) emptyList() else newLinks
    )
}

internal data class GroupCloudSnapshotPayload(
    val view: GroupCloudRoomView,
    val names: Map<String, String>,
    val inviteCode: String?,
    val inviteExpiresAtMs: Long?,
    val requireApproval: Boolean,
    val pendingRequests: List<GroupJoinRequest>,
    val removedIds: Set<String>,
    val endedReason: String?,
    val rtcRoomId: String? = null,
    val hybridTransport: GroupHybridTransportPayload? = null
) {
    override fun toString() = "GroupCloudSnapshotPayload(rev=${view.revision}, ended=${view.ended}, members=${view.members.size})"
}

internal data class CloudAdmittedSession(
    val roomId: String,
    val sessionToken: String,
    val tokenExpiresAtMs: Long,
    val latestSeq: Long,
    val snapshot: GroupCloudSnapshotPayload,
    val rtc: CloudRtcToken?
) {
    override fun toString() = "CloudAdmittedSession(room=${roomId.takeLast(8)}, rev=${snapshot.view.revision})"
}

internal sealed interface CloudJoinSubmission {
    data class Admitted(val session: CloudAdmittedSession) : CloudJoinSubmission
    data class Pending(
        val roomId: String,
        val requestId: String,
        val requestSecret: String,
        val expiresAtMs: Long
    ) : CloudJoinSubmission {
        override fun toString() = "CloudJoinSubmission.Pending(req=${requestId.takeLast(8)})"
    }
}

internal sealed interface CloudJoinPollResult {
    data class Pending(val requestId: String, val expiresAtMs: Long) : CloudJoinPollResult
    data class Admitted(val session: CloudAdmittedSession) : CloudJoinPollResult
    data class Rejected(val code: String, val message: String) : CloudJoinPollResult
    data class Ended(val code: String, val message: String) : CloudJoinPollResult
    data class Expired(val message: String) : CloudJoinPollResult
}

internal sealed interface GroupCloudEvent {
    val seq: Long
    val revision: Long

    data class Snapshot(
        override val seq: Long,
        override val revision: Long,
        val payload: GroupCloudSnapshotPayload
    ) : GroupCloudEvent

    data class Terminal(
        override val seq: Long,
        override val revision: Long,
        val reason: String,
        val message: String
    ) : GroupCloudEvent
}

internal data class CloudEventBatch(
    val resync: Boolean,
    val latestSeq: Long,
    val snapshot: GroupCloudSnapshotPayload?,
    val events: List<GroupCloudEvent>
)

internal data class CloudResumeResult(
    val resumed: Boolean,
    val latestSeq: Long,
    val snapshot: GroupCloudSnapshotPayload,
    val rtc: CloudRtcToken?
)

internal data class CloudTransferResult(
    val left: Boolean,
    val snapshot: GroupCloudSnapshotPayload?
)

internal data class CloudChannelActionResult(
    val snapshot: GroupCloudSnapshotPayload,
    val rtc: CloudRtcToken?
)

internal data class CloudPrivateRequestResult(
    val inviteId: String,
    val snapshot: GroupCloudSnapshotPayload
)

internal data class CloudTransportSwitchResult(
    val switched: Boolean,
    val snapshot: GroupCloudSnapshotPayload,
    val rtc: CloudRtcToken?
)

internal data class CloudLocalStatusResult(
    val switched: Boolean,
    val snapshot: GroupCloudSnapshotPayload
)

internal object GroupCloudCodec {
    fun parseAdmittedSession(obj: JSONObject): CloudAdmittedSession {
        val snapshot = parseSnapshot(obj.getJSONObject("snapshot"))
        val rtc = parseOptionalRtcToken(obj)
        if (snapshot.view.mode == GroupMode.NETWORK) {
            requireNotNull(rtc) { "网络模式响应缺少 RTC 凭证" }
        }
        return CloudAdmittedSession(
            roomId = obj.getString("roomId"),
            sessionToken = obj.getString("sessionToken"),
            tokenExpiresAtMs = obj.getLong("tokenExpiresAtMs"),
            latestSeq = obj.optLong("latestSeq", 0L),
            snapshot = snapshot,
            rtc = rtc
        )
    }

    fun parseJoinSubmission(obj: JSONObject): CloudJoinSubmission = when (val status = obj.getString("status")) {
        "ADMITTED" -> CloudJoinSubmission.Admitted(parseAdmittedSession(obj))
        "PENDING" -> CloudJoinSubmission.Pending(
            roomId = obj.getString("roomId"),
            requestId = obj.getString("requestId"),
            requestSecret = obj.getString("requestSecret"),
            expiresAtMs = obj.getLong("expiresAtMs")
        )
        else -> throw IllegalArgumentException("未知加入状态: $status")
    }

    fun parseJoinPollResult(obj: JSONObject): CloudJoinPollResult = when (val status = obj.getString("status")) {
        "PENDING" -> CloudJoinPollResult.Pending(obj.getString("requestId"), obj.getLong("expiresAtMs"))
        "ADMITTED" -> CloudJoinPollResult.Admitted(parseAdmittedSession(obj))
        "REJECTED" -> CloudJoinPollResult.Rejected(
            obj.optString("code", "REJECTED"),
            obj.optString("message", "群主已拒绝加入申请")
        )
        "ENDED" -> CloudJoinPollResult.Ended(
            obj.optString("code", "ROOM_ENDED"),
            obj.optString("message", "网络房间已结束")
        )
        "EXPIRED" -> CloudJoinPollResult.Expired(obj.optString("message", "加入申请已超时，请重新申请"))
        "CANCELLED" -> CloudJoinPollResult.Ended("CANCELLED", "已取消加入申请")
        else -> throw IllegalArgumentException("未知审批状态: $status")
    }

    fun parseRtcToken(obj: JSONObject): CloudRtcToken = CloudRtcToken(
        appId = obj.getString("appId"),
        rtcRoomId = obj.getString("rtcRoomId"),
        rtcUserId = obj.getString("rtcUserId"),
        rtcToken = obj.getString("rtcToken"),
        expiresAtMs = obj.getLong("expiresAtMs")
    )

    fun parseOptionalRtcToken(obj: JSONObject): CloudRtcToken? =
        if (obj.has("rtc") && !obj.isNull("rtc")) parseRtcToken(obj.getJSONObject("rtc")) else null

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").ifBlank { null }

    fun parseSnapshot(obj: JSONObject): GroupCloudSnapshotPayload {
        val mode = when (obj.getString("mode")) {
            "NETWORK" -> GroupMode.NETWORK
            "HYBRID" -> GroupMode.HYBRID
            else -> throw IllegalArgumentException("不支持的云端房间模式")
        }
        val roomId = obj.getString("roomId")
        val roomEpoch = obj.getString("roomEpoch")
        val rtcRoomId = obj.optNullableString("rtcRoomId")
        val revision = obj.getLong("revision")
        val ownerId = obj.optString("ownerId", "")
        val hostDeviceId = obj.optNullableString("hostDeviceId")
        val ended = obj.getBoolean("ended")
        val endedReason = obj.optNullableString("endedReason")
        val requireApproval = obj.optBoolean("requireApproval", true)
        val defaultCap = if (mode == GroupMode.HYBRID) GroupRoom.MAX_MEMBERS else GroupRoomView.NETWORK_MAX_MEMBERS
        val maxMembers = obj.optInt("maxMembers", defaultCap)
        val riderIncludesFormation = if (mode == GroupMode.HYBRID) obj.optBoolean("riderIncludesFormation", true) else false

        val names = mutableMapOf<String, String>()
        val membersJson = obj.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (i in 0 until membersJson.length()) {
                val item = membersJson.getJSONObject(i)
                val deviceId = item.getString("deviceId")
                val runtimeId = item.getString("runtimeId")
                val nickname = item.optString("nickname", "").ifBlank { "骑士" }
                require(nickname.toByteArray(Charsets.UTF_8).size <= 64)
                names[deviceId] = nickname
                val role = when (item.getString("role")) {
                    "OWNER" -> GroupRole.OWNER
                    "RIDING_OFFICER" -> {
                        require(mode == GroupMode.HYBRID) { "网络模式不支持的角色" }
                        GroupRole.RIDING_OFFICER
                    }
                    "HEAD" -> {
                        require(mode == GroupMode.HYBRID) { "网络模式不支持的角色" }
                        GroupRole.HEAD
                    }
                    "TAIL" -> {
                        require(mode == GroupMode.HYBRID) { "网络模式不支持的角色" }
                        GroupRole.TAIL
                    }
                    "MEMBER" -> GroupRole.MEMBER
                    else -> throw IllegalArgumentException("网络模式不支持的角色")
                }
                val channel = parseChannel(item.optString("channel", "PUBLIC"), mode)
                val status = when (item.getString("status")) {
                    "ADMITTED" -> GroupMemberStatus.ADMITTED
                    "RESERVED" -> GroupMemberStatus.RESERVED
                    else -> throw IllegalArgumentException("未知成员状态")
                }
                val reservedUntil = if (item.has("reservedUntilMs") && !item.isNull("reservedUntilMs")) {
                    item.getLong("reservedUntilMs")
                } else null
                add(
                    GroupMember(
                        lease = GroupMemberLease(
                            deviceId = deviceId,
                            runtimeId = runtimeId,
                            incarnation = item.getLong("incarnation"),
                            controlGeneration = item.getLong("controlGeneration")
                        ),
                        status = status,
                        role = role,
                        channel = channel,
                        channelRevision = item.optLong("channelRevision", 1L).coerceAtLeast(1L),
                        audioAvailable = item.optBoolean("audioAvailable", false),
                        reservedUntilMs = reservedUntil,
                        rtcUserId = item.optString("rtcUserId", deviceId),
                        supportsFastP2p = item.optBoolean("supportsFastP2p", false)
                    )
                )
            }
        }

        val privateInvitesJson = obj.optJSONArray("privateInvites") ?: JSONArray()
        val privateInvites = if (mode == GroupMode.HYBRID && !ended) {
            buildList {
                for (i in 0 until privateInvitesJson.length()) {
                    val item = privateInvitesJson.getJSONObject(i)
                    add(
                        GroupPrivateInvite(
                            inviteId = item.getString("inviteId"),
                            callerId = item.getString("callerId"),
                            calleeId = item.getString("calleeId"),
                            expiresAtMs = item.getLong("expiresAtMs")
                        )
                    )
                }
            }
        } else emptyList()

        val transportObj = if (mode == GroupMode.HYBRID && obj.has("transport") && !obj.isNull("transport")) {
            obj.getJSONObject("transport")
        } else null
        val hybridTransport = transportObj?.let { transport ->
            val active = when (transport.getString("active")) {
                "LOCAL" -> GroupTransport.LOCAL
                "CLOUD" -> GroupTransport.CLOUD
                else -> throw IllegalArgumentException("未知双模传输状态")
            }
            val localCode = if (transport.has("localCode") && !transport.isNull("localCode")) {
                transport.optString("localCode", "").ifBlank { null }
            } else null
            val localP2pSsid = if (transport.has("localP2pSsid") && !transport.isNull("localP2pSsid")) {
                transport.optString("localP2pSsid", "").ifBlank { null }
            } else null
            val localP2pPassphrase = if (transport.has("localP2pPassphrase") && !transport.isNull("localP2pPassphrase")) {
                transport.optString("localP2pPassphrase", "").ifBlank { null }
            } else null
            val localHostAddress = if (transport.has("localHostAddress") && !transport.isNull("localHostAddress")) {
                transport.optString("localHostAddress", "").ifBlank { null }
            } else null
            val localFrequencyMhz = if (transport.has("localFrequencyMhz") && !transport.isNull("localFrequencyMhz")) {
                transport.optInt("localFrequencyMhz", 0).takeIf { it in 2400..6000 }
            } else null
            val recoveryStage = when (transport.optString("recoveryStage", "IDLE")) {
                "PROBING" -> GroupHybridRecoveryStage.PROBING
                "PRECONNECTING" -> GroupHybridRecoveryStage.PRECONNECTING
                else -> GroupHybridRecoveryStage.IDLE
            }
            val handoverFromEpoch = if (transport.has("handoverFromEpoch") && !transport.isNull("handoverFromEpoch")) {
                transport.optLong("handoverFromEpoch", 0L).takeIf { it > 0L }
            } else null
            GroupHybridTransportPayload(
                active = active,
                epoch = transport.getLong("epoch"),
                reason = transport.optString("reason", ""),
                localHostDeviceId = transport.optString("localHostDeviceId", ""),
                localRoomInstanceId = transport.getString("localRoomInstanceId"),
                localCode = localCode,
                changedAtMs = transport.optLong("changedAtMs", 0L),
                cooldownUntilMs = transport.optLong("cooldownUntilMs", 0L),
                localP2pSsid = localP2pSsid,
                localP2pPassphrase = localP2pPassphrase,
                localControlPort = transport.optInt("localControlPort", 8899).takeIf { it in 1..65535 } ?: 8899,
                localHostAddress = localHostAddress,
                localFrequencyMhz = localFrequencyMhz,
                recoveryStage = recoveryStage,
                handoverFromEpoch = handoverFromEpoch
            )
        }
        if (mode == GroupMode.HYBRID) {
            require(hybridTransport != null) { "双模快照缺少 transport 状态" }
        }

        val pendingJson = obj.optJSONArray("pendingRequests") ?: JSONArray()
        val pendingRequests = buildList {
            for (i in 0 until pendingJson.length()) {
                val item = pendingJson.getJSONObject(i)
                add(
                    GroupJoinRequest(
                        requestId = item.getString("requestId"),
                        deviceId = item.getString("deviceId"),
                        nickname = item.optString("nickname", "骑士").ifBlank { "骑士" },
                        expiresAtMs = item.getLong("expiresAtMs")
                    )
                )
            }
        }

        val removedJson = obj.optJSONArray("removed") ?: JSONArray()
        val removedIds = mutableSetOf<String>()
        for (i in 0 until removedJson.length()) {
            val item = removedJson.getJSONObject(i)
            val id = item.getString("deviceId")
            removedIds += id
            if (id !in names) {
                names[id] = item.optString("nickname", "骑士").ifBlank { "骑士" }
            }
        }

        val inviteObj = if (obj.has("invite") && !obj.isNull("invite")) obj.getJSONObject("invite") else null
        val inviteCode = inviteObj?.optString("code", "")?.ifBlank { null }
        val inviteExpiresAtMs = inviteObj?.optLong("expiresAtMs", 0L)?.takeIf { it > 0L }

        val view = GroupCloudRoomView(
            key = GroupRoomKey(roomId, roomEpoch),
            revision = revision,
            ownerId = ownerId,
            ended = ended,
            members = members,
            maxMembers = maxMembers,
            mode = mode,
            hostDeviceId = if (mode == GroupMode.HYBRID && !ended) hostDeviceId else null,
            riderIncludesFormation = riderIncludesFormation,
            privateInvites = privateInvites
        )
        return GroupCloudSnapshotPayload(
            view = view,
            names = names.toMap(),
            inviteCode = inviteCode,
            inviteExpiresAtMs = inviteExpiresAtMs,
            requireApproval = requireApproval,
            pendingRequests = pendingRequests,
            removedIds = removedIds.toSet(),
            endedReason = endedReason,
            rtcRoomId = rtcRoomId,
            hybridTransport = hybridTransport
        )
    }

    private fun parseChannel(raw: String, mode: GroupMode): GroupChannel = when {
        raw == "PUBLIC" -> GroupChannel.Public
        raw == "RIDER" -> {
            require(mode == GroupMode.HYBRID) { "网络模式不支持领队频道" }
            GroupChannel.Rider
        }
        raw.startsWith("PRIVATE:") -> {
            require(mode == GroupMode.HYBRID) { "网络模式不支持私聊频道" }
            GroupChannel.Private(raw.removePrefix("PRIVATE:"))
        }
        else -> throw IllegalArgumentException("未知频道类型")
    }

    fun parseEventBatch(obj: JSONObject): CloudEventBatch {
        val resync = obj.optBoolean("resync", false)
        val latestSeq = obj.getLong("latestSeq")
        val snapshot = if (obj.has("snapshot") && !obj.isNull("snapshot")) {
            parseSnapshot(obj.getJSONObject("snapshot"))
        } else null
        val arr = obj.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val seq = item.getLong("seq")
                val revision = item.optLong("revision", 0L)
                when (item.getString("type")) {
                    "SNAPSHOT" -> add(GroupCloudEvent.Snapshot(seq, revision, parseSnapshot(item.getJSONObject("snapshot"))))
                    "TERMINAL" -> add(
                        GroupCloudEvent.Terminal(
                            seq = seq,
                            revision = revision,
                            reason = item.getString("reason"),
                            message = item.optString("message", "网络房间已结束")
                        )
                    )
                }
            }
        }
        return CloudEventBatch(resync = resync, latestSeq = latestSeq, snapshot = snapshot, events = events)
    }
}
