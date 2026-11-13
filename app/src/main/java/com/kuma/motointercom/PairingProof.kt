package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class PairingTranscript(
    val protocolVersion: Int = 1,
    val pairId: String,
    val deviceIdA: String,
    val deviceIdB: String,
    val publicKeyA: String,
    val publicKeyB: String,
    val createdAt: Long,
    val nonce: String,
    val expiresAt: Long
) {
    fun json(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", protocolVersion); addProperty("pairId", pairId)
        addProperty("deviceIdA", deviceIdA); addProperty("deviceIdB", deviceIdB)
        addProperty("publicKeyA", publicKeyA); addProperty("publicKeyB", publicKeyB)
        addProperty("createdAt", createdAt); addProperty("nonce", nonce); addProperty("expiresAt", expiresAt)
    }
    fun bytes(): ByteArray = json().toString().toByteArray(Charsets.UTF_8)
    fun validAt(now: Long): Boolean = runCatching {
        protocolVersion == 1 && cloudId(pairId) && cloudId(deviceIdA) && cloudId(deviceIdB) &&
            deviceIdA < deviceIdB && nonce.matches(Regex("[A-Za-z0-9_-]{16,128}")) &&
            createdAt <= now + 60_000 && expiresAt > now && expiresAt > createdAt && expiresAt - createdAt <= 300_000 &&
            CloudCrypto.publicKey(publicKeyA) != CloudCrypto.publicKey(publicKeyB)
    }.getOrDefault(false)

    companion object {
        fun parse(json: JsonObject): PairingTranscript {
            require(json.keySet() == setOf("protocolVersion", "pairId", "deviceIdA", "deviceIdB", "publicKeyA", "publicKeyB", "createdAt", "nonce", "expiresAt"))
            return PairingTranscript(json["protocolVersion"].asInt, json["pairId"].asString,
                json["deviceIdA"].asString, json["deviceIdB"].asString, json["publicKeyA"].asString,
                json["publicKeyB"].asString, json["createdAt"].asLong, json["nonce"].asString, json["expiresAt"].asLong)
        }
    }
}

internal data class PairingProof(val transcript: PairingTranscript, val signatureA: String, val signatureB: String) {
    fun verified(): Boolean = CloudCrypto.verify(transcript.publicKeyA, transcript.bytes(), signatureA) &&
        CloudCrypto.verify(transcript.publicKeyB, transcript.bytes(), signatureB)
    fun json(): JsonObject = JsonObject().apply {
        add("transcript", transcript.json()); addProperty("signatureA", signatureA); addProperty("signatureB", signatureB)
    }
    override fun toString(): String = "PairingProof(pairId=${transcript.pairId})"
    companion object {
        fun parse(text: String): PairingProof {
            val r = JsonParser.parseString(text).asJsonObject
            return PairingProof(PairingTranscript.parse(r["transcript"].asJsonObject), r["signatureA"].asString, r["signatureB"].asString)
        }
    }
}

internal fun cloudId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
