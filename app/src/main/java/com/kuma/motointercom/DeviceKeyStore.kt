package com.kuma.motointercom

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

internal interface DeviceSigner {
    fun publicKeyBase64(): String
    fun sign(bytes: ByteArray): String
}

// Only public SPKI bytes and DER signatures leave this class. Call on Dispatchers.IO.
internal class DeviceKeyStore(private val alias: String = "motocom_gme_device_p256_v1") : DeviceSigner {
    @Synchronized private fun entry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKeyPair()
        }
        return store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("云端设备密钥不可用，请重新配对")
    }

    override fun publicKeyBase64(): String = CloudCrypto.base64(entry().certificate.publicKey.encoded)
    override fun sign(bytes: ByteArray): String = CloudCrypto.base64(Signature.getInstance("SHA256withECDSA").run {
        initSign(entry().privateKey); update(bytes); sign()
    })
}

internal object CloudCrypto {
    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(value: String): ByteArray {
        require(value.length in 1..2048 && value.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        return Base64.decode(value, Base64.NO_WRAP).also { require(base64(it) == value) }
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun publicKey(value: String): ECPublicKey =
        (KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(value))) as ECPublicKey).also {
            require(it.params.curve.field.fieldSize == 256 && it.params.order == P256_ORDER && base64(it.encoded) == value)
        }

    fun verify(key: String, bytes: ByteArray, signature: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey(key)); update(bytes); verify(decode(signature)) }
    }.getOrDefault(false)

    fun canonicalRequest(method: String, path: String, body: String, timestamp: String, nonce: String): ByteArray =
        listOf(method, path, sha256(body.toByteArray(Charsets.UTF_8)), timestamp, nonce).joinToString("\n").toByteArray(Charsets.UTF_8)

    private val P256_ORDER = java.math.BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
}
