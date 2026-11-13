package com.kuma.motointercom

import android.content.Context
import java.io.Closeable

internal fun cloudAudioRoute(selection: AudioRouteSelection): AudioRouteSelection =
    if(selection==AudioRouteSelection.EARPIECE) AudioRouteSelection.SPEAKER else selection

internal class CloudAudioStartupFailure(private val released: ResourceReleaseBarrier) :
    RuntimeException("无法准备云端音频路由") {
    fun whenReleased(callback: (Boolean)->Unit) = released.whenReleased(callback)
}

// Shares route/focus/phone policy with local audio without creating an ADM,
// WebRTC track or custom PCM producer.
internal class CloudAudioSessionController(
    private val route: RiderAudioRoute,
    private val coordinator: CommunicationAudioCoordinator,
    initialSelection: AudioRouteSelection
) : Closeable {
    private var closed=false
    private val releaseBarrier=ResourceReleaseBarrier()
    private var selection=cloudAudioRoute(initialSelection)
    fun preferredRoute()=selection
    fun start() { check(!closed); coordinator.start(); coordinator.beginMediaSession() }
    fun updateRoute(value: AudioRouteSelection, force: Boolean=true) {
        if(closed) return
        val effective=cloudAudioRoute(value)
        val changed=selection!=effective; selection=effective
        if(changed || force) coordinator.reapplyPreferredRoute(force=true)
    }
    override fun close() {
        if(closed) return
        closed=true
        val phoneActive=coordinator.isPhoneCallActive()
        val policyFailure=runCatching { coordinator.close() }.exceptionOrNull()
        val routeFailure=runCatching { route.closeRoute(restoreInitialState=!phoneActive) }.exceptionOrNull()
        route.whenReleased { releaseBarrier.complete(it && policyFailure==null && routeFailure==null) }
        policyFailure?.let { throw it }; routeFailure?.let { throw it }
    }
    fun closeWhenReleased(callback: (Boolean)->Unit) {
        runCatching { close() }
        releaseBarrier.whenReleased(callback)
    }
    companion object {
        fun create(
            context: Context,target: CommunicationAudioTarget,selection: AudioRouteSelection,
            onScoConnected: (String)->Unit,onScoDisconnected: ()->Unit,
            onSpeakerFallback: (Boolean)->Unit,onEarpieceActive: ()->Unit,
            onExternalAudioActive: (String)->Unit,onError: (Throwable)->Unit,
            onInterruption: (AudioInterruptionState)->Unit
        ): CloudAudioSessionController {
            var coordinator: CommunicationAudioCoordinator?=null
            var controller: CloudAudioSessionController?=null
            val route=AudioRouteController(context,onScoConnected=onScoConnected,onScoDisconnected=onScoDisconnected,
                onSpeakerFallback=onSpeakerFallback,onEarpieceActive=onEarpieceActive,onExternalAudioActive=onExternalAudioActive,
                onError=onError,onRouteReady={coordinator?.onRouteReady()},onRouteInvalidated={coordinator?.reapplyPreferredRoute()})
            try {
                val phone=AndroidIntercomPhoneState(context)
                route.snapshotBeforeSdk(neutralAfterPhone=phone.isCallActiveNow())
                route.restrictRestoreTo { !phone.isCallActiveNow() }
                val policy=CommunicationAudioCoordinator(target,route,AndroidIntercomAudioFocus(context),phone,
                    activateRoute={route.select(controller?.preferredRoute() ?: selection)},onStateChanged=onInterruption)
                coordinator=policy
                return CloudAudioSessionController(route,policy,selection).also { controller=it }
            } catch(t: Throwable) {
                val released=ResourceReleaseBarrier()
                val policyFailure=runCatching { coordinator?.close() }.exceptionOrNull()
                val routeFailure=runCatching { route.closeRoute(false) }.exceptionOrNull()
                route.whenReleased { released.complete(it && policyFailure==null && routeFailure==null) }
                throw CloudAudioStartupFailure(released)
            }
        }
    }
}
