package com.kuma.motointercom

internal data class GmeConfig(
    val enabled: Boolean,
    val controlPlaneUrl: String,
    val webSocketUrl: String = ""
) {
    val isConfigured: Boolean
        get() = enabled && runCatching {
            val uri = java.net.URI(controlPlaneUrl)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()
        }.getOrDefault(false)

    companion object {
        fun fromBuildConfig(): GmeConfig = GmeConfig(
            enabled = BuildConfig.GME_ENABLED,
            controlPlaneUrl = BuildConfig.GME_CONTROL_PLANE_URL.trim().trimEnd('/'),
            webSocketUrl = BuildConfig.GME_WEBSOCKET_URL.trim().trimEnd('/')
        )
    }

    val canConnect: Boolean get() = isConfigured && runCatching {
        val uri = java.net.URI(webSocketUrl)
        uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)
}
