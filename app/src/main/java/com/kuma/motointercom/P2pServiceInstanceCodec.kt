package com.kuma.motointercom

import java.math.BigInteger
import java.util.Locale
import java.util.UUID

internal object P2pServiceInstanceCodec {
    private const val V2_PREFIX = "MotoCom2"
    private const val LEGACY_PREFIX = "MotoCom-"
    private const val UUID_TOKEN_LENGTH = 25
    internal const val MAX_DNS_SD_INSTANCE_BYTES = 63

    private val uuidTokenPattern = Regex("[0-9a-z]{$UUID_TOKEN_LENGTH}")

    fun encode(deviceId: String, sessionId: RuntimeSessionId): String {
        requireCanonicalUuid(deviceId, "deviceId")
        requireCanonicalUuid(sessionId.value, "sessionId")
        val instanceName = "$V2_PREFIX-${encodeUuid(deviceId)}-${encodeUuid(sessionId.value)}"
        require(instanceName.toByteArray(Charsets.US_ASCII).size <= MAX_DNS_SD_INSTANCE_BYTES) {
            "P2P service instance exceeds the DNS-SD label limit"
        }
        return instanceName
    }

    fun decodeClaim(
        instanceName: String,
        fallbackDeviceName: String
    ): DiscoveryIdentityClaim? {
        val parts = instanceName.split('-')
        if (parts.size != 3 || parts[0] != V2_PREFIX) return null
        val deviceId = decodeUuid(parts[1]) ?: return null
        val sessionId = decodeUuid(parts[2]) ?: return null
        return DiscoveryIdentityClaim(
            claimedDeviceId = deviceId,
            sourceSessionId = RuntimeSessionId(sessionId),
            nickname = fallbackDeviceName,
            deviceName = fallbackDeviceName,
            protocolVersion = 2
        )
    }

    fun isLegacy(instanceName: String): Boolean = instanceName.startsWith(LEGACY_PREFIX)

    private fun encodeUuid(value: String): String {
        val uuid = UUID.fromString(value)
        val hex = String.format(
            Locale.ROOT,
            "%016x%016x",
            uuid.mostSignificantBits,
            uuid.leastSignificantBits
        )
        return BigInteger(hex, 16).toString(36).padStart(UUID_TOKEN_LENGTH, '0')
    }

    private fun decodeUuid(token: String): String? {
        if (!uuidTokenPattern.matches(token)) return null
        val hex = runCatching { BigInteger(token, 36).toString(16) }.getOrNull() ?: return null
        if (hex.length > 32) return null
        val normalized = hex.padStart(32, '0')
        val canonical = buildString(36) {
            append(normalized, 0, 8)
            append('-')
            append(normalized, 8, 12)
            append('-')
            append(normalized, 12, 16)
            append('-')
            append(normalized, 16, 20)
            append('-')
            append(normalized, 20, 32)
        }
        return runCatching { UUID.fromString(canonical).toString() }.getOrNull()
    }
}
