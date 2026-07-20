package com.kuma.motointercom

internal enum class PrimaryIntercomAction {
    START,
    DISCONNECT_CURRENT,
    STOP_RUNTIME,
    NONE
}

internal fun primaryIntercomAction(state: IntercomState): PrimaryIntercomAction = when (state) {
    IntercomState.Offline -> PrimaryIntercomAction.START
    is IntercomState.Connecting,
    is IntercomState.Optimizing,
    is IntercomState.Connected,
    is IntercomState.Recovering -> PrimaryIntercomAction.DISCONNECT_CURRENT
    is IntercomState.Discovering,
    is IntercomState.IncomingConfirmation,
    is IntercomState.Resetting -> PrimaryIntercomAction.STOP_RUNTIME
    is IntercomState.Stopping -> PrimaryIntercomAction.NONE
}

internal fun primaryIntercomActionLabel(state: IntercomState): String =
    when (primaryIntercomAction(state)) {
        PrimaryIntercomAction.START -> "启动摩声"
        PrimaryIntercomAction.DISCONNECT_CURRENT -> "断开当前车友"
        PrimaryIntercomAction.STOP_RUNTIME -> "停止摩声"
        PrimaryIntercomAction.NONE -> "停止中..."
    }
