package com.kuma.motointercom.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kuma.motointercom.group.network.GroupBleClient
import com.kuma.motointercom.group.network.GroupBleDiscovery
import com.kuma.motointercom.group.network.GroupBleServer
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns no product state. Publication/disclosure predicates read the writer's immutable snapshot. */
internal class GroupBootstrapHost(
    context: Context,
    private val descriptor: GroupDescriptor,
    private val code: GroupJoinCode,
    private val network: () -> GroupNetworkDescriptor,
    private val mayDiscloseTo: (GroupAuthEndpoint) -> Boolean,
    private val isCurrent: () -> Boolean,
    onReady: () -> Unit,
    onError: (String) -> Unit
) : Closeable {
    private class Peer {
        val valid = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        @Volatile var session: GroupBootstrapHostSession? = null
        fun cancel() {
            valid.set(false)
            if (!busy.get()) session?.close()
        }
    }
    private val closed = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "group-bootstrap").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val peers = mutableMapOf<UUID, Peer>() // BLE callbacks run only on main.
    private val server = GroupBleServer(context, onReady, ::request, { id -> peers.remove(id)?.cancel() }, onError)
    fun start() = server.start()
    private fun request(id: UUID, bytes: ByteArray, reply: (ByteArray?) -> Unit) {
        if (closed.get() || !isCurrent()) { reply(null); return }
        val peer = peers.getOrPut(id) { Peer() }
        if (!peer.busy.compareAndSet(false, true)) { reply(null); return }
        try {
            workers.execute {
                try {
                    if (!peer.valid.get() || closed.get() || !isCurrent()) { reply(null); return@execute }
                    val session = peer.session ?: GroupBootstrapHostSession(descriptor, code, network,
                        mayDiscloseTo, SystemClock::elapsedRealtime,
                        { !closed.get() && peer.valid.get() && isCurrent() }).also { peer.session = it }
                    val response = session.respond(bytes)
                    reply(response.takeIf { !closed.get() && peer.valid.get() && isCurrent() })
                } catch (_: Exception) { peer.valid.set(false); reply(null) }
                finally {
                    bytes.fill(0)
                    peer.busy.set(false)
                    if (!peer.valid.get() || closed.get()) peer.session?.close()
                }
            }
        } catch (_: Exception) { peer.busy.set(false); peer.cancel(); reply(null) }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!closed.compareAndSet(false, true)) return
        server.close()
        peers.values.forEach(Peer::cancel); peers.clear()
        workers.shutdownNow()
    }
}

/** Complete a finite candidate set before selecting; multiple authenticated matches go to the UI. */
internal class GroupCandidateSearch(
    private val context: Context,
    private val endpoint: GroupAuthEndpoint,
    private val code: GroupJoinCode,
    private val onProgress: (Int, Int) -> Unit,
    private val onMatches: (List<GroupBootstrapMatch>) -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var client: GroupBleClient? = null // main-thread ownership
    private val discovery = GroupBleDiscovery(context, { candidates ->
        scope.launch {
            val matches = linkedMapOf<GroupRoomKey, GroupBootstrapMatch>()
            candidates.forEachIndexed { index, candidate ->
                ensureActive()
                withContext(Dispatchers.Main) { if (!closed.get()) onProgress(index + 1, candidates.size) }
                val ready = CompletableDeferred<Unit>()
                var pending: CompletableDeferred<ByteArray>? = null // accessed on main
                val connection = withContext(Dispatchers.Main) {
                    GroupBleClient(context, candidate, { ready.complete(Unit) }, {
                        ready.completeExceptionally(IOException("Candidate unavailable"))
                        pending?.completeExceptionally(IOException("Candidate unavailable"))
                    }).also { client = it; it.start() }
                }
                try {
                    ready.await()
                    val match = authenticateGroupCandidate(endpoint, code, SystemClock::elapsedRealtime, { !closed.get() }) { request ->
                        val response = CompletableDeferred<ByteArray>()
                        withContext(Dispatchers.Main) {
                            check(!closed.get())
                            pending = response
                            connection.exchange(request) { response.complete(it) }
                        }
                        response.await()
                    }
                    ensureActive()
                    matches[match.descriptor.room] = match
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Unverified candidates never become rooms. */ }
                finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        pending = null
                        connection.close()
                        if (client === connection) client = null
                    }
                }
            }
            withContext(Dispatchers.Main) { if (!closed.get()) onMatches(matches.values.toList()) }
        }
    }, { if (!closed.get()) onError(it) })
    fun start() { check(!closed.get()); discovery.start() }
    override fun close() {
        check(Looper.myLooper() == main.looper)
        if (!closed.compareAndSet(false, true)) return
        scope.cancel(); discovery.close(); client?.close(); client = null
    }
}
