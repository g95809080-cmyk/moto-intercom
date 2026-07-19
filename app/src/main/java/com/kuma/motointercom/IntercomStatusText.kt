package com.kuma.motointercom

internal fun recoveryStatusText(peer: PeerIdentity): String {
    val rider = peer.nickname.ifBlank { peer.deviceName }.ifBlank { "原车友" }
    return "正在恢复与 $rider 的连接"
}

internal fun intercomStatusDetail(state: IntercomState): String = when (state) {
    IntercomState.Offline -> "已停止对讲，可重新启动"
    is IntercomState.Discovering -> "正在搜索附近 MotoCom 车友..."
    is IntercomState.IncomingConfirmation -> "等待确认陌生车友的连接请求"
    is IntercomState.Connecting -> "正在建立信令与媒体通道"
    is IntercomState.Optimizing -> "正在选择更稳定的连接通道"
    is IntercomState.Connected -> "语音通道在线，保持骑行沟通"
    is IntercomState.Recovering -> recoveryStatusText(state.peer)
    is IntercomState.Resetting -> "正在重置无线连接"
    is IntercomState.Stopping -> "正在停止对讲"
}

internal fun foregroundNotificationText(
    state: IntercomState,
    fallback: String
): String = when (state) {
    is IntercomState.Recovering,
    is IntercomState.Resetting -> intercomStatusDetail(state)
    else -> fallback
}
