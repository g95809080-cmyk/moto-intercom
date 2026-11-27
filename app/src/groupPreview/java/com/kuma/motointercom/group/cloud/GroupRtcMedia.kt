package com.kuma.motointercom.group.cloud

import android.content.Context
import com.kuma.motointercom.AudioRouteSelection
import java.io.Closeable

internal const val RTC_TOKEN_RETRY_MESSAGE = "语音凭证续签失败，正在自动重试"

internal sealed interface GroupRtcEvent {
    data object Joined : GroupRtcEvent
    data class JoinFailed(val code: Int, val message: String) : GroupRtcEvent
    data object TokenWillExpire : GroupRtcEvent
    data class TokenUpdated(val token: CloudRtcToken, val accepted: Boolean) : GroupRtcEvent
    data class ConnectionLost(val message: String) : GroupRtcEvent
    data class ActiveSpeakers(val userIds: Set<String>) : GroupRtcEvent
    data class Error(val message: String) : GroupRtcEvent
}

internal interface GroupRtcMedia : Closeable {
    fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection)
    fun updateToken(token: CloudRtcToken): Boolean
    fun setLocalMuted(muted: Boolean)
    fun setAllowedRemoteUsers(userIds: Set<String>)
    fun selectRoute(route: AudioRouteSelection)
}

internal fun GroupRtcMedia?.applyTokenUpdate(token: CloudRtcToken): GroupRtcEvent.TokenUpdated {
    val accepted = try {
        this?.updateToken(token) == true
    } catch (_: Exception) {
        false
    }
    return GroupRtcEvent.TokenUpdated(token, accepted)
}

internal fun interface GroupRtcMediaFactory {
    fun create(
        context: Context,
        onEvent: (GroupRtcEvent) -> Unit,
        onRouteLabel: (String) -> Unit
    ): GroupRtcMedia
}
