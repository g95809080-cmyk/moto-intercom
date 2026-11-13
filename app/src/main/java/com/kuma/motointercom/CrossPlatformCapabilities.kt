package com.kuma.motointercom

import java.util.Locale

internal enum class PeerPlatform {
    ANDROID,
    IOS,
    UNKNOWN;

    companion object {
        fun parse(raw: String): PeerPlatform =
            entries.firstOrNull { it.name == raw.trim().uppercase(Locale.ROOT) } ?: UNKNOWN
    }
}

internal enum class BootstrapNetworkRole {
    HOST,
    JOINER,
    EITHER;

    companion object {
        fun parse(raw: String): BootstrapNetworkRole =
            entries.firstOrNull { it.name == raw.trim().uppercase(Locale.ROOT) } ?: EITHER
    }
}

internal object MotoComCapability {
    const val BLE_BOOTSTRAP = "BLE_BOOTSTRAP"
    const val LAN = "LAN"
    const val ANDROID_LOCAL_ONLY_HOTSPOT_HOST = "ANDROID_LOCAL_ONLY_HOTSPOT_HOST"
    const val WIFI_JOIN = "WIFI_JOIN"
    const val ANDROID_WIFI_DIRECT = "ANDROID_WIFI_DIRECT"
    const val IOS_PEER_TO_PEER = "IOS_PEER_TO_PEER"
    const val IOS_PERSONAL_HOTSPOT_MANUAL = "IOS_PERSONAL_HOTSPOT_MANUAL"
}

internal data class CrossPlatformCapabilities(
    val platform: PeerPlatform,
    val apiLevel: Int? = null,
    val capabilities: Set<String> = emptySet(),
    val networkRole: BootstrapNetworkRole = BootstrapNetworkRole.EITHER
) {
    init {
        require(apiLevel == null || apiLevel >= 0) { "apiLevel must not be negative" }
        require(capabilities.all(::isValidCapability)) {
            "capabilities must be uppercase ASCII tokens"
        }
    }

    fun supports(capability: String): Boolean = capability in capabilities

    fun canHostAndroidLocalOnlyHotspot(): Boolean =
        platform == PeerPlatform.ANDROID &&
            supports(MotoComCapability.ANDROID_LOCAL_ONLY_HOTSPOT_HOST) &&
            networkRole != BootstrapNetworkRole.JOINER

    fun canJoinWifi(): Boolean = supports(MotoComCapability.WIFI_JOIN)

    fun canUseLan(): Boolean = supports(MotoComCapability.LAN)

    companion object {
        fun localAndroid(
            apiLevel: Int,
            blePermission: Boolean,
            wifiEnabled: Boolean,
            wifiDirectSupported: Boolean
        ): CrossPlatformCapabilities {
            val capabilities = linkedSetOf<String>()
            if (blePermission) capabilities += MotoComCapability.BLE_BOOTSTRAP
            if (wifiEnabled) {
                capabilities += MotoComCapability.LAN
                capabilities += MotoComCapability.WIFI_JOIN
            }
            if (apiLevel >= 26 && wifiEnabled) {
                capabilities += MotoComCapability.ANDROID_LOCAL_ONLY_HOTSPOT_HOST
            }
            if (wifiDirectSupported) capabilities += MotoComCapability.ANDROID_WIFI_DIRECT
            return CrossPlatformCapabilities(
                platform = PeerPlatform.ANDROID,
                apiLevel = apiLevel,
                capabilities = capabilities,
                networkRole = BootstrapNetworkRole.EITHER
            )
        }
    }
}

internal enum class BootstrapPath {
    COMMON_LAN,
    ANDROID_LOCAL_ONLY_HOTSPOT,
    MANUAL_SYSTEM_WIFI,
    ANDROID_WIFI_DIRECT,
    UNSUPPORTED
}

internal enum class BootstrapHostPreference {
    AUTOMATIC,
    LOCAL,
    REMOTE
}

internal object CrossPlatformBootstrapPolicy {
    fun choosePath(
        local: CrossPlatformCapabilities,
        remote: CrossPlatformCapabilities,
        commonLanAvailable: Boolean,
        hostPreference: BootstrapHostPreference = BootstrapHostPreference.AUTOMATIC
    ): BootstrapPath {
        if (commonLanAvailable && local.canUseLan() && remote.canUseLan()) {
            return BootstrapPath.COMMON_LAN
        }

        val androidCanHost = local.canHostAndroidLocalOnlyHotspot() && remote.canJoinWifi()
        val remoteCanHostManually =
            local.canJoinWifi() &&
                remote.networkRole != BootstrapNetworkRole.JOINER &&
                when (remote.platform) {
                    PeerPlatform.IOS -> remote.supports(MotoComCapability.IOS_PERSONAL_HOTSPOT_MANUAL)
                    PeerPlatform.ANDROID -> remote.canHostAndroidLocalOnlyHotspot()
                    PeerPlatform.UNKNOWN -> false
                }

        val androidWifiDirectAvailable =
            local.platform == PeerPlatform.ANDROID &&
                remote.platform == PeerPlatform.ANDROID &&
                local.supports(MotoComCapability.ANDROID_WIFI_DIRECT) &&
                remote.supports(MotoComCapability.ANDROID_WIFI_DIRECT)

        when (hostPreference) {
            BootstrapHostPreference.LOCAL -> {
                if (androidCanHost) return BootstrapPath.ANDROID_LOCAL_ONLY_HOTSPOT
            }
            BootstrapHostPreference.REMOTE -> {
                if (remoteCanHostManually) return BootstrapPath.MANUAL_SYSTEM_WIFI
            }
            BootstrapHostPreference.AUTOMATIC -> {
                if (androidWifiDirectAvailable) return BootstrapPath.ANDROID_WIFI_DIRECT
                if (androidCanHost) return BootstrapPath.ANDROID_LOCAL_ONLY_HOTSPOT
            }
        }

        if (remoteCanHostManually) return BootstrapPath.MANUAL_SYSTEM_WIFI

        if (androidWifiDirectAvailable) {
            return BootstrapPath.ANDROID_WIFI_DIRECT
        }

        return BootstrapPath.UNSUPPORTED
    }
}

internal data class CrossPlatformCapabilityAdvertisement(
    val app: String = APP_ID,
    val protocolVersion: Int = SignalingV2Codec.PROTOCOL_VERSION,
    val platform: PeerPlatform,
    val deviceId: String,
    val sessionId: String,
    val capabilities: Set<String>,
    val networkRole: BootstrapNetworkRole = BootstrapNetworkRole.EITHER,
    val tcpPort: Int = DEFAULT_TCP_PORT
) {
    init {
        require(app == APP_ID) { "Unsupported app ID: $app" }
        require(protocolVersion == SignalingV2Codec.PROTOCOL_VERSION) {
            "Unsupported bootstrap protocol version: $protocolVersion"
        }
        DeviceId.parse(deviceId)
        requireCanonicalUuid(sessionId, "sessionId")
        require(capabilities.all(::isValidCapability)) {
            "capabilities must be uppercase ASCII tokens"
        }
        require(tcpPort in 1..65535) { "tcpPort is invalid" }
    }

    fun toCapabilities(apiLevel: Int? = null): CrossPlatformCapabilities =
        CrossPlatformCapabilities(platform, apiLevel, capabilities, networkRole)

    companion object {
        const val APP_ID = "motocom"
        const val DEFAULT_TCP_PORT = 8890
    }
}

private fun isValidCapability(raw: String): Boolean =
    raw.isNotBlank() && raw.length <= 64 && raw.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
