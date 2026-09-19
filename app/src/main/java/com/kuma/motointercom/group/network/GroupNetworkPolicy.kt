package com.kuma.motointercom.group.network

import java.io.Closeable

/** Caps callback work before posting to Android's unbounded Handler queue. Overflow is terminal. */
internal class GroupBoundedCallbacks(
    private val enqueue: (() -> Unit) -> Unit,
    private val onOverflow: () -> Unit,
    private val capacity: Int = 64
) : Closeable {
    private val lock = Any()
    private var accepting = true
    private var pending = 0
    init { require(capacity > 0) }
    fun post(action: () -> Unit) {
        val mode = synchronized(lock) {
            if (!accepting) 0
            else if (pending >= capacity) { accepting = false; 2 }
            else { pending++; 1 }
        }
        when (mode) {
            1 -> enqueue {
                val run = synchronized(lock) { pending--; accepting }
                if (run) action()
            }
            2 -> enqueue(onOverflow)
        }
    }
    override fun close() { synchronized(lock) { accepting = false } }
}

/** ATT offsets are always zero. These offsets belong only to our application message. */
internal object GroupBleChunks {
    const val MAX_MESSAGE = 8192
    const val CHUNK_BYTES = 20
    fun split(message: ByteArray): List<ByteArray> {
        require(message.size in 1..MAX_MESSAGE)
        return (message.indices step 16).map { offset ->
            val length = minOf(16, message.size - offset)
            byteArrayOf((message.size ushr 8).toByte(), message.size.toByte(),
                (offset ushr 8).toByte(), offset.toByte()) + message.copyOfRange(offset, offset + length)
        }
    }
}

internal class GroupBleAssembler : Closeable {
    private var buffer: ByteArray? = null
    private var offset = 0
    private var closed = false

    fun accept(chunk: ByteArray): ByteArray? = try {
        check(!closed)
        require(chunk.size in 5..GroupBleChunks.CHUNK_BYTES)
        val total = ((chunk[0].toInt() and 255) shl 8) or (chunk[1].toInt() and 255)
        val position = ((chunk[2].toInt() and 255) shl 8) or (chunk[3].toInt() and 255)
        require(total in 1..GroupBleChunks.MAX_MESSAGE && position == offset)
        if (buffer == null) buffer = ByteArray(total)
        val target = checkNotNull(buffer)
        require(target.size == total && chunk.size - 4 == minOf(16, total - offset))
        chunk.copyInto(target, offset, 4)
        offset += chunk.size - 4
        if (offset == total) {
            buffer = null
            offset = 0
            target
        } else null
    } catch (failure: Exception) { close(); throw failure }

    override fun close() { closed = true; buffer?.fill(0); buffer = null; offset = 0 }
}

/** A room-wide budget. An unauthenticated address is a rate key, never an identity. */
internal class GroupBleBudget(private val nowMs: () -> Long) {
    private val attempts = mutableMapOf<String, ArrayDeque<Long>>()
    private val global = ArrayDeque<Long>()
    private var lastTime = 0L
    fun acquire(address: String, activeConnections: Int): Boolean {
        val now = nowMs()
        require(now >= lastTime)
        lastTime = now
        if (activeConnections >= 4 || address.length !in 1..64) return false
        global.removeAll { now - it >= 60_000 }
        attempts.values.forEach { times -> times.removeAll { now - it >= 60_000 } }
        attempts.entries.removeAll { it.value.isEmpty() }
        if (global.size >= 12 || (address !in attempts && attempts.size >= 64)) return false
        val perAddress = attempts.getOrPut(address) { ArrayDeque() }
        if (perAddress.size >= 3) return false
        global.addLast(now)
        perAddress.addLast(now)
        return true
    }
}

internal enum class GroupNetworkCloseResult { RELEASED, UNKNOWN }

/** One process-wide GO owner. Timeout does not grant a successor permission to mutate P2P. */
internal class GroupGoOwnership {
    class Lease internal constructor()
    private var owner: Lease? = null
    private var pendingCreate = false
    @Synchronized fun acquire(): Lease? = if (owner == null) Lease().also { owner = it } else null
    @Synchronized fun owns(lease: Lease) = owner == lease
    @Synchronized fun creating(lease: Lease) { check(owns(lease)); pendingCreate = true }
    @Synchronized fun createFinished(lease: Lease): Boolean {
        if (!owns(lease)) return false
        pendingCreate = false
        return true
    }
    @Synchronized fun releaseConfirmed(lease: Lease): Boolean {
        if (!owns(lease) || pendingCreate) return false
        owner = null
        return true
    }
    companion object { val process = GroupGoOwnership() }
}

internal class GroupWifiCredentials(val ssid: String, val passphrase: String) {
    init {
        require(ssid.toByteArray(Charsets.UTF_8).size in 1..32 && ssid.none { it == '\u0000' })
        require(passphrase.length in 8..63 && passphrase.all { it.code in 32..126 })
    }
    override fun toString() = "GroupWifiCredentials(REDACTED)"
}
