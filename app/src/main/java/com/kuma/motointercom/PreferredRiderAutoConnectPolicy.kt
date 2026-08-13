package com.kuma.motointercom

internal data class PreferredAutoConnectTarget(
    val deviceId: String,
    val sessionId: RuntimeSessionId,
    val availableTransports: Set<Transport>
) {
    val key: PreferredAutoConnectTargetKey
        get() = PreferredAutoConnectTargetKey(deviceId, sessionId)
}

internal data class PreferredAutoConnectTargetKey(
    val deviceId: String,
    val sessionId: RuntimeSessionId
)

internal fun preferredAutoConnectTarget(
    state: IntercomState,
    presences: List<RiderPresence>
): PreferredAutoConnectTarget? {
    if (state !is IntercomState.Discovering) return null
    val presence = presences.firstOrNull {
        it.isPreferred && it.isSelectable && !it.deviceId.isNullOrBlank()
    } ?: return null
    val deviceId = presence.deviceId ?: return null
    val sessionId = presence.sessionId ?: return null
    return PreferredAutoConnectTarget(
        deviceId = deviceId,
        sessionId = sessionId,
        availableTransports = presence.availableTransports
    )
}
