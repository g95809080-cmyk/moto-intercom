package com.kuma.motointercom.group.hybrid

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.AudioControlSettings
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.IntercomRuntimeKeepAlive
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Service-owned R5 HYBRID runtime.
 * Strictly enforces mutual exclusion between local media (GroupAudio) and cloud media (VolcGroupRtcMedia).
 */
internal class GroupHybridRuntime(
    private val context: Context,
    private val endpoint: GroupAuthEndpoint,
    private val nickname: String,
    private val request: GroupCloudStartRequest,
    private var initialRoute: AudioRouteSelection,
    private var initialControls: AudioControlSettings,
    private val onSnapshot: (GroupSessionSnapshot) -> Unit,
    private val onAudioLabel: (String) -> Unit,
    private val onReleased: () -> Unit,
    private val pollApiFactory: (String) -> GroupCloudApi = { HttpGroupCloudClient(it) },
    private val actionApiFactory: (String) -> GroupCloudApi = { HttpGroupCloudClient(it) },
    private val localNetworkFactory: ((() -> GroupSessionSnapshot, (GroupSessionEvent) -> Unit) -> GroupNetworkEffects)? = null,
    private val localAudioFactory: ((() -> GroupSessionSnapshot, (GroupSessionEvent) -> Unit, GroupAudioLifetime) -> GroupAudioEffects)? = null,
    private val rtcMediaFactory: GroupRtcMediaFactory = object : GroupRtcMediaFactory {
        override fun create(
            context: Context,
            onEvent: (GroupRtcEvent) -> Unit,
            onRouteLabel: (String) -> Unit
        ): GroupRtcMedia = VolcGroupRtcMedia(context, onEvent, onRouteLabel)
    },
    private val proximityProbeFactory: GroupLocalProximityProbeFactory = object : GroupLocalProximityProbeFactory {
        override fun create(context: Context): GroupLocalProximityProbe = GroupBleProximityProbe(context)
    },
    private val acquireKeepAlive: () -> Closeable = { IntercomRuntimeKeepAlive.acquire(context) }
) : GroupRuntimeHandle {
    private val main = Handler(Looper.getMainLooper())
    private val pollExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "motocom-hybrid-poll").apply { isDaemon = true }
    }
    private val actionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "motocom-hybrid-action").apply { isDaemon = true }
    }

    private var stopping = false
    private var cloudReleased = false
    private var released = false
    private var ownership: Any? = null
    private var keepAlive: Closeable? = null
    private var pollApi: GroupCloudApi? = null
    private var actionApi: GroupCloudApi? = null

    private var localEpochGeneration = 0L
    private var activeLocalEpoch: Long? = null
    private var localNetwork: GroupNetworkEffects? = null
    private var localSlot: LocalTransportSlot? = null
    private var localAudio: GroupAudioEffects? = null
    private val localAudioLeases = mutableSetOf<Any>()
    private var drainingLocalEpoch: Long? = null
    private var drainingLocalNetworkDone = true
    private var localDisposalFailed = false

    private var preconnectEpochGeneration = 0L
    private var preconnectEpoch: Long? = null
    private var preconnectNetwork: GroupNetworkEffects? = null
    private var preconnectSlot: LocalTransportSlot? = null
    private var drainingPreconnectDone = true
    private val pendingAfterPreconnectRelease = mutableListOf<() -> Unit>()

    private var rtc: GroupRtcMedia? = null
    private var rtcGeneration = 0L
    private var cloudDisposalFailed = false

    private var proximityProbe: GroupLocalProximityProbe? = null
    private var proximityGeneration = 0L

    private class LocalTransportSlot(
        var epoch: Long,
        var generation: Long,
        var promotedToLocal: Boolean
    )

    val writer = GroupHybridSessionOrchestrator(
        endpoint = endpoint,
        nickname = nickname,
        nowMs = ::groupNowMs,
        assertWriter = { check(Looper.myLooper() == Looper.getMainLooper()) },
        effects = ::execute,
        diagnostics = GroupDiagnostics(),
        wallNowMs = System::currentTimeMillis
    )

    override val snapshot: GroupSessionSnapshot get() = writer.snapshot

    private val tick = object : Runnable {
        override fun run() {
            if (!stopping && snapshot.operation != null) {
                writer.dispatch(GroupSessionEvent.Tick)
                localAudio?.poll()
                main.postDelayed(this, 1_000)
            }
        }
    }

    override fun start(code: GroupJoinCode?) {
        try {
            val normalizedUrl = GroupCloudUrlPolicy.normalizeConfiguredUrl(request.serverUrl)
            ownership = checkNotNull(GroupRuntimeOwnership.acquire())
            keepAlive = acquireKeepAlive()
            pollApi = pollApiFactory(normalizedUrl)
            actionApi = actionApiFactory(normalizedUrl)
            writer.dispatchInternal(GroupHybridInternalEvent.Start(request))
            writer.dispatchInternal(GroupHybridInternalEvent.RouteChanged(initialRoute))
            main.post(tick)
        } catch (error: Exception) {
            stop(false)
            throw IllegalStateException(error.message ?: "无法启动双模群组，请确认其他通话已结束")
        }
    }

    override fun dispatch(event: GroupSessionEvent) = writer.dispatch(event)

    override fun route(value: AudioRouteSelection) {
        initialRoute = value
        localAudio?.selectRoute(value)
        writer.dispatchInternal(GroupHybridInternalEvent.RouteChanged(value))
    }

    override fun vox(value: Boolean) {
        initialControls = initialControls.copy(voxEnabled = value)
        localAudio?.vox(value)
    }

    private fun postInternal(event: GroupHybridInternalEvent) {
        main.post {
            if (!stopping) writer.dispatchInternal(event)
        }
    }

    private fun runAction(
        op: UUID,
        actionName: String,
        block: (GroupCloudApi) -> GroupHybridInternalEvent?
    ) {
        val api = actionApi ?: return
        if (actionExecutor.isShutdown) return
        actionExecutor.execute {
            try {
                val result = block(api)
                if (result != null) postInternal(result)
            } catch (error: GroupCloudException) {
                postInternal(GroupHybridInternalEvent.ActionFailed(op, actionName, error))
            } catch (_: Exception) {
                postInternal(
                    GroupHybridInternalEvent.ActionFailed(
                        op,
                        actionName,
                        GroupCloudException(0, "NETWORK_ERROR", "网络请求失败，请检查连接后重试")
                    )
                )
            }
        }
    }

    private fun runPoll(
        op: UUID,
        block: (GroupCloudApi) -> GroupHybridInternalEvent
    ) {
        val api = pollApi ?: return
        if (pollExecutor.isShutdown) return
        pollExecutor.execute {
            try {
                postInternal(block(api))
            } catch (error: GroupCloudException) {
                postInternal(GroupHybridInternalEvent.PollFailed(op, error))
            } catch (_: Exception) {
                postInternal(
                    GroupHybridInternalEvent.PollFailed(
                        op,
                        GroupCloudException(0, "NETWORK_ERROR", "云端事件同步中断")
                    )
                )
            }
        }
    }

    private fun withLocalNetwork(epoch: Long, block: (GroupNetworkEffects) -> Unit) {
        check(!cloudDisposalFailed && rtc == null) { "Cannot start local network while Cloud RTC is active or undisposed" }
        if (activeLocalEpoch == epoch && localNetwork != null) {
            block(localNetwork!!)
            return
        }
        val gen = ++localEpochGeneration
        activeLocalEpoch = epoch
        withPreconnectReleased {
            if (stopping || activeLocalEpoch != epoch || localEpochGeneration != gen) return@withPreconnectReleased
            check(!cloudDisposalFailed && rtc == null) { "Cannot start local network while Cloud RTC is active or undisposed" }
            val existing = localNetwork
            if (existing != null) {
                block(existing)
                return@withPreconnectReleased
            }
            val slot = LocalTransportSlot(epoch = epoch, generation = gen, promotedToLocal = true)
            val created = localNetworkFactory?.invoke({ writer.snapshot }, { event ->
                dispatchSlotEvent(slot, event)
            }) ?: GroupNetworkRuntime(context, endpoint, { writer.snapshot }, { event ->
                dispatchSlotEvent(slot, event)
            })
            localSlot = slot
            localNetwork = created
            block(created)
        }
    }

    private fun withPreconnectNetwork(epoch: Long, block: (GroupNetworkEffects) -> Unit) {
        check(!localDisposalFailed && !cloudDisposalFailed && localAudio == null && localAudioLeases.isEmpty()) {
            "Control-only preconnect cannot run while local audio is active or disposal failed"
        }
        if (preconnectEpoch == epoch && preconnectNetwork != null && drainingPreconnectDone) {
            block(preconnectNetwork!!)
            return
        }
        stopPreconnectNetwork()
        val gen = ++preconnectEpochGeneration
        preconnectEpoch = epoch
        withPreconnectReleased {
            if (stopping || preconnectEpoch != epoch || preconnectEpochGeneration != gen) return@withPreconnectReleased
            if (localAudio != null || localAudioLeases.isNotEmpty() || localDisposalFailed || cloudDisposalFailed) return@withPreconnectReleased
            val existing = preconnectNetwork
            if (existing != null) {
                block(existing)
                return@withPreconnectReleased
            }
            val slot = LocalTransportSlot(epoch = epoch, generation = gen, promotedToLocal = false)
            val created = localNetworkFactory?.invoke({ writer.snapshot }, { event ->
                dispatchSlotEvent(slot, event)
            }) ?: GroupNetworkRuntime(context, endpoint, { writer.snapshot }, { event ->
                dispatchSlotEvent(slot, event)
            })
            preconnectSlot = slot
            preconnectNetwork = created
            block(created)
        }
    }

    private fun withPreconnectReleased(action: () -> Unit) {
        if (preconnectNetwork == null && drainingPreconnectDone) {
            action()
            return
        }
        if (preconnectNetwork != null) {
            stopPreconnectNetwork()
        }
        if (preconnectNetwork == null && drainingPreconnectDone) {
            action()
        } else {
            pendingAfterPreconnectRelease += action
        }
    }

    private fun flushPendingAfterPreconnectRelease() {
        if (!drainingPreconnectDone || preconnectNetwork != null) return
        val tasks = pendingAfterPreconnectRelease.toList()
        pendingAfterPreconnectRelease.clear()
        tasks.forEach { it() }
    }

    private fun stopPreconnectNetwork() {
        preconnectEpochGeneration += 1L
        preconnectEpoch = null
        preconnectSlot = null
        val current = preconnectNetwork ?: return
        preconnectNetwork = null
        drainingPreconnectDone = false
        current.stop(flush = false) {
            main.post {
                drainingPreconnectDone = true
                flushPendingAfterPreconnectRelease()
                releaseIfDone()
            }
        }
    }

    private fun promotePreconnectToLocal(fromEpoch: Long, toEpoch: Long) {
        check(!cloudDisposalFailed && rtc == null) {
            "Cannot promote preconnect to active local transport while Cloud RTC is active or undisposed"
        }
        check(!localDisposalFailed && localAudio == null && localAudioLeases.isEmpty() && localNetwork == null) {
            "Cannot promote preconnect while prior local media or local network is active"
        }
        val promoted = preconnectNetwork
        val slot = preconnectSlot
        check(promoted != null && slot != null && preconnectEpoch == fromEpoch && !slot.promotedToLocal) {
            "Missing ready control-only preconnect network for handover from epoch $fromEpoch"
        }
        preconnectEpochGeneration += 1L
        preconnectEpoch = null
        preconnectNetwork = null
        preconnectSlot = null
        val generation = ++localEpochGeneration
        activeLocalEpoch = toEpoch
        localNetwork = promoted
        localSlot = slot
        slot.epoch = toEpoch
        slot.generation = generation
        slot.promotedToLocal = true
    }

    private fun ensureLocalAudio(epoch: Long): GroupAudioEffects {
        check(!cloudDisposalFailed && rtc == null) { "Cannot open Local GroupAudio while Cloud RTC is active or undisposed" }
        check(!localDisposalFailed) { "本地音频平台释放失败，禁止重新启动音频" }
        localAudio?.let { return it }
        check(localAudioLeases.isEmpty()) { "正在释放旧本地音频平台，请稍后重试" }
        val generation = localEpochGeneration
        val token = Any()
        localAudioLeases += token
        val lifetime = GroupAudioLifetime(
            release = {
                main.post {
                    localAudioLeases.remove(token)
                    completeLocalDrainIfReady()
                    releaseIfDone()
                }
            },
            fail = {
                main.post {
                    localDisposalFailed = true
                    val failedEpoch = drainingLocalEpoch ?: epoch
                    drainingLocalEpoch = null
                    if (!stopping) {
                        writer.dispatchInternal(
                            GroupHybridInternalEvent.LocalMediaDisposeFailed(
                                failedEpoch,
                                "本地语音资源释放失败，已阻止云端语音启动以避免双路音频"
                            )
                        )
                    }
                    releaseIfDone()
                }
            }
        )
        try {
            val created = localAudioFactory?.invoke(
                { writer.snapshot },
                { event -> dispatchLocalEvent(epoch, generation, event) },
                lifetime
            ) ?: GroupAudio(
                context = context,
                snapshot = { writer.snapshot },
                dispatch = { event -> dispatchLocalEvent(epoch, generation, event) },
                isCurrent = { !stopping && activeLocalEpoch == epoch && localEpochGeneration == generation },
                initialRoute = initialRoute,
                initialControls = initialControls.copy(muted = snapshot.participation.selfMuted),
                onRouteLabel = onAudioLabel,
                lifetime = lifetime
            )
            lifetime.acquired()
            localAudio = created
            return created
        } catch (error: Throwable) {
            lifetime.constructionFailed()
            throw error
        }
    }

    private fun dispatchSlotEvent(slot: LocalTransportSlot, event: GroupSessionEvent) {
        val deliver = {
            if (!stopping) {
                if (slot.promotedToLocal) {
                    if (activeLocalEpoch == slot.epoch && localEpochGeneration == slot.generation) {
                        translateLocalEvent(slot.epoch, event)?.let(writer::dispatchInternal)
                    }
                } else if (preconnectEpoch == slot.epoch && preconnectEpochGeneration == slot.generation) {
                    translatePreconnectEvent(slot.epoch, event)?.let(writer::dispatchInternal)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post(deliver)
    }

    private fun dispatchLocalEvent(epoch: Long, generation: Long, event: GroupSessionEvent) {
        val deliver = {
            if (!stopping && activeLocalEpoch == epoch && localEpochGeneration == generation) {
                translateLocalEvent(epoch, event)?.let(writer::dispatchInternal)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post(deliver)
    }

    private fun translateLocalEvent(epoch: Long, event: GroupSessionEvent): GroupHybridInternalEvent? = when (event) {
        is GroupSessionEvent.HostReady -> GroupHybridInternalEvent.LocalHostReady(epoch, event.operation, event.hostAddress, event.frequencyMhz)
        is GroupSessionEvent.Found -> GroupHybridInternalEvent.LocalFound(epoch, event.operation, event.matches)
        is GroupSessionEvent.Failed -> GroupHybridInternalEvent.LocalSearchFailed(epoch, event.operation, event.message)
        is GroupSessionEvent.NetworkReady -> GroupHybridInternalEvent.LocalNetworkReady(epoch, event.operation, event.hostAddress)
        is GroupSessionEvent.NetworkFailed -> GroupHybridInternalEvent.LocalNetworkFailed(epoch, event.attempt, event.message)
        is GroupSessionEvent.NetworkLost -> GroupHybridInternalEvent.LocalNetworkLost(epoch, event.operation)
        is GroupSessionEvent.Authenticated -> GroupHybridInternalEvent.LocalAuthenticated(epoch, event.channel, event.context)
        is GroupSessionEvent.Control -> GroupHybridInternalEvent.LocalControl(epoch, event.channel, event.message)
        is GroupSessionEvent.Closed -> GroupHybridInternalEvent.LocalChannelClosed(epoch, event.channel)
        is GroupSessionEvent.ControlFailed -> GroupHybridInternalEvent.LocalControlFailed(epoch, event.attempt)
        is GroupSessionEvent.AudioAvailable -> GroupHybridInternalEvent.LocalAudioAvailable(epoch, event.available)
        is GroupSessionEvent.Evidence -> GroupHybridInternalEvent.LocalVoiceEvidence(epoch, event.evidence)
        is GroupSessionEvent.Outgoing -> GroupHybridInternalEvent.LocalOutgoing(epoch, event.lease, event.message)
        is GroupSessionEvent.MediaFailed -> GroupHybridInternalEvent.LocalMediaFailed(epoch, event.lease)
        else -> null
    }

    private fun translatePreconnectEvent(epoch: Long, event: GroupSessionEvent): GroupHybridInternalEvent? = when (event) {
        is GroupSessionEvent.HostReady -> GroupHybridInternalEvent.PreconnectHostReady(epoch, event.operation, event.hostAddress, event.frequencyMhz)
        is GroupSessionEvent.NetworkReady -> GroupHybridInternalEvent.PreconnectNetworkReady(epoch, event.operation, event.hostAddress)
        is GroupSessionEvent.NetworkFailed -> GroupHybridInternalEvent.PreconnectFailed(epoch, event.attempt, event.message)
        is GroupSessionEvent.NetworkLost -> GroupHybridInternalEvent.PreconnectFailed(epoch, event.operation, "预连网络已断开")
        is GroupSessionEvent.ControlFailed -> GroupHybridInternalEvent.PreconnectFailed(epoch, event.attempt, "预连控制认证失败")
        is GroupSessionEvent.Authenticated -> GroupHybridInternalEvent.PreconnectAuthenticated(epoch, event.channel, event.context)
        is GroupSessionEvent.Control -> GroupHybridInternalEvent.PreconnectControl(epoch, event.channel, event.message)
        is GroupSessionEvent.Closed -> GroupHybridInternalEvent.PreconnectChannelClosed(epoch, event.channel)
        else -> null
    }

    private fun stopLocalTransportAndMedia(epoch: Long) {
        localEpochGeneration += 1L
        activeLocalEpoch = null
        localSlot = null
        drainingLocalEpoch = epoch
        val currentAudio = localAudio
        localAudio = null
        val audioCloseFailure = runCatching { currentAudio?.close() }.exceptionOrNull()
        if (audioCloseFailure != null) {
            localDisposalFailed = true
            drainingLocalEpoch = null
        }
        val currentNetwork = localNetwork
        localNetwork = null
        if (currentNetwork != null) {
            drainingLocalNetworkDone = false
            currentNetwork.stop(flush = false) {
                main.post {
                    drainingLocalNetworkDone = true
                    completeLocalDrainIfReady()
                    releaseIfDone()
                }
            }
        } else {
            drainingLocalNetworkDone = true
            completeLocalDrainIfReady()
        }
        if (audioCloseFailure != null && !stopping) {
            postInternal(
                GroupHybridInternalEvent.LocalMediaDisposeFailed(
                    epoch,
                    "本地语音资源释放失败，已阻止云端语音启动以避免双路音频"
                )
            )
        }
    }

    private fun completeLocalDrainIfReady() {
        if (localDisposalFailed) return
        val epoch = drainingLocalEpoch ?: return
        if (drainingLocalNetworkDone && localAudioLeases.isEmpty()) {
            drainingLocalEpoch = null
            if (!stopping) {
                postInternal(GroupHybridInternalEvent.LocalTransportDisposed(epoch))
            }
        }
    }

    private fun joinRtc(effect: GroupHybridEffect.JoinRtc) {
        check(!localDisposalFailed && !cloudDisposalFailed && localAudio == null && localAudioLeases.isEmpty() && localNetwork == null) {
            "Cannot join Cloud RTC while local media or local network is active"
        }
        val preCloseFailure = closeRtcMedia()
        if (preCloseFailure != null) {
            cloudDisposalFailed = true
            if (!stopping) {
                postInternal(
                    GroupHybridInternalEvent.CloudMediaDisposeFailed(
                        effect.attemptId,
                        "旧云端语音资源释放失败，已阻止新媒体启动"
                    )
                )
            }
            return
        }
        val generation = ++rtcGeneration
        val attemptId = effect.attemptId
        lateinit var media: GroupRtcMedia
        media = rtcMediaFactory.create(
            context = context,
            onEvent = { rtcEvent ->
                main.post {
                    if (!stopping && rtcGeneration == generation && rtc === media) {
                        writer.dispatchInternal(GroupHybridInternalEvent.Rtc(rtcEvent, attemptId))
                    }
                }
            },
            onRouteLabel = { label ->
                main.post {
                    if (!stopping && rtcGeneration == generation && rtc === media) {
                        onAudioLabel(label)
                    }
                }
            }
        )
        rtc = media
        try {
            media.setAllowedRemoteUsers(effect.allowedUsers)
            media.join(effect.token, effect.muted, effect.route)
        } catch (error: Throwable) {
            val cleanupFailure = closeRtcMedia()
            if (cleanupFailure != null) {
                cloudDisposalFailed = true
                if (!stopping) {
                    postInternal(
                        GroupHybridInternalEvent.CloudMediaDisposeFailed(
                            attemptId,
                            "云端语音启动失败且资源释放失败，已终止媒体切换"
                        )
                    )
                }
                return
            }
            throw error
        }
    }

    private fun closeRtcMedia(): Throwable? {
        rtcGeneration += 1L
        val current = rtc
        rtc = null
        return runCatching { current?.close() }.exceptionOrNull()
    }

    private fun leaveRtcWithBarrier(attemptId: Long) {
        val failure = closeRtcMedia()
        if (failure != null) {
            cloudDisposalFailed = true
            if (!stopping) {
                postInternal(
                    GroupHybridInternalEvent.CloudMediaDisposeFailed(
                        attemptId,
                        "云端语音资源释放失败，已阻止本地音频启动以避免双路音频"
                    )
                )
            }
        } else {
            postInternal(GroupHybridInternalEvent.CloudMediaDisposed(attemptId))
        }
    }

    private fun stopProximityProbe() {
        proximityGeneration += 1L
        val current = proximityProbe
        proximityProbe = null
        runCatching { current?.close() }
    }

    private fun execute(effect: GroupHybridEffect) {
        when (effect) {
            is GroupHybridEffect.CreateHybridRoom -> runAction(effect.operation, "create") { api ->
                GroupHybridInternalEvent.Created(
                    effect.operation,
                    api.createHybridRoom(endpoint.deviceId, endpoint.runtimeId, nickname, effect.requireApproval)
                )
            }
            is GroupHybridEffect.SubmitJoin -> runAction(effect.operation, "join") { api ->
                GroupHybridInternalEvent.JoinSubmitted(
                    effect.operation,
                    api.submitJoinRequest(effect.inviteCode, endpoint.deviceId, endpoint.runtimeId, nickname, effect.receipt)
                )
            }
            is GroupHybridEffect.PollJoinRequest -> runPoll(effect.operation) { api ->
                GroupHybridInternalEvent.JoinPolled(
                    effect.operation,
                    api.pollJoinRequest(effect.requestId, effect.requestSecret, POLL_WAIT_MS)
                )
            }
            is GroupHybridEffect.CancelJoinRequest -> runAction(effect.operation, "cancel_join") { api ->
                effect.retraction.execute(api)
                GroupHybridInternalEvent.JoinCancelled(effect.operation)
            }
            is GroupHybridEffect.PollEvents -> runPoll(effect.operation) { api ->
                GroupHybridInternalEvent.BatchReceived(
                    effect.operation,
                    api.pollEvents(effect.token, effect.roomId, effect.afterSeq, POLL_WAIT_MS)
                )
            }
            is GroupHybridEffect.AckEvents -> {
                val api = actionApi ?: return
                if (!actionExecutor.isShutdown) {
                    actionExecutor.execute { runCatching { api.ackEvents(effect.token, effect.roomId, effect.sequence) } }
                }
            }
            is GroupHybridEffect.ResumeRoom -> runAction(effect.operation, "resume") { api ->
                GroupHybridInternalEvent.Resumed(effect.operation, api.resumeMember(effect.token, effect.roomId))
            }
            is GroupHybridEffect.RefreshRtcToken -> runAction(effect.operation, "rtc_token") { api ->
                GroupHybridInternalEvent.RtcTokenRefreshed(effect.operation, api.refreshRtcToken(effect.token, effect.roomId))
            }
            is GroupHybridEffect.ApproveJoin -> runAction(effect.operation, "approve") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.approveJoinRequest(effect.token, effect.roomId, effect.requestId, effect.expectedRevision),
                    message = "已同意成员加入"
                )
            }
            is GroupHybridEffect.RejectJoin -> runAction(effect.operation, "reject") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.rejectJoinRequest(effect.token, effect.roomId, effect.requestId, effect.expectedRevision),
                    message = "已拒绝加入申请"
                )
            }
            is GroupHybridEffect.RotateInvite -> runAction(effect.operation, "rotate_invite") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.rotateInvite(effect.token, effect.roomId, effect.expectedRevision),
                    message = "已刷新 6 位数字邀请码"
                )
            }
            is GroupHybridEffect.SetRequireApproval -> runAction(effect.operation, "approval_policy") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.setRequireApproval(effect.token, effect.roomId, effect.required, effect.expectedRevision),
                    message = if (effect.required) "已开启群主审批" else "已关闭群主审批"
                )
            }
            is GroupHybridEffect.TransferOwner -> runAction(effect.operation, "transfer_owner") { api ->
                GroupHybridInternalEvent.TransferCompleted(
                    effect.operation,
                    api.transferOwner(effect.token, effect.roomId, effect.targetDeviceId, effect.alsoLeave, effect.expectedRevision)
                )
            }
            is GroupHybridEffect.RemoveMember -> runAction(effect.operation, "remove") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.removeMember(effect.token, effect.roomId, effect.targetDeviceId, effect.expectedRevision),
                    message = "已移出该成员"
                )
            }
            is GroupHybridEffect.UnblockMember -> runAction(effect.operation, "unblock") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.unblockMember(effect.token, effect.roomId, effect.targetDeviceId, effect.expectedRevision),
                    message = "已解除移除限制"
                )
            }
            is GroupHybridEffect.SetRole -> runAction(effect.operation, "set_role") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.setRole(effect.token, effect.roomId, effect.targetDeviceId, effect.role, effect.expectedRevision),
                    message = "成员角色已更新"
                )
            }
            is GroupHybridEffect.SetRiderIncludesFormation -> runAction(effect.operation, "rider_policy") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.setRiderIncludesFormation(effect.token, effect.roomId, effect.included, effect.expectedRevision)
                )
            }
            is GroupHybridEffect.SwitchChannel -> runAction(effect.operation, "switch_channel") { api ->
                val result = api.switchChannel(effect.token, effect.roomId, effect.channel, effect.expectedRevision)
                GroupHybridInternalEvent.SnapshotApplied(effect.operation, result.snapshot, rtc = result.rtc)
            }
            is GroupHybridEffect.RequestPrivate -> runAction(effect.operation, "request_private") { api ->
                val result = api.requestPrivate(effect.token, effect.roomId, effect.targetDeviceId, effect.expectedRevision)
                GroupHybridInternalEvent.SnapshotApplied(effect.operation, result.snapshot, message = "已发送私聊呼叫")
            }
            is GroupHybridEffect.AcceptPrivate -> runAction(effect.operation, "accept_private") { api ->
                val result = api.acceptPrivate(effect.token, effect.roomId, effect.inviteId, effect.expectedRevision)
                GroupHybridInternalEvent.SnapshotApplied(effect.operation, result.snapshot, rtc = result.rtc, message = "已进入私聊频道")
            }
            is GroupHybridEffect.DeclinePrivate -> runAction(effect.operation, "decline_private") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.declinePrivate(effect.token, effect.roomId, effect.inviteId, effect.expectedRevision),
                    message = "已拒绝私聊"
                )
            }
            is GroupHybridEffect.CancelPrivate -> runAction(effect.operation, "cancel_private") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.cancelPrivate(effect.token, effect.roomId, effect.inviteId, effect.expectedRevision),
                    message = "已取消私聊呼叫"
                )
            }
            is GroupHybridEffect.ClosePrivate -> runAction(effect.operation, "close_private") { api ->
                val result = api.closePrivate(effect.token, effect.roomId, effect.expectedRevision)
                GroupHybridInternalEvent.SnapshotApplied(effect.operation, result.snapshot, rtc = result.rtc, message = "已结束私聊，返回公共频道")
            }
            is GroupHybridEffect.SwitchTransport -> runAction(effect.operation, "switch_transport") { api ->
                val result = api.switchTransport(
                    effect.token,
                    effect.roomId,
                    effect.target,
                    effect.reason,
                    effect.expectedRevision,
                    effect.expectedTransportEpoch
                )
                GroupHybridInternalEvent.TransportSwitched(effect.operation, result)
            }
            is GroupHybridEffect.ReportLocalStatus -> runAction(effect.operation, "local_status") { api ->
                val result = api.reportLocalStatus(
                    effect.token,
                    effect.roomId,
                    effect.transportEpoch,
                    effect.localReady,
                    effect.localReachable,
                    effect.localProbeActive,
                    effect.localRssiDbm,
                    effect.localControlReady,
                    effect.localHostAddress,
                    effect.localFrequencyMhz,
                    effect.preconnectFailed
                )
                GroupHybridInternalEvent.LocalStatusReported(effect.operation, result)
            }
            is GroupHybridEffect.LeaveRoom -> {
                val api = actionApi ?: return
                if (!actionExecutor.isShutdown) {
                    actionExecutor.execute { runCatching { api.leaveRoom(effect.token, effect.roomId) } }
                }
            }
            is GroupHybridEffect.EndRoom -> runAction(effect.operation, "end") { api ->
                api.endRoom(effect.token, effect.roomId, effect.expectedRevision)
                GroupHybridInternalEvent.EndCompleted(effect.operation)
            }
            is GroupHybridEffect.ReportAudio -> runAction(effect.operation, "audio") { api ->
                GroupHybridInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.updateAudioAvailability(effect.token, effect.roomId, effect.available)
                )
            }

            is GroupHybridEffect.StartLocalHost -> {
                withLocalNetwork(effect.epoch) { it.host(effect.attempt, effect.descriptor, effect.code) }
            }
            is GroupHybridEffect.StartLocalHostDirect -> {
                withLocalNetwork(effect.epoch) {
                    it.hostDirect(effect.attempt, effect.descriptor, effect.code, effect.preset, enableBleBootstrap = true)
                }
            }
            is GroupHybridEffect.StartLocalSearch -> {
                withLocalNetwork(effect.epoch) { it.search(GroupSessionEffect.Search(effect.attempt, effect.endpoint, effect.code)) }
            }
            is GroupHybridEffect.JoinLocalNetwork -> {
                if (activeLocalEpoch == effect.epoch) {
                    localNetwork?.join(GroupSessionEffect.JoinNetwork(effect.attempt, effect.match))
                }
            }
            is GroupHybridEffect.JoinLocalDirect -> {
                withLocalNetwork(effect.epoch) {
                    it.joinDirect(effect.attempt, effect.preset, effect.expectedHostAddress)
                }
            }
            is GroupHybridEffect.ConnectLocalControl -> {
                if (activeLocalEpoch == effect.epoch) {
                    localNetwork?.connect(
                        GroupSessionEffect.ConnectControl(effect.attempt, effect.attempt, effect.context, effect.code, effect.match)
                    )
                }
            }
            is GroupHybridEffect.ConnectLocalDirect -> {
                if (activeLocalEpoch == effect.epoch) {
                    localNetwork?.connectDirect(effect.attempt, effect.context, effect.code, effect.hostAddress, effect.port)
                }
            }
            is GroupHybridEffect.SendLocal -> {
                if (activeLocalEpoch == effect.epoch) {
                    localNetwork?.send(GroupSessionEffect.Send(effect.channel, effect.build, effect.terminal))
                }
            }
            is GroupHybridEffect.AdmitLocalChannel -> {
                if (activeLocalEpoch == effect.epoch) localNetwork?.admit(effect.channel)
            }
            is GroupHybridEffect.CloseLocalChannel -> {
                if (activeLocalEpoch == effect.epoch) localNetwork?.closeChannel(effect.channel)
            }
            is GroupHybridEffect.OpenLocalMedia -> {
                if (activeLocalEpoch == effect.epoch) {
                    ensureLocalAudio(effect.epoch).open(effect.lease, effect.offerer)
                }
            }
            is GroupHybridEffect.CloseLocalMedia -> {
                if (activeLocalEpoch == effect.epoch) localAudio?.close(effect.lease)
            }
            is GroupHybridEffect.LocalMediaSignal -> {
                if (activeLocalEpoch == effect.epoch) localAudio?.signal(effect.lease, effect.message)
            }
            is GroupHybridEffect.SetLocalMuted -> localAudio?.mute(effect.muted)
            is GroupHybridEffect.SetLocalBlocked -> localAudio?.block(effect.peerId, effect.blocked)
            is GroupHybridEffect.StopLocalTransportAndMedia -> stopLocalTransportAndMedia(effect.epoch)

            is GroupHybridEffect.StartPreconnectHostBeacon -> {
                withPreconnectNetwork(effect.epoch) {
                    it.hostDirect(effect.attempt, effect.descriptor, code = null, preset = effect.preset, enableBleBootstrap = false)
                }
            }
            is GroupHybridEffect.StartPreconnectControlServer -> {
                if (preconnectEpoch == effect.epoch) {
                    val existing = preconnectNetwork
                    if (existing != null) {
                        existing.startDirectControlServer(effect.attempt, effect.descriptor, effect.code)
                    } else {
                        withPreconnectNetwork(effect.epoch) {
                            it.hostDirect(effect.attempt, effect.descriptor, effect.code, effect.preset, enableBleBootstrap = false)
                        }
                    }
                }
            }
            is GroupHybridEffect.JoinPreconnectDirect -> {
                withPreconnectNetwork(effect.epoch) {
                    it.joinDirect(effect.attempt, effect.preset, effect.expectedHostAddress)
                }
            }
            is GroupHybridEffect.ConnectPreconnectDirect -> {
                if (preconnectEpoch == effect.epoch) {
                    preconnectNetwork?.connectDirect(effect.attempt, effect.context, effect.code, effect.hostAddress, effect.port)
                }
            }
            is GroupHybridEffect.SendPreconnect -> {
                if (preconnectEpoch == effect.epoch) {
                    preconnectNetwork?.send(GroupSessionEffect.Send(effect.channel, effect.build, effect.terminal))
                }
            }
            is GroupHybridEffect.AdmitPreconnectChannel -> {
                if (preconnectEpoch == effect.epoch) preconnectNetwork?.admit(effect.channel)
            }
            is GroupHybridEffect.ClosePreconnectChannel -> {
                if (preconnectEpoch == effect.epoch) preconnectNetwork?.closeChannel(effect.channel)
            }
            is GroupHybridEffect.PromotePreconnectToLocal -> promotePreconnectToLocal(effect.fromEpoch, effect.toEpoch)
            GroupHybridEffect.StopPreconnectNetwork -> stopPreconnectNetwork()

            is GroupHybridEffect.JoinRtc -> joinRtc(effect)
            is GroupHybridEffect.LeaveRtc -> leaveRtcWithBarrier(effect.attemptId)
            is GroupHybridEffect.ApplyRtcToken -> writer.dispatchInternal(
                GroupHybridInternalEvent.Rtc(rtc.applyTokenUpdate(effect.token), effect.attemptId)
            )
            is GroupHybridEffect.SetRtcMuted -> rtc?.setLocalMuted(effect.muted)
            is GroupHybridEffect.SetRtcAllowedUsers -> rtc?.setAllowedRemoteUsers(effect.userIds)
            is GroupHybridEffect.SetRtcRoute -> rtc?.selectRoute(effect.route)

            is GroupHybridEffect.StartProximityBeacon -> {
                stopProximityProbe()
                val gen = ++proximityGeneration
                val probe = proximityProbeFactory.create(context)
                proximityProbe = probe
                probe.startBeacon(effect.roomInstanceId) { active ->
                    main.post {
                        if (!stopping && proximityGeneration == gen && proximityProbe === probe) {
                            writer.dispatchInternal(
                                GroupHybridInternalEvent.ProximityUpdated(effect.epoch, beaconActive = active, reachable = active)
                            )
                        }
                    }
                }
            }
            is GroupHybridEffect.StartProximityScan -> {
                stopProximityProbe()
                val gen = ++proximityGeneration
                val probe = proximityProbeFactory.create(context)
                proximityProbe = probe
                probe.startScan(effect.roomInstanceId, effect.targetWifiSsid) { report ->
                    main.post {
                        if (!stopping && proximityGeneration == gen && proximityProbe === probe) {
                            writer.dispatchInternal(
                                GroupHybridInternalEvent.ProximityUpdated(
                                    effect.epoch,
                                    beaconActive = false,
                                    reachable = report.reachable,
                                    rssiDbm = report.rssiDbm
                                )
                            )
                        }
                    }
                }
            }
            GroupHybridEffect.StopProximityProbe -> stopProximityProbe()

            is GroupHybridEffect.Publish -> onSnapshot(effect.snapshot)
            is GroupHybridEffect.Stop -> stop(effect.flushTerminal)
        }
    }

    private fun stop(flush: Boolean) {
        if (stopping) return
        stopping = true
        main.removeCallbacks(tick)
        stopProximityProbe()
        stopPreconnectNetwork()
        if (closeRtcMedia() != null) {
            cloudDisposalFailed = true
        }
        val currentAudio = localAudio
        localAudio = null
        if (runCatching { currentAudio?.close() }.isFailure) {
            localDisposalFailed = true
        }
        val currentLocalNet = localNetwork
        localNetwork = null
        localSlot = null
        if (currentLocalNet != null) {
            drainingLocalNetworkDone = false
            currentLocalNet.stop(flush = false) {
                main.post {
                    drainingLocalNetworkDone = true
                    releaseIfDone()
                }
            }
        }
        val finishCloud = {
            runCatching { pollApi?.close() }
            pollApi = null
            runCatching { actionApi?.close() }
            actionApi = null
            pollExecutor.shutdownNow()
            actionExecutor.shutdownNow()
            cloudReleased = true
            releaseIfDone()
        }
        if (flush) main.postDelayed(finishCloud, 350L) else finishCloud()
    }

    private fun releaseIfDone() {
        if (
            stopping &&
            cloudReleased &&
            drainingLocalNetworkDone &&
            drainingPreconnectDone &&
            (localAudioLeases.isEmpty() || localDisposalFailed) &&
            !released
        ) {
            released = true
            runCatching { keepAlive?.close() }
            keepAlive = null
            if (!localDisposalFailed && !cloudDisposalFailed) {
                ownership?.let(GroupRuntimeOwnership::release)
                ownership = null
            }
            onReleased()
        }
    }

    override fun close() {
        // Service destruction is a local lifetime boundary, not the user-facing Leave command.
        // An owner with admitted peers cannot leave the cloud room directly, but this process must
        // still release microphone, transports, preconnect state, and runtime ownership.
        stop(false)
    }

    companion object {
        private const val POLL_WAIT_MS = 18_000
    }
}
