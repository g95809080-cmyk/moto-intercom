package com.kuma.motointercom.group.cloud

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class GroupEntitlementSummary(
    val entitlementId: String,
    val status: String,
    val remainingSeconds: Long,
    val remainingMinutes: Int,
    val totalMinutes: Int,
    val usedMinutes: Int,
    val maxMembers: Int,
    val maxConcurrentRooms: Int,
    val maxSessionMinutes: Int,
    val nearestExpiresAtMs: Long?,
    val furthestExpiresAtMs: Long?,
    val activeGrantCount: Int,
    val codeMask: String? = null,
    val serverNowMs: Long = System.currentTimeMillis()
) {
    val isUsable: Boolean
        get() = status == "ACTIVE" && activeGrantCount > 0 && remainingSeconds > 0L

    fun remainingDays(nowMs: Long = serverNowMs): Int {
        val target = furthestExpiresAtMs ?: return 0
        val diff = target - nowMs
        if (diff <= 0L) return 0
        return ((diff + DAY_MS - 1L) / DAY_MS).toInt()
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

internal data class GroupEntitlementRedeemResult(
    val action: String,
    val rebound: Boolean,
    val entitlementToken: String,
    val codeMask: String,
    val summary: GroupEntitlementSummary
) {
    override fun toString(): String =
        "GroupEntitlementRedeemResult(action=$action, rebound=$rebound, mask=$codeMask)"
}

internal object GroupActivationCodeValidator {
    private const val PREFIX = "MOTO"
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val DATA_LEN = 10
    private const val BODY_LEN = 12

    fun normalizeOrNull(raw: String): String? {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace(Regex("[\\s-]+"), "")
        val body = if (cleaned.startsWith(PREFIX)) cleaned.removePrefix(PREFIX) else cleaned
        if (body.length != BODY_LEN) return null
        val mapped = buildString(BODY_LEN) {
            for (ch in body) {
                val normalized = when (ch) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> ch
                }
                if (normalized !in CROCKFORD) return null
                append(normalized)
            }
        }
        val dataPart = mapped.substring(0, DATA_LEN)
        val checkPart = mapped.substring(DATA_LEN)
        if (computeCheckChars(dataPart) != checkPart) return null
        return "$PREFIX-${mapped.substring(0, 4)}-${mapped.substring(4, 8)}-${mapped.substring(8, 12)}"
    }

    fun isValid(raw: String): Boolean = normalizeOrNull(raw) != null

    private fun computeCheckChars(dataPart: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$PREFIX:$dataPart".toByteArray(Charsets.US_ASCII))
        val value = (((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)) and 0x3ff
        return "${CROCKFORD[(value ushr 5) and 0x1f]}${CROCKFORD[value and 0x1f]}"
    }
}

internal interface EntitlementTokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}

internal class AndroidKeyStoreTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : EntitlementTokenCipher {
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return CIPHER_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? {
        if (!ciphertext.startsWith(CIPHER_PREFIX)) return null
        return runCatching {
            val combined = Base64.decode(ciphertext.removePrefix(CIPHER_PREFIX), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES + GCM_TAG_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val body = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "motocom_group_entitlement_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CIPHER_PREFIX = "v1:"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128
    }
}

/** Pure-JVM AES-256-GCM cipher used strictly under Robolectric where AndroidKeyStore is absent. */
private object RobolectricTestTokenCipher : EntitlementTokenCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val CIPHER_PREFIX = "v1:"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()
    private val testKey: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, testKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return CIPHER_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? {
        if (!ciphertext.startsWith(CIPHER_PREFIX)) return null
        return runCatching {
            val combined = Base64.decode(ciphertext.removePrefix(CIPHER_PREFIX), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES + GCM_TAG_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val body = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, testKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }
}

private fun defaultEntitlementTokenCipher(): EntitlementTokenCipher {
    if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true) && Security.getProvider("AndroidKeyStore") == null) {
        return RobolectricTestTokenCipher
    }
    return AndroidKeyStoreTokenCipher()
}

/** Persists the entitlement token encrypted with AndroidKeyStore AES-256-GCM plus display summary. */
internal class GroupEntitlementStore(
    context: Context,
    private val tokenCipher: EntitlementTokenCipher = defaultEntitlementTokenCipher()
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (prefs.contains(LEGACY_KEY_TOKEN)) {
            prefs.edit { remove(LEGACY_KEY_TOKEN) }
        }
    }

    fun loadToken(): String? {
        val encrypted = prefs.getString(KEY_TOKEN_ENC, null)?.takeIf { it.isNotBlank() } ?: return null
        val plain = tokenCipher.decrypt(encrypted)?.takeIf { it.isNotBlank() }
        if (plain == null) {
            prefs.edit { remove(KEY_TOKEN_ENC) }
            return null
        }
        return plain
    }

    fun loadSummary(): GroupEntitlementSummary? {
        val entitlementId = prefs.getString(KEY_ENTITLEMENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val status = prefs.getString(KEY_STATUS, "ACTIVE") ?: "ACTIVE"
        return GroupEntitlementSummary(
            entitlementId = entitlementId,
            status = status,
            remainingSeconds = prefs.getLong(KEY_REMAINING_SECONDS, 0L),
            remainingMinutes = prefs.getInt(KEY_REMAINING_MINUTES, 0),
            totalMinutes = prefs.getInt(KEY_TOTAL_MINUTES, 0),
            usedMinutes = prefs.getInt(KEY_USED_MINUTES, 0),
            maxMembers = prefs.getInt(KEY_MAX_MEMBERS, 0),
            maxConcurrentRooms = prefs.getInt(KEY_MAX_CONCURRENT_ROOMS, 1),
            maxSessionMinutes = prefs.getInt(KEY_MAX_SESSION_MINUTES, 120),
            nearestExpiresAtMs = prefs.getLong(KEY_NEAREST_EXPIRES_MS, -1L).takeIf { it > 0L },
            furthestExpiresAtMs = prefs.getLong(KEY_FURTHEST_EXPIRES_MS, -1L).takeIf { it > 0L },
            activeGrantCount = prefs.getInt(KEY_ACTIVE_GRANT_COUNT, 0),
            codeMask = prefs.getString(KEY_CODE_MASK, null)?.takeIf { it.isNotBlank() },
            serverNowMs = prefs.getLong(KEY_SERVER_NOW_MS, System.currentTimeMillis())
        )
    }

    fun saveRedeemResult(result: GroupEntitlementRedeemResult) {
        val encrypted = tokenCipher.encrypt(result.entitlementToken)
        require(encrypted.isNotBlank() && encrypted != result.entitlementToken) {
            "Failed to encrypt entitlement token"
        }
        prefs.edit {
            remove(LEGACY_KEY_TOKEN)
            putString(KEY_TOKEN_ENC, encrypted)
            putString(KEY_CODE_MASK, result.codeMask)
            writeSummaryFields(result.summary.copy(codeMask = result.codeMask))
        }
    }

    fun saveSummary(summary: GroupEntitlementSummary) {
        val existingMask = summary.codeMask ?: prefs.getString(KEY_CODE_MASK, null)
        prefs.edit {
            if (!existingMask.isNullOrBlank()) putString(KEY_CODE_MASK, existingMask)
            writeSummaryFields(summary.copy(codeMask = existingMask))
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun android.content.SharedPreferences.Editor.writeSummaryFields(summary: GroupEntitlementSummary) {
        putString(KEY_ENTITLEMENT_ID, summary.entitlementId)
        putString(KEY_STATUS, summary.status)
        putLong(KEY_REMAINING_SECONDS, summary.remainingSeconds)
        putInt(KEY_REMAINING_MINUTES, summary.remainingMinutes)
        putInt(KEY_TOTAL_MINUTES, summary.totalMinutes)
        putInt(KEY_USED_MINUTES, summary.usedMinutes)
        putInt(KEY_MAX_MEMBERS, summary.maxMembers)
        putInt(KEY_MAX_CONCURRENT_ROOMS, summary.maxConcurrentRooms)
        putInt(KEY_MAX_SESSION_MINUTES, summary.maxSessionMinutes)
        putLong(KEY_NEAREST_EXPIRES_MS, summary.nearestExpiresAtMs ?: -1L)
        putLong(KEY_FURTHEST_EXPIRES_MS, summary.furthestExpiresAtMs ?: -1L)
        putInt(KEY_ACTIVE_GRANT_COUNT, summary.activeGrantCount)
        putLong(KEY_SERVER_NOW_MS, summary.serverNowMs)
    }

    companion object {
        private const val PREFS_NAME = "motocom_group_entitlement_prefs"
        private const val LEGACY_KEY_TOKEN = "entitlement_token"
        private const val KEY_TOKEN_ENC = "entitlement_token_enc"
        private const val KEY_ENTITLEMENT_ID = "entitlement_id"
        private const val KEY_STATUS = "status"
        private const val KEY_REMAINING_SECONDS = "remaining_seconds"
        private const val KEY_REMAINING_MINUTES = "remaining_minutes"
        private const val KEY_TOTAL_MINUTES = "total_minutes"
        private const val KEY_USED_MINUTES = "used_minutes"
        private const val KEY_MAX_MEMBERS = "max_members"
        private const val KEY_MAX_CONCURRENT_ROOMS = "max_concurrent_rooms"
        private const val KEY_MAX_SESSION_MINUTES = "max_session_minutes"
        private const val KEY_NEAREST_EXPIRES_MS = "nearest_expires_ms"
        private const val KEY_FURTHEST_EXPIRES_MS = "furthest_expires_ms"
        private const val KEY_ACTIVE_GRANT_COUNT = "active_grant_count"
        private const val KEY_CODE_MASK = "code_mask"
        private const val KEY_SERVER_NOW_MS = "server_now_ms"
    }
}
