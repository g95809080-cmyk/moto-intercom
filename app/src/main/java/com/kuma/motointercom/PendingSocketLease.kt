package com.kuma.motointercom

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns the physical socket until the Service atomically installs the verified session. */
internal class PendingSocketLease(
    val socket: Socket,
    val originatingAttempt: ConnectionAttempt?,
    private val admissionDeadlineElapsedMs: Long,
    private val clock: MonotonicClock,
    private val onReleased: (PendingSocketLease) -> Unit = {}
) : Closeable {
    internal enum class Stage { CONNECTING, HELLO, ADMISSION_PENDING, TRANSFERRED, CLOSED }

    private val lock = Any()
    private var stage = Stage.CONNECTING
    private var closeResource: () -> Unit = { socket.close() }
    private var admissionTimer: ScheduledFuture<*>? = null
    private var helloTimer: ScheduledFuture<*>? = null
    private var helloDeadlineElapsedMs = 0L
    private var adapterCurrent: (() -> Boolean)? = null
    private var onTransferred: () -> Unit = {}

    val currentStage: Stage get() = synchronized(lock) { stage }

    // Called only after registration in the adapter's pending registry.
    fun armAdmissionDeadline() {
        synchronized(lock) {
            if (stage == Stage.CLOSED || stage == Stage.TRANSFERRED || admissionTimer != null) return
            admissionTimer = schedule(admissionDeadlineElapsedMs) { expire(helloOnly = false) }
        }
    }

    fun beginHello() {
        synchronized(lock) {
            requirePending(Stage.CONNECTING, admissionDeadlineElapsedMs)
            stage = Stage.HELLO
            helloDeadlineElapsedMs = minOf(
                Math.addExact(clock.now().elapsedRealtimeMs, WHOLE_HELLO_TIMEOUT_MS),
                admissionDeadlineElapsedMs
            )
            helloTimer = schedule(helloDeadlineElapsedMs) { expire(helloOnly = true) }
        }
    }

    /** One absolute budget covers both the length prefix and all payload reads. */
    fun newHelloFrameReadGuard(): () -> Unit {
        val deadline = synchronized(lock) {
            requirePending(Stage.HELLO, helloDeadlineElapsedMs)
            minOf(
                Math.addExact(clock.now().elapsedRealtimeMs, HELLO_FRAME_TIMEOUT_MS),
                helloDeadlineElapsedMs
            )
        }
        return {
            val remaining = synchronized(lock) {
                requirePending(Stage.HELLO, deadline)
                deadline - clock.now().elapsedRealtimeMs
            }
            if (remaining <= 0L) throw SignalingV2Exception("HELLO frame deadline expired")
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    fun completeHello(session: SignalingSessionV2): Boolean = synchronized(lock) {
        if (stage != Stage.HELLO || socket.isClosed ||
            clock.now().elapsedRealtimeMs >= helloDeadlineElapsedMs ||
            clock.now().elapsedRealtimeMs >= admissionDeadlineElapsedMs
        ) return@synchronized false
        closeResource = { session.close() }
        stage = Stage.ADMISSION_PENDING
        helloTimer?.cancel(false)
        helloTimer = null
        true
    }

    fun prepareAdmission(
        isAdapterCurrent: () -> Boolean,
        onTransferred: () -> Unit = {}
    ): Boolean = synchronized(lock) {
        if (stage != Stage.ADMISSION_PENDING || adapterCurrent != null || socket.isClosed ||
            clock.now().elapsedRealtimeMs >= admissionDeadlineElapsedMs
        ) return@synchronized false
        adapterCurrent = isAdapterCurrent
        this.onTransferred = onTransferred
        true
    }

    /** Callbacks here only inspect current identities and insert into the Service map. No I/O. */
    fun tryTransfer(isServiceCurrent: () -> Boolean, install: () -> Unit): Boolean {
        val acknowledgment = synchronized(lock) {
            if (stage != Stage.ADMISSION_PENDING || socket.isClosed ||
                clock.now().elapsedRealtimeMs >= admissionDeadlineElapsedMs ||
                adapterCurrent?.invoke() != true || !isServiceCurrent()
            ) return false
            install()
            stage = Stage.TRANSFERRED
            admissionTimer?.cancel(false)
            helloTimer?.cancel(false)
            admissionTimer = null
            helloTimer = null
            onTransferred
        }
        onReleased(this)
        acknowledgment()
        return true
    }

    override fun close() {
        val resource = synchronized(lock) { revokeLocked() } ?: return
        release(resource)
    }

    private fun expire(helloOnly: Boolean) {
        val resource = synchronized(lock) {
            if (helloOnly && stage != Stage.HELLO) return
            val deadline = if (helloOnly) helloDeadlineElapsedMs else admissionDeadlineElapsedMs
            if (stage == Stage.TRANSFERRED || stage == Stage.CLOSED) return
            if (clock.now().elapsedRealtimeMs < deadline) {
                if (helloOnly) helloTimer = schedule(deadline) { expire(true) }
                else admissionTimer = schedule(deadline) { expire(false) }
                return
            }
            revokeLocked()
        } ?: return
        release(resource)
    }

    private fun revokeLocked(): (() -> Unit)? {
        if (stage == Stage.TRANSFERRED || stage == Stage.CLOSED) return null
        stage = Stage.CLOSED
        admissionTimer?.cancel(false)
        helloTimer?.cancel(false)
        admissionTimer = null
        helloTimer = null
        return closeResource
    }

    private fun release(resource: () -> Unit) {
        try { runCatching(resource) } finally { onReleased(this) }
    }

    private fun requirePending(expected: Stage, deadline: Long) {
        if (stage != expected || socket.isClosed || clock.now().elapsedRealtimeMs >= deadline) {
            throw SignalingV2Exception("pending Socket is closed or its HELLO budget expired")
        }
    }

    private fun schedule(deadline: Long, action: () -> Unit): ScheduledFuture<*> =
        watchdog.schedule(
            action,
            (deadline - clock.now().elapsedRealtimeMs).coerceAtLeast(1L),
            TimeUnit.MILLISECONDS
        )

    companion object {
        const val HELLO_FRAME_TIMEOUT_MS = 1_000L
        const val WHOLE_HELLO_TIMEOUT_MS = 2_000L
        const val PASSIVE_ADMISSION_TIMEOUT_MS = 3_000L
        private val watchdog = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "MotoIntercom-pending-socket").apply { isDaemon = true }
        }
    }
}
