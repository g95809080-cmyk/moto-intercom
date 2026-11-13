package com.kuma.motointercom

internal sealed interface CloudIntercomState {
    data class SwitchingLocal(val peerName: String): CloudIntercomState
    data class LocalConnected(val peerName: String): CloudIntercomState
    data object Idle : CloudIntercomState
    data class Inviting(val peerDeviceId: String, val peerName: String, val expiresAt: Long) : CloudIntercomState
    data class ResumeAvailable(val rideId: String, val peerName: String) : CloudIntercomState
    data class LimitReached(val rideId: String, val peerName: String) : CloudIntercomState

    data class Preparing(
        val peerDeviceId: String,
        val peerName: String
    ) : CloudIntercomState

    data class JoiningRoom(
        val peerName: String
    ) : CloudIntercomState

    data class WaitingForPeer(
        val peerName: String
    ) : CloudIntercomState

    data class Connected(
        val peerName: String,
        val remoteUserCount: Int
    ) : CloudIntercomState

    data class Reconnecting(
        val peerName: String
    ) : CloudIntercomState

    data class Failed(
        val peerName: String,
        val message: String
    ) : CloudIntercomState

    data class Stopping(
        val peerName: String
    ) : CloudIntercomState
}

internal val CloudIntercomState.ownsCloudSession: Boolean
    get() = when (this) {
        CloudIntercomState.Idle,
        is CloudIntercomState.ResumeAvailable,
        is CloudIntercomState.LimitReached,
        is CloudIntercomState.Failed -> false
        is CloudIntercomState.Inviting,
        is CloudIntercomState.Preparing,
        is CloudIntercomState.JoiningRoom,
        is CloudIntercomState.WaitingForPeer,
        is CloudIntercomState.Connected,
        is CloudIntercomState.Reconnecting,
        is CloudIntercomState.SwitchingLocal,
        is CloudIntercomState.LocalConnected,
        is CloudIntercomState.Stopping -> true
    }

internal fun CloudIntercomState.peerLabelOrNull(): String? = when (this) {
    is CloudIntercomState.SwitchingLocal -> peerName
    is CloudIntercomState.LocalConnected -> peerName
    CloudIntercomState.Idle -> null
    is CloudIntercomState.Inviting -> peerName
    is CloudIntercomState.ResumeAvailable -> peerName
    is CloudIntercomState.LimitReached -> peerName
    is CloudIntercomState.Preparing -> peerName
    is CloudIntercomState.JoiningRoom -> peerName
    is CloudIntercomState.WaitingForPeer -> peerName
    is CloudIntercomState.Connected -> peerName
    is CloudIntercomState.Reconnecting -> peerName
    is CloudIntercomState.Failed -> peerName
    is CloudIntercomState.Stopping -> peerName
}

internal fun CloudIntercomState.statusText(): String = when (this) {
    is CloudIntercomState.SwitchingLocal -> "正在恢复本地对讲…"
    is CloudIntercomState.LocalConnected -> "本地对讲中 · $peerName"
    CloudIntercomState.Idle -> "连接已配对车友"
    is CloudIntercomState.Inviting -> "已邀请 $peerName，等待接受…"
    is CloudIntercomState.ResumeAvailable -> "可继续与 $peerName 的本次骑行"
    is CloudIntercomState.LimitReached -> "云端已达 4 小时时限，可手动延长"
    is CloudIntercomState.Preparing -> "正在准备云端房间…"
    is CloudIntercomState.JoiningRoom -> "正在进入云端房间…"
    is CloudIntercomState.WaitingForPeer -> "等待 $peerName 进入云端房间…"
    is CloudIntercomState.Connected -> "云端对讲中 · $peerName"
    is CloudIntercomState.Reconnecting -> "云端链路中断，正在重连…"
    is CloudIntercomState.Failed -> message
    is CloudIntercomState.Stopping -> "正在结束云端对讲…"
}

internal fun CloudIntercomState.statusText(interruption: AudioInterruptionState): String =
    if(!ownsCloudSession || interruption==AudioInterruptionState.NORMAL) statusText() else when(interruption) {
        AudioInterruptionState.PHONE_RINGING,AudioInterruptionState.PHONE_ACTIVE -> "对讲已暂停"
        AudioInterruptionState.FOCUS_LOST -> "音频被其他应用占用"
        AudioInterruptionState.ROUTE_UNAVAILABLE -> "通信设备暂不可用"
        else -> "正在恢复对讲音频"
    }

internal fun CloudIntercomState.actionLabel(): String = when (this) {
    is CloudIntercomState.SwitchingLocal,is CloudIntercomState.LocalConnected -> "结束对讲"
    CloudIntercomState.Idle,
    is CloudIntercomState.Failed -> "云端对讲"
    is CloudIntercomState.Inviting -> "取消邀请"
    is CloudIntercomState.ResumeAvailable -> "继续本次骑行"
    is CloudIntercomState.LimitReached -> "延长云端对讲"
    is CloudIntercomState.Preparing,
    is CloudIntercomState.JoiningRoom -> "取消云端对讲"
    is CloudIntercomState.WaitingForPeer,
    is CloudIntercomState.Connected,
    is CloudIntercomState.Reconnecting -> "结束云端对讲"
    is CloudIntercomState.Stopping -> "正在结束…"
}

internal val CloudIntercomState.actionEnabled: Boolean
    get() = this !is CloudIntercomState.Stopping

internal val CloudIntercomState.blocksLocalStart: Boolean
    get() = ownsCloudSession || this is CloudIntercomState.ResumeAvailable || this is CloudIntercomState.LimitReached
