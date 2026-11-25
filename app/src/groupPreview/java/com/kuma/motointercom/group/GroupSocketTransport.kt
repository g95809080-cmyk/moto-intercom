package com.kuma.motointercom.group

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun groupNowMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

/** Owns socket+keys, not room state. All callbacks carry this object's unique channel identity. */
internal class GroupSocketChannel internal constructor(
    private val socket: Socket,
    val context: GroupAuthContext,
    private val secure: SecureGroupChannel,
    private val scheduler: ScheduledExecutorService,
    private val isCurrent: () -> Boolean,
    private val onMessage: (GroupSocketChannel, ByteArray) -> Unit,
    private val onClosed: (GroupSocketChannel) -> Unit,
    private val nowMs: () -> Long = ::groupNowMs
) : Closeable {
    val id: UUID = UUID.randomUUID()
    private val closed = AtomicBoolean(false)
    private val admitted = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val created = nowMs()
    private val lastRead = AtomicLong(created)
    private val writeStarted = AtomicLong(0)
    private val heartbeatQueued = AtomicBoolean(false)
    private val output = DataOutputStream(socket.getOutputStream())
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "group-control-write").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private var sequence = 0L // writer only
    private val timerLock = Any()
    private var watchdog: ScheduledFuture<*>? = null
    private var heartbeat: ScheduledFuture<*>? = null
    internal fun startTimers() = synchronized(timerLock) {
        if (closed.get()) return@synchronized
        check(watchdog == null)
        watchdog = scheduler.scheduleAtFixedRate({
        val now = nowMs()
        if (!isCurrent() && !draining.get() || !admitted.get() && now - created >= 10_000 ||
            now - lastRead.get() >= 8_000 || writeStarted.get().let { it != 0L && now - it >= 5_000 }) close()
        }, 100, 100, TimeUnit.MILLISECONDS)
        heartbeat = scheduler.scheduleAtFixedRate({
        if (heartbeatQueued.compareAndSet(false, true)) enqueue {
            try { write(byteArrayOf(0)) } finally { heartbeatQueued.set(false) }
        }
        }, 2, 2, TimeUnit.SECONDS)
    }
    val isClosed: Boolean get() = closed.get()
    fun markAdmitted() { if (!closed.get() && isCurrent()) admitted.set(true) }

    /** Builder runs on the same writer that assigns AEAD nonces and writes the frame. */
    fun send(build: (Long) -> ByteArray) = enqueue {
        check(sequence < Long.MAX_VALUE)
        val payload = build(++sequence)
        require(payload.size in 1..65_535)
        try { write(byteArrayOf(1) + payload) } finally { payload.fill(0) }
    }
    fun sendFinal(build: (Long) -> ByteArray) {
        // Only the owning writer may authorize this terminal, channel-bound flush after intent revocation.
        if (closed.get() || !draining.compareAndSet(false, true)) return
        writer.queue.clear()
        try {
            writer.execute {
                try {
                    if (!closed.get()) {
                        check(sequence < Long.MAX_VALUE)
                        val payload = build(++sequence)
                        require(payload.size in 1..65_535)
                        try { write(byteArrayOf(1) + payload) } finally { payload.fill(0) }
                    }
                } catch (_: Exception) { /* Terminal flush remains best effort. */ }
                finally { close() }
            }
            scheduler.schedule({ close() }, 1, TimeUnit.SECONDS)
        } catch (_: Exception) { close() }
    }
    private fun enqueue(action: () -> Unit) {
        if (closed.get() || draining.get()) return
        try {
            writer.execute {
                if (!closed.get()) {
                    try { check(isCurrent()); action() } catch (_: Exception) { close() }
                }
            }
        } catch (_: Exception) { close() }
    }
    private fun write(plain: ByteArray) {
        writeStarted.set(nowMs())
        try {
            val packet = secure.encrypt(plain)
            try { GroupSocketPackets.write(output, packet, 65_560) } finally { packet.fill(0) }
        } finally { plain.fill(0); writeStarted.set(0) }
    }
    internal fun readLoop() {
        try {
            socket.soTimeout = 8_000
            val input = DataInputStream(socket.getInputStream())
            while (!closed.get() && isCurrent()) {
                val encrypted = GroupSocketPackets.read(input, 65_560)
                val plain = try { secure.decrypt(encrypted) } finally { encrypted.fill(0) }
                try {
                    check(plain.isNotEmpty())
                    when (plain[0].toInt()) {
                        0 -> check(plain.size == 1)
                        1 -> { check(plain.size > 1); onMessage(this, plain.copyOfRange(1, plain.size)) }
                        else -> error("Unknown transport payload")
                    }
                    lastRead.set(nowMs())
                } finally { plain.fill(0) }
            }
        } catch (_: Exception) { /* Public result is loss, never raw decrypted diagnostics. */ }
        finally { close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Socket first: a blocked writer/reader must wake before any key monitor is acquired.
        runCatching { socket.close() }
        synchronized(timerLock) { watchdog?.cancel(false); heartbeat?.cancel(false) }
        writer.shutdownNow(); secure.close()
        runCatching { onClosed(this) }
    }
}

internal object GroupSocketPackets {
    fun read(input: DataInputStream, max: Int): ByteArray {
        val size = input.readInt(); require(size in 1..max)
        return ByteArray(size).also(input::readFully)
    }
    fun write(output: DataOutputStream, bytes: ByteArray, max: Int) {
        require(bytes.size in 1..max)
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    fun header(context: GroupAuthContext): ByteArray = with(GroupBinary) {
        encode(128) { writeInt(0x4d434748); writeByte(1); context(context) }
    }
    fun header(bytes: ByteArray): GroupAuthContext = with(GroupBinary) {
        decode(bytes, 128) { require(readInt() == 0x4d434748 && readUnsignedByte() == 1); context() }
    }
    fun authenticate(socket: Socket, context: GroupAuthContext, role: GroupAuthRole, code: GroupJoinCode,
        current: () -> Boolean): SecureGroupChannel {
        val auth = GroupAuthentication(context, role, code, ::groupNowMs, current)
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        try {
            write(output, auth.start(), 4096)
            repeat(3) { round ->
                check(current())
                val response = auth.receive(read(input, 4096))
                if (round < 2) write(output, checkNotNull(response), 4096) else check(response == null)
            }
            check(current())
            return auth.takeChannel()
        } finally { auth.close() }
    }
}

/** At most seven sockets, of which at most four are unauthenticated. Capacity is decided by the writer. */
internal class GroupSocketHost(
    private val descriptor: GroupDescriptor,
    private val code: GroupJoinCode,
    private val bindAddress: InetAddress,
    private val port: Int,
    private val isCurrent: () -> Boolean,
    private val onAuthenticated: (GroupSocketChannel) -> Unit,
    private val onMessage: (GroupSocketChannel, ByteArray) -> Unit,
    private val onClosed: (GroupSocketChannel) -> Unit,
    private val onError: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val socketLock = Any()
    private val sockets = mutableSetOf<Socket>()
    private val connections = mutableSetOf<GroupSocketChannel>()
    private val pending = Semaphore(4)
    private val budget = com.kuma.motointercom.group.network.GroupBleBudget(::groupNowMs)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "group-host-timers").apply { isDaemon = true } }
    private val readers = ThreadPoolExecutor(7, 7, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { task -> Thread(task, "group-control-read").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val server = ServerSocket()
    @Volatile private var started = false
    val localPort: Int get() = server.localPort
    fun start() {
        check(!started && !closed.get()); started = true
        try { server.reuseAddress = true; server.bind(InetSocketAddress(bindAddress, port), 4) }
        catch (failure: Exception) { close(); throw IOException("Cannot open room control listener") }
        Thread({ acceptLoop() }, "group-control-accept").apply { isDaemon = true; start() }
    }
    private fun acceptLoop() {
        try {
            while (!closed.get() && isCurrent()) {
                val socket = server.accept()
                val accepted = synchronized(socketLock) {
                    if (closed.get() || sockets.size >= 7 ||
                        !budget.acquire(socket.inetAddress.hostAddress ?: "unknown", 4 - pending.availablePermits()) || !pending.tryAcquire()) false
                    else { sockets.add(socket); true }
                }
                if (!accepted) { socket.close(); continue }
                try { readers.execute { serve(socket) } }
                catch (_: Exception) {
                    pending.release(); synchronized(socketLock) { sockets.remove(socket) }; socket.close()
                }
            }
        } catch (_: Exception) { if (!closed.get()) runCatching(onError) }
        finally { close() }
    }
    private fun serve(socket: Socket) {
        var pendingPermit = true
        var secure: SecureGroupChannel? = null
        var connection: GroupSocketChannel? = null
        var timeout: ScheduledFuture<*>? = null
        try {
            timeout = scheduler.schedule({ runCatching { socket.close() } }, 20, TimeUnit.SECONDS)
            socket.soTimeout = 20_000; socket.tcpNoDelay = true
            val context = GroupSocketPackets.header(GroupSocketPackets.read(DataInputStream(socket.getInputStream()), 128))
            check(context.room == descriptor.room && context.host == descriptor.host)
            secure = GroupSocketPackets.authenticate(socket, context, GroupAuthRole.HOST, code) { !closed.get() && !socket.isClosed && isCurrent() }
            check(!closed.get() && !socket.isClosed && isCurrent())
            val channel = GroupSocketChannel(socket, context, secure, scheduler,
                { !closed.get() && isCurrent() }, onMessage, onClosed)
            secure = null
            connection = channel
            synchronized(socketLock) {
                check(!closed.get()); connections.add(channel)
            }
            pending.release(); pendingPermit = false
            timeout.cancel(false)
            channel.startTimers()
            onAuthenticated(channel)
            channel.readLoop()
        } catch (_: Exception) { /* No unauthenticated peer is published as admitted. */ }
        finally {
            timeout?.cancel(false)
            if (pendingPermit) pending.release()
            runCatching { socket.close() }; connection?.close(); secure?.close()
            synchronized(socketLock) { sockets.remove(socket); connections.remove(connection) }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        val active = synchronized(socketLock) {
            sockets.forEach { runCatching { it.close() } }
            connections.toList()
        }
        active.forEach { it.close() }
        readers.shutdownNow(); scheduler.shutdownNow()
    }
}

/** A fresh attempt for every reconnect. Cancellation owns even the pre-authentication socket. */
internal class GroupSocketClient(
    private val context: GroupAuthContext,
    private val code: GroupJoinCode,
    private val address: InetSocketAddress,
    private val bindSocket: (Socket) -> Unit,
    private val isCurrent: () -> Boolean,
    private val onAuthenticated: (GroupSocketChannel) -> Unit,
    private val onMessage: (GroupSocketChannel, ByteArray) -> Unit,
    private val onClosed: (GroupSocketChannel) -> Unit,
    private val onFailed: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val socket = Socket()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "group-client-timers").apply { isDaemon = true } }
    @Volatile private var channel: GroupSocketChannel? = null
    fun start() {
        check(started.compareAndSet(false, true) && !closed.get())
        Thread({ run() }, "group-client-control").apply { isDaemon = true; start() }
    }
    private fun run() {
        var secure: SecureGroupChannel? = null
        var timeout: ScheduledFuture<*>? = null
        try {
            check(!closed.get() && isCurrent())
            timeout = scheduler.schedule({ runCatching { socket.close() } }, 10, TimeUnit.SECONDS)
            bindSocket(socket)
            socket.connect(address, 10_000)
            timeout.cancel(false)
            timeout = scheduler.schedule({ runCatching { socket.close() } }, 20, TimeUnit.SECONDS)
            socket.soTimeout = 20_000; socket.tcpNoDelay = true
            GroupSocketPackets.write(DataOutputStream(socket.getOutputStream()), GroupSocketPackets.header(context), 128)
            secure = GroupSocketPackets.authenticate(socket, context, GroupAuthRole.CLIENT, code) { !closed.get() && !socket.isClosed && isCurrent() }
            check(!closed.get() && !socket.isClosed && isCurrent())
            val connection = GroupSocketChannel(socket, context, secure, scheduler,
                { !closed.get() && isCurrent() }, onMessage, onClosed)
            secure = null; channel = connection
            check(!closed.get() && isCurrent())
            timeout.cancel(false)
            connection.startTimers()
            onAuthenticated(connection)
            connection.readLoop()
        } catch (_: Exception) { if (!closed.get()) runCatching(onFailed) }
        finally { timeout?.cancel(false); secure?.close(); close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }; channel?.close(); scheduler.shutdownNow()
    }
}
