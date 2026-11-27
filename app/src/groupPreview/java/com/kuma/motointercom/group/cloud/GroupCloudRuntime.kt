package com.kuma.motointercom.group.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.IntercomRuntimeKeepAlive
import com.kuma.motointercom.group.*
import java.io.Closeable
import java.util.concurrent.Executors

/** Service-owned NETWORK mode runtime. RTC media is created lazily and only while at least two members are admitted. */
internal class GroupCloudRuntime(
    private val context: Context,
    private val endpoint: GroupAuthEndpoint,
    private val nickname: String,
    private val request: GroupCloudStartRequest,
    private var initialRoute: AudioRouteSelection,
    private val onSnapshot: (GroupSessionSnapshot) -> Unit,
    private val onAudioLabel: (String) -> Unit,
    private val onReleased: () -> Unit,
    private val pollApiFactory: (String) -> GroupCloudApi = { url ->
        HttpGroupCloudClient(
            url,
            entitlementTokenProvider = { GroupEntitlementStore(context).loadToken() },
            deviceIdProvider = { endpoint.deviceId }
        )
    },
    private val actionApiFactory: (String) -> GroupCloudApi = { url ->
        HttpGroupCloudClient(
            url,
            entitlementTokenProvider = { GroupEntitlementStore(context).loadToken() },
            deviceIdProvider = { endpoint.deviceId }
        )
    },
    private val rtcMediaFactory: GroupRtcMediaFactory = object : GroupRtcMediaFactory {
        override fun create(
            context: Context,
            onEvent: (GroupRtcEvent) -> Unit,
            onRouteLabel: (String) -> Unit
        ): GroupRtcMedia = VolcGroupRtcMedia(context, onEvent, onRouteLabel)
    },
    nowMs: () -> Long = ::groupNowMs,
    private val acquireKeepAlive: () -> Closeable = { IntercomRuntimeKeepAlive.acquire(context) }
) : GroupRuntimeHandle {
    private val main = Handler(Looper.getMainLooper())
    private val pollExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "motocom-cloud-poll").apply { isDaemon = true }
    }
    private val actionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "motocom-cloud-action").apply { isDaemon = true }
    }

    private var stopping = false
    private var networkReleased = false
    private var released = false
    private var rtcDisposalFailed = false
    private var ownership: Any? = null
    private var keepAlive: Closeable? = null
    private var pollApi: GroupCloudApi? = null
    private var actionApi: GroupCloudApi? = null
    private var rtc: GroupRtcMedia? = null
    private var rtcGeneration = 0L

    val writer = GroupCloudSessionOrchestrator(
        endpoint = endpoint,
        nickname = nickname,
        nowMs = nowMs,
        assertWriter = { check(Looper.myLooper() == Looper.getMainLooper()) },
        effects = ::execute,
        diagnostics = GroupDiagnostics()
    )

    override val snapshot: GroupSessionSnapshot get() = writer.snapshot

    private val tick = object : Runnable {
        override fun run() {
            if (!stopping && snapshot.operation != null) {
                writer.dispatch(GroupSessionEvent.Tick)
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
            writer.dispatchInternal(GroupCloudInternalEvent.Start(request))
            writer.dispatchInternal(GroupCloudInternalEvent.RouteChanged(initialRoute))
            main.post(tick)
        } catch (error: Exception) {
            stop(false)
            throw IllegalStateException(error.message ?: "无法启动网络群组，请确认其他通话已结束")
        }
    }

    override fun dispatch(event: GroupSessionEvent) = writer.dispatch(event)

    override fun route(value: AudioRouteSelection) {
        initialRoute = value
        writer.dispatchInternal(GroupCloudInternalEvent.RouteChanged(value))
    }

    override fun vox(value: Boolean) = Unit

    private fun postInternal(event: GroupCloudInternalEvent) {
        main.post {
            if (!stopping) writer.dispatchInternal(event)
        }
    }

    private fun runAction(
        op: java.util.UUID,
        actionName: String,
        block: (GroupCloudApi) -> GroupCloudInternalEvent?
    ) {
        val api = actionApi ?: return
        if (actionExecutor.isShutdown) return
        actionExecutor.execute {
            try {
                val result = block(api)
                if (result != null) postInternal(result)
            } catch (error: GroupCloudException) {
                postInternal(GroupCloudInternalEvent.ActionFailed(op, actionName, error))
            } catch (_: Exception) {
                postInternal(
                    GroupCloudInternalEvent.ActionFailed(
                        op,
                        actionName,
                        GroupCloudException(0, "NETWORK_ERROR", "云端操作失败，请检查网络")
                    )
                )
            }
        }
    }

    private fun runFireAndForget(block: (GroupCloudApi) -> Unit) {
        val api = actionApi ?: return
        if (actionExecutor.isShutdown) return
        actionExecutor.execute { runCatching { block(api) } }
    }

    private fun runPoll(op: java.util.UUID, block: (GroupCloudApi) -> GroupCloudInternalEvent) {
        val api = pollApi ?: return
        if (pollExecutor.isShutdown) return
        pollExecutor.execute {
            try {
                postInternal(block(api))
            } catch (error: GroupCloudException) {
                postInternal(GroupCloudInternalEvent.PollFailed(op, error))
            } catch (_: Exception) {
                postInternal(
                    GroupCloudInternalEvent.PollFailed(
                        op,
                        GroupCloudException(0, "NETWORK_ERROR", "网络连接中断")
                    )
                )
            }
        }
    }

    private fun execute(effect: GroupCloudEffect) {
        when (effect) {
            is GroupCloudEffect.CreateRoom -> runAction(effect.operation, "create") { api ->
                GroupCloudInternalEvent.Created(
                    effect.operation,
                    api.createRoom(endpoint.deviceId, endpoint.runtimeId, nickname, effect.requireApproval)
                )
            }
            is GroupCloudEffect.SubmitJoin -> runAction(effect.operation, "join") { api ->
                GroupCloudInternalEvent.JoinSubmitted(
                    effect.operation,
                    api.submitJoinRequest(effect.inviteCode, endpoint.deviceId, endpoint.runtimeId, nickname, effect.receipt)
                )
            }
            is GroupCloudEffect.PollJoinRequest -> runPoll(effect.operation) { api ->
                GroupCloudInternalEvent.JoinPolled(
                    effect.operation,
                    api.pollJoinRequest(effect.requestId, effect.requestSecret, POLL_WAIT_MS)
                )
            }
            is GroupCloudEffect.CancelJoinRequest -> runAction(effect.operation, "cancel_join") { api ->
                effect.retraction.execute(api)
                GroupCloudInternalEvent.JoinCancelled(effect.operation)
            }
            is GroupCloudEffect.PollEvents -> runPoll(effect.operation) { api ->
                GroupCloudInternalEvent.BatchReceived(
                    effect.operation,
                    api.pollEvents(effect.token, effect.roomId, effect.afterSeq, POLL_WAIT_MS)
                )
            }
            is GroupCloudEffect.AckEvents -> runFireAndForget { api ->
                api.ackEvents(effect.token, effect.roomId, effect.sequence)
            }
            is GroupCloudEffect.ResumeRoom -> runAction(effect.operation, "resume") { api ->
                GroupCloudInternalEvent.Resumed(effect.operation, api.resumeMember(effect.token, effect.roomId))
            }
            is GroupCloudEffect.RefreshRtcToken -> runAction(effect.operation, "rtc_token") { api ->
                GroupCloudInternalEvent.RtcTokenRefreshed(
                    effect.operation,
                    api.refreshRtcToken(effect.token, effect.roomId)
                )
            }
            is GroupCloudEffect.ApproveJoin -> runAction(effect.operation, "approve") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.approveJoinRequest(effect.token, effect.roomId, effect.requestId, effect.expectedRevision),
                    "已同意车友加入网络房间"
                )
            }
            is GroupCloudEffect.RejectJoin -> runAction(effect.operation, "reject") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.rejectJoinRequest(effect.token, effect.roomId, effect.requestId, effect.expectedRevision),
                    "已拒绝该加入申请"
                )
            }
            is GroupCloudEffect.RotateInvite -> runAction(effect.operation, "rotate_invite") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.rotateInvite(effect.token, effect.roomId, effect.expectedRevision),
                    "已生成新的邀请码"
                )
            }
            is GroupCloudEffect.SetRequireApproval -> runAction(effect.operation, "approval_policy") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.setRequireApproval(effect.token, effect.roomId, effect.required, effect.expectedRevision),
                    if (effect.required) "已开启加入需群主审批" else "已关闭审批，持邀请码可直接加入"
                )
            }
            is GroupCloudEffect.TransferOwner -> runAction(effect.operation, "transfer_owner") { api ->
                GroupCloudInternalEvent.TransferCompleted(
                    effect.operation,
                    api.transferOwner(
                        effect.token,
                        effect.roomId,
                        effect.targetDeviceId,
                        effect.alsoLeave,
                        effect.expectedRevision
                    )
                )
            }
            is GroupCloudEffect.RemoveMember -> runAction(effect.operation, "remove") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.removeMember(effect.token, effect.roomId, effect.targetDeviceId, effect.expectedRevision),
                    "已将成员移出网络房间"
                )
            }
            is GroupCloudEffect.UnblockMember -> runAction(effect.operation, "unblock") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.unblockMember(effect.token, effect.roomId, effect.targetDeviceId, effect.expectedRevision),
                    "已解除该成员的移除限制"
                )
            }
            is GroupCloudEffect.LeaveRoom -> runFireAndForget { api ->
                api.leaveRoom(effect.token, effect.roomId)
            }
            is GroupCloudEffect.EndRoom -> runAction(effect.operation, "end_room") { api ->
                api.endRoom(effect.token, effect.roomId, effect.expectedRevision)
                GroupCloudInternalEvent.EndCompleted(effect.operation)
            }
            is GroupCloudEffect.ReportAudio -> runAction(effect.operation, "audio") { api ->
                GroupCloudInternalEvent.SnapshotApplied(
                    effect.operation,
                    api.updateAudioAvailability(effect.token, effect.roomId, effect.available)
                )
            }
            is GroupCloudEffect.JoinRtc -> joinRtc(effect)
            GroupCloudEffect.LeaveRtc -> leaveRtc()
            is GroupCloudEffect.ApplyRtcToken -> writer.dispatchInternal(
                GroupCloudInternalEvent.Rtc(rtc.applyTokenUpdate(effect.token), effect.attemptId)
            )
            is GroupCloudEffect.SetRtcMuted -> rtc?.setLocalMuted(effect.muted)
            is GroupCloudEffect.SetRtcAllowedUsers -> rtc?.setAllowedRemoteUsers(effect.userIds)
            is GroupCloudEffect.SetRtcRoute -> rtc?.selectRoute(effect.route)
            is GroupCloudEffect.Stop -> stop(effect.flushTerminal)
            is GroupCloudEffect.Publish -> onSnapshot(effect.snapshot)
        }
    }

    private fun joinRtc(effect: GroupCloudEffect.JoinRtc) {
        if (rtc != null || rtcDisposalFailed) return
        val generation = ++rtcGeneration
        val attemptId = effect.attemptId
        var createdRef: GroupRtcMedia? = null
        val created = rtcMediaFactory.create(
            context,
            { event: GroupRtcEvent ->
                main.post {
                    if (stopping || rtcGeneration != generation || rtc !== createdRef) return@post
                    if (event is GroupRtcEvent.JoinFailed || event is GroupRtcEvent.ConnectionLost) {
                        leaveRtc()
                    }
                    writer.dispatchInternal(GroupCloudInternalEvent.Rtc(event, attemptId))
                }
            },
            { label: String ->
                main.post {
                    if (!stopping && rtcGeneration == generation && rtc === createdRef) {
                        onAudioLabel(label)
                    }
                }
            }
        )
        createdRef = created
        rtc = created
        try {
            created.setAllowedRemoteUsers(effect.allowedUsers)
            created.join(effect.token, effect.muted, effect.route)
        } catch (error: Exception) {
            if (rtc === created) leaveRtc()
            throw error
        }
    }

    private fun leaveRtc() {
        rtcGeneration += 1
        val current = rtc ?: return
        rtc = null
        runCatching { current.close() }.onFailure {
            rtcDisposalFailed = true
            snapshot.operation?.takeUnless { stopping }?.let { operation ->
                writer.dispatchInternal(GroupCloudInternalEvent.Fatal(operation, "RTC 音频资源释放失败，请重启应用"))
            }
        }
    }

    private fun stop(flushTerminal: Boolean) {
        if (stopping) return
        stopping = true
        main.removeCallbacks(tick)
        leaveRtc()
        runCatching { pollApi?.close() }
        pollApi = null
        pollExecutor.shutdownNow()
        if (flushTerminal && !actionExecutor.isShutdown) {
            val apiToClose = actionApi
            actionApi = null
            val forceDeadline = Runnable {
                runCatching { apiToClose?.close() }
                actionExecutor.shutdownNow()
                networkReleased = true
                releaseIfDone()
            }
            main.postDelayed(forceDeadline, FLUSH_DEADLINE_MS)
            actionExecutor.execute {
                runCatching { apiToClose?.close() }
                main.post {
                    main.removeCallbacks(forceDeadline)
                    networkReleased = true
                    releaseIfDone()
                }
            }
            actionExecutor.shutdown()
        } else {
            runCatching { actionApi?.close() }
            actionApi = null
            actionExecutor.shutdownNow()
            networkReleased = true
            releaseIfDone()
        }
    }

    private fun releaseIfDone() {
        if (released || !networkReleased) return
        runCatching { keepAlive?.close() }
        keepAlive = null
        released = true
        if (!rtcDisposalFailed) {
            ownership?.let(GroupRuntimeOwnership::release)
            ownership = null
        }
        onReleased()
    }

    override fun close() {
        if (snapshot.operation != null && !stopping) {
            runCatching {
                if (snapshot.isOwner && (snapshot.view?.members?.size ?: 0) > 1) {
                    writer.dispatch(GroupSessionEvent.EndRoom)
                } else {
                    writer.dispatch(GroupSessionEvent.Leave)
                }
            }
        }
        stop(false)
    }

    companion object {
        private const val POLL_WAIT_MS = 15_000
        private const val FLUSH_DEADLINE_MS = 2_500L
    }
}
