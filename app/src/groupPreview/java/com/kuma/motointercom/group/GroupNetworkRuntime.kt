package com.kuma.motointercom.group

import android.content.Context
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.group.network.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface GroupNetworkEffects {
    fun search(effect: GroupSessionEffect.Search)
    fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode)
    fun hostDirect(
        attempt: UUID,
        descriptor: GroupDescriptor,
        code: GroupJoinCode?,
        preset: GroupWifiP2pPreset,
        enableBleBootstrap: Boolean = false
    ) {
        if (code != null) host(attempt, descriptor, code)
    }
    fun startDirectControlServer(
        attempt: UUID,
        descriptor: GroupDescriptor,
        code: GroupJoinCode
    ) = Unit
    fun join(effect: GroupSessionEffect.JoinNetwork)
    fun joinDirect(
        attempt: UUID,
        preset: GroupWifiP2pPreset,
        expectedHostAddress: String? = null
    ) = Unit
    fun connect(effect: GroupSessionEffect.ConnectControl)
    fun connectDirect(
        attempt: UUID,
        context: GroupAuthContext,
        code: GroupJoinCode,
        hostAddress: String,
        port: Int
    ) = Unit
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
    @Volatile private var wifiP2pClient: GroupWifiP2pClient? = null
    private var bootstrap: GroupBootstrapHost? = null
    private var server: GroupSocketHost? = null
    @Volatile private var client: GroupSocketClient? = null
    private var network: Network? = null
    private var directSocketLink: GroupLocalSocketLink? = null
    private var activeHostAddress: Inet4Address? = null
    private var activeHostFrequencyMhz: Int? = null
    private var activeHostAttempt: UUID? = null
    private val channels = ConcurrentHashMap<UUID, GroupSocketChannel>()
    private val ingress = ConcurrentHashMap<UUID, GroupBoundedCallbacks>()
    private fun current(attempt: UUID) = !stopped && snapshot().networkAttempt == attempt
    private fun post(action: () -> Unit) { main.post { action() } }
    override fun search(effect: GroupSessionEffect.Search) {
        search?.close()
        wifiClient?.let { old ->
            wifiClient = null
            network = null
            closeControl()
            old.close()
        }
        lateinit var scan: GroupCandidateSearch
        fun active() = !stopped && search === scan &&
            (snapshot().operation == effect.operation || snapshot().networkAttempt == effect.operation)
        scan = GroupCandidateSearch(context, endpoint, effect.code, { index, total ->
            if (active()) dispatch(GroupSessionEvent.SearchProgress(effect.operation, index, total))
        }, {
            if (active()) dispatch(GroupSessionEvent.Found(effect.operation, it))
        }, {
            if (active()) dispatch(GroupSessionEvent.Failed(effect.operation, it))
        })
        search = scan
        scan.start()
    }
    override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
        closeControl(); bootstrap?.close(); bootstrap = null
        releaseP2pClient {
            releaseHost {
            if (!current(attempt)) return@releaseHost
            lateinit var adapter: GroupWifiHost
            var reportedFrequency: Int? = null
            adapter = GroupWifiHost(context, { credentials, address ->
                if (!current(attempt) || wifiHost !== adapter) return@GroupWifiHost
                try {
                    activeHostAddress = address
                    activeHostFrequencyMhz = reportedFrequency
                    activeHostAttempt = attempt
                    val root = snapshot().operation!!
                    val socketHost = GroupSocketHost(descriptor, code, address, 8899,
                        { current(attempt) && wifiHost === adapter },
                        { authenticated(it, root, attempt) }, ::message, ::closed,
                        { post { if (current(attempt)) dispatch(GroupSessionEvent.NetworkLost(attempt)) } })
                    server = socketHost; socketHost.start()
                    val description = GroupNetworkDescriptor(credentials, address.hostAddress!!, 8899)
                    bootstrap = GroupBootstrapHost(context, descriptor, code, { description },
                        { it.deviceId !in snapshot().removed }, { current(attempt) && wifiHost === adapter },
                        { if (current(attempt)) dispatch(GroupSessionEvent.HostReady(attempt, address.hostAddress, reportedFrequency)) },
                        { if (current(attempt)) dispatch(GroupSessionEvent.NetworkFailed(attempt, it)) }).also { it.start() }
                } catch (_: Exception) { dispatch(GroupSessionEvent.NetworkFailed(attempt, "创建房间连接失败")) }
            }, {
                if (current(attempt) && wifiHost === adapter) {
                    if (snapshot().phase == GroupPhase.CREATING) dispatch(GroupSessionEvent.NetworkFailed(attempt, it))
                    else dispatch(GroupSessionEvent.NetworkLost(attempt))
                }
            }, onGroupFrequency = { reportedFrequency = it })
            wifiHost = adapter; adapter.start()
            }
        }
    }

    override fun hostDirect(
        attempt: UUID,
        descriptor: GroupDescriptor,
        code: GroupJoinCode?,
        preset: GroupWifiP2pPreset,
        enableBleBootstrap: Boolean
    ) {
        closeControl(); bootstrap?.close(); bootstrap = null
        activeHostAddress = null; activeHostFrequencyMhz = null; activeHostAttempt = null
        releaseP2pClient {
            releaseHost {
                if (!current(attempt)) return@releaseHost
                lateinit var adapter: GroupWifiHost
                var reportedFrequency: Int? = null
                adapter = GroupWifiHost(
                    context = context,
                    onReady = { credentials, address ->
                        if (!current(attempt) || wifiHost !== adapter) return@GroupWifiHost
                        try {
                            activeHostAddress = address
                            activeHostFrequencyMhz = reportedFrequency
                            activeHostAttempt = attempt
                            if (code != null) {
                                val root = snapshot().operation!!
                                val socketHost = GroupSocketHost(
                                    descriptor,
                                    code,
                                    address,
                                    8899,
                                    { current(attempt) && wifiHost === adapter },
                                    { authenticated(it, root, attempt) },
                                    ::message,
                                    ::closed,
                                    { post { if (current(attempt)) dispatch(GroupSessionEvent.NetworkLost(attempt)) } }
                                )
                                server = socketHost; socketHost.start()
                                if (enableBleBootstrap) {
                                    val description = GroupNetworkDescriptor(credentials, address.hostAddress!!, 8899)
                                    runCatching {
                                        bootstrap = GroupBootstrapHost(
                                            context,
                                            descriptor,
                                            code,
                                            { description },
                                            { it.deviceId !in snapshot().removed },
                                            { current(attempt) && wifiHost === adapter },
                                            {},
                                            {}
                                        ).also { it.start() }
                                    }
                                }
                            }
                            if (current(attempt)) {
                                dispatch(GroupSessionEvent.HostReady(attempt, address.hostAddress, reportedFrequency))
                            }
                        } catch (_: Exception) {
                            dispatch(GroupSessionEvent.NetworkFailed(attempt, "创建直连房间服务失败"))
                        }
                    },
                    onError = {
                        if (current(attempt) && wifiHost === adapter) {
                            if (snapshot().phase == GroupPhase.CREATING) dispatch(GroupSessionEvent.NetworkFailed(attempt, it))
                            else dispatch(GroupSessionEvent.NetworkLost(attempt))
                        }
                    },
                    preset = if (Build.VERSION.SDK_INT >= 29) preset else null,
                    onGroupFrequency = { reportedFrequency = it }
                )
                wifiHost = adapter
                adapter.start()
            }
        }
    }

    override fun startDirectControlServer(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
        val address = activeHostAddress
        val hostAdapter = wifiHost
        if (!current(attempt) || address == null || hostAdapter == null) {
            dispatch(GroupSessionEvent.NetworkFailed(attempt, "本地主机网络尚未就绪"))
            return
        }
        closeControl()
        try {
            val root = snapshot().operation!!
            val socketHost = GroupSocketHost(
                descriptor,
                code,
                address,
                8899,
                { current(attempt) && wifiHost === hostAdapter },
                { authenticated(it, root, attempt) },
                ::message,
                ::closed,
                { post { if (current(attempt)) dispatch(GroupSessionEvent.NetworkLost(attempt)) } }
            )
            server = socketHost
            socketHost.start()
            activeHostAttempt = attempt
            dispatch(GroupSessionEvent.HostReady(attempt, address.hostAddress, activeHostFrequencyMhz))
        } catch (_: Exception) {
            dispatch(GroupSessionEvent.NetworkFailed(attempt, "启动本地预连控制服务失败"))
        }
    }
    override fun join(effect: GroupSessionEffect.JoinNetwork) {
        closeControl(); wifiClient?.close(); wifiClient = null; network = null; directSocketLink = null
        search?.close(); search = null
        val attempt = effect.operation
        val code = effect.refreshCode
        if (code == null) { joinVerified(attempt, effect.match); return }
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

    override fun joinDirect(attempt: UUID, preset: GroupWifiP2pPreset, expectedHostAddress: String?) {
        closeControl()
        wifiClient?.close(); wifiClient = null; network = null
        search?.close(); search = null
        directSocketLink = null
        releaseHost {
            releaseP2pClient {
                if (!current(attempt)) return@releaseP2pClient
                lateinit var adapter: GroupWifiP2pClient
                adapter = GroupWifiP2pClient(
                    context = context,
                    preset = preset,
                    expectedHostAddress = expectedHostAddress,
                    onReady = { link ->
                        if (current(attempt) && wifiP2pClient === adapter) {
                            directSocketLink = link
                            dispatch(GroupSessionEvent.NetworkReady(attempt, link.hostAddress))
                        }
                    },
                    onError = { message ->
                        if (current(attempt) && wifiP2pClient === adapter) {
                            val hadLink = directSocketLink != null
                            directSocketLink = null
                            if (hadLink) dispatch(GroupSessionEvent.NetworkLost(attempt))
                            else dispatch(GroupSessionEvent.NetworkFailed(attempt, message))
                        }
                    }
                )
                wifiP2pClient = adapter
                adapter.start()
            }
        }
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

    override fun connectDirect(
        attempt: UUID,
        context: GroupAuthContext,
        code: GroupJoinCode,
        hostAddress: String,
        port: Int
    ) {
        client?.close()
        val link = directSocketLink ?: throw IllegalStateException("No current direct P2P link")
        val networkAttempt = snapshot().networkAttempt ?: return
        lateinit var adapter: GroupSocketClient
        adapter = GroupSocketClient(
            context,
            code,
            InetSocketAddress(hostAddress.ifBlank { link.hostAddress }, port),
            link.bindSocket,
            { current(networkAttempt) && client === adapter },
            { authenticated(it, attempt, networkAttempt) },
            ::message,
            ::closed,
            { post { if (!stopped && client === adapter) dispatch(GroupSessionEvent.ControlFailed(attempt)) } }
        )
        client = adapter
        adapter.start()
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
        val queue = ingress[channel.id]
        if (queue == null) {
            channels.remove(channel.id, channel)
            post { if (!stopped) dispatch(GroupSessionEvent.Closed(channel.id)) }
            return
        }
        queue.finish {
            ingress.remove(channel.id, queue)
            channels.remove(channel.id, channel)
            if (!stopped) dispatch(GroupSessionEvent.Closed(channel.id))
        }
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
    private var releasingP2pClient: GroupWifiP2pClient? = null
    private val p2pClientReleaseWaiters = mutableListOf<() -> Unit>()
    private var p2pClientReleaseRetry: Runnable? = null
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
                activeHostAddress = null; activeHostFrequencyMhz = null; activeHostAttempt = null
                val waiters = releaseWaiters.toList(); releaseWaiters.clear()
                waiters.forEach { runCatching { it() } }
            } else if (releaseRetry == null) {
                releaseRetry = Runnable { releaseRetry = null; pollHostRelease(old) }
                    .also { main.postDelayed(it, 1_000) }
            }
        }
    }
    private fun releaseP2pClient(done: () -> Unit) {
        val old = wifiP2pClient ?: return done()
        p2pClientReleaseWaiters += done
        if (releasingP2pClient != null) return
        releasingP2pClient = old
        pollP2pClientRelease(old)
    }
    private fun pollP2pClientRelease(old: GroupWifiP2pClient) {
        if (releasingP2pClient !== old) return
        old.retryCleanup()
        old.close { result ->
            if (releasingP2pClient !== old) return@close
            if (result == GroupNetworkCloseResult.RELEASED) {
                p2pClientReleaseRetry?.let(main::removeCallbacks); p2pClientReleaseRetry = null
                releasingP2pClient = null
                if (wifiP2pClient === old) wifiP2pClient = null
                directSocketLink = null
                val waiters = p2pClientReleaseWaiters.toList(); p2pClientReleaseWaiters.clear()
                waiters.forEach { runCatching { it() } }
            } else if (p2pClientReleaseRetry == null) {
                p2pClientReleaseRetry = Runnable { p2pClientReleaseRetry = null; pollP2pClientRelease(old) }
                    .also { main.postDelayed(it, 1_000) }
            }
        }
    }
    override fun stop(flush: Boolean, done: () -> Unit) {
        if (stopped) return
        stopped = true
        search?.close(); search = null; bootstrap?.close(); bootstrap = null
        val finish = {
            closeControl(); wifiClient?.close(); wifiClient = null; network = null; directSocketLink = null
            releaseP2pClient { releaseHost(done) }
        }
        if (flush) main.postDelayed(finish, 1_000) else finish()
    }
}

