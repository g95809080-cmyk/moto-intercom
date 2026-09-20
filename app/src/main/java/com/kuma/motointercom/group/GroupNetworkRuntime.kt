package com.kuma.motointercom.group

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.group.network.*
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface GroupNetworkEffects {
    fun search(effect: GroupSessionEffect.Search)
    fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode)
    fun join(effect: GroupSessionEffect.JoinNetwork)
    fun connect(effect: GroupSessionEffect.ConnectControl)
    fun send(effect: GroupSessionEffect.Send)
    fun closeChannel(id: UUID)
    fun admit(id: UUID)
    fun stop(flush: Boolean, done: () -> Unit)
}
/** Network resource owner only; membership decisions always return to the writer. */
internal class GroupNetworkRuntime(
    context: Context,
    private val endpoint: GroupAuthEndpoint,
    private val snapshot: () -> GroupSessionSnapshot,
    private val dispatch: (GroupSessionEvent) -> Unit
) : GroupNetworkEffects {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var search: GroupCandidateSearch? = null
    @Volatile private var wifiHost: GroupWifiHost? = null
    private var wifiClient: GroupWifiClient? = null
    private var bootstrap: GroupBootstrapHost? = null
    private var server: GroupSocketHost? = null
    @Volatile private var client: GroupSocketClient? = null
    private var network: Network? = null
    private var joinedBefore = false
    private val channels = ConcurrentHashMap<UUID, GroupSocketChannel>()
    private val ingress = ConcurrentHashMap<UUID, GroupBoundedCallbacks>()
    private fun current(attempt: UUID) = !stopped && snapshot().networkAttempt == attempt
    private fun post(action: () -> Unit) { main.post { action() } }
    override fun search(effect: GroupSessionEffect.Search) {
        search?.close()
        search = GroupCandidateSearch(context, endpoint, effect.code, { index, total ->
            if (!stopped) dispatch(GroupSessionEvent.SearchProgress(effect.operation, index, total))
        }, {
            if (!stopped && snapshot().operation == effect.operation) dispatch(GroupSessionEvent.Found(effect.operation, it))
        }, {
            if (!stopped) dispatch(GroupSessionEvent.Failed(effect.operation, it))
        }).also { it.start() }
    }
    override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
        closeControl(); bootstrap?.close(); bootstrap = null
        releaseHost {
            if (!current(attempt)) return@releaseHost
            lateinit var adapter: GroupWifiHost
            adapter = GroupWifiHost(context, { credentials, address ->
                if (!current(attempt) || wifiHost !== adapter) return@GroupWifiHost
                try {
                    val root = snapshot().operation!!
                    val socketHost = GroupSocketHost(descriptor, code, address, 8899,
                        { current(attempt) && wifiHost === adapter },
                        { authenticated(it, root, attempt) }, ::message, ::closed,
                        { post { if (current(attempt)) dispatch(GroupSessionEvent.NetworkLost(attempt)) } })
                    server = socketHost; socketHost.start()
                    val description = GroupNetworkDescriptor(credentials, address.hostAddress!!, 8899)
                    bootstrap = GroupBootstrapHost(context, descriptor, code, { description },
                        { it.deviceId !in snapshot().removed }, { current(attempt) && wifiHost === adapter },
                        { if (current(attempt)) dispatch(GroupSessionEvent.HostReady(attempt)) },
                        { if (current(attempt)) dispatch(GroupSessionEvent.NetworkFailed(attempt, it)) }).also { it.start() }
                } catch (_: Exception) { dispatch(GroupSessionEvent.NetworkFailed(attempt, "创建房间连接失败")) }
            }, {
                if (current(attempt) && wifiHost === adapter) {
                    if (snapshot().phase == GroupPhase.CREATING) dispatch(GroupSessionEvent.NetworkFailed(attempt, it))
                    else dispatch(GroupSessionEvent.NetworkLost(attempt))
                }
            })
            wifiHost = adapter; adapter.start()
        }
    }
    override fun join(effect: GroupSessionEffect.JoinNetwork) {
        closeControl(); wifiClient?.close(); wifiClient = null; network = null
        search?.close(); search = null
        val attempt = effect.operation
        if (!joinedBefore) { joinedBefore = true; joinVerified(attempt, effect.match); return }
        val code = snapshot().code ?: return
        search = GroupCandidateSearch(context, endpoint, code, { _, _ -> }, { matches ->
            if (current(attempt)) {
                val match = matches.singleOrNull { it.descriptor.room == effect.match.descriptor.room && it.descriptor.host == effect.match.descriptor.host }
                if (match == null) dispatch(GroupSessionEvent.NetworkFailed(attempt, "正在寻找原房间"))
                else {
                    dispatch(GroupSessionEvent.NetworkRefreshed(attempt, match))
                    joinVerified(attempt, match)
                }
            }
        }, { if (current(attempt)) dispatch(GroupSessionEvent.NetworkFailed(attempt, it)) }).also { it.start() }
    }
    private fun joinVerified(attempt: UUID, match: GroupBootstrapMatch) {
        if (!current(attempt)) return
        lateinit var adapter: GroupWifiClient
        adapter = GroupWifiClient(context, match.network.credentials, {
            if (current(attempt) && wifiClient === adapter) { network = it; dispatch(GroupSessionEvent.NetworkReady(attempt)) }
        }, {
            if (current(attempt) && wifiClient === adapter) {
                network = null
                dispatch(GroupSessionEvent.NetworkFailed(attempt, it))
            }
        })
        wifiClient = adapter; adapter.start()
    }
    override fun connect(effect: GroupSessionEffect.ConnectControl) {
        client?.close()
        val target = network ?: throw IllegalStateException("No current local network")
        val networkAttempt = snapshot().networkAttempt ?: return
        lateinit var adapter: GroupSocketClient
        adapter = GroupSocketClient(effect.context, effect.code,
            InetSocketAddress(effect.match.network.hostAddress, effect.match.network.port), target::bindSocket,
            { current(networkAttempt) && client === adapter },
            { authenticated(it, effect.attempt, networkAttempt) }, ::message, ::closed,
            { post { if (!stopped && client === adapter) dispatch(GroupSessionEvent.ControlFailed(effect.attempt)) } })
        client = adapter; adapter.start()
    }
    private fun authenticated(channel: GroupSocketChannel, operation: UUID, attempt: UUID) {
        val authenticatedAt = groupNowMs()
        val queue = GroupBoundedCallbacks(::post, { channel.close() })
        channels[channel.id] = channel; ingress[channel.id] = queue
        queue.post {
            if (current(attempt) && channels[channel.id] === channel) {
                dispatch(GroupSessionEvent.Authenticated(operation, channel.id, channel.context, authenticatedAt))
            } else channel.close()
        }
    }
    private fun message(channel: GroupSocketChannel, bytes: ByteArray) {
        try {
            val decoded = GroupControlCodec.decode(bytes, groupNowMs())
            ingress[channel.id]?.post {
                if (!stopped && channels[channel.id] === channel) dispatch(GroupSessionEvent.Control(channel.id, decoded))
            }
        } catch (_: Exception) { channel.close() }
        finally { bytes.fill(0) }
    }
    private fun closed(channel: GroupSocketChannel) {
        ingress.remove(channel.id)?.close(); channels.remove(channel.id, channel)
        post { if (!stopped) dispatch(GroupSessionEvent.Closed(channel.id)) }
    }
    override fun send(effect: GroupSessionEffect.Send) {
        val channel = channels[effect.channel] ?: return
        val build: (Long) -> ByteArray = { GroupControlCodec.encode(effect.build(it), groupNowMs()) }
        if (effect.terminal) channel.sendFinal(build) else channel.send(build)
    }
    override fun closeChannel(id: UUID) { channels[id]?.close() }
    override fun admit(id: UUID) { channels[id]?.markAdmitted() }
    private fun closeControl() {
        ingress.values.forEach { it.close() }; ingress.clear()
        channels.values.toList().forEach { it.close() }; channels.clear()
        client?.close(); client = null; server?.close(); server = null
    }
    /** This application-context owner survives UI/service detach until GO release is known. */
    private var releasingHost: GroupWifiHost? = null
    private val releaseWaiters = mutableListOf<() -> Unit>()
    private var releaseRetry: Runnable? = null
    private fun releaseHost(done: () -> Unit) {
        val old = wifiHost ?: return done()
        releaseWaiters += done
        if (releasingHost != null) return
        releasingHost = old
        pollHostRelease(old)
    }
    private fun pollHostRelease(old: GroupWifiHost) {
        if (releasingHost !== old) return
        old.retryCleanup()
        old.close { result ->
            if (releasingHost !== old) return@close
            if (result == GroupNetworkCloseResult.RELEASED) {
                releaseRetry?.let(main::removeCallbacks); releaseRetry = null
                releasingHost = null
                if (wifiHost === old) wifiHost = null
                val waiters = releaseWaiters.toList(); releaseWaiters.clear()
                waiters.forEach { runCatching { it() } }
            } else if (releaseRetry == null) {
                releaseRetry = Runnable { releaseRetry = null; pollHostRelease(old) }
                    .also { main.postDelayed(it, 1_000) }
            }
        }
    }
    override fun stop(flush: Boolean, done: () -> Unit) {
        if (stopped) return
        stopped = true
        search?.close(); search = null; bootstrap?.close(); bootstrap = null
        val finish = {
            closeControl(); wifiClient?.close(); wifiClient = null; network = null
            releaseHost(done)
        }
        if (flush) main.postDelayed(finish, 1_000) else finish()
    }
}
