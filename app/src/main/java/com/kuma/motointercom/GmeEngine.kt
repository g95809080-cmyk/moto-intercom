package com.kuma.motointercom

import android.content.Context
import com.gme.TMG.ITMGContext

// The same narrow surface is exercised with a deterministic engine in tests.
// Production always delegates to Tencent's native SDK and its standard audio I/O.
internal interface GmeEngine {
    fun initialize(appId: Int, openId: String): Int
    fun enter(ticket: GmeTicket): Int
    fun poll(): Int
    fun anchor(): Int
    fun mic(enabled: Boolean): Int
    fun speaker(enabled: Boolean): Int
    fun audioSend(enabled: Boolean): Int
    fun micLevel(): Int
    fun micVolume(volume: Int): Int
    fun pause(): Int
    fun resume(): Int
    fun exit(): Int
    fun uninitialize(): Int
    fun audioDevicesDisabled(): Boolean? = null
}

internal class TencentGmeEngine(context: Context, delegate: ITMGContext.ITMGDelegate) : GmeEngine {
    private val sdk=ITMGContext.GetInstance(context.applicationContext).also {
        it.SetLogLevel(ITMGContext.ITMG_LOG_LEVEL_NONE,ITMGContext.ITMG_LOG_LEVEL_NONE)
        it.SetTMGDelegate(delegate)
    }
    override fun initialize(appId: Int,openId: String)=sdk.Init(appId.toString(),openId)
    override fun enter(ticket: GmeTicket)=sdk.EnterRoom(ticket.roomId,ITMGContext.ITMG_ROOM_TYPE_FLUENCY,ticket.authBuffer)
    override fun poll()=sdk.Poll()
    override fun anchor()=sdk.SetAudioRole(ITMGContext.ITMG_AUDIO_MEMBER_ROLE.ITMG_AUDIO_MEMBER_ROLE_ANCHOR)
    override fun mic(enabled: Boolean)=sdk.GetAudioCtrl().EnableMic(enabled)
    override fun speaker(enabled: Boolean)=sdk.GetAudioCtrl().EnableSpeaker(enabled)
    override fun audioSend(enabled: Boolean)=sdk.GetAudioCtrl().EnableAudioSend(enabled)
    override fun micLevel()=sdk.GetAudioCtrl().GetMicLevel()
    override fun micVolume(volume: Int)=sdk.GetAudioCtrl().SetMicVolume(volume)
    override fun pause()=sdk.Pause()
    override fun resume()=sdk.Resume()
    override fun exit()=sdk.ExitRoom()
    override fun uninitialize()=sdk.Uninit()
    override fun audioDevicesDisabled()=!sdk.GetAudioCtrl().IsAudioCaptureDeviceEnabled() && !sdk.GetAudioCtrl().IsAudioPlayDeviceEnabled()
}
