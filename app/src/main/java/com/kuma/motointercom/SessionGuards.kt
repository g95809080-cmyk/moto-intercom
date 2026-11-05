package com.kuma.motointercom

/**
 * 会话准入与效果执行的纯谓词。
 *
 * 这里的每个函数只读取调用方传入的快照，不持有状态、不产生副作用：它们回答
 * “在当前会话、当前 attempt、当前信令状态下，这个回调或效果是否仍然成立”。
 * 单独成文件的目的是让这些不变量可以逐条评审，而不是埋在服务实现里。
 */

internal fun canApplyAttemptCallback(
    accepted: Boolean,
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    expectedAttempt: ConnectionAttempt
): Boolean = accepted && sessionCurrent && currentAttempt == expectedAttempt

internal fun canRegisterControlChannel(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    session: SignalingSessionV2,
    currentState: IntercomState? = null,
    originFollowsCurrentAttempt: Boolean = false
): Boolean {
    val attempt = session.originatingAttempt
    val recoveryAttempt = (currentState as? IntercomState.Recovering)?.attempt
    return !session.isClosed &&
        sessionCurrent &&
        (
            recoveryAttempt == null ||
                (currentAttempt == recoveryAttempt && !isNonTargetRecoveryChannel(currentState, session.targetLock))
            ) &&
        (attempt == null || currentAttempt == attempt || originFollowsCurrentAttempt &&
            currentAttempt != null && currentAttempt.runtimeSessionId == attempt.runtimeSessionId &&
            currentAttempt.targetLock == attempt.targetLock &&
            currentAttempt.deadlineElapsedRealtimeMs == attempt.deadlineElapsedRealtimeMs &&
            session.requestRole == RequestRole.RESPONDER && session.wireRequestKey.attemptId == currentAttempt.id &&
            session.channel.transport in currentAttempt.channelPlan) &&
        (attempt == null || session.channel.transport in attempt.channelPlan) &&
        (attempt == null || attempt.targetLock == session.targetLock) &&
        session.peer.isVerifiedFor(session.targetLock)
}

internal fun canInstallControlSession(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    existingSession: SignalingSessionV2?,
    session: SignalingSessionV2,
    currentState: IntercomState? = null,
    originFollowsCurrentAttempt: Boolean = false
): Boolean = existingSession == null &&
    canRegisterControlChannel(sessionCurrent, currentAttempt, session, currentState, originFollowsCurrentAttempt)

internal enum class ControlChannelAdmissionOutcome {
    ADMITTED,
    REJECTED,
    REJECTED_NON_TARGET_WIFI_DIRECT
}

internal fun admitControlSession(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    existingSession: SignalingSessionV2?,
    session: SignalingSessionV2,
    currentState: IntercomState? = null,
    originFollowsCurrentAttempt: Boolean = false,
    onRejectedNonTargetWifiDirect: (ConnectionAttempt, TargetLock) -> Unit
): ControlChannelAdmissionOutcome {
    if (
        canInstallControlSession(
            sessionCurrent,
            currentAttempt,
            existingSession,
            session,
            currentState,
            originFollowsCurrentAttempt
        )
    ) {
        return ControlChannelAdmissionOutcome.ADMITTED
    }

    val recoveryAttempt = (currentState as? IntercomState.Recovering)?.attempt
    val rejectedNonTargetWifiDirect =
        session.channel.transport == Transport.WIFI_DIRECT &&
            recoveryAttempt != null &&
            recoveryAttempt.targetLock != session.targetLock
    session.close()
    if (rejectedNonTargetWifiDirect) {
        onRejectedNonTargetWifiDirect(requireNotNull(recoveryAttempt), session.targetLock)
        return ControlChannelAdmissionOutcome.REJECTED_NON_TARGET_WIFI_DIRECT
    }
    return ControlChannelAdmissionOutcome.REJECTED
}

internal fun isNonTargetRecoveryChannel(
    currentState: IntercomState?,
    targetLock: TargetLock
): Boolean = (currentState as? IntercomState.Recovering)
    ?.attempt
    ?.targetLock
    ?.let { it != targetLock } == true

internal fun canExecuteAbortAttemptEffect(
    effect: SessionEffect.AbortAttemptAndResumeDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?,
    terminalOutcome: ConnectionAttemptTerminalOutcome?
): Boolean = currentState == IntercomState.Discovering(effect.runtimeSessionId) &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null &&
    terminalOutcome != null

internal fun canExecuteRefreshDiscoveryEffect(
    effect: SessionEffect.RefreshDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = canResumePassiveDiscovery(
    effect.runtimeSessionId,
    currentState,
    currentAttempt,
    activeAttempt,
    pendingInbound
)

internal fun canResumePassiveDiscovery(
    runtimeSessionId: RuntimeSessionId,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = currentState == IntercomState.Discovering(runtimeSessionId) &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null

internal fun canFinalizeActiveSessionReleaseEffect(
    effect: SessionEffect.ReleaseActiveSessionAndContinueDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = currentState == IntercomState.Discovering(effect.attempt.runtimeSessionId) &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null

internal fun canDeliverRuntimeAudioCallback(
    running: Boolean,
    activeRuntimeSessionId: RuntimeSessionId?,
    callbackRuntimeSessionId: RuntimeSessionId
): Boolean = running && activeRuntimeSessionId == callbackRuntimeSessionId

internal fun controlSendCompletionEvent(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    channelId: ControlChannelId,
    messageType: SignalingMessageTypeV2,
    result: Result<Unit>
): SessionEvent = result.fold(
    onSuccess = {
        SessionEvent.SignalingMessageSent(
            runtimeSessionId,
            attemptId,
            channelId,
            messageType
        )
    },
    onFailure = { failure ->
        SessionEvent.SignalingSendFailed(
            runtimeSessionId,
            attemptId,
            channelId,
            messageType,
            failure.message.orEmpty()
        )
    }
)

internal fun canDeliverDecodedControlEnvelope(
    sessionCurrent: Boolean,
    sessionClosed: Boolean,
    registeredSessionMatches: Boolean
): Boolean {
    // The frame was decoded and identity/phase checked before this main-thread seam.
    // A following EOF may close the reader, but cannot invalidate that earlier frame.
    return sessionCurrent && registeredSessionMatches
}

internal fun canExecuteRestartDiscoveryEffect(
    effect: SessionEffect.RestartDiscovery,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?
): Boolean = currentState is IntercomState.Recovering &&
    effect.runtimeSessionId == effect.attempt.runtimeSessionId &&
    currentState.attempt == effect.attempt &&
    currentAttempt == effect.attempt

internal fun canExecuteResetWirelessEnvironmentEffect(
    effect: SessionEffect.ResetWirelessEnvironment,
    currentState: IntercomState,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    pendingInbound: PendingInboundRequest?
): Boolean = currentState is IntercomState.Resetting &&
    currentState.runtimeSessionId == effect.runtimeSessionId &&
    currentState.targetDeviceId == effect.targetDeviceId &&
    currentState.failedAttemptId == effect.failedAttemptId &&
    currentState.consecutiveFinalFailures == effect.consecutiveFinalFailures &&
    currentAttempt == null &&
    activeAttempt == null &&
    pendingInbound == null

internal fun canRestartRecoveryAttempt(
    expectedAttempt: ConnectionAttempt?,
    currentAttempt: ConnectionAttempt?,
    now: MonotonicTimestamp
): Boolean = if (expectedAttempt == null) {
    currentAttempt == null
} else {
    expectedAttempt.hasSameImmutableIdentity(currentAttempt) &&
        expectedAttempt.remainingMillis(now) > 0L
}

internal fun SignalingSessionV2.toConnectionCandidateContext(
    attempt: ConnectionAttempt
): ConnectionCandidateContext? = runCatching {
    ConnectionCandidateContext(
        attempt = attempt,
        channelId = channel.channelId,
        wireRequestKey = wireRequestKey,
        targetLock = targetLock,
        transport = channel.transport,
        requestRole = requestRole,
        peer = peer
    )
}.getOrNull()

internal fun SignalingSessionV2.verifiedChannel() = VerifiedControlChannel(
    channel.channelId, channel.transport, requestRole, wireRequestKey, targetLock, peer, originatingAttempt
)

internal fun SignalingSessionV2.matchesControlHandle(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    channelId: ControlChannelId
): Boolean = pinnedIdentity.localSessionId == runtimeSessionId &&
    wireRequestKey.attemptId == attemptId &&
    channel.channelId == channelId

internal fun SignalingSessionV2.matchesControlHandle(
    runtimeSessionId: RuntimeSessionId,
    attemptId: ConnectionAttemptId,
    channelId: ControlChannelId,
    targetLock: TargetLock
): Boolean = matchesControlHandle(runtimeSessionId, attemptId, channelId) &&
    this.targetLock == targetLock

internal fun SignalingSessionV2.hasCandidateIdentity(
    candidate: ConnectionCandidateContext
): Boolean = matchesControlHandle(
        candidate.runtimeSessionId,
        candidate.attemptId,
        candidate.channelId
    ) &&
    wireRequestKey == candidate.wireRequestKey &&
    targetLock == candidate.targetLock &&
    channel.transport == candidate.transport &&
    requestRole == candidate.requestRole &&
    peer == candidate.peer

internal fun SignalingSessionV2.matchesCandidate(
    candidate: ConnectionCandidateContext
): Boolean = !isClosed && hasCandidateIdentity(candidate)

internal fun canUseMediaCandidate(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    candidate: ConnectionCandidateContext,
    session: SignalingSessionV2,
    expectedRole: WebRtcRole? = null
): Boolean = sessionCurrent &&
    isCurrentMediaCandidate(currentAttempt, activeAttempt, candidate) &&
    session.matchesCandidate(candidate) &&
    (expectedRole == null || session.requestRole.webRtcRole == expectedRole)

internal fun canBufferAcceptedMedia(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    candidate: ConnectionCandidateContext,
    session: SignalingSessionV2,
    now: MonotonicTimestamp
): Boolean = sessionCurrent && session.matchesCandidate(candidate) &&
    !candidate.attempt.isExpiredAt(now) && currentAttempt == candidate.attempt &&
    activeAttempt?.let {
        it.attempt == candidate.attempt && it.phase == SignalingAttemptPhase.ACCEPTING &&
            it.terminalOutcome == null && it.mediaOwnerChannelId == candidate.channelId &&
            candidate.channelId in it.channelIds && it.wireRequestKey == candidate.wireRequestKey && it.peer == candidate.peer
    } == true && session.requestRole == RequestRole.RESPONDER &&
    session.phase == SignalingPhase.READY_TO_SEND_ANSWER

internal fun canStartWebRtc(
    sessionCurrent: Boolean,
    currentAttempt: ConnectionAttempt?,
    expectedAttempt: ConnectionAttempt,
    session: SignalingSessionV2,
    expectedRole: WebRtcRole
): Boolean = !session.isClosed &&
    sessionCurrent &&
    currentAttempt == expectedAttempt &&
    session.wireRequestKey.attemptId == expectedAttempt.id &&
    session.channel.transport in expectedAttempt.channelPlan &&
    session.targetLock == expectedAttempt.targetLock &&
    session.peer.isVerifiedFor(expectedAttempt.targetLock) &&
    session.requestRole.webRtcRole == expectedRole &&
    session.phase in setOf(SignalingPhase.ACCEPTED, SignalingPhase.READY_TO_SEND_ANSWER)
