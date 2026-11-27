package com.kuma.motointercom.group.cloud

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.AndroidIntercomAudioFocus
import com.kuma.motointercom.AudioFocusResult
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.runAllCleanupSteps
import com.kuma.motointercom.group.GroupDiagnostics
import com.ss.bytertc.engine.IAudioFrameProcessor
import com.ss.bytertc.engine.RTCEngine
import com.ss.bytertc.engine.RTCRoom
import com.ss.bytertc.engine.RTCRoomConfig
import com.ss.bytertc.engine.UserInfo
import com.ss.bytertc.engine.data.AudioChannel
import com.ss.bytertc.engine.data.AudioFormat
import com.ss.bytertc.engine.data.AudioProcessorMethod
import com.ss.bytertc.engine.data.AudioPropertiesConfig
import com.ss.bytertc.engine.data.AudioSampleRate
import com.ss.bytertc.engine.data.AudioRoute
import com.ss.bytertc.engine.data.EngineConfig
import com.ss.bytertc.engine.data.PublishState
import com.ss.bytertc.engine.data.PublishStateChangeReason
import com.ss.bytertc.engine.data.RemoteAudioPropertiesInfo
import com.ss.bytertc.engine.data.StreamInfo
import com.ss.bytertc.engine.data.SubscribeState
import com.ss.bytertc.engine.data.SubscribeStateChangeReason
import com.ss.bytertc.engine.handler.IRTCEngineEventHandler
import com.ss.bytertc.engine.handler.IRTCRoomEventHandler
import com.ss.bytertc.engine.type.AudioScenarioType
import com.ss.bytertc.engine.type.ChannelProfile
import com.ss.bytertc.engine.type.ErrorCode
import com.ss.bytertc.engine.type.RTCRoomStats
import com.ss.bytertc.engine.type.RoomState
import com.ss.bytertc.engine.type.RoomStateChangeReason
import com.ss.bytertc.engine.utils.IAudioFrame

internal fun volcRoomStateEvent(state: Int): GroupRtcEvent = when (state) {
    0 -> GroupRtcEvent.Joined
    ErrorCode.ERROR_CODE_INVALID_TOKEN,
    ErrorCode.ERROR_CODE_TOKEN_EXPIRED,
    ErrorCode.ERROR_CODE_UPDATE_TOKEN_WITH_INVALID_TOKEN -> GroupRtcEvent.JoinFailed(
        ErrorCode.ERROR_CODE_INVALID_TOKEN, "RTC Token 无效或已过期"
    )
    else -> GroupRtcEvent.JoinFailed(state, volcJoinFailureMessage(state))
}

internal fun releaseVolcRtcResources(
    leaveRoom: (() -> Int)?,
    destroyRoom: (() -> Unit)?,
    stopAudioCapture: (() -> Unit)?,
    destroyEngine: (() -> Unit)?,
    closeAudioFocus: () -> Unit,
    expectLeaveRoomSuccess: Boolean = false
) {
    runAllCleanupSteps(
        {
            if (leaveRoom != null) {
                val code = leaveRoom()
                if (expectLeaveRoomSuccess) {
                    check(code == 0) { "RTCRoom.leaveRoom failed with code $code" }
                }
            }
        },
        { destroyRoom?.invoke() },
        { stopAudioCapture?.invoke() },
        { destroyEngine?.invoke() },
        closeAudioFocus
    )
}

internal fun volcJoinFailureMessage(code: Int): String = when (code) {
    -1080 -> "发布流达到上限，请确认大群 AppID 已开启音频选路"
    -1070 -> "订阅流达到上限，请确认大群 AppID 已开启音频选路"
    -1000 -> "RTC Token 或房间参数无效"
    -1001 -> "加入 RTC 房间失败（-1001），请确认 AppID 属于「实时音视频 RTC」而非 WTN"
    -1006 -> "已被服务端移出 RTC 房间"
    else -> "加入火山引擎 RTC 失败（$code）"
}

internal class VolcRemoteAudioMuteTracker : IAudioFrameProcessor {
    @Volatile private var allowedUsers = emptySet<String>()
    private val userStreams = mutableMapOf<String, MutableSet<String>>()

    override fun onProcessRemoteUserAudioFrame(streamId: String?, streamInfo: StreamInfo?, audioFrame: IAudioFrame): Int {
        if (streamInfo?.userId in allowedUsers) return 0
        val buffer = audioFrame.dataBuffer
        val end = buffer.position() + minOf(audioFrame.data_size(), buffer.remaining())
        var index = buffer.position()
        while (index + Long.SIZE_BYTES <= end) {
            buffer.putLong(index, 0L)
            index += Long.SIZE_BYTES
        }
        while (index < end) buffer.put(index++, 0)
        return 0
    }

    override fun onProcessRecordAudioFrame(audioFrame: IAudioFrame) = 0
    override fun onProcessPlayBackAudioFrame(audioFrame: IAudioFrame) = 0
    override fun onProcessEarMonitorAudioFrame(audioFrame: IAudioFrame) = 0
    override fun onProcessScreenAudioFrame(audioFrame: IAudioFrame) = 0

    fun setAllowedUsers(userIds: Set<String>, setStreamMuted: (String, Boolean) -> Unit) {
        val previous = allowedUsers
        allowedUsers = userIds.toSet()
        for ((user, streams) in userStreams) {
            val allowed = user in userIds
            if (allowed != (user in previous)) streams.forEach { setStreamMuted(it, !allowed) }
        }
    }

    fun onRemoteStreamObserved(userId: String?, streamId: String?, published: Boolean): String? {
        if (userId.isNullOrBlank() || streamId.isNullOrBlank()) return null
        if (published) {
            val streams = userStreams.getOrPut(userId) { mutableSetOf() }
            val newlyTracked = streams.add(streamId)
            return if (newlyTracked && userId !in allowedUsers) streamId else null
        }
        userStreams[userId]?.let { streams ->
            streams.remove(streamId)
            if (streams.isEmpty()) userStreams.remove(userId)
        }
        return null
    }

    fun onUserLeft(userId: String?) {
        if (!userId.isNullOrBlank()) userStreams.remove(userId)
    }

    fun mutedStreamIds(): Set<String> = buildSet {
        for ((user, streams) in userStreams) if (user !in allowedUsers) addAll(streams)
    }

    fun clear() {
        allowedUsers = emptySet()
        userStreams.clear()
    }
}

internal class VolcRoomEventGate {
    private var joined = false
    private var terminalReported = false

    fun isTerminal(): Boolean = terminalReported

    fun reset() {
        joined = false
        terminalReported = false
    }

    fun accept(event: GroupRtcEvent): Boolean {
        if (terminalReported) return false
        return when (event) {
            GroupRtcEvent.Joined -> {
                if (joined) false else {
                    joined = true
                    true
                }
            }
            is GroupRtcEvent.JoinFailed,
            is GroupRtcEvent.ConnectionLost -> {
                joined = false
                terminalReported = true
                true
            }
            else -> true
        }
    }
}

internal class VolcGroupRtcMedia(
    context: Context,
    private val onEvent: (GroupRtcEvent) -> Unit,
    private val onRouteLabel: (String) -> Unit
) : GroupRtcMedia {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: RTCEngine? = null
    private var room: RTCRoom? = null
    private var engineHandler: IRTCEngineEventHandler? = null
    private var roomHandler: IRTCRoomEventHandler? = null
    private var joinedToken: CloudRtcToken? = null
    private var closed = false
    private var localMuted = false
    private var selectedRoute = AudioRouteSelection.EARPIECE
    private var focusSuspended = false
    private var audioCaptureStarted = false
    private var roomJoined = false
    private val eventGate = VolcRoomEventGate()
    private val remoteMuteTracker = VolcRemoteAudioMuteTracker()
    private val audioFocus = AndroidIntercomAudioFocus(appContext)
    private val diagnostics = GroupDiagnostics()

    init {
        audioFocus.setListener(::onAudioFocusChanged)
    }

    override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
        check(!closed) { "RTC media is closed" }
        check(room == null) { "RTC room is already active" }
        check(audioFocus.request() == AudioFocusResult.GRANTED) { "无法获取语音音频焦点" }
        joinedToken = token
        localMuted = startMuted
        selectedRoute = route
        focusSuspended = false
        eventGate.reset()
        val engineConfig = EngineConfig().apply {
            context = appContext
            appID = token.appId
        }
        val engineHandler = object : IRTCEngineEventHandler() {
            override fun onAudioRouteChanged(audioRoute: AudioRoute?) {
                val mapped = audioRoute?.toSelection()
                if (mapped != null) {
                    main.post {
                        if (!closed && !eventGate.isTerminal()) {
                            selectedRoute = mapped
                            onRouteLabel(mapped.label())
                        }
                    }
                }
            }

            override fun onRemoteAudioPropertiesReport(
                audioPropertiesInfos: Array<RemoteAudioPropertiesInfo>?,
                totalRemoteVolume: Int
            ) {
                val discovered = audioPropertiesInfos
                    ?.mapNotNull { info ->
                        val userId = info.streamInfo?.userId
                        val streamId = info.streamId?.takeIf { it.isNotBlank() } ?: info.streamInfo?.streamId
                        if (!userId.isNullOrBlank() && !streamId.isNullOrBlank()) userId to streamId else null
                    }
                    .orEmpty()
                if (discovered.isNotEmpty()) {
                    main.post { discovered.forEach { (userId, streamId) -> observeRemoteAudioStream(userId, streamId, true) } }
                }
                val active = audioPropertiesInfos
                    ?.filter { it.audioPropertiesInfo?.vad != 0 }
                    ?.mapNotNull { it.streamInfo?.userId }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty()
                reportRtcEvent(GroupRtcEvent.ActiveSpeakers(active))
            }

            override fun onError(error: Int) {
                diagnostics.cloudRtcSdkError(error)
                reportRtcEvent(GroupRtcEvent.Error("火山引擎 RTC 错误 $error"))
            }

            override fun onConnectionStateChanged(state: Int, reason: Int) {
                diagnostics.cloudRtcConnectionState(state, reason)
                if (state == 7) {
                    reportRtcEvent(GroupRtcEvent.ConnectionLost("RTC 连接失败，请重试"))
                }
            }
        }
        this.engineHandler = engineHandler
        val created = checkNotNull(RTCEngine.createRTCEngine(engineConfig, engineHandler)) {
            "无法创建火山引擎 RTC 引擎"
        }
        engine = created
        check(created.registerAudioProcessor(remoteMuteTracker) == 0) { "无法注册远端音频处理器" }
        val format = AudioFormat(AudioSampleRate.AUDIO_SAMPLE_RATE_AUTO, AudioChannel.AUDIO_CHANNEL_AUTO)
        check(created.enableAudioProcessor(AudioProcessorMethod.AUDIO_FRAME_PROCESSOR_REMOTE_USER, format) == 0) {
            "无法启用远端音频处理器"
        }
        created.setAudioScenario(AudioScenarioType.CHATROOM)
        created.setAudioProfile(com.ss.bytertc.engine.type.AudioProfileType.AUDIO_PROFILE_HD_MONO)
        created.setDefaultAudioRoute(route.toAudioRoute())
        created.enableAudioPropertiesReport(AudioPropertiesConfig(500, false, true))
        created.startAudioCapture()
        audioCaptureStarted = true
        created.muteAudioCapture(startMuted)

        val roomHandler = object : IRTCRoomEventHandler() {
            override fun onRoomStateChangedWithReason(
                roomId: String?,
                uid: String?,
                state: RoomState?,
                reason: RoomStateChangeReason?
            ) {
                diagnostics.cloudRtcRoomState(
                    token.rtcRoomId,
                    "typed",
                    state?.value() ?: -1,
                    reason?.value()
                )
                if (reason == RoomStateChangeReason.UPDATE_TOKEN_WITH_INVALID_TOKEN) {
                    reportRtcEvent(GroupRtcEvent.JoinFailed(-1000, roomStateFailureMessage(reason)))
                    return
                }
                when (state) {
                    RoomState.JOIN_SUCCESS -> reportRtcEvent(GroupRtcEvent.Joined)
                    RoomState.JOIN_FAILED -> reportRtcEvent(GroupRtcEvent.JoinFailed(
                        roomStateReasonCode(reason),
                        roomStateFailureMessage(reason)
                    ))
                    RoomState.LEFT -> reportRtcEvent(GroupRtcEvent.ConnectionLost("已离开火山引擎 RTC 房间"))
                    null -> Unit
                }
            }

            override fun onRoomStateChanged(
                roomId: String?,
                uid: String?,
                state: Int,
                extraInfo: String?
            ) {
                diagnostics.cloudRtcRoomState(token.rtcRoomId, "compat", state, null)
                reportRtcEvent(volcRoomStateEvent(state))
            }

            override fun onTokenWillExpire() {
                reportRtcEvent(GroupRtcEvent.TokenWillExpire)
            }

            override fun onPublishPrivilegeTokenWillExpire() {
                reportRtcEvent(GroupRtcEvent.TokenWillExpire)
            }

            override fun onSubscribePrivilegeTokenWillExpire() {
                reportRtcEvent(GroupRtcEvent.TokenWillExpire)
            }

            override fun onAudioPublishStateChanged(
                streamId: String?,
                streamInfo: StreamInfo?,
                state: PublishState?,
                reason: PublishStateChangeReason?
            ) {
                if (state == PublishState.UNPUBLISHED &&
                    reason == PublishStateChangeReason.OVER_STREAM_PUBLISH_LIMIT
                ) {
                    reportRtcEvent(GroupRtcEvent.Error("发布流达到上限（-1080），请确认大群 AppID 已开启音频选路"))
                }
            }

            override fun onAudioSubscribeStateChanged(
                streamId: String?,
                streamInfo: StreamInfo?,
                state: SubscribeState?,
                reason: SubscribeStateChangeReason?
            ) {
                val resolvedStreamId = streamId?.takeIf { it.isNotBlank() } ?: streamInfo?.streamId
                val resolvedUserId = streamInfo?.userId
                if (!resolvedUserId.isNullOrBlank() && !resolvedStreamId.isNullOrBlank() && state == SubscribeState.SUBSCRIBED) {
                    main.post { observeRemoteAudioStream(resolvedUserId, resolvedStreamId, true) }
                }
                if (state == SubscribeState.UNSUBSCRIBED &&
                    reason == SubscribeStateChangeReason.OVER_STREAM_SUBSCRIBE_LIMIT
                ) {
                    reportRtcEvent(GroupRtcEvent.Error("订阅流达到上限（-1070），请确认大群 AppID 已开启音频选路"))
                }
            }

            override fun onUserPublishStreamAudio(
                streamId: String?,
                streamInfo: StreamInfo?,
                isPublish: Boolean
            ) {
                val resolvedStreamId = streamId?.takeIf { it.isNotBlank() } ?: streamInfo?.streamId
                val resolvedUserId = streamInfo?.userId
                if (!resolvedUserId.isNullOrBlank() && !resolvedStreamId.isNullOrBlank()) {
                    main.post { observeRemoteAudioStream(resolvedUserId, resolvedStreamId, isPublish) }
                }
            }

            override fun onUserLeave(uid: String?, reason: Int) {
                if (!uid.isNullOrBlank()) main.post { if (!closed) remoteMuteTracker.onUserLeft(uid) }
            }

            override fun onLeaveRoom(stats: RTCRoomStats?) {
                reportRtcEvent(GroupRtcEvent.ConnectionLost("已离开火山引擎 RTC 房间"))
            }
        }
        this.roomHandler = roomHandler
        val createdRoom = checkNotNull(created.createRTCRoom(token.rtcRoomId)) { "无法创建 RTC 房间" }
        room = createdRoom
        createdRoom.setRTCRoomEventHandler(roomHandler)
        val config = RTCRoomConfig(
            ChannelProfile.CHANNEL_PROFILE_COMMUNICATION,
            false, // Publish after Joined, using the latest local mute and focus state.
            false,
            true,
            false
        )
        diagnostics.cloudRtcJoinAttempt(token.rtcRoomId)
        val code = createdRoom.joinRoom(
            token.rtcToken,
            UserInfo(token.rtcUserId, "motocom-group"),
            true,
            config
        )
        diagnostics.cloudRtcJoinReturned(token.rtcRoomId, code)
        if (code != 0) {
            reportRtcEvent(volcRoomStateEvent(code))
        }
    }

    override fun updateToken(token: CloudRtcToken): Boolean {
        val current = joinedToken ?: return false
        if (closed || current.rtcRoomId != token.rtcRoomId || current.rtcUserId != token.rtcUserId) return false
        val code = room?.updateToken(token.rtcToken) ?: -1
        diagnostics.cloudRtcTokenUpdate(code)
        if (code == 0) joinedToken = token
        return code == 0
    }

    override fun setLocalMuted(muted: Boolean) {
        if (closed) return
        localMuted = muted
        applyLocalMute()
    }

    override fun setAllowedRemoteUsers(userIds: Set<String>) {
        if (!closed) remoteMuteTracker.setAllowedUsers(userIds, ::applyRemoteStreamMute)
    }

    override fun selectRoute(route: AudioRouteSelection) {
        if (closed) return
        selectedRoute = route
        engine?.setDefaultAudioRoute(route.toAudioRoute())
        engine?.setAudioRoute(route.toAudioRoute())
        postRoute(route)
    }

    override fun close() {
        if (closed) return
        closed = true
        val activeRoom = room
        val activeEngine = engine
        room = null
        engine = null
        joinedToken = null
        val stopCapture = audioCaptureStarted
        audioCaptureStarted = false
        val expectLeaveSuccess = roomJoined && !eventGate.isTerminal()
        roomJoined = false
        focusSuspended = false
        eventGate.reset()
        roomHandler = null
        engineHandler = null
        releaseVolcRtcResources(
            leaveRoom = activeRoom?.let { r -> { r.leaveRoom() } },
            destroyRoom = activeRoom?.let { r -> { r.destroy() } },
            stopAudioCapture = if (activeEngine != null && stopCapture) ({ activeEngine.stopAudioCapture() }) else null,
            destroyEngine = if (activeEngine != null) ({ RTCEngine.destroyRTCEngine() }) else null,
            closeAudioFocus = audioFocus::close,
            expectLeaveRoomSuccess = expectLeaveSuccess
        )
        remoteMuteTracker.clear()
    }

    private fun applyLocalMute() {
        val activeRoom = room ?: return
        engine?.muteAudioCapture(localMuted || focusSuspended)
        if (roomJoined) activeRoom.publishStreamAudio(!localMuted && !focusSuspended)
    }

    private fun observeRemoteAudioStream(userId: String, streamId: String, published: Boolean) {
        if (closed) return
        val mutedStreamId = remoteMuteTracker.onRemoteStreamObserved(userId, streamId, published)
        if (mutedStreamId != null) {
            applyRemoteStreamMute(mutedStreamId, true)
        }
    }

    private fun applyRemoteStreamMute(streamId: String, muted: Boolean) {
        room?.subscribeStreamAudio(streamId, !muted)
        engine?.setRemoteAudioPlaybackVolume(streamId, if (muted || focusSuspended) 0 else 100)
    }

    private fun syncMutedPeers() {
        remoteMuteTracker.mutedStreamIds().forEach { streamId -> applyRemoteStreamMute(streamId, true) }
    }

    private fun reportRtcEvent(event: GroupRtcEvent) {
        main.post {
            if (closed) return@post
            if (!eventGate.accept(event)) return@post
            if (event is GroupRtcEvent.Joined) {
                roomJoined = true
                applyLocalMute()
                syncMutedPeers()
            } else if (event is GroupRtcEvent.JoinFailed || event is GroupRtcEvent.ConnectionLost) {
                roomJoined = false
            }
            onEvent(event)
        }
    }

    private fun postRoute(selection: AudioRouteSelection) {
        main.post { if (!closed) onRouteLabel(selection.label()) }
    }

    private fun AudioRouteSelection.label(): String = when (this) {
        AudioRouteSelection.BLUETOOTH -> "蓝牙耳机"
        AudioRouteSelection.EARPIECE -> "手机听筒 / 有线耳机"
        AudioRouteSelection.SPEAKER -> "手机扬声器"
    }

    private fun onAudioFocusChanged(change: Int) {
        main.post {
            if (closed) return@post
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (focusSuspended) return@post
                    focusSuspended = true
                    applyLocalMute()
                    room?.pauseAllSubscribedStreamAudio()
                    engine?.setPlaybackVolume(0)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (!focusSuspended) return@post
                    focusSuspended = false
                    applyLocalMute()
                    room?.resumeAllSubscribedStreamAudio()
                    engine?.setPlaybackVolume(100)
                    syncMutedPeers()
                    selectRoute(selectedRoute)
                }
            }
        }
    }

    private fun AudioRouteSelection.toAudioRoute(): AudioRoute = when (this) {
        AudioRouteSelection.BLUETOOTH -> AudioRoute.AUDIO_ROUTE_HEADSET_BLUETOOTH
        AudioRouteSelection.EARPIECE -> AudioRoute.AUDIO_ROUTE_EARPIECE
        AudioRouteSelection.SPEAKER -> AudioRoute.AUDIO_ROUTE_SPEAKERPHONE
    }

    private fun AudioRoute.toSelection(): AudioRouteSelection? = when (this) {
        AudioRoute.AUDIO_ROUTE_HEADSET_BLUETOOTH,
        AudioRoute.AUDIO_ROUTE_HEADSET,
        AudioRoute.AUDIO_ROUTE_HEADSET_USB -> AudioRouteSelection.BLUETOOTH
        AudioRoute.AUDIO_ROUTE_EARPIECE -> AudioRouteSelection.EARPIECE
        AudioRoute.AUDIO_ROUTE_SPEAKERPHONE -> AudioRouteSelection.SPEAKER
        else -> null
    }

    private fun roomStateReasonCode(reason: RoomStateChangeReason?): Int = when (reason) {
        RoomStateChangeReason.EXCEEDS_THE_UPPER_LIMIT -> -1080
        RoomStateChangeReason.ROOM_FORBIDDEN,
        RoomStateChangeReason.USER_FORBIDDEN,
        RoomStateChangeReason.KICKED_OUT,
        RoomStateChangeReason.ROOM_DISMISS -> -1006
        RoomStateChangeReason.INVALID_TOKEN,
        RoomStateChangeReason.TOKEN_EXPIRED,
        RoomStateChangeReason.UPDATE_TOKEN_WITH_INVALID_TOKEN -> -1000
        RoomStateChangeReason.JOIN_ROOM_FAILED -> -1001
        else -> -1
    }

    private fun roomStateFailureMessage(reason: RoomStateChangeReason?): String = when (reason) {
        RoomStateChangeReason.EXCEEDS_THE_UPPER_LIMIT ->
            "RTC 房间流数超限，请确认大群 AppID 已开启音频选路"
        RoomStateChangeReason.ROOM_FORBIDDEN,
        RoomStateChangeReason.USER_FORBIDDEN,
        RoomStateChangeReason.KICKED_OUT,
        RoomStateChangeReason.ROOM_DISMISS -> "已被服务端移出 RTC 房间"
        RoomStateChangeReason.INVALID_TOKEN,
        RoomStateChangeReason.TOKEN_EXPIRED,
        RoomStateChangeReason.UPDATE_TOKEN_WITH_INVALID_TOKEN -> "RTC Token 无效或已过期"
        RoomStateChangeReason.JOIN_ROOM_FAILED ->
            "加入 RTC 房间失败（-1001），请确认 AppID 属于「实时音视频 RTC」而非 WTN"
        else -> "加入火山引擎 RTC 失败，请重试"
    }
}
