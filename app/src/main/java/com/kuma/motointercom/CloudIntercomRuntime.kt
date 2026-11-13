package com.kuma.motointercom

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CloudPeerInfo(val deviceId: String, val pairId: String?, val registered: Boolean, val online: Boolean)

// Service-owned adapter between persistent cloud state, local selected channels,
// invitation UI and the SDK owner. It never captures or plays audio itself.
internal class CloudIntercomRuntime(
    context: Context,
    parentScope: CoroutineScope,
    private val identityStore: LocalIdentityStore,
    private val pairingRepository: PairingRepository,
    private val available: () -> Boolean,
    private val busy: () -> Boolean,
    private val channelCurrent: (SignalingSessionV2) -> Boolean,
    private val peerName: (String) -> String,
    private val onState: (CloudIntercomState) -> Unit,
    private val onInvitation: (CloudInvitePrompt?) -> Unit,
    private val onPairingPrompt: (CloudPairingPrompt?) -> Unit,
    private val onPeers: (List<CloudPeerInfo>) -> Unit,
    private val notice: (String) -> Unit,
    private val startMedia: suspend (ApprovedCloudRide,GmeTicketSource,()->Boolean) -> Boolean,
    private val stopMedia: () -> Unit,
    private val config: GmeConfig = GmeConfig.fromBuildConfig(),
    private val onLocalRide: (RideSession,LocalRideAccess)->Unit = {_,_->},
    private val hasLocalMedia: (String)->Boolean = {false}
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job])+Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    val preferences = CloudPreferences(appContext)
    val store = RideSessionStore(appContext)
    private val signer: DeviceSigner = DeviceKeyStore()
    private val initMutex = Mutex()
    private var coordinator: DefaultCloudSessionCoordinator? = null
    private var transport: ControlPlaneClient? = null
    private var http: okhttp3.OkHttpClient? = null
    private val subscriptions = mutableListOf<Job>()
    private var destroyed = false
    private var intentGeneration = 0L
    private var networkRegistered = false
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private var link: SignalingSessionV2? = null
    private var connected = false
    private var freshPair = false
    private class LocalLease(var attempt: ConnectionAttempt,val id: String=UUID.randomUUID().toString()) {
        val peer get()=attempt.targetDeviceId
        var lost=false
    }
    private var localLease: LocalLease?=null
    private var localRideJob: Job?=null
    private var exchange: CloudPairingExchange? = null
    private var exchangeSession: SignalingSessionV2? = null
    private val peerGenerations = mutableMapOf<String, Long>()
    private val deniedPeers = mutableSetOf<String>()
    private val pendingFrames = mutableListOf<SignalingMessageV2>()
    var invitation: CloudInvitePrompt? = null
        private set
    var pairingPrompt: CloudPairingPrompt? = null
        private set
    var peerInfos: List<CloudPeerInfo> = emptyList()
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        private fun changed() { scope.launch { if (!destroyed) { coordinator?.policyChanged(); updateAvailability() } } }
    }

    fun wifiAllowed(): Boolean {
        if (!preferences.wifiOnly) return true
        val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun ensure(): DefaultCloudSessionCoordinator? = initMutex.withLock {
        coordinator?.let { return@withLock it }
        if (destroyed || !config.canConnect || !preferences.enabled) return@withLock null
        val local = identityStore.getOrCreateDeviceId()
        if (destroyed || !preferences.enabled) return@withLock null
        val client = cloudHttpClient()
        val signed = ControlPlaneClient(config.controlPlaneUrl,local,signer,client,
            allowed = { preferences.enabled }, wifiAllowed = ::wifiAllowed)
        val api = HttpCloudControlPlane(signed)
        lateinit var co: DefaultCloudSessionCoordinator
        co = DefaultCloudSessionCoordinator(api,CloudControlSocket(config.webSocketUrl,client),store,
            consentAt={ preferences.consentAt },enabled={ preferences.enabled },wifiAllowed=::wifiAllowed,
            busy=busy,peerName=peerName,startMedia={ approved ->
                val intent=intentGeneration
                val lease=localLease
                val admitted=co.mediaAdmissionGuard(approved.rideId)
                !destroyed && preferences.enabled && wifiAllowed() && approved.peerDeviceId !in deniedPeers &&
                    peerInfos.any { it.deviceId == approved.peerDeviceId && it.registered } &&
                    startMedia(approved,GmeTicketClient(signed)) {
                        admitted() && intent==intentGeneration && localLease===lease && !destroyed && preferences.enabled && wifiAllowed() &&
                            approved.peerDeviceId !in deniedPeers && peerInfos.any { it.deviceId==approved.peerDeviceId && it.registered }
                    }
            },stopMedia=stopMedia,serverNow=api::serverNow,onPairingsChanged={ refreshPeers(); maybeAttachLocalRide() },
            canReplaceLocal={ approved -> approved.reason==CloudEntryReason.LOCAL_FAILURE &&
                localLease?.let { validLocal(it) && it.peer==approved.peerDeviceId &&
                    co.hasLiveLocalBinding(approved.rideId,approved.peerDeviceId) }==true || hasLocalMedia(approved.rideId) },
            onRideUpdate={r ->
                val intent=intentGeneration
                val guard=co.rideAuthorizationGuard(r.rideId)
                val record=store.pairs().firstOrNull {it.registered && it.proof.transcript.pairId==r.pairId}
                if(record!=null && guard() && intent==intentGeneration) onLocalRide(r,LocalRideAccess(api,signer,record.proof,
                    current={intent==intentGeneration && coordinator===co && guard() && !destroyed && record.peerDeviceId !in deniedPeers},
                    publish={co.submitLocalSnapshot(it) {intent==intentGeneration && coordinator===co && guard() && !destroyed}},
                    lost={co.submitLocalLoss(r.rideId) {intent==intentGeneration && coordinator===co && guard() && !destroyed}}))
            },hasLocalMedia=hasLocalMedia)
        http = client; transport = signed; coordinator = co
        subscriptions += scope.launch { co.state.collect(onState) }
        subscriptions += scope.launch { co.incoming.collect { invitation=it; onInvitation(it) } }
        subscriptions += scope.launch { co.peers.collect { refreshPeers(it) } }
        if (!networkRegistered) {
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback)
            networkRegistered = connectivity != null
        }
        refreshPeers(); co
    }

    fun updateAvailability() { launch {
        if (preferences.enabled && config.canConnect && available()) ensure()?.start()
        else if (!available() && coordinator?.state?.value?.ownsCloudSession != true) disposeCoordinator()
    } }

    fun settingsChanged() {
        if(!preferences.enabled) {
            intentGeneration++; localLease=null; localRideJob?.cancel(); localRideJob=null
        }
        launch {
            if (!preferences.enabled) {
                coordinator?.cancelCurrent(); disposeCoordinator(); closeExchange(); onState(CloudIntercomState.Idle)
            } else { ensure()?.policyChanged(); updateAvailability() }
        }
    }

    fun invite(peer: String) { val generation=++intentGeneration; launch {
        val co = ensure() ?: throw ControlPlaneException(0,if(preferences.enabled)"NOT_CONFIGURED" else "CONSENT_REQUIRED")
        val record = store.pairs().firstOrNull { it.peerDeviceId == peer && it.registered }
            ?: throw ControlPlaneException(403,"PAIR_NOT_AUTHORIZED")
        refreshPeers()
        if (generation==intentGeneration) co.invite(record.proof.transcript.pairId)
    } }

    fun respond(inviteId: String, accept: Boolean, actionNonce: String) { val generation=++intentGeneration; launch {
        val p = invitation?.takeIf { it.invitation.inviteId == inviteId && it.actionNonce == actionNonce } ?: return@launch
        if (p.expiresAtLocal <= System.currentTimeMillis()) {
            if (accept) onState(CloudIntercomState.Failed(p.peerName,"邀请已过期"))
            return@launch
        }
        val co=ensure()
        if (generation==intentGeneration) co?.respond(inviteId,accept)
    } }
    fun resume(rideId: String) { val generation=++intentGeneration; launch {
        val co=ensure(); if (generation==intentGeneration) co?.resumeManually(rideId)
    } }
    fun endCurrent() = endLocal()
    fun endLocal(afterEnded: ()->Unit = {}) {
        intentGeneration++; localLease=null; localRideJob?.cancel(); localRideJob=null
        launch { try {
            store.cancelStarts()
            coordinator?.cancelCurrent() ?: onState(CloudIntercomState.Idle)
        } finally { afterEnded() } }
    }
    fun localFailure(attempt: ConnectionAttempt) {
        val lease=localLease?.takeIf { it.attempt==attempt && validLocal(it) } ?: return
        lease.lost=true
        launch { coordinator?.establishedLocalLost(lease.id) }
    }
    fun localBecameBusy() { launch { coordinator?.localBecameBusy() } }
    fun mediaState(state: CloudIntercomState) { coordinator?.mediaStateChanged(state) ?: onState(state) }
    fun mediaClosed() { coordinator?.mediaClosed() }

    // This synchronous invalidation precedes any Room/IO work on user unpair.
    fun invalidatePeer(peer: String) {
        if(localLease?.peer==peer) { localLease=null; localRideJob?.cancel(); localRideJob=null }
        peerGenerations[peer] = (peerGenerations[peer] ?: 0)+1; deniedPeers.add(peer)
        if (link?.peer?.deviceId == peer) { closeExchange(); freshPair = false }
        peerInfos = peerInfos.filterNot { it.deviceId == peer }; onPeers(peerInfos)
    }
    suspend fun forgetPeer(peer: String) = withContext(Dispatchers.Main.immediate) {
        store.forget(peer); refreshPeers()
        if (coordinator?.ride?.value?.members?.contains(peer) == true) coordinator?.cancelCurrent()
        // The durable revocation survives an offline server. Local unpairing must finish.
        try { coordinator?.synchronizePairs() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice("云端撤销已排队，联网后完成同步") }
    }

    fun localAccepted(session: SignalingSessionV2, trigger: ConnectionTrigger) {
        localClosed(); link=session
        launch {
            val unknown = pairingRepository.getByDeviceId(session.peer.deviceId.orEmpty()) == null
            if (link !== session || !channelCurrent(session)) return@launch
            freshPair = unknown && trigger in setOf(ConnectionTrigger.USER,ConnectionTrigger.INBOUND)
            maybeStartFresh()
        }
    }
    fun localConnected(session: SignalingSessionV2,attempt: ConnectionAttempt) {
        if (link !== session || !channelCurrent(session)) return
        connected=true; maybeStartFresh()
        val old=localLease
        if(old==null || old.peer!=attempt.targetDeviceId || old.attempt.runtimeSessionId!=attempt.runtimeSessionId) {
            intentGeneration++
            localRideJob?.cancel()
            localLease=LocalLease(attempt)
        } else old.attempt=attempt
        maybeAttachLocalRide()
        pendingFrames.toList().also { pendingFrames.clear() }.forEach { localFrame(session,it) }
    }
    private fun validLocal(lease: LocalLease)=!destroyed && localLease===lease && preferences.enabled && lease.peer !in deniedPeers
    private fun maybeAttachLocalRide() {
        val lease=localLease ?: return
        if(!validLocal(lease) || !config.canConnect || !wifiAllowed() || localRideJob?.isActive==true ||
            peerInfos.none { it.deviceId==lease.peer && it.registered }) return
        localRideJob=scope.launch {
            while(validLocal(lease)) {
                try {
                    val co=ensure() ?: return@launch
                    co.attachEstablishedLocal(lease.peer,lease.id) { validLocal(lease) }
                    if(lease.lost) co.establishedLocalLost(lease.id)
                    if(co.knowsLocalLease(lease.id)) return@launch
                    delay(2_000)
                } catch(c: CancellationException) { throw c }
                catch(_: Exception) { delay(5_000) }
            }
        }
    }
    private fun maybeStartFresh() {
        val s = link ?: return
        if (!connected || !freshPair || !preferences.enabled || s.requestRole != RequestRole.REQUESTER || exchange != null || CLOUD_PAIRING_CAPABILITY !in s.peerCapabilities) return
        createExchange(s)?.initiate()
    }
    fun localFrame(session: SignalingSessionV2, message: SignalingMessageV2) {
        if (CLOUD_PAIRING_CAPABILITY !in session.peerCapabilities || !channelCurrent(session)) return
        if (link !== session) return
        if (!connected) { if (pendingFrames.size < 8) pendingFrames.add(message); return }
        if (session.peer.deviceId in deniedPeers) return
        val e = exchange?.takeIf { exchangeSession === session } ?: createExchange(session) ?: return
        e.receive(message)
    }
    fun requestPairing(peer: String) {
        val s = link
        if (!connected || s == null || s.peer.deviceId != peer || !channelCurrent(s)) { notice("请先与这位车友建立本地对讲，再重新配对云端"); return }
        if (CLOUD_PAIRING_CAPABILITY !in s.peerCapabilities) { notice("对方版本暂不支持云端配对"); return }
        if (!preferences.enabled) { notice("请先同意云端隐私说明"); return }
        deniedPeers.remove(peer); closeExchange(); createExchange(s)?.initiate()
    }

    fun approvePairing(pairId: String, nonce: String, accepted: Boolean) {
        val p = pairingPrompt?.takeIf { it.pairId==pairId && it.actionNonce==nonce } ?: return
        if (!accepted) { closeExchange(); return }
        if (!preferences.enabled || p.expiresAt <= System.currentTimeMillis()) { closeExchange(); return }
        pairingPrompt=null; onPairingPrompt(null); exchange?.approve(pairId)
    }

    private fun createExchange(session: SignalingSessionV2): CloudPairingExchange? {
        val peer = session.peer.deviceId ?: return null
        if (destroyed || CLOUD_PAIRING_CAPABILITY !in session.peerCapabilities || !channelCurrent(session)) return null
        val version = peerGenerations[peer] ?: 0
        val live = { !destroyed && exchangeSession === session && link === session && connected &&
            (peerGenerations[peer] ?: 0)==version && channelCurrent(session) }
        exchangeSession=session
        return CloudPairingExchange(scope,session.pinnedIdentity,signer,store,canContinue=live,
            authorize={
                if (!live() || !preferences.enabled) null else {
                    if (store.pairs().any { it.peerDeviceId==peer }) store.forget(peer)
                    if (!live()) null else store.epoch(peer)
                }
            },send={ message ->
                check(live() && preferences.enabled)
                suspendCancellableCoroutine { continuation ->
                    session.send(message) { result ->
                        if (continuation.isActive) result.fold({ continuation.resume(Unit) }, { continuation.resumeWithException(it) })
                    }
                }
            },requestApproval={ pairId,expires ->
                pairingPrompt=CloudPairingPrompt(pairId,peer,peerName(peer),UUID.randomUUID().toString(),expires)
                onPairingPrompt(pairingPrompt)
            },completed={ proof,epoch ->
                if (live() && preferences.enabled && store.putProof(peer,proof,epoch) && live()) {
                    deniedPeers.remove(peer); pairingPrompt=null; onPairingPrompt(null)
                    refreshPeers(); notice("云端配对证明已保存，联网后完成登记")
                    ensure()?.synchronizePairs()
                    maybeAttachLocalRide()
                }
            },failed={ pairingPrompt=null; onPairingPrompt(null); notice("云端配对未完成，请重新配对。本地对讲可继续") },
            autoAuthorize=freshPair && preferences.enabled).also { exchange=it }
    }

    fun localClosed() { closeExchange(); link=null; connected=false; freshPair=false; pendingFrames.clear() }
    fun localDisconnected() { closeExchange(); connected=false; freshPair=false; pendingFrames.clear() }
    private fun closeExchange() { exchange?.close(); exchange=null; exchangeSession=null; pairingPrompt=null; onPairingPrompt(null) }
    private suspend fun refreshPeers(presences: List<CloudPeerPresence> = coordinator?.peers?.value.orEmpty()) {
        val records=store.pairs()
        peerInfos=records.filter { it.peerDeviceId !in deniedPeers }.map { r ->
            CloudPeerInfo(r.peerDeviceId,r.proof.transcript.pairId,r.registered,
                presences.any { it.deviceId==r.peerDeviceId && it.pairId==r.proof.transcript.pairId && it.online })
        }
        onPeers(peerInfos)
    }
    private fun launch(block: suspend () -> Unit) { scope.launch {
        try { if (!destroyed) block() }
        catch (c: CancellationException) { throw c }
        catch (e: Exception) { notice((e as? ControlPlaneException)?.message ?: "云端服务暂时无法连接，请稍后重试")
            if (coordinator?.state?.value?.ownsCloudSession != true) onState(CloudIntercomState.Failed("",(e as? ControlPlaneException)?.message ?: "云端服务暂时无法连接"))
        }
    } }
    private fun disposeCoordinator() {
        subscriptions.forEach { it.cancel() }; subscriptions.clear()
        coordinator?.close(); coordinator=null; transport=null
        http?.dispatcher?.cancelAll(); http?.connectionPool?.evictAll(); http=null
        invitation=null; onInvitation(null)
    }
    override fun close() {
        destroyed=true; localLease=null; localRideJob?.cancel(); localRideJob=null; localClosed(); disposeCoordinator()
        if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        networkRegistered=false
        scope.cancel()
    }
}
