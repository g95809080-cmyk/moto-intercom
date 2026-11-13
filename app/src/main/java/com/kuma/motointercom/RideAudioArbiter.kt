package com.kuma.motointercom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal enum class RideAudioSource {CLOUD,LOCAL}

// One focus/phone/route owner delegates only to the current source. A grant is
// made after the previous source's stop fence, and remains subject to focus.
internal class RideAudioArbiter(private val dispatch: (()->Unit)->Unit,private val now: ()->Long = {System.nanoTime()/1_000_000}) : CommunicationAudioTarget {
    private val lock=Mutex()
    private var cloud: CommunicationAudioTarget?=null
    private var local: CommunicationAudioTarget?=null
    var source=RideAudioSource.CLOUD;private set
    var authorized=false;private set
    private var closed=false
    private var generation=0L
    private data class ResumeWait(val source: RideAudioSource,val target: CommunicationAudioTarget,val done: CompletableDeferred<Boolean>)
    private var resumed: ResumeWait?=null
    private var transitioning=false
    private var blocked=false
    fun cloud(target: CommunicationAudioTarget?) {if(resumed?.target===cloud && target!==cloud)resumed?.done?.complete(false);cloud=target}
    fun local(target: CommunicationAudioTarget?) {if(resumed?.target===local && target!==local)resumed?.done?.complete(false);local=target}
    fun localReleased(target: CommunicationAudioTarget,success: Boolean) {
        if(local!==target)return
        if(resumed?.target===target)resumed?.done?.complete(false)
        if(!success) {blocked=true;target.suspendAudio();return}
        local=null
        if(source==RideAudioSource.LOCAL)source=RideAudioSource.CLOUD
        blocked=false
    }
    private fun target()=if(source==RideAudioSource.CLOUD)cloud else local
    override fun suspendAudio() {
        authorized=false;generation++;target()?.suspendAudio()
    }
    override fun resumeAudio() {
        if(closed)return
        authorized=true
        if(transitioning || blocked)return
        val media=target() ?: return
        val revision=++generation
        media.resumeAudio()
        media.whenAudioApplied(true) {ok -> dispatch {
            if(!closed && revision==generation && authorized && media===target())
                resumed?.takeIf {it.source==source && it.target===media}?.done?.complete(ok)
        }}
    }
    suspend fun select(next: RideAudioSource,reapplyRoute: ()->Unit,budgetMs: Long=300): Boolean=lock.withLock {
        if(closed || blocked || next==RideAudioSource.LOCAL && !authorized) return@withLock false
        val deadline=now()+budgetMs
        val chosen=if(next==RideAudioSource.CLOUD)cloud else local
        if(chosen==null)return@withLock false
        if(next!=source) {
            val old=target() ?: return@withLock false
            transitioning=true
            val revision=generation
            val stopped=try {withTimeoutOrNull((deadline-now()).coerceAtLeast(1)) {suspendCancellableCoroutine<Boolean> {continuation ->
                old.suspendForHandover {ok ->dispatch {if(continuation.isActive)continuation.resume(ok)}}
            }}==true} finally {transitioning=false}
            if(!stopped || closed || revision!=generation || target()!==old ||
                chosen!==(if(next==RideAudioSource.CLOUD)cloud else local) || next==RideAudioSource.LOCAL && !authorized)return@withLock false
            source=next
        }
        val completed=CompletableDeferred<Boolean>();val wait=ResumeWait(next,chosen,completed);resumed=wait
        var success=false
        try {
            reapplyRoute()
            if(now()>=deadline)return@withLock false
            success=withTimeoutOrNull((deadline-now()).coerceAtLeast(1)) {completed.await()}==true &&
                source==next && target()===chosen && authorized && !closed && !blocked && now()<deadline
            success
        }
        finally {
            if(resumed===wait)resumed=null
            if(!success && next==RideAudioSource.LOCAL && source==next) {blocked=true;generation++;chosen.suspendAudio()}
        }
    }
    fun close() {closed=true;authorized=false;generation++;resumed?.done?.complete(false);cloud?.suspendAudio();local?.suspendAudio()}
}
