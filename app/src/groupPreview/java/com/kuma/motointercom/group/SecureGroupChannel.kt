package com.kuma.motointercom.group

import java.io.Closeable
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Used only after J-PAKE confirmation; one instance per exchange, never restored or copied. */
internal class SecureGroupChannel internal constructor(keys: ByteArray, binding: ByteArray, role: GroupAuthRole) : Closeable {
    private val sending = keys.copyOfRange(if (role == GroupAuthRole.HOST) 0 else 32, if (role == GroupAuthRole.HOST) 32 else 64)
    private val receiving = keys.copyOfRange(if (role == GroupAuthRole.HOST) 32 else 0, if (role == GroupAuthRole.HOST) 64 else 32)
    private val context = binding.copyOf()
    private val sendDirection = if (role == GroupAuthRole.HOST) 0x484f5354 else 0x434c4e54
    private val receiveDirection = if (role == GroupAuthRole.HOST) 0x434c4e54 else 0x484f5354
    private var sent = 0L
    private var received = 0L
    private var closed = false

    init { require(keys.size == 64 && binding.size == 32) }

    @Synchronized fun encrypt(plain: ByteArray): ByteArray = guarded {
        require(plain.size <= MAX_PLAIN_BYTES && sent < Long.MAX_VALUE)
        val sequence = ++sent
        val encrypted = cipher(Cipher.ENCRYPT_MODE, sending, sendDirection, sequence).doFinal(plain)
        ByteBuffer.allocate(8 + encrypted.size).putLong(sequence).put(encrypted).array()
    }

    @Synchronized fun decrypt(packet: ByteArray): ByteArray = guarded {
        require(packet.size in 24..MAX_CIPHER_BYTES && received < Long.MAX_VALUE)
        val input = ByteBuffer.wrap(packet)
        val sequence = input.long
        require(sequence == received + 1)
        val plaintext = cipher(Cipher.DECRYPT_MODE, receiving, receiveDirection, sequence)
            .doFinal(packet, 8, packet.size - 8)
        received = sequence
        plaintext
    }

    private fun cipher(mode: Int, key: ByteArray, direction: Int, sequence: Long): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(context)
            updateAAD(ByteBuffer.allocate(8).putLong(sequence).array())
        }
    }

    private inline fun <T> guarded(action: () -> T): T = try {
        check(!closed)
        action()
    } catch (_: Exception) {
        close()
        throw GroupAuthException()
    }

    @Synchronized override fun close() {
        closed = true
        sending.fill(0); receiving.fill(0); context.fill(0)
    }

    companion object {
        const val MAX_PLAIN_BYTES = 65_536
        const val MAX_CIPHER_BYTES = MAX_PLAIN_BYTES + 8 + 16
    }
}
