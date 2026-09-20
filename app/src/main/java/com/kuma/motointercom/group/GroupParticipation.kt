package com.kuma.motointercom.group

internal data class GroupIntentToken(val room: GroupRoomKey, val runtimeId: String, val generation: Long)

/** Process-local value only. A newly created instance never restores an old room or audio intent. */
internal class GroupParticipation private constructor(
    private val runtimeId: String,
    val generation: Long,
    val token: GroupIntentToken?,
    val selfMuted: Boolean,
    private val blocked: Set<String>
) {
    constructor() : this(java.util.UUID.randomUUID().toString(), 0, null, false, emptySet())
    fun isCurrent(candidate: GroupIntentToken) = token != null && token == candidate
    fun isBlocked(deviceId: String) = deviceId in blocked

    fun begin(room: GroupRoomKey): GroupParticipation {
        check(token == null) { "leave current room before beginning another" }
        return GroupParticipation(runtimeId, generation + 1, GroupIntentToken(room, runtimeId, generation + 1), false, emptySet())
    }

    fun stop(candidate: GroupIntentToken): GroupParticipation = if (!isCurrent(candidate)) this else
        GroupParticipation(runtimeId, generation + 1, null, false, emptySet())

    fun muteSelf(candidate: GroupIntentToken, muted: Boolean): GroupParticipation =
        if (!isCurrent(candidate)) this else GroupParticipation(runtimeId, generation, token, muted, blocked)

    fun block(candidate: GroupIntentToken, deviceId: String, muted: Boolean): GroupParticipation {
        if (!isCurrent(candidate)) return this
        require(deviceId.isNotBlank())
        return GroupParticipation(runtimeId, generation, token, selfMuted, if (muted) blocked + deviceId else blocked - deviceId)
    }
}
