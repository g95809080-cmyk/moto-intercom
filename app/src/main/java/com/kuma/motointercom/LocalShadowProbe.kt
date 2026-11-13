package com.kuma.motointercom

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

internal data class ShadowProbeNetworks(val lanKey: String?,val p2pAllowed: Boolean)
internal interface ShadowDiscoveryAdapter {
    val source: FreshDiscoverySource
    fun start(): Boolean
    fun connect(attempt: ConnectionAttempt): Boolean
    fun refresh()
    fun retain(attempt: ConnectionAttempt,session: SignalingSessionV2): Boolean = true
    fun closeWhenReleased(callback: (Boolean)->Unit)
}
internal fun interface ShadowAdapterFactory {
    fun create(transport: Transport,runtime: RuntimeSessionId,onSession: (SignalingSessionV2,PendingSocketLease)->Unit,
        onObservation: (FreshDiscoveryObservation)->Unit,onFailure: (ConnectionAttempt?)->Unit): ShadowDiscoveryAdapter
}

// One independent discovery runtime. Network changes replace adapters only after
// their exact close fence; no normal local runtime or second audio owner starts.
internal class LocalShadowProbe(private val context: Context,private val localId: String,private val peerId: String,
    private val onSelected: (SignalingSessionV2)->Unit,private val onLost: ()->Unit,
    private val keepSelectedP2p: ()->Boolean = {false},private val factory: ShadowAdapterFactory?=null,
    private val networks: ()->ShadowProbeNetworks = {readNetworks(context)}) : Closeable {
    private val handler=Handler(Looper.getMainLooper())
    private val sessions=SessionGeneration();private val token=sessions.start()
    private val runtime=RuntimeSessionId.create()
    private val release=ResourceReleaseBarrier()
    private val adapters=linkedMapOf<Transport,ShadowDiscoveryAdapter>()
    private val releases=linkedMapOf<ShadowDiscoveryAdapter,ResourceReleaseBarrier>()
    private val retiring=mutableSetOf<Transport>()
    private val latest=mutableMapOf<Transport,FreshDiscoveryObservation>()
    private val retryAfter=mutableMapOf<Transport,Long>()
    private var lanKey: String?=null
    private var closed=false
    private var selected: SignalingSessionV2?=null
    private var selectedAttempt: ConnectionAttempt?=null
    private var pending: ConnectionAttempt?=null
    private var lastRefresh=0L
    private var multicast: WifiManager.MulticastLock?=null
    private val cm=context.getSystemService(ConnectivityManager::class.java)
    private val registered=mutableListOf<ConnectivityManager.NetworkCallback>()
    private fun callback()=object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network)=main {refreshNetworks()}
        override fun onLost(network: Network)=main {refreshNetworks()}
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities)=main {refreshNetworks()}
        override fun onLinkPropertiesChanged(network: Network,linkProperties: LinkProperties)=main {refreshNetworks()}
    }
    private val tick=object : Runnable {
        override fun run() {if(!closed) {refreshNetworks();handler.postDelayed(this,2_000)}}
    }
    fun start() {
        check(!closed)
        if(factory==null) {
            multicast=runCatching {context.getSystemService(WifiManager::class.java).createMulticastLock("motocom-shadow").apply {setReferenceCounted(false);acquire()}}.getOrNull()
            if(Build.VERSION.SDK_INT>=24)runCatching {callback().also {cm.registerDefaultNetworkCallback(it);registered.add(it)}}
            runCatching {callback().also {cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),it);registered.add(it)}}
        }
        refreshNetworks();handler.postDelayed(tick,2_000)
    }
    internal fun refreshNetworks() {
        if(closed)return
        val state=runCatching {networks()}.getOrDefault(ShadowProbeNetworks(null,false))
        pending?.takeIf {it.deadlineElapsedRealtimeMs<=SystemClock.elapsedRealtime()}?.let {failed(it)}
        if(state.lanKey!=lanKey) {
            if(selected?.channel?.transport==Transport.LAN) {onLost();return}
            lanKey=state.lanKey
            retire(Transport.LAN)
        }
        if(state.lanKey!=null && Transport.LAN !in adapters && Transport.LAN !in retiring) create(Transport.LAN)
        if(!state.p2pAllowed) {
            if(selected?.channel?.transport==Transport.WIFI_DIRECT) {
                if(!keepSelectedP2p())onLost()
            } else retire(Transport.WIFI_DIRECT)
        } else if(Transport.WIFI_DIRECT !in adapters && Transport.WIFI_DIRECT !in retiring)create(Transport.WIFI_DIRECT)
        if(selected==null && pending==null && SystemClock.elapsedRealtime()-lastRefresh>=5_000)requestFresh()
    }
    private fun create(transport: Transport) {
        lateinit var adapter: ShadowDiscoveryAdapter
        adapter=(factory ?: androidFactory()).create(transport,runtime,
            {s,l->main {if(adapters[transport]!==adapter)l.close() else admit(s,l)}},
            {o->observed(o)},
            {attempt->main {if(adapters[transport]===adapter) {
                if(attempt!=null && selectedAttempt==attempt)onLost() else attempt?.let {failed(it)}
            }}})
        adapters[transport]=adapter;releases[adapter]=ResourceReleaseBarrier()
        if(!adapter.start())retire(transport,refreshAfter=false) else lastRefresh=SystemClock.elapsedRealtime()
    }
    private fun retire(transport: Transport,refreshAfter: Boolean=true) {
        val old=adapters.remove(transport) ?: return
        latest.remove(transport)
        if(pending?.preferredTransport==transport)pending=null
        retiring.add(transport)
        val barrier=releases[old]!!
        old.closeWhenReleased {ok->main {
            barrier.complete(ok)
            if(ok)retiring.remove(transport)
            if(ok && refreshAfter && !closed)handler.post {refreshNetworks()}
        }}
    }
    private fun observed(observation: FreshDiscoveryObservation)=main {
        val candidate=observation.candidate;val transport=candidate.transport
        if(closed || selected!=null || localId>=peerId || candidate.identity.claimedDeviceId!=peerId || !candidate.hasStableV2ObservationIdentity() ||
            adapters[transport]?.source!==observation.source)return@main
        latest[transport]=observation
        val now=SystemClock.elapsedRealtime()
        if(now-observation.receivedAtElapsedRealtimeMs !in 0..3_000 || now<(retryAfter[transport] ?: 0))return@main
        if(transport==Transport.WIFI_DIRECT && !runCatching {networks().p2pAllowed}.getOrDefault(false)) {refreshNetworks();return@main}
        if(pending?.let {it.deadlineElapsedRealtimeMs>now}==true)return@main
        observation.source.claimIfCurrent(observation) {
            val attempt=ConnectionAttempt(ConnectionAttemptId.create(),runtime,TargetLock(peerId,candidate.identity.sourceSessionId!!),
                ConnectionTrigger.RECOVERY,ChannelPlan.single(transport),now+15_000)
            pending=attempt
            if(adapters[transport]?.connect(attempt)!=true)failed(attempt)
            attempt
        }
    }
    private fun failed(attempt: ConnectionAttempt) {
        if(pending!=attempt)return
        pending=null;retryAfter[attempt.preferredTransport]=SystemClock.elapsedRealtime()+5_000
        val other=latest.entries.firstOrNull {it.key!=attempt.preferredTransport}?.value
        if(other!=null)observed(other)
        requestFresh()
    }
    private fun requestFresh() {lastRefresh=SystemClock.elapsedRealtime();adapters.values.forEach {runCatching {it.refresh()}}}
    private fun admit(session: SignalingSessionV2,lease: PendingSocketLease) {
        val correctRole=if(localId<peerId)RequestRole.REQUESTER else RequestRole.RESPONDER
        if(closed || selected!=null || session.peer.deviceId!=peerId || session.pinnedIdentity.localSessionId!=runtime ||
            session.requestRole!=correctRole || CLOUD_SHADOW_CAPABILITY !in session.peerCapabilities ||
            session.channel.transport==Transport.WIFI_DIRECT && !runCatching {networks().p2pAllowed}.getOrDefault(false)) {lease.close();return}
        if(!lease.tryTransfer(isServiceCurrent={!closed && selected==null && sessions.isCurrent(token)},install={selected=session})) {lease.close();return}
        val attempt=session.originatingAttempt ?: ConnectionAttempt(session.wireRequestKey.attemptId,runtime,
            session.targetLock,ConnectionTrigger.RECOVERY,ChannelPlan.single(session.channel.transport),SystemClock.elapsedRealtime()+15_000)
        if(adapters[session.channel.transport]?.retain(attempt,session)!=true) {selected=null;session.close();return}
        selectedAttempt=attempt;pending=null;onSelected(session)
    }
    private fun androidFactory()=ShadowAdapterFactory {transport,runtime,onSession,onObservation,onFailure->
        if(transport==Transport.LAN) {
            val adapter=LanDiscoveryCoordinator(context,token,{sessions.isCurrent(it)},localId,runtime,"骑士",Build.MODEL.orEmpty(),
                SignalingV2Codec.PROTOCOL_VERSION,{},onSession,{},{},onFreshObservation=onObservation,
                onTargetedConnectFailure={a,_->onFailure(a)},bindBroadcastToWifi=true)
            object : ShadowDiscoveryAdapter {
                override val source get()=adapter.observationSource
                override fun start()=adapter.start()
                override fun connect(attempt: ConnectionAttempt)=adapter.connect(attempt)
                override fun refresh()=adapter.probeDiscovery()
                override fun closeWhenReleased(callback: (Boolean)->Unit) {callback(runCatching {adapter.close()}.isSuccess)}
            }
        } else {
            val adapter=WifiDirectTunnel(context,onSession,localDeviceId=localId,localDeviceName=Build.MODEL.orEmpty(),sessionId=runtime,
                onFreshObservation=onObservation,onTargetedTransportFailed={a,_->onFailure(a)},onSelectedChannelLost=onFailure)
            object : ShadowDiscoveryAdapter {
                override val source get()=adapter.observationSource
                override fun start(): Boolean {adapter.start();return true}
                override fun connect(attempt: ConnectionAttempt)=adapter.connect(attempt)
                override fun refresh()=adapter.discoverPeers()
                override fun retain(attempt: ConnectionAttempt,session: SignalingSessionV2)=adapter.retainSelectedChannel(attempt,session)
                override fun closeWhenReleased(callback: (Boolean)->Unit) {runCatching {adapter.closeWhenReleased(callback)}.onFailure {callback(false)}}
            }
        }
    }
    private fun main(action: ()->Unit) {if(Looper.myLooper()==Looper.getMainLooper())action() else handler.post {action()}}
    override fun close() {
        if(closed)return
        closed=true;sessions.invalidate();handler.removeCallbacks(tick);selected?.close();selected=null;selectedAttempt=null
        registered.forEach {runCatching {cm.unregisterNetworkCallback(it)}};registered.clear()
        var success=runCatching {multicast?.let {if(it.isHeld)it.release()}}.isSuccess
        val all=releases.toList()
        if(all.isEmpty()) {release.complete(success);return}
        val remaining=java.util.concurrent.atomic.AtomicInteger(all.size)
        all.forEach {(adapter,barrier)->
            barrier.whenReleased {ok->main {success=success && ok;if(remaining.decrementAndGet()==0)release.complete(success)}}
            runCatching {adapter.closeWhenReleased {barrier.complete(it)}}.onFailure {barrier.complete(false)}
        }
    }
    fun closeWhenReleased(callback: (Boolean)->Unit) {close();release.whenReleased(callback)}
    companion object {
        @Suppress("DEPRECATION") private fun readNetworks(context: Context): ShadowProbeNetworks {
            val cm=context.getSystemService(ConnectivityManager::class.java)
            val caps=cm.activeNetwork?.let {cm.getNetworkCapabilities(it)}
            val cell=caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val allowed=cell && PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).all {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
            val raw=context.getSystemService(WifiManager::class.java).connectionInfo.ipAddress
            val ip=(0..3).joinToString(".") {((raw ushr (it*8)) and 255).toString()}
            val wifi=if(raw==0)null else cm.allNetworks.firstOrNull {network ->
                val c=cm.getNetworkCapabilities(network)
                c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    cm.getLinkProperties(network)?.linkAddresses?.any {it.address.hostAddress==ip}==true
            }
            return ShadowProbeNetworks(wifi?.let {"$it|$ip"},allowed)
        }
    }
}
