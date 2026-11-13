package com.kuma.motointercom

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.UUID

internal class DefaultCloudSessionCoordinator(
    private val api: CloudControlPlane,
    private val socket: CloudEventSocket,
    private val store: CloudSessionStore,
    private val consentAt: () -> Long?,
    private val enabled: () -> Boolean,
    private val wifiAllowed: () -> Boolean,
    private val busy: () -> Boolean,
    private val peerName: (String) -> String,
    private val startMedia: suspend (ApprovedCloudRide) -> Boolean,
    private val stopMedia: () -> Unit,
    private val serverNow: () -> Long = System::currentTimeMillis,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val heartbeatMs: Long = 30_000,
    private val onPairingsChanged: suspend () -> Unit = {},
    private val canReplaceLocal: (ApprovedCloudRide)->Boolean = { false },
    private val onRideUpdate: suspend (RideSession)->Unit = {},
    private val hasLocalMedia: (String)->Boolean = {false}
) : CloudSessionCoordinator, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _ride = MutableStateFlow<RideSession?>(null)
    override val ride: StateFlow<RideSession?> = _ride
    private val _state = MutableStateFlow<CloudIntercomState>(CloudIntercomState.Idle)
    val state: StateFlow<CloudIntercomState> = _state
    private val _incoming = MutableStateFlow<CloudInvitePrompt?>(null)
    val incoming: StateFlow<CloudInvitePrompt?> = _incoming
    private val _peers = MutableStateFlow<List<CloudPeerPresence>>(emptyList())
    val peers: StateFlow<List<CloudPeerPresence>> = _peers
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private var monitor: Job? = null
    private var inviteExpiry: Job? = null
    private var incomingExpiry: Job? = null
    private var ready = CompletableDeferred<Unit>()
    private var connectionEpoch = 0L
    private var operationEpoch = 0L
    private var joinedRide: String? = null
    private var inFlightIntent = false
    private var pendingIntentRideId: String? = null
    private val authorized = mutableSetOf<String>()
    private val ended = linkedSetOf<String>()
    private val events = linkedSetOf<String>()
    private val revisions = mutableMapOf<String, Long>()
    private val handledInvites = linkedSetOf<String>()
    private class LocalBinding(val peer: String,val lease: String,val pairId: String,val current: ()->Boolean) {
        var rideId: String?=null
        var requestId: String?=null
        var lost=false
        var fallbackInFlight=false
    }
    private var localBinding: LocalBinding?=null
    private val retiredLocalLeases=linkedSetOf<String>()
    fun knowsLocalLease(lease: String)=localBinding?.let { it.lease==lease && it.rideId!=null }==true || lease in retiredLocalLeases
    fun hasLiveLocalBinding(rideId: String,peer: String)=localBinding?.let { it.rideId==rideId && it.peer==peer && it.current() }==true

    fun mediaAdmissionGuard(rideId: String): ()->Boolean {
        val epoch=operationEpoch
        val binding=localBinding?.takeIf { it.rideId==rideId }
        return { epoch==operationEpoch && joinedRide==rideId && rideId in authorized && rideId !in ended && enabled() && wifiAllowed() &&
            (binding==null || localBinding===binding && binding.current() && binding.rideId==rideId) }
    }

    fun rideAuthorizationGuard(rideId: String): ()->Boolean {
        val epoch=operationEpoch
        return {epoch==operationEpoch && rideId in authorized && rideId !in ended && _ride.value?.rideId==rideId && enabled() && wifiAllowed()}
    }
    suspend fun acceptLocalSnapshot(r: RideSession)=withContext(dispatcher) {
        if(r.rideId in authorized && r.rideId !in ended) applyRide(r)
    }
    suspend fun submitLocalSnapshot(r: RideSession,current: ()->Boolean) {
        scope.launch {if(current())acceptLocalSnapshot(r)}.join()
    }
    suspend fun submitLocalLoss(rideId: String,current: ()->Boolean) {
        scope.launch {if(current())localMediaLost(rideId)}.join()
    }
    suspend fun localMediaLost(rideId: String)=withContext(dispatcher) {
        val epoch=operationEpoch
        val r=_ride.value?.takeIf {it.rideId==rideId && it.rideId in authorized && it.rideId !in ended} ?: return@withContext
        if(r.state!=RideState.LOCAL_ACTIVE || !r.everLocalActive || !enabled() || !wifiAllowed())return@withContext
        val cloud=api.fallback(rideId)
        if(epoch==operationEpoch && rideId !in ended)applyRide(cloud)
    }

    suspend fun attachEstablishedLocal(peer: String,lease: String,current: ()->Boolean) = withContext(dispatcher) {
        if(lease in retiredLocalLeases) return@withContext
        localBinding?.takeIf { it.lease!=lease && !it.current() }?.let { cancelCurrent() }
        localBinding?.takeIf { it.peer==peer && it.lease==lease && it.rideId!=null }?.let {
            reconcileLocalBinding(it); return@withContext
        }
        if(!current() || !enabled() || !wifiAllowed() || _state.value.ownsCloudSession) return@withContext
        val record=store.pairs().firstOrNull { it.peerDeviceId==peer && it.registered } ?: return@withContext
        val binding=localBinding?.takeIf { it.peer==peer && it.lease==lease }
            ?: LocalBinding(peer,lease,record.proof.transcript.pairId,current).also { localBinding=it }
        val epoch=++operationEpoch; inFlightIntent=true
        val pairId=record.proof.transcript.pairId
        var created: RideSession?=null
        var requestId: String?=null
        fun live()=epoch==operationEpoch && localBinding===binding && current() && enabled()
        try {
            awaitReady(); if(!live()) return@withContext
            requestId=store.startRequest(pairId)
            binding.requestId=requestId
            if(!live()) return@withContext
            var r=try { api.startRide(pairId,requestId) }
            catch(e: ControlPlaneException) {
                if(e.code!="BUSY") throw e
                api.snapshot().activeRide?.takeIf { it.pairId==pairId && it.members.contains(peer) &&
                    it.state in setOf(RideState.LOCAL_PENDING,RideState.LOCAL_ACTIVE,RideState.CLOUD_PENDING,RideState.CLOUD_ACTIVE) } ?: throw e
            }
            created=r
            if(!live()) { abandonCreated(r); return@withContext }
            if(r.rideId in ended || r.rideId in store.pendingEnds()) throw ControlPlaneException(0,"PREVIOUS_RIDE_ENDING")
            r=api.ride(r.rideId)
            if(r.state==RideState.ENDED) {
                retireLocalBinding(binding); return@withContext
            }
            if(!live()) { abandonCreated(r); return@withContext }
            if(r.rideId in ended || r.rideId in store.pendingEnds()) throw ControlPlaneException(0,"PREVIOUS_RIDE_ENDING")
            if(!live()) return@withContext
            if(r.pairId!=pairId || peer !in r.members || r.state !in setOf(RideState.LOCAL_PENDING,RideState.LOCAL_ACTIVE)) throw ControlPlaneException(409,"BUSY")
            binding.rideId=r.rideId; authorized.add(r.rideId); setRide(r)
            if(!live()) { abandonCreated(r); return@withContext }
            api.localEstablished(r.rideId)
            if(!live()) { abandonCreated(r); return@withContext }
            applyRide(api.ride(r.rideId))
            store.completeStart(pairId,requestId)
        } catch(c: CancellationException) { throw c }
        catch(e: Exception) {
            if(e is ControlPlaneException && e.status in 400..499 && e.code !in setOf("BUSY","DEVICE_NOT_REGISTERED")) {
                retireLocalBinding(binding)
                created?.let { store.queueEnd(it.rideId); runCatching { api.end(it.rideId); store.completeEnd(it.rideId) } }
            }
            throw e
        } finally { if(epoch==operationEpoch) inFlightIntent=false }
    }

    private suspend fun reconcileLocalBinding(binding: LocalBinding) {
        val id=binding.rideId ?: return
        val epoch=operationEpoch
        fun live()=epoch==operationEpoch && localBinding===binding && binding.current() && enabled()
        if(!live()) return
        awaitReady()
        var r=api.ride(id)
        if(!live()) return
        if(r.state==RideState.ENDED) { retireLocalBinding(binding); return }
        if(r.pairId!=binding.pairId || binding.peer !in r.members) { retireLocalBinding(binding); return }
        if(r.state in setOf(RideState.LOCAL_PENDING,RideState.LOCAL_ACTIVE)) {
            api.localEstablished(id)
            if(!live()) return
            r=api.ride(id)
        } else if(r.cloudReason!=CloudEntryReason.LOCAL_FAILURE) { retireLocalBinding(binding); return }
        if(!live()) return
        authorized.add(id); applyRide(r)
        binding.requestId?.let { store.completeStart(binding.pairId,it) }
    }
    private suspend fun retireLocalBinding(binding: LocalBinding) {
        retiredLocalLeases.add(binding.lease)
        while(retiredLocalLeases.size>128) retiredLocalLeases.remove(retiredLocalLeases.first())
        val id=binding.rideId
        id?.let {
            ended.add(it)
            authorized.remove(it)
            if(_ride.value?.rideId==it) _ride.value=null
        }
        if(localBinding===binding) localBinding=null
        id?.let { store.clearRide(it) }
        binding.requestId?.let { store.completeStart(binding.pairId,it) }
    }

    suspend fun establishedLocalLost(lease: String) = withContext(dispatcher) {
        val binding=localBinding?.takeIf { it.lease==lease && it.current() } ?: return@withContext
        binding.lost=true
        requestLocalFallback(binding)
    }

    private suspend fun requestLocalFallback(binding: LocalBinding) {
        val id=binding.rideId ?: return
        if(binding.fallbackInFlight || !binding.current() || localBinding!==binding || id !in authorized || id in ended || !enabled() || !wifiAllowed()) return
        val epoch=operationEpoch
        binding.fallbackInFlight=true
        try {
            if(id in store.pendingEnds() || epoch!=operationEpoch || id in ended) return
            val r=api.ride(id)
            if(epoch!=operationEpoch || localBinding!==binding || !binding.current() || id in ended) return
            if(r.state in setOf(RideState.CLOUD_PENDING,RideState.CLOUD_ACTIVE)) { applyRide(r); return }
            if(!r.mayRequestFallback(LocalSessionEndReason.TRANSPORT_FAILURE,consentAt(),serverNow())) return
            val approved=api.fallback(id)
            if(epoch==operationEpoch && localBinding===binding && binding.current() && id !in ended) applyRide(approved)
        } finally { binding.fallbackInFlight=false }
    }

    suspend fun start() = withContext(dispatcher) {
        if (monitor?.isActive == true) return@withContext
        monitor = scope.launch {
            var backoff = 1_000L
            while (isActive && enabled()) {
                try {
                    if (!wifiAllowed()) throw ControlPlaneException(0,"WIFI_REQUIRED")
                    api.registerDevice()
                    flushPending()
                    syncPairs()
                    if (!_online.value) connect()
                    val current = _ride.value?.rideId?.takeIf { it in authorized }
                    if (!api.heartbeat(current)) { disconnect(); connect() }
                    refresh()
                    backoff = 1_000
                    delay(heartbeatMs)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    disconnect()
                    if (!_state.value.blocksLocalStart && !inFlightIntent) _state.value = failed("", error)
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    private suspend fun connect() {
        val epoch = ++connectionEpoch
        val token = api.socketToken()
        if (epoch != connectionEpoch || !enabled() || consentAt() == null || !wifiAllowed()) throw ControlPlaneException(0,"CONTROL_DISCONNECTED")
        ready = CompletableDeferred()
        socket.connect(token, onOpen = {
            scope.launch { if (epoch == connectionEpoch) { _online.value = true; ready.complete(Unit); safeRefresh() } }
        }, onEvent = { text -> scope.launch { if (epoch == connectionEpoch) handleEvent(text) } }, onDisconnected = {
            scope.launch { if (epoch == connectionEpoch) { _online.value = false; if (!ready.isCompleted) ready.completeExceptionally(ControlPlaneException(0,"CONTROL_DISCONNECTED")) } }
        })
        if (withTimeoutOrNull(10_000) { ready.await(); true } != true) throw ControlPlaneException(0,"CONTROL_DISCONNECTED")
    }

    private fun disconnect() { connectionEpoch++; _online.value = false; socket.close() }
    private suspend fun awaitReady() {
        if (!enabled() || consentAt() == null) throw ControlPlaneException(0,"CONSENT_REQUIRED")
        if (!wifiAllowed()) throw ControlPlaneException(0,"WIFI_REQUIRED")
        start()
        if (withTimeoutOrNull(12_000) { online.first { it }; true } != true) throw ControlPlaneException(0,"CONTROL_DISCONNECTED")
    }

    override suspend fun invite(pairId: String) = withContext(dispatcher) {
        if (_state.value.blocksLocalStart || inFlightIntent || busy()) throw ControlPlaneException(409,"BUSY")
        val record = store.pairs().firstOrNull { it.proof.transcript.pairId == pairId && it.registered }
            ?: throw ControlPlaneException(403,"PAIR_NOT_AUTHORIZED")
        val epoch = ++operationEpoch; inFlightIntent = true
        _state.value = CloudIntercomState.Preparing(record.peerDeviceId, peerName(record.peerDeviceId))
        var created: RideSession? = null
        var requestId: String? = null
        try {
            awaitReady()
            if (epoch != operationEpoch) return@withContext
            requestId = store.startRequest(pairId)
            var r = api.startRide(pairId,requestId); created = r
            if (epoch != operationEpoch) { abandonCreated(r); return@withContext }
            r = api.ride(r.rideId)
            if (r.state == RideState.ENDED) {
                store.completeStart(pairId,requestId); requestId = store.startRequest(pairId)
                r = api.startRide(pairId,requestId); created = r
            }
            if (epoch != operationEpoch) { abandonCreated(r); return@withContext }
            authorized.add(r.rideId); setRide(r)
            val invitation = api.invite(r.rideId)
            if (epoch != operationEpoch) { abandonCreated(r); return@withContext }
            store.completeStart(pairId,requestId)
            _state.value = CloudIntercomState.Inviting(record.peerDeviceId, peerName(record.peerDeviceId), invitation.expiresAt)
            scheduleInviteExpiry(invitation)
            applyRide(api.ride(r.rideId))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (created != null) abandonCreated(created)
            if (created != null) store.completeStart(pairId,requestId)
            if (epoch == operationEpoch) _state.value = failed(peerName(record.peerDeviceId), error)
        } finally { if (epoch == operationEpoch) inFlightIntent = false }
    }

    override suspend fun respond(inviteId: String, accept: Boolean) = withContext(dispatcher) {
        val prompt = _incoming.value?.takeIf { it.invitation.inviteId == inviteId } ?: return@withContext
        val i = prompt.invitation
        handledInvites.add(inviteId)
        while (handledInvites.size > 256) handledInvites.remove(handledInvites.first())
        if (!accept) { _incoming.value = null; incomingExpiry?.cancel(); api.respond(i,"DECLINE"); return@withContext }
        if (i.expiresAt <= serverNow()) { _incoming.value = null; throw ControlPlaneException(409,"INVITE_EXPIRED") }
        if (busy() || _state.value.blocksLocalStart || inFlightIntent) {
            _incoming.value = null; api.respond(i,"BUSY"); return@withContext
        }
        val epoch = ++operationEpoch; inFlightIntent = true
        _incoming.value = null; incomingExpiry?.cancel()
        pendingIntentRideId = i.rideId
        _state.value = CloudIntercomState.Preparing(i.fromDeviceId,prompt.peerName)
        try {
            awaitReady()
            if (epoch != operationEpoch) return@withContext
            authorized.add(i.rideId)
            val r = api.respond(i,"ACCEPT")
            if (epoch != operationEpoch) { abandonCreated(r); return@withContext }
            if (r.state == RideState.ENDED) throw ControlPlaneException(409,"BUSY")
            authorized.add(r.rideId); setRide(r); applyRide(r)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (error is ControlPlaneException && error.status in 400..499) authorized.remove(i.rideId)
            if (epoch == operationEpoch) _state.value = failed(prompt.peerName,error)
        }
        finally { if (epoch == operationEpoch) { inFlightIntent = false; pendingIntentRideId = null } }
    }

    suspend fun refresh() = withContext(dispatcher) {
        val epoch = operationEpoch
        val snapshot = api.snapshot()
        if (epoch != operationEpoch) return@withContext
        _peers.value = snapshot.peers
        val active = snapshot.activeRide
        if (active != null && active.rideId !in ended && active.rideId !in store.pendingEnds()) {
            if (active.members.contains(api.deviceId)) applyRide(active)
        } else if (_ride.value?.rideId in authorized) {
            val current = _ride.value ?: return@withContext
            try { applyRide(api.ride(current.rideId)) }
            catch (error: ControlPlaneException) {
                if (error.code !in setOf("PAIR_NOT_AUTHORIZED","RIDE_NOT_AUTHORIZED")) throw error
                if(epoch!=operationEpoch || _ride.value?.rideId!=current.rideId) return@withContext
                ended.add(current.rideId); authorized.remove(current.rideId)
                if (joinedRide == current.rideId || hasLocalMedia(current.rideId)) { joinedRide = null; stopMedia() }
                _ride.value = null; store.clearRide(current.rideId)
                if(epoch==operationEpoch && _ride.value==null) _state.value = CloudIntercomState.Idle
            }
        } else if (!inFlightIntent && joinedRide == null) {
            _ride.value = null; store.saveRide(null)
            if (_state.value is CloudIntercomState.ResumeAvailable || _state.value is CloudIntercomState.LimitReached) _state.value = CloudIntercomState.Idle
        }
        val pending = snapshot.invitations.filter { it.toDeviceId == api.deviceId && it.expiresAt > serverNow() && it.rideId !in ended }
        if (_incoming.value?.invitation?.inviteId !in pending.map { it.inviteId }) { _incoming.value = null; incomingExpiry?.cancel() }
        for (i in pending) {
            if (i.inviteId in handledInvites) continue
            if (busy() || _state.value.blocksLocalStart || inFlightIntent) { api.respond(i,"BUSY"); continue }
            if (store.pairs().none { it.peerDeviceId == i.fromDeviceId && it.registered }) { api.respond(i,"DECLINE"); continue }
            if (_incoming.value?.invitation?.inviteId == i.inviteId) continue
            if (_incoming.value != null) { api.respond(i,"BUSY"); continue }
            _incoming.value = CloudInvitePrompt(i,peerName(i.fromDeviceId),UUID.randomUUID().toString(),wallNow()+(i.expiresAt-serverNow()).coerceAtLeast(0))
            incomingExpiry?.cancel(); incomingExpiry = scope.launch {
                delay((i.expiresAt - serverNow()).coerceAtLeast(0))
                if (_incoming.value?.invitation?.inviteId == i.inviteId) _incoming.value = null
            }
        }
    }

    private suspend fun applyRide(r: RideSession) {
        val epoch = operationEpoch
        val pendingEnds = store.pendingEnds()
        if (epoch != operationEpoch || r.rideId in ended || r.rideId in pendingEnds) return
        if (_ride.value?.rideId == r.rideId && r.revision < (_ride.value?.revision ?: 0)) return
        if (r.state == RideState.ENDED) {
            val binding=localBinding?.takeIf { it.rideId==r.rideId }
            ended.add(r.rideId); authorized.remove(r.rideId); inviteExpiry?.cancel()
            if (joinedRide == r.rideId || hasLocalMedia(r.rideId)) { joinedRide = null; stopMedia() }
            if(_ride.value?.rideId==r.rideId) _ride.value=null
            binding?.let { retireLocalBinding(it) }
            store.clearRide(r.rideId)
            if(epoch==operationEpoch && _ride.value==null) _state.value = CloudIntercomState.Idle
            return
        }
        setRide(r)
        if (epoch != operationEpoch || r.rideId in ended) return
        onRideUpdate(r)
        if(epoch!=operationEpoch || r.rideId in ended)return
        val peer = r.members.single { it != api.deviceId }; val name = peerName(peer)
        if (r.state == RideState.CLOUD_PAUSED) {
            if (joinedRide == r.rideId || hasLocalMedia(r.rideId)) {joinedRide=null;stopMedia()}
            _state.value=CloudIntercomState.LimitReached(r.rideId,name);return
        }
        if (r.rideId !in authorized) {
            if (!inFlightIntent) _state.value = CloudIntercomState.ResumeAvailable(r.rideId,name)
            return
        }
        if(r.state==RideState.LOCAL_ACTIVE && r.localSwitch?.phase=="COMMITTED") {
            joinedRide=null
            if(hasLocalMedia(r.rideId)) _state.value=CloudIntercomState.LocalConnected(name)
            else {stopMedia();_state.value=CloudIntercomState.ResumeAvailable(r.rideId,name)}
            return
        }
        if(r.state==RideState.SWITCHING_LOCAL) {
            _state.value=CloudIntercomState.SwitchingLocal(name)
            return
        }
        if(r.state==RideState.CLOUD_ACTIVE && joinedRide==r.rideId && _state.value is CloudIntercomState.SwitchingLocal)
            _state.value=CloudIntercomState.Connected(name,1)
        val local=localBinding?.takeIf { it.rideId==r.rideId && it.peer==peer && it.current() }
        if(local!=null && r.state in setOf(RideState.LOCAL_PENDING,RideState.LOCAL_ACTIVE)) {
            _state.value=CloudIntercomState.Idle
            if(local.lost && r.state==RideState.LOCAL_ACTIVE && !local.fallbackInFlight) {
                scope.launch { runCatching { requestLocalFallback(local) } }
            }
            return
        }
        if (r.state == RideState.INVITE_PENDING) {
            _state.value = CloudIntercomState.Inviting(peer,name,r.inviteExpiresAt ?: serverNow())
        }
        if (r.state in setOf(RideState.CLOUD_PENDING,RideState.CLOUD_ACTIVE) && joinedRide != r.rideId) {
            val approved = ApprovedCloudRide.from(r,api.deviceId,name,consentAt(),wallNow(),serverNow())
                ?: throw ControlPlaneException(409,"CLOUD_NOT_APPROVED")
            val stillPaired = store.pairs().any { it.registered && it.proof.transcript.pairId == r.pairId && it.peerDeviceId == peer }
            if (epoch != operationEpoch || r.rideId in ended) return
            if (!stillPaired) { end(r.rideId); throw ControlPlaneException(403,"PAIR_NOT_AUTHORIZED") }
            if (!enabled() || !wifiAllowed() || busy() && !canReplaceLocal(approved)) { end(r.rideId); throw ControlPlaneException(409,"BUSY") }
            inviteExpiry?.cancel(); joinedRide = r.rideId
            _state.value = CloudIntercomState.Preparing(peer,name)
            val started=startMedia(approved)
            if(epoch!=operationEpoch || r.rideId in ended) return
            if (!started) { joinedRide = null; end(r.rideId); _state.value = CloudIntercomState.Failed(name,"无法启动云端音频") }
        }
    }

    private suspend fun setRide(r: RideSession) { _ride.value = r; store.saveRide(r) }
    private suspend fun abandonCreated(r: RideSession) {
        ended.add(r.rideId); authorized.remove(r.rideId); store.queueEnd(r.rideId)
        runCatching { api.end(r.rideId); store.completeEnd(r.rideId) }
        if (_ride.value?.rideId == r.rideId) _ride.value = null
    }

    override suspend fun end(rideId: String) = withContext(dispatcher) {
        if(localBinding?.rideId==rideId) localBinding=null
        operationEpoch++; inFlightIntent = false; inviteExpiry?.cancel(); incomingExpiry?.cancel(); _incoming.value = null
        ended.add(rideId); while (ended.size > 64) ended.remove(ended.first())
        authorized.remove(rideId); store.cancelStarts(); store.queueEnd(rideId)
        val hadMedia = joinedRide == rideId || hasLocalMedia(rideId); joinedRide = null
        _state.value = if (hadMedia) CloudIntercomState.Stopping(_ride.value?.members?.firstOrNull { it != api.deviceId }?.let(peerName).orEmpty()) else CloudIntercomState.Idle
        _ride.value = null
        if (hadMedia) stopMedia()
        runCatching { api.end(rideId); store.completeEnd(rideId) }
        if (_state.value is CloudIntercomState.Stopping) _state.value = CloudIntercomState.Idle
    }

    suspend fun cancelCurrent() = withContext(dispatcher) {
        val epoch=++operationEpoch; inFlightIntent = false
        localBinding?.let {
            retiredLocalLeases.add(it.lease)
            while(retiredLocalLeases.size>128) retiredLocalLeases.remove(retiredLocalLeases.first())
        }
        localBinding=null
        val incomingRide = _incoming.value?.invitation?.rideId
        _incoming.value = null; incomingExpiry?.cancel(); inviteExpiry?.cancel()
        val unresolved = (authorized + listOfNotNull(_ride.value?.rideId,pendingIntentRideId,incomingRide)).toSet()
        unresolved.forEach { ended.add(it); authorized.remove(it) }
        pendingIntentRideId = null
        store.cancelStarts()
        for(id in unresolved) store.queueEnd(id)
        if(epoch==operationEpoch) {
            if(joinedRide in unresolved || unresolved.any(hasLocalMedia)) { joinedRide=null; stopMedia() }
            if(_ride.value?.rideId in unresolved) _ride.value=null
            _state.value=CloudIntercomState.Idle
        }
        for(id in unresolved) runCatching { api.end(id); store.completeEnd(id) }
    }

    override suspend fun resumeManually(rideId: String) = withContext(dispatcher) {
        if (busy() || inFlightIntent) throw ControlPlaneException(409,"BUSY")
        val epoch = ++operationEpoch; inFlightIntent = true
        pendingIntentRideId = rideId
        try {
            awaitReady()
            var r = api.ride(rideId)
            if (epoch != operationEpoch) return@withContext
            if (!r.canManuallyResume(serverNow()) || r.members.none { it == api.deviceId } || r.rideId in ended) throw ControlPlaneException(409,"RIDE_ENDED")
            if (r.state == RideState.CLOUD_PAUSED) r = api.extend(rideId)
            if (r.state == RideState.SWITCHING_LOCAL) r.localSwitch?.let {r=api.localSwitch(rideId,it.switchId,it.probeId,"FAIL")}
            if (r.state == RideState.LOCAL_ACTIVE) r = api.fallback(rideId)
            authorized.add(rideId); setRide(r)
            if (r.state in setOf(RideState.LOCAL_PENDING,RideState.INVITE_PENDING)) {
                val i = api.invite(rideId); scheduleInviteExpiry(i); r = api.ride(rideId)
            }
            if (epoch == operationEpoch) applyRide(r) else abandonCreated(r)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (epoch == operationEpoch) {
                val current = _ride.value
                _state.value = if (current != null && current.state != RideState.ENDED) {
                    if (current.state == RideState.CLOUD_PAUSED) CloudIntercomState.LimitReached(current.rideId,peerName(current.members.single { it != api.deviceId }))
                    else CloudIntercomState.ResumeAvailable(current.rideId,peerName(current.members.single { it != api.deviceId }))
                } else failed("",error)
            }
            throw error
        } finally { if (epoch == operationEpoch) { inFlightIntent = false; pendingIntentRideId = null } }
    }

    override suspend fun localEstablished(rideId: String) { api.localEstablished(rideId); applyRide(api.ride(rideId)) }
    override suspend fun localLost(rideId: String, reason: LocalSessionEndReason) = withContext(dispatcher) {
        val r = _ride.value ?: return@withContext
        if (r.rideId != rideId || !r.mayRequestFallback(reason,consentAt(),serverNow()) || !enabled() || !wifiAllowed()) return@withContext
        authorized.add(rideId); applyRide(api.fallback(rideId))
    }

    fun mediaStateChanged(state: CloudIntercomState) { scope.launch {
        val current = _ride.value
        if (joinedRide == null) return@launch
        if(current?.state==RideState.LOCAL_ACTIVE && current.localSwitch?.phase=="COMMITTED")return@launch
        if(state is CloudIntercomState.ResumeAvailable && current?.rideId==state.rideId) {
            localBinding?.let {retiredLocalLeases.add(it.lease);localBinding=null}
            joinedRide=null;authorized.remove(current.rideId);stopMedia();_state.value=state
            return@launch
        }
        if (state !is CloudIntercomState.Failed && !(current?.state==RideState.SWITCHING_LOCAL && state is CloudIntercomState.Connected)) _state.value = state
        if (state is CloudIntercomState.Connected && current != null) runCatching {api.cloudStatus(current.rideId,true);applyRide(api.ride(current.rideId))}
        if (state is CloudIntercomState.Failed && current != null) {
            val deadline = current.cloudStartedAt?.plus(current.cloudAllowanceMs-current.cloudUsedMs)
            if (deadline != null && serverNow() >= deadline) {
                joinedRide = null; stopMedia(); _state.value = CloudIntercomState.LimitReached(current.rideId,state.peerName)
            } else {
                end(current.rideId); _state.value = state
            }
        }
    } }
    fun mediaClosed() { scope.launch { if (_state.value is CloudIntercomState.Stopping) _state.value = CloudIntercomState.Idle } }
    suspend fun policyChanged() = withContext(dispatcher) {
        if (!enabled() || !wifiAllowed()) { cancelCurrent(); disconnect() } else { start(); safeRefresh() }
    }
    suspend fun localBecameBusy() = withContext(dispatcher) {
        if(localBinding?.current?.invoke()==true) return@withContext
        if (joinedRide == null) {
            operationEpoch++; inFlightIntent = false
            val pending = authorized.toList(); authorized.clear()
            for (id in pending) { ended.add(id); store.queueEnd(id) }
            if (pending.isNotEmpty()) { _ride.value = null; _state.value = CloudIntercomState.Idle }
        }
        _incoming.value?.let { val i = it.invitation; _incoming.value = null; runCatching { api.respond(i,"BUSY") } }
    }

    private suspend fun flushPending() {
        for (id in store.pendingEnds()) {
            try { api.end(id); store.completeEnd(id) }
            catch (e: ControlPlaneException) { if (e.code in setOf("RIDE_NOT_AUTHORIZED","PAIR_NOT_AUTHORIZED")) store.completeEnd(id) else throw e }
        }
        for (id in store.revocations()) {
            try { api.revokePair(id); store.completeRevocation(id) }
            catch (e: ControlPlaneException) { if (e.code == "PAIR_NOT_AUTHORIZED") store.completeRevocation(id) else throw e }
        }
        for ((requestId,pairId) in store.cancelledStarts()) {
            try {
                val r=api.startRide(pairId,requestId)
                store.queueEnd(r.rideId); api.end(r.rideId); store.completeEnd(r.rideId)
                store.completeCancelledStart(requestId)
            } catch (e: ControlPlaneException) {
                if (e.code in setOf("PAIR_NOT_AUTHORIZED","PAIR_REVOKED")) store.completeCancelledStart(requestId)
                else if (e.code != "BUSY") throw e
            }
        }
    }
    private suspend fun syncPairs() {
        for (r in store.pairs().filter { !it.registered }) {
            val epoch=store.epoch(r.peerDeviceId)
            try { api.registerPair(r.proof); store.markRegistered(r.peerDeviceId,r.proof.transcript.pairId) }
            catch (e: ControlPlaneException) {
                if (e.code in setOf("PAIR_REVOKED","PAIR_PROOF_EXPIRED","PAIR_KEY_MISMATCH","INVALID_PAIR_SIGNATURE")) store.forgetIfCurrent(r.peerDeviceId,r.proof.transcript.pairId,epoch)
                else if (e.code != "DEVICE_NOT_REGISTERED") throw e
            }
        }
        onPairingsChanged()
    }
    suspend fun synchronizePairs() = withContext(dispatcher) {
        if (_online.value) { api.registerDevice(); flushPending(); api.registerDevice(); syncPairs(); safeRefresh() }
        else start()
    }
    private fun scheduleInviteExpiry(i: CloudInvitation) {
        inviteExpiry?.cancel(); inviteExpiry = scope.launch {
            delay((i.expiresAt-serverNow()).coerceAtLeast(0))
            if (_ride.value?.rideId == i.rideId && _state.value is CloudIntercomState.Inviting) {
                // end() cancels the invite timer; do not cancel this durable cleanup midway.
                inviteExpiry = null
                end(i.rideId); _state.value = CloudIntercomState.Failed(peerName(i.toDeviceId),"邀请已过期")
            }
        }
    }
    private suspend fun handleEvent(text: String) {
        val r = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val id = runCatching { r["eventId"].asString }.getOrNull()?.takeIf(::cloudId) ?: return
        if (!events.add(id)) return
        while (events.size > 256) events.remove(events.first())
        val rideId = runCatching { r["rideId"]?.takeUnless { it.isJsonNull }?.asString }.getOrElse { return }
        val revision = runCatching { r["revision"].asLong }.getOrNull() ?: return
        if (rideId != null) {
            if (!cloudId(rideId) || rideId in ended || revision < (revisions[rideId] ?: 0)) return
            revisions[rideId] = revision; if (revisions.size > 64) revisions.remove(revisions.keys.first())
        }
        safeRefresh()
    }
    private suspend fun safeRefresh() { try { refresh() } catch (c: CancellationException) { throw c } catch (_: Exception) { /* Next signed snapshot repairs missed events. */ } }
    private fun failed(name: String, error: Exception): CloudIntercomState.Failed = CloudIntercomState.Failed(name,
        (error as? ControlPlaneException)?.message ?: "云端服务暂时无法连接，请稍后重试")
    override fun close() { disconnect(); monitor?.cancel(); scope.cancel() }
}
