package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.group.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupCloudSessionOrchestratorTest {
    private var now = 1_750_000_000_000L
    private val effects = mutableListOf<GroupCloudEffect>()
    private val logs = mutableListOf<String>()

    private fun writer(
        deviceId: String = "dev-a",
        runtimeId: String = "run-a",
        nickname: String = "队长",
        monotonicNowMs: () -> Long = { now },
        wallNowMs: () -> Long = { now }
    ) = GroupCloudSessionOrchestrator(
        endpoint = GroupAuthEndpoint(deviceId, runtimeId),
        nickname = nickname,
        nowMs = monotonicNowMs,
        wallNowMs = wallNowMs,
        assertWriter = {},
        effects = { effects += it },
        diagnostics = GroupDiagnostics { logs += it }
    )

    private fun member(
        deviceId: String,
        role: GroupRole = GroupRole.MEMBER,
        status: GroupMemberStatus = GroupMemberStatus.ADMITTED,
        audioAvailable: Boolean = status == GroupMemberStatus.ADMITTED
    ) = GroupMember(
        lease = GroupMemberLease(
            deviceId,
            if (deviceId == "dev-a") "run-a" else "run-$deviceId",
            1,
            1
        ),
        status = status,
        reservedUntilMs = if (status == GroupMemberStatus.RESERVED) now + 60_000L else null,
        audioAvailable = audioAvailable,
        role = role
    )

    private fun rtcToken(
        ttlMs: Long = 600_000L,
        roomId: String = "rtc-room-1",
        userId: String = "rtc-user-a"
    ) = CloudRtcToken(
        appId = "volc-app-id",
        rtcRoomId = roomId,
        rtcUserId = userId,
        rtcToken = "formal-token-$userId-${now}-$ttlMs",
        expiresAtMs = now + ttlMs
    )

    private fun snapshot(
        roomId: String = "room-1",
        epoch: String = "epoch-1",
        rev: Long = 1,
        ownerId: String = "dev-a",
        members: List<GroupMember>,
        inviteCode: String? = "123456",
        requireApproval: Boolean = true,
        pending: List<GroupJoinRequest> = emptyList(),
        removedIds: Set<String> = emptySet(),
        ended: Boolean = false
    ): GroupCloudSnapshotPayload {
        val names = members.associate { it.lease.deviceId to "骑士-${it.lease.deviceId}" } +
            removedIds.associateWith { "已移出-$it" }
        return GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(
                key = GroupRoomKey(roomId, epoch),
                revision = rev,
                ownerId = if (ended) "" else ownerId,
                ended = ended,
                members = if (ended) emptyList() else members
            ),
            names = names,
            inviteCode = inviteCode,
            inviteExpiresAtMs = 9_999_999_999_000L,
            requireApproval = requireApproval,
            pendingRequests = pending,
            removedIds = removedIds,
            endedReason = if (ended) "OWNER_ENDED" else null
        )
    }

    private fun createAdmitted(
        orchestrator: GroupCloudSessionOrchestrator,
        members: List<GroupMember>,
        rtc: CloudRtcToken = rtcToken(),
        requireApproval: Boolean = true
    ): UUID {
        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.Start(
                GroupCloudStartRequest.Create("https://cloud.example.test", requireApproval)
            )
        )
        val operation = effects.filterIsInstance<GroupCloudEffect.CreateRoom>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.Created(
                operation,
                CloudAdmittedSession(
                    roomId = "room-1",
                    sessionToken = "top-secret-session-token",
                    tokenExpiresAtMs = now + 3_600_000L,
                    latestSeq = 1,
                    snapshot = snapshot(members = members, requireApproval = requireApproval),
                    rtc = rtc
                )
            )
        )
        return operation
    }

    @Test
    fun singleMemberWaitsWithoutJoiningRtcAndPeerArrivalStartsAudio() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val operation = createAdmitted(orchestrator, listOf(self))

        assertEquals(GroupPhase.IN_ROOM, orchestrator.snapshot.phase)
        assertEquals(GroupNetworkMediaState.WAITING_FOR_PEER, orchestrator.snapshot.networkMediaState)
        assertEquals(50, orchestrator.snapshot.maxMembers)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
        assertTrue(orchestrator.snapshot.view!!.links.isEmpty())

        effects.clear()
        val peer = member("dev-b")
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.SnapshotApplied(
                operation,
                snapshot(rev = 2, members = listOf(self, peer))
            )
        )
        val join = effects.filterIsInstance<GroupCloudEffect.JoinRtc>().single()
        assertEquals("volc-app-id", join.token.appId)
        assertFalse(join.muted)
        assertEquals(GroupNetworkMediaState.JOINING, orchestrator.snapshot.networkMediaState)

        effects.clear()
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))
        assertEquals(GroupNetworkMediaState.JOINED, orchestrator.snapshot.networkMediaState)
        assertTrue(orchestrator.snapshot.voiceReady)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })

        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.SnapshotApplied(
                operation,
                snapshot(rev = 3, members = listOf(self))
            )
        )
        assertTrue(effects.any { it is GroupCloudEffect.LeaveRtc })
        assertEquals(GroupNetworkMediaState.WAITING_FOR_PEER, orchestrator.snapshot.networkMediaState)
        assertFalse(orchestrator.snapshot.voiceReady)
    }

    @Test
    fun expiredRtcTokenUsesWallClockWhenFirstPeerArrivesAfterLongWait() {
        var monotonic = 5_000L
        var wall = 1_750_000_000_000L
        val orchestrator = writer(monotonicNowMs = { monotonic }, wallNowMs = { wall })
        val self = member("dev-a", role = GroupRole.OWNER)
        val expiring = CloudRtcToken(
            appId = "volc-app-id",
            rtcRoomId = "rtc-room-1",
            rtcUserId = "rtc-user-a",
            rtcToken = "formal-token-wall-clock-test",
            expiresAtMs = wall + 300_000L
        )
        val operation = createAdmitted(orchestrator, listOf(self), rtc = expiring)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })

        monotonic += 660_000L
        wall += 660_000L
        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.SnapshotApplied(
                operation,
                snapshot(rev = 2, members = listOf(self, member("dev-b")))
            )
        )

        assertTrue(effects.any { it is GroupCloudEffect.RefreshRtcToken })
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
    }

    @Test
    fun admittedSessionWithSingleMemberNeverCreatesRtcEngine() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        createAdmitted(orchestrator, listOf(self))
        repeat(3) {
            now += 1_000L
            effects.clear()
            orchestrator.dispatch(GroupSessionEvent.Tick)
            assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
        }
        assertEquals(GroupNetworkMediaState.WAITING_FOR_PEER, orchestrator.snapshot.networkMediaState)
    }

    @Test
    fun waitingForApprovalNeverRequestsRtcJoin() {
        effects.clear()
        val orchestrator = writer(deviceId = "dev-b", runtimeId = "run-b", nickname = "队员", monotonicNowMs = { 3_000L })
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.Start(
                GroupCloudStartRequest.Join(
                    "https://cloud.example.test",
                    GroupCloudInviteCode("123456")
                )
            )
        )
        val operation = effects.filterIsInstance<GroupCloudEffect.SubmitJoin>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.JoinSubmitted(
                operation,
                CloudJoinSubmission.Pending("room-1", "req-1", "secret-1", now + 120_000L)
            )
        )
        assertEquals(GroupPhase.WAITING, orchestrator.snapshot.phase)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
        assertTrue(effects.any { it is GroupCloudEffect.PollJoinRequest })

        orchestrator.dispatchInternal(GroupCloudInternalEvent.PollFailed(operation, GroupCloudException(0, "NETWORK", "断网")))
        now += 119_999L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupPhase.WAITING, orchestrator.snapshot.phase)
        now++
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        assertEquals(GroupTerminalReason.FAILED, orchestrator.snapshot.terminalReason)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
    }

    @Test
    fun cancelBeforeJoinReplyWaitsForConfirmationAndRetriesAfterRevocationFailure() {
        val orchestrator = writer()
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Start(GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"))))
        val submit = effects.filterIsInstance<GroupCloudEffect.SubmitJoin>().single()
        orchestrator.dispatch(GroupSessionEvent.Leave)
        assertEquals(GroupPhase.JOINING, orchestrator.snapshot.phase)
        val pending = CloudJoinSubmission.Pending("room-1", submit.receipt.id, submit.receipt.secret, now + 60_000)
        orchestrator.dispatchInternal(GroupCloudInternalEvent.JoinSubmitted(submit.operation, pending))
        assertEquals(1, effects.filterIsInstance<GroupCloudEffect.CancelJoinRequest>().size)
        orchestrator.dispatchInternal(GroupCloudInternalEvent.ActionFailed(submit.operation, "cancel_join", GroupCloudException(502, "RTC_REVOCATION_FAILED", "重试")))
        assertNotEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        now += 2_000
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertEquals(2, effects.filterIsInstance<GroupCloudEffect.CancelJoinRequest>().size)
        orchestrator.dispatchInternal(GroupCloudInternalEvent.JoinCancelled(submit.operation))
        assertEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        assertEquals(GroupTerminalReason.USER_LEFT, orchestrator.snapshot.terminalReason)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
    }

    @Test
    fun rtcTokenRefreshHonorsThirtySecondMarginAndAppliesUpdatedToken() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val peer = member("dev-b")
        val initial = rtcToken(ttlMs = 120_000L)
        val operation = createAdmitted(orchestrator, listOf(self, peer), rtc = initial)
        assertTrue(effects.any { it is GroupCloudEffect.JoinRtc })
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))

        now = initial.expiresAtMs - 31_000L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupCloudEffect.RefreshRtcToken })

        now = initial.expiresAtMs - 30_000L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.any { it is GroupCloudEffect.RefreshRtcToken })

        val refreshed = rtcToken(ttlMs = 600_000L, userId = "rtc-user-a")
        effects.clear()
        orchestrator.dispatchInternal(GroupCloudInternalEvent.RtcTokenRefreshed(operation, refreshed))
        val applied = effects.filterIsInstance<GroupCloudEffect.ApplyRtcToken>().single()
        assertEquals(refreshed.rtcToken, applied.token.rtcToken)
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.TokenUpdated(refreshed, false), applied.attemptId))
        now += 1_999L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupCloudEffect.RefreshRtcToken })
        now++
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertEquals(1, effects.filterIsInstance<GroupCloudEffect.RefreshRtcToken>().size)
        val replacement = rtcToken()
        orchestrator.dispatchInternal(GroupCloudInternalEvent.RtcTokenRefreshed(operation, replacement))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.TokenUpdated(replacement, true), applied.attemptId))
        now = initial.expiresAtMs
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupCloudEffect.RefreshRtcToken || it is GroupCloudEffect.LeaveRtc })
        assertTrue(orchestrator.snapshot.voiceReady)
    }

    @Test
    fun tokenExpiryCallbackRequestsRefreshAndFiltersUnknownSpeakers() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        createAdmitted(orchestrator, listOf(self, member("dev-b")))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))

        effects.clear()
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.TokenWillExpire))
        assertTrue(effects.any { it is GroupCloudEffect.RefreshRtcToken })

        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.Rtc(GroupRtcEvent.ActiveSpeakers(setOf("dev-b", "outside-room")))
        )
        assertEquals(setOf("dev-b"), orchestrator.snapshot.activeSpeakerIds)
    }

    @Test
    fun joiningRtcAppliesMuteImmediatelyAndReplaysBlockedPeerOnNewAttempt() {
        val orchestrator = writer()
        createAdmitted(orchestrator, listOf(member("dev-a", role = GroupRole.OWNER), member("dev-b")))
        val first = effects.filterIsInstance<GroupCloudEffect.JoinRtc>().single()
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Mute(true))
        orchestrator.dispatch(GroupSessionEvent.Block("dev-b", true))
        assertEquals(listOf(GroupCloudEffect.SetRtcMuted(true)), effects.filterIsInstance<GroupCloudEffect.SetRtcMuted>())
        assertEquals(setOf("dev-a"), effects.filterIsInstance<GroupCloudEffect.SetRtcAllowedUsers>().single().userIds)
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, first.attemptId))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.ConnectionLost("掉线"), first.attemptId))
        effects.clear()
        now += 5_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        val second = effects.filterIsInstance<GroupCloudEffect.JoinRtc>().single()
        assertTrue(second.muted)
        assertTrue(second.attemptId > first.attemptId)
        assertEquals(setOf("dev-a"), second.allowedUsers)
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Mute(false))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, second.attemptId))
        assertEquals(listOf(GroupCloudEffect.SetRtcMuted(false)), effects.filterIsInstance<GroupCloudEffect.SetRtcMuted>())
        assertTrue(effects.none { it is GroupCloudEffect.SetRtcAllowedUsers })
    }

    @Test
    fun remoteSpeakingAndBlockingFollowTheCurrentSeatOfTheSameDevice() {
        val writer = writer()
        val owner = member("dev-a", role = GroupRole.OWNER)
        val firstSeat = member("dev-b").copy(rtcUserId = "rtc-old")
        val op = createAdmitted(writer, listOf(owner, firstSeat))
        val attempt = effects.filterIsInstance<GroupCloudEffect.JoinRtc>().single().attemptId
        writer.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, attempt))
        writer.dispatch(GroupSessionEvent.Block("dev-b", true))
        assertEquals(setOf("dev-a"), effects.filterIsInstance<GroupCloudEffect.SetRtcAllowedUsers>().last().userIds)
        effects.clear()
        writer.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.ActiveSpeakers(setOf("rtc-old")), attempt))
        assertEquals(setOf("dev-b"), writer.snapshot.activeSpeakerIds)
        val newSeat = firstSeat.copy(lease = GroupMemberLease("dev-b", "new-run", 2, 1), rtcUserId = "rtc-new")
        writer.dispatchInternal(GroupCloudInternalEvent.SnapshotApplied(op, snapshot(rev = 2, members = listOf(owner, newSeat))))
        assertTrue(effects.none { it is GroupCloudEffect.SetRtcAllowedUsers })
        writer.dispatch(GroupSessionEvent.Block("dev-b", false))
        assertEquals(setOf("dev-a", "rtc-new"), effects.filterIsInstance<GroupCloudEffect.SetRtcAllowedUsers>().single().userIds)
        writer.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.ActiveSpeakers(setOf("rtc-old")), attempt))
        assertTrue(writer.snapshot.activeSpeakerIds.isEmpty())
        writer.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.ActiveSpeakers(setOf("rtc-new")), attempt))
        assertEquals(setOf("dev-b"), writer.snapshot.activeSpeakerIds)
        writer.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.ConnectionLost("离线"), attempt))
        assertTrue(writer.snapshot.activeSpeakerIds.isEmpty())
    }

    @Test
    fun localMuteAndPerPeerSubscriptionMuteStayClientScoped() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val peer = member("dev-b")
        createAdmitted(orchestrator, listOf(self, peer))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))

        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Mute(true))
        assertTrue(effects.any { it is GroupCloudEffect.SetRtcMuted && it.muted })
        assertTrue(orchestrator.snapshot.participation.selfMuted)

        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Block("dev-b", true))
        assertEquals(setOf("dev-a"), effects.filterIsInstance<GroupCloudEffect.SetRtcAllowedUsers>().single().userIds)
        assertTrue(orchestrator.snapshot.participation.isBlocked("dev-b"))

        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Block("dev-b", false))
        assertEquals(setOf("dev-a", "dev-b"), effects.filterIsInstance<GroupCloudEffect.SetRtcAllowedUsers>().single().userIds)
    }

    @Test
    fun ownerMustTransferOrDisbandBeforeLeaving() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val peer = member("dev-b")
        createAdmitted(orchestrator, listOf(self, peer))

        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Leave)
        assertEquals(GroupPhase.IN_ROOM, orchestrator.snapshot.phase)
        assertTrue(effects.none { it is GroupCloudEffect.LeaveRoom || it is GroupCloudEffect.Stop })
        assertTrue(orchestrator.snapshot.message.contains("转让群主"))

        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.TransferOwner("dev-b", alsoLeave = true))
        val transfer = effects.filterIsInstance<GroupCloudEffect.TransferOwner>().single()
        assertEquals("dev-b", transfer.targetDeviceId)
        assertTrue(transfer.alsoLeave)
        assertEquals(1L, transfer.expectedRevision)
    }

    @Test
    fun transferAndLeaveClosesRtcBeforeStoppingRuntime() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val peer = member("dev-b")
        val operation = createAdmitted(orchestrator, listOf(self, peer))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))

        effects.clear()
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.TransferCompleted(
                operation,
                CloudTransferResult(left = true, snapshot = null)
            )
        )
        assertEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        assertEquals(GroupTerminalReason.USER_LEFT, orchestrator.snapshot.terminalReason)
        assertTrue(effects.any { it is GroupCloudEffect.LeaveRtc })
        assertTrue(effects.any { it is GroupCloudEffect.Stop })
        assertTrue(effects.indexOfFirst { it is GroupCloudEffect.LeaveRtc } <
            effects.indexOfFirst { it is GroupCloudEffect.Stop })
    }

    @Test
    fun reservedMemberRecoveryStillExpiresAtSixtySeconds() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val peer = member("dev-b")
        val operation = createAdmitted(orchestrator, listOf(self, peer))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))

        val reservedSelf = member("dev-a", role = GroupRole.OWNER, status = GroupMemberStatus.RESERVED)
        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.SnapshotApplied(
                operation,
                snapshot(rev = 2, members = listOf(reservedSelf, peer))
            )
        )
        assertEquals(GroupPhase.RECONNECTING, orchestrator.snapshot.phase)
        assertTrue(effects.any { it is GroupCloudEffect.LeaveRtc })

        now += 61_000L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        assertEquals(GroupTerminalReason.RECOVERY_EXPIRED, orchestrator.snapshot.terminalReason)
    }

    @Test
    fun diagnosticsNeverContainRtcTokenOrSessionSecret() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val token = rtcToken()
        createAdmitted(orchestrator, listOf(self, member("dev-b")), rtc = token)
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))

        val joined = logs.joinToString("\n")
        assertFalse(joined.contains(token.rtcToken))
        assertFalse(joined.contains("top-secret-session-token"))
    }

    @Test
    fun lateJoinedAfterJoinFailedOrConnectionLostCannotHijackStateOrBlockRetry() {
        val orchestrator = writer()
        val self = member("dev-a", role = GroupRole.OWNER)
        val peer = member("dev-b")
        val operation = createAdmitted(orchestrator, listOf(self, peer))
        val firstAttempt = effects.filterIsInstance<GroupCloudEffect.JoinRtc>().single().attemptId
        assertTrue(firstAttempt > 0L)

        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.Rtc(GroupRtcEvent.JoinFailed(-1000, "token invalid"), firstAttempt)
        )
        assertEquals(GroupNetworkMediaState.FAILED, orchestrator.snapshot.networkMediaState)

        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, firstAttempt))
        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined))
        assertEquals(GroupNetworkMediaState.FAILED, orchestrator.snapshot.networkMediaState)
        assertFalse(orchestrator.snapshot.voiceReady)

        now += 5_000L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupCloudEffect.JoinRtc })
        val fresh = rtcToken()
        orchestrator.dispatchInternal(GroupCloudInternalEvent.RtcTokenRefreshed(operation, fresh))
        val retry = effects.filterIsInstance<GroupCloudEffect.JoinRtc>().single()
        assertEquals(fresh, retry.token)
        val secondAttempt = retry.attemptId
        assertTrue(secondAttempt > firstAttempt)

        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, firstAttempt))
        assertEquals(GroupNetworkMediaState.JOINING, orchestrator.snapshot.networkMediaState)
        assertFalse(orchestrator.snapshot.voiceReady)

        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, secondAttempt))
        assertEquals(GroupNetworkMediaState.JOINED, orchestrator.snapshot.networkMediaState)
        assertTrue(orchestrator.snapshot.voiceReady)

        orchestrator.dispatchInternal(
            GroupCloudInternalEvent.Rtc(GroupRtcEvent.ConnectionLost("lost"), secondAttempt)
        )
        assertEquals(GroupNetworkMediaState.JOINING, orchestrator.snapshot.networkMediaState)
        assertFalse(orchestrator.snapshot.voiceReady)

        orchestrator.dispatchInternal(GroupCloudInternalEvent.Rtc(GroupRtcEvent.Joined, secondAttempt))
        assertEquals(GroupNetworkMediaState.JOINING, orchestrator.snapshot.networkMediaState)
        assertFalse(orchestrator.snapshot.voiceReady)
    }
}
