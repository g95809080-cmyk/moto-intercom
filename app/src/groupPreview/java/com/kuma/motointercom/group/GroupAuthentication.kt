package com.kuma.motointercom.group

import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

internal enum class GroupAuthRole { HOST, CLIENT }
internal data class GroupAuthEndpoint(val deviceId: String, val runtimeId: String)
internal data class GroupAuthContext(
    val room: GroupRoomKey,
    val host: GroupAuthEndpoint,
    val client: GroupAuthEndpoint,
    val handshakeId: String
) {
    init {
        listOf(room.instanceId, room.hostRuntimeId, host.deviceId, host.runtimeId,
            client.deviceId, client.runtimeId, handshakeId).forEach { require(UUID.fromString(it).toString() == it) }
        require(room.hostRuntimeId == host.runtimeId && host.deviceId != client.deviceId)
    }
    fun binding(): String = listOf("MCGA1", room.instanceId, room.hostRuntimeId,
        host.deviceId, host.runtimeId, client.deviceId, client.runtimeId, handshakeId).joinToString("|")
    fun participant(role: GroupAuthRole) = binding() + "|" + role.name
}

internal class GroupAuthException : Exception("Group authentication failed")

/** One exchange per transport attempt. No admission proof or product state is created here. */
internal class GroupAuthentication(
    private val context: GroupAuthContext,
    private val role: GroupAuthRole,
    code: GroupJoinCode,
    private val nowMs: () -> Long,
    private val isCurrent: () -> Boolean
) : Closeable {
    private var participant: JPAKEParticipant? = code.digits.toCharArray().let { password ->
        try { JPAKEParticipant(context.participant(role), password) } finally { password.fill('\u0000') }
    }
    private val other = if (role == GroupAuthRole.HOST) GroupAuthRole.CLIENT else GroupAuthRole.HOST
    private val started = nowMs().also { require(it >= 0 && it <= Long.MAX_VALUE - TIMEOUT_MS) }
    private var lastTime = started
    private var expectedRound = 0
    private var material: BigInteger? = null
    private var confirmed = false
    private var closed = false

    @Synchronized fun start(): ByteArray = guarded {
        check(expectedRound == 0)
        val round = requireNotNull(participant).createRound1PayloadToSend()
        expectedRound = 1
        packet(1, listOf(round.gx1, round.gx2) + round.knowledgeProofForX1 + round.knowledgeProofForX2)
    }

    /** Returns the next round, or null after validating the peer's final confirmation. */
    @Synchronized fun receive(bytes: ByteArray): ByteArray? = guarded {
        check(expectedRound in 1..3)
        val values = unpack(bytes, expectedRound)
        val id = context.participant(other)
        val exchange = requireNotNull(participant)
        when (expectedRound) {
            1 -> {
                exchange.validateRound1PayloadReceived(JPAKERound1Payload(id, values[0], values[1],
                    arrayOf(values[2], values[3]), arrayOf(values[4], values[5])))
                val round = exchange.createRound2PayloadToSend()
                expectedRound = 2
                packet(2, listOf(round.a) + round.knowledgeProofForX2s)
            }
            2 -> {
                exchange.validateRound2PayloadReceived(JPAKERound2Payload(id, values[0], arrayOf(values[1], values[2])))
                material = exchange.calculateKeyingMaterial()
                val round = exchange.createRound3PayloadToSend(material)
                expectedRound = 3
                packet(3, listOf(round.macTag))
            }
            else -> {
                exchange.validateRound3PayloadReceived(JPAKERound3Payload(id, values[0]), material)
                confirmed = true
                expectedRound = 4
                null
            }
        }
    }

    @Synchronized fun takeChannel(): SecureGroupChannel = guarded {
        check(confirmed && expectedRound == 4)
        val salt = MessageDigest.getInstance("SHA-256").digest(context.binding().toByteArray(Charsets.UTF_8))
        val secret = requireNotNull(material).toByteArray()
        val keys = ByteArray(64)
        try {
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(secret, salt, "MotoCom group channel v1".toByteArray(Charsets.UTF_8)))
                generateBytes(keys, 0, keys.size)
            }
            validateTime()
            val channel = SecureGroupChannel(keys, salt, role)
            participant = null
            material = null
            confirmed = false
            closed = true
            channel
        } finally { secret.fill(0); keys.fill(0); salt.fill(0) }
    }

    @Synchronized override fun close() {
        closed = true
        confirmed = false
        participant = null
        material = null
    }

    private fun validateTime() {
        val now = nowMs()
        check(!closed && isCurrent() && now >= lastTime && now - started < TIMEOUT_MS)
        lastTime = now
    }

    private inline fun <T> guarded(action: () -> T): T = try {
        validateTime()
        val value = action()
        // takeChannel closes the handshake while transferring its only derived channel.
        if (!closed) validateTime()
        value
    } catch (_: Exception) {
        close()
        throw GroupAuthException()
    }

    private fun packet(round: Int, values: List<BigInteger>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(1)
            output.writeByte(round)
            values.forEach {
                val value = it.toByteArray()
                require(value.size in 1..MAX_INTEGER_BYTES)
                output.writeShort(value.size)
                output.write(value)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_HANDSHAKE_BYTES) }
    }

    private fun unpack(bytes: ByteArray, round: Int): List<BigInteger> {
        require(bytes.size in 6..MAX_HANDSHAKE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readUnsignedByte() == 1 && input.readUnsignedByte() == round)
        val count = when (round) { 1 -> 6; 2 -> 3; else -> 1 }
        val values = List(count) {
            val length = input.readUnsignedShort()
            require(length in 1..(if (round == 3) 32 else MAX_INTEGER_BYTES) && length <= input.available())
            val number = ByteArray(length); input.readFully(number)
            BigInteger(number).also {
                require(it.toByteArray().contentEquals(number))
                if (round != 3) require(it.signum() >= 0)
            }
        }
        require(input.available() == 0)
        return values
    }

    companion object {
        const val TIMEOUT_MS = 20_000L
        const val MAX_HANDSHAKE_BYTES = 4096
        private const val MAX_INTEGER_BYTES = 385
        private const val MAGIC = 0x4d434741
    }
}
