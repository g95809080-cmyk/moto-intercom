package com.kuma.motointercom.group.hybrid

import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import com.kuma.motointercom.group.network.GroupWifiCredentials
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupHybridSessionOrchestratorTest {
    private val hostId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
    private val hostRun = UUID.fromString("11111111-1111-4111-8111-111111111112").toString()
    private val peerId = UUID.fromString("22222222-2222-4222-8222-222222222221").toString()
    private val peerRun = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
    private val rogueId = UUID.fromString("33333333-3333-4333-8333-333333333331").toString()
    private val rogueRun = UUID.fromString("33333333-3333-4333-8333-333333333332").toString()
    private val roomId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()
    private val roomEpoch = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString()

    private fun tokenFor(rtcRoomId: String, userId: String, nowMs: Long): CloudRtcToken = CloudRtcToken(
        appId = "app-r5",
        rtcRoomId = rtcRoomId,
        rtcUserId = userId,
        rtcToken = "001token-$rtcRoomId-$userId",
        expiresAtMs = nowMs + 600_000L
    )

    private fun hybridSnapshot(
        revision: Long,
        activeTransport: GroupTransport,
        epoch: Long,
        reason: String = "INITIAL_LOCAL",
        members: List<GroupMember>,
        localCode: String? = if (activeTransport == GroupTransport.LOCAL) "654321" else null,
        rtcRoomId: String? = "mcr-r5_pub",
        cooldownUntilMs: Long = 0L,
        riderIncludesFormation: Boolean = true,
        privateInvites: List<GroupPrivateInvite> = emptyList(),
        localP2pSsid: String? = null,
        localP2pPassphrase: String? = null,
        localHostAddress: String? = null,
        localFrequencyMhz: Int? = null,
        recoveryStage: GroupHybridRecoveryStage = GroupHybridRecoveryStage.IDLE,
        handoverFromEpoch: Long? = null
    ): GroupCloudSnapshotPayload {
        val transport = GroupHybridTransportPayload(
            active = activeTransport,
            epoch = epoch,
            reason = reason,
            localHostDeviceId = hostId,
            localRoomInstanceId = roomId,
            localCode = localCode,
            changedAtMs = 1_000L,
            cooldownUntilMs = cooldownUntilMs,
            localP2pSsid = localP2pSsid,
            localP2pPassphrase = localP2pPassphrase,
            localHostAddress = localHostAddress,
            localFrequencyMhz = localFrequencyMhz,
            recoveryStage = recoveryStage,
            handoverFromEpoch = handoverFromEpoch
        )
        val view = GroupCloudRoomView(
            key = GroupRoomKey(roomId, roomEpoch),
            revision = revision,
            ownerId = hostId,
            ended = false,
            members = members,
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = if (activeTransport == GroupTransport.LOCAL) hostId else null,
            riderIncludesFormation = riderIncludesFormation,
            privateInvites = privateInvites
        )
        val names = members.associate {
            it.lease.deviceId to when (it.lease.deviceId) {
                hostId -> "队长"
                peerId -> "头车"
                else -> "骑士"
            }
        }
        return GroupCloudSnapshotPayload(
            view = view,
            names = names,
            inviteCode = "123456",
            inviteExpiresAtMs = 999_999L,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null,
            rtcRoomId = rtcRoomId,
            hybridTransport = transport
        )
    }

    @Test
    fun localHostEnforcesCloudAuthorityAndRejectsUnadmittedPeer() {
        var now = 10_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val logs = mutableListOf<String>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics { logs += it }
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val createOp = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val snap = hybridSnapshot(1L, GroupTransport.LOCAL, 1L, members = members)
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Created(
                createOp,
                CloudAdmittedSession(roomId, "sess-1", now + 3_600_000L, 1L, snap, tokenFor("mcr-r5_pub", hostId, now))
            )
        )
        val startHost = effects.filterIsInstance<GroupHybridEffect.StartLocalHost>().single()
        assertEquals(HybridMediaPhase.LOCAL_ACTIVE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalHostReady(1L, startHost.attempt))
        val rogueChannel = UUID.randomUUID()
        val rogueContext = GroupAuthContext(
            GroupRoomKey(roomId, hostRun),
            GroupAuthEndpoint(hostId, hostRun),
            GroupAuthEndpoint(rogueId, rogueRun),
            UUID.randomUUID().toString()
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, rogueChannel, rogueContext))
        assertTrue(effects.any { it is GroupHybridEffect.CloseLocalChannel && it.channel == rogueChannel })
        assertTrue(logs.any { it.contains("group_hybrid_local_authority_reject") })
        assertTrue(logs.none { it.contains("654321") || it.contains("123456") || it.contains("001token") })

        effects.clear()
        val validChannel = UUID.randomUUID()
        val validContext = GroupAuthContext(
            GroupRoomKey(roomId, hostRun),
            GroupAuthEndpoint(hostId, hostRun),
            GroupAuthEndpoint(peerId, peerRun),
            UUID.randomUUID().toString()
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, validChannel, validContext))
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.LocalControl(
                1L,
                validChannel,
                GroupControl.Join("头车")
            )
        )
        assertTrue(effects.any { it is GroupHybridEffect.AdmitLocalChannel && it.channel == validChannel })
        assertTrue(effects.any { it is GroupHybridEffect.OpenLocalMedia && it.lease.peer.deviceId == peerId })
    }

    @Test
    fun localToCloudAndBackEnforcesStrictQuiescenceBarrierWithZeroOverlap() {
        var now = 20_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val op = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.RIDING_OFFICER)
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Created(
                op,
                CloudAdmittedSession(
                    roomId,
                    "sess-host",
                    now + 3_600_000L,
                    1L,
                    hybridSnapshot(1L, GroupTransport.LOCAL, 1L, members = members),
                    tokenFor("mcr-r5_pub", hostId, now)
                )
            )
        )
        orchestrator.dispatch(GroupSessionEvent.Mute(true))
        orchestrator.dispatch(GroupSessionEvent.Block(peerId, true))

        // Cloud commands whole-group switch to CLOUD (epoch 2).
        effects.clear()
        val cloudSnap = hybridSnapshot(
            revision = 2L,
            activeTransport = GroupTransport.CLOUD,
            epoch = 2L,
            reason = "LOCAL_LINK_LOST",
            members = members,
            cooldownUntilMs = now + 15_000L
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                op,
                cloudSnap,
                rtc = tokenFor("mcr-r5_pub", hostId, now)
            )
        )

        // Barrier check: StopLocalTransportAndMedia emitted, JoinRtc MUST NOT fire yet!
        assertEquals(HybridMediaPhase.DRAINING_LOCAL, orchestrator.currentMediaPhase)
        assertTrue(orchestrator.snapshot.transportSwitching)
        assertTrue(effects.any { it is GroupHybridEffect.StopLocalTransportAndMedia && it.epoch == 1L })
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })

        // Release barrier: LocalTransportDisposed(1L) now permits JoinRtc.
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalTransportDisposed(1L))
        assertEquals(HybridMediaPhase.CLOUD_ACTIVE, orchestrator.currentMediaPhase)
        val joinRtc = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single()
        assertTrue(joinRtc.muted)
        assertEquals("mcr-r5_pub", joinRtc.token.rtcRoomId)
        assertEquals(setOf(hostId), joinRtc.allowedUsers)

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.Joined, joinRtc.attemptId))
        assertTrue(orchestrator.snapshot.voiceReady)
        assertTrue(effects.none { it is GroupHybridEffect.SetRtcAllowedUsers })

        // During cooldown, proximity beacon must NOT start.
        assertTrue(effects.none { it is GroupHybridEffect.StartProximityBeacon })

        // After cooldown elapses, host starts proximity beacon.
        effects.clear()
        now += 16_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.any { it is GroupHybridEffect.StartProximityBeacon && it.epoch == 2L })

        // Cloud recovers whole group back to LOCAL (epoch 3).
        effects.clear()
        val recoveredSnap = hybridSnapshot(
            revision = 3L,
            activeTransport = GroupTransport.LOCAL,
            epoch = 3L,
            reason = "LOCAL_RECOVERED",
            members = members,
            localCode = "987654"
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.SnapshotApplied(op, recoveredSnap))

        // Barrier check: LeaveRtc + StopProximityProbe emitted, StartLocalHost MUST NOT fire yet!
        assertEquals(HybridMediaPhase.DRAINING_CLOUD, orchestrator.currentMediaPhase)
        assertTrue(orchestrator.snapshot.transportSwitching)
        assertTrue(effects.any { it is GroupHybridEffect.LeaveRtc && it.attemptId == joinRtc.attemptId })
        assertTrue(effects.any { it is GroupHybridEffect.StopProximityProbe })
        assertTrue(effects.none { it is GroupHybridEffect.StartLocalHost })

        // Late Joined from old RTC attempt is completely ignored.
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.Joined, joinRtc.attemptId))
        assertEquals(HybridMediaPhase.DRAINING_CLOUD, orchestrator.currentMediaPhase)
        assertFalse(orchestrator.snapshot.voiceReady)

        // Release barrier: CloudMediaDisposed permits StartLocalHost for epoch 3.
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.CloudMediaDisposed(joinRtc.attemptId))
        assertEquals(HybridMediaPhase.LOCAL_ACTIVE, orchestrator.currentMediaPhase)
        val startHost3 = effects.filterIsInstance<GroupHybridEffect.StartLocalHost>().single()
        assertEquals(3L, startHost3.epoch)
        assertEquals("987654", startHost3.code.digits)
    }

    @Test
    fun channelSwitchInCloudLeavesOldRoomAndLazyJoinsNewChannelOnlyWithTwoPeers() {
        var now = 30_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val op = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        val publicMembers = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER, channel = GroupChannel.Public),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.RIDING_OFFICER, channel = GroupChannel.Public)
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Created(
                op,
                CloudAdmittedSession(
                    roomId,
                    "sess-host",
                    now + 3_600_000L,
                    1L,
                    hybridSnapshot(1L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = publicMembers, rtcRoomId = "mcr-r5_pub"),
                    tokenFor("mcr-r5_pub", hostId, now)
                )
            )
        )
        val pubJoin = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.Joined, pubJoin.attemptId))
        assertTrue(orchestrator.snapshot.voiceReady)

        // Host alone moves to RIDER -> leaves PUBLIC RTC and stays WAITING_FOR_PEER (quota guardrail!).
        effects.clear()
        val hostAloneRider = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER, channel = GroupChannel.Rider, channelRevision = 2),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.RIDING_OFFICER, channel = GroupChannel.Public, channelRevision = 1)
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                op,
                hybridSnapshot(2L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = hostAloneRider, rtcRoomId = "mcr-r5_rider"),
                rtc = tokenFor("mcr-r5_rider", hostId, now)
            )
        )
        assertTrue(effects.any { it is GroupHybridEffect.LeaveRtc && it.attemptId == pubJoin.attemptId })
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.CloudMediaDisposed(pubJoin.attemptId))
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertEquals(GroupNetworkMediaState.WAITING_FOR_PEER, orchestrator.snapshot.networkMediaState)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })

        // Peer also enters RIDER -> now joins mcr-r5_rider!
        effects.clear()
        val bothRider = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER, channel = GroupChannel.Rider, channelRevision = 2),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.RIDING_OFFICER, channel = GroupChannel.Rider, channelRevision = 2)
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                op,
                hybridSnapshot(3L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = bothRider, rtcRoomId = "mcr-r5_rider")
            )
        )
        val riderJoin = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single()
        assertEquals("mcr-r5_rider", riderJoin.token.rtcRoomId)
    }

    @Test
    fun cloudPollErrorWhileLocalActiveKeepsLocalVoiceAliveAndResumesCleanly() {
        var now = 40_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val op = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER)
        )
        val snap = hybridSnapshot(1L, GroupTransport.LOCAL, 1L, members = members)
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Created(
                op,
                CloudAdmittedSession(roomId, "sess-host", now + 3_600_000L, 1L, snap, tokenFor("mcr-r5_pub", hostId, now))
            )
        )

        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.PollFailed(op, GroupCloudException(0, "NETWORK_ERROR", "网络中断"))
        )
        assertTrue(orchestrator.snapshot.cloudOffline)
        assertEquals(GroupPhase.IN_ROOM, orchestrator.snapshot.phase)
        assertEquals(HybridMediaPhase.LOCAL_ACTIVE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.StopLocalTransportAndMedia })

        now += 3_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.any { it is GroupHybridEffect.ResumeRoom })

        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Resumed(
                op,
                CloudResumeResult(resumed = true, latestSeq = 2L, snapshot = snap, rtc = tokenFor("mcr-r5_pub", hostId, now))
            )
        )
        assertFalse(orchestrator.snapshot.cloudOffline)
        assertEquals(HybridMediaPhase.LOCAL_ACTIVE, orchestrator.currentMediaPhase)
        assertTrue(effects.any { it is GroupHybridEffect.PollEvents })
    }

    @Test
    fun joiningNetworkRoomInHybridOrchestratorIsRejectedCleanly() {
        val now = 50_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("234567"), GroupMode.HYBRID)
            )
        )
        val op = effects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        val netMembers = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val netSnap = GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(GroupRoomKey(roomId, roomEpoch), 1L, hostId, false, netMembers, maxMembers = 50),
            names = mapOf(hostId to "队长", peerId to "队员"),
            inviteCode = null,
            inviteExpiresAtMs = null,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null
        )
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op,
                CloudJoinSubmission.Admitted(
                    CloudAdmittedSession(roomId, "sess-peer", now + 3_600_000L, 1L, netSnap, tokenFor("mcr-net", peerId, now))
                )
            )
        )
        assertEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        assertEquals(GroupTerminalReason.REFUSED, orchestrator.snapshot.terminalReason)
        assertTrue(effects.any { it is GroupHybridEffect.LeaveRoom })
        assertTrue(effects.any { it is GroupHybridEffect.Stop })
    }

    @Test
    fun localSessionAcceptsNullRtcFetchesOnCloudAndBarrierFailureTerminatesWithoutStartingOppositeMedia() {
        var now = 60_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID))
        )
        val op = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Created(
                op,
                CloudAdmittedSession(
                    roomId,
                    "sess-host",
                    now + 3_600_000L,
                    1L,
                    hybridSnapshot(1L, GroupTransport.LOCAL, 1L, members = members, rtcRoomId = ""),
                    null
                )
            )
        )
        assertEquals(GroupPhase.IN_ROOM, orchestrator.snapshot.phase)
        assertEquals(HybridMediaPhase.LOCAL_ACTIVE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc || it is GroupHybridEffect.RefreshRtcToken })

        // Transition to CLOUD without pre-issued token -> drains local first, then fetches /rtc-token after LocalTransportDisposed.
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                op,
                hybridSnapshot(2L, GroupTransport.CLOUD, 2L, reason = "LOCAL_LINK_LOST", members = members, rtcRoomId = "mcr-r5_e2_pub"),
                rtc = null
            )
        )
        assertEquals(HybridMediaPhase.DRAINING_LOCAL, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc || it is GroupHybridEffect.RefreshRtcToken })

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalTransportDisposed(1L))
        assertTrue(effects.any { it is GroupHybridEffect.RefreshRtcToken })
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })

        // Receive refreshed token -> joins Cloud RTC.
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.RtcTokenRefreshed(op, tokenFor("mcr-r5_e2_pub", hostId, now))
        )
        val joinEffect = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single()
        assertEquals("mcr-r5_e2_pub", joinEffect.token.rtcRoomId)

        // Cloud cleanup fails during CLOUD -> LOCAL -> enters BARRIER_FAILED and NEVER starts local host!
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                op,
                hybridSnapshot(3L, GroupTransport.LOCAL, 3L, reason = "LOCAL_RECOVERED", members = members, localCode = "654321", rtcRoomId = "")
            )
        )
        assertEquals(HybridMediaPhase.DRAINING_CLOUD, orchestrator.currentMediaPhase)
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.CloudMediaDisposeFailed(joinEffect.attemptId, "云端释放失败")
        )
        assertEquals(HybridMediaPhase.BARRIER_FAILED, orchestrator.currentMediaPhase)
        assertEquals(GroupPhase.IDLE, orchestrator.snapshot.phase)
        assertEquals(GroupTerminalReason.FAILED, orchestrator.snapshot.terminalReason)
        assertTrue(effects.none { it is GroupHybridEffect.StartLocalHost })
        assertTrue(effects.any { it is GroupHybridEffect.Stop })
    }

    @Test
    fun cancellingWithLostJoinReplyUsesOriginalReceiptAndIgnoresLatePolls() {
        var now = 20_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val writer = GroupHybridSessionOrchestrator(GroupAuthEndpoint(peerId, peerRun), "队员", { now }, {}, { effects += it }, GroupDiagnostics {})
        writer.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)))
        val submit = effects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single()
        writer.dispatch(GroupSessionEvent.Leave)
        writer.dispatchInternal(GroupHybridInternalEvent.ActionFailed(submit.operation, "join", GroupCloudException(0, "NETWORK_ERROR", "回包丢失")))
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.CancelJoinRequest>().size)
        writer.dispatchInternal(GroupHybridInternalEvent.ActionFailed(submit.operation, "cancel_join", GroupCloudException(502, "RTC_REVOCATION_FAILED", "重试")))
        now += 2_000
        writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(2, effects.filterIsInstance<GroupHybridEffect.CancelJoinRequest>().size)
        writer.dispatchInternal(GroupHybridInternalEvent.JoinCancelled(submit.operation))
        assertEquals(GroupTerminalReason.USER_LEFT, writer.snapshot.terminalReason)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc || it is GroupHybridEffect.StartLocalSearch })
    }

    @Test
    fun cloudTokenRejectedBySdkRetriesAfterEarlyWarningAndKeepsMediaJoined() {
        var now = 80_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val writer = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun), nickname = "队长", nowMs = { now },
            assertWriter = {}, effects = { effects += it }, diagnostics = GroupDiagnostics {}
        )
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1))
        )
        val rtcRoom = "mcr-r5_e2_pub"
        val initial = tokenFor(rtcRoom, hostId, now)
        val payload = hybridSnapshot(1L, GroupTransport.CLOUD, 2L, "MANUAL_CLOUD", members, rtcRoomId = rtcRoom)
        writer.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val op = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        writer.dispatchInternal(GroupHybridInternalEvent.Created(op, CloudAdmittedSession(roomId, "session", now + 3_600_000L, 1L, payload, initial)))
        val attempt = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single().attemptId
        writer.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.Joined, attempt))
        writer.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.TokenWillExpire, attempt))
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.RefreshRtcToken>().size)
        val candidate = initial.copy(rtcToken = "renewed", expiresAtMs = now + 900_000L)
        writer.dispatchInternal(GroupHybridInternalEvent.RtcTokenRefreshed(op, candidate))
        assertEquals(GroupHybridEffect.ApplyRtcToken(candidate, attempt), effects.filterIsInstance<GroupHybridEffect.ApplyRtcToken>().single())
        writer.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.TokenUpdated(candidate, false), attempt))
        effects.clear()
        now += 1_999L
        writer.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupHybridEffect.RefreshRtcToken })
        now++
        writer.dispatch(GroupSessionEvent.Tick)
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.RefreshRtcToken>().size)
        writer.dispatchInternal(GroupHybridInternalEvent.RtcTokenRefreshed(op, candidate))
        writer.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.TokenUpdated(candidate, true), attempt))
        effects.clear()
        now = initial.expiresAtMs
        writer.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupHybridEffect.RefreshRtcToken || it is GroupHybridEffect.LeaveRtc })
        assertEquals(GroupNetworkMediaState.JOINED, writer.snapshot.networkMediaState)

        writer.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.JoinFailed(-1000, "SDK 异步拒绝新令牌"), attempt))
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.RefreshRtcToken>().size)
        writer.dispatchInternal(GroupHybridInternalEvent.CloudMediaDisposed(attempt))
        now += 5_000L
        writer.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })
        val fresh = candidate.copy(rtcToken = "fresh-after-rejection", expiresAtMs = now + 600_000L)
        writer.dispatchInternal(GroupHybridInternalEvent.RtcTokenRefreshed(op, fresh))
        assertEquals(fresh, effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single().token)
    }

    @Test
    fun cloudSnapshotWithNullRtcRoomIdClearsCachedTokenNeverJoinsAndDrainsActiveCloud() {
        var now = 80_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val createOp = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        val singleMember = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER)
        )
        val twoMembers = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        // Enter CLOUD with 1 member so rtcToken is cached while mediaPhase stays NONE (lazy join guard).
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Created(
                createOp,
                CloudAdmittedSession(
                    roomId,
                    "sess-cloud",
                    now + 3_600_000L,
                    1L,
                    hybridSnapshot(1L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = singleMember, rtcRoomId = "mcr-r5_e2_pub"),
                    tokenFor("mcr-r5_e2_pub", hostId, now)
                )
            )
        )
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })

        // CLOUD snapshot arrives with 2 members but rtcRoomId = null -> MUST clear cached token and NEVER JoinRtc.
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                createOp,
                hybridSnapshot(2L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = twoMembers, rtcRoomId = null)
            )
        )
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertEquals(GroupNetworkMediaState.FAILED, orchestrator.snapshot.networkMediaState)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })
        assertTrue(effects.none { it is GroupHybridEffect.RefreshRtcToken })

        // Stale RtcTokenRefreshed while rtcRoomId == null MUST be ignored.
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.RtcTokenRefreshed(createOp, tokenFor("mcr-r5_e2_pub", hostId, now))
        )
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })
        assertTrue(effects.none { it is GroupHybridEffect.ApplyRtcToken })

        // Valid rtcRoomId returns without inline token -> proves old cache was cleared (requests refresh, does NOT immediately JoinRtc).
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                createOp,
                hybridSnapshot(3L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = twoMembers, rtcRoomId = "mcr-r5_e2_pub")
            )
        )
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.RefreshRtcToken>().size)

        // Mismatched refreshed token MUST be ignored; matching refreshed token joins CLOUD_ACTIVE.
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.RtcTokenRefreshed(createOp, tokenFor("mcr-r5_e2_rider", hostId, now))
        )
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })

        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.RtcTokenRefreshed(createOp, tokenFor("mcr-r5_e2_pub", hostId, now))
        )
        val joinEffect = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single()
        assertEquals(HybridMediaPhase.CLOUD_ACTIVE, orchestrator.currentMediaPhase)
        assertEquals("mcr-r5_e2_pub", joinEffect.token.rtcRoomId)
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.Joined, joinEffect.attemptId))

        // While CLOUD_ACTIVE, a CLOUD snapshot with rtcRoomId = null MUST immediately drain (LeaveRtc) and refuse restart.
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.SnapshotApplied(
                createOp,
                hybridSnapshot(4L, GroupTransport.CLOUD, 2L, reason = "MANUAL_CLOUD", members = twoMembers, rtcRoomId = null)
            )
        )
        val leaveEffect = effects.filterIsInstance<GroupHybridEffect.LeaveRtc>().single()
        assertEquals(joinEffect.attemptId, leaveEffect.attemptId)
        assertEquals(HybridMediaPhase.DRAINING_CLOUD, orchestrator.currentMediaPhase)

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.CloudMediaDisposed(joinEffect.attemptId))
        assertEquals(HybridMediaPhase.NONE, orchestrator.currentMediaPhase)
        assertEquals(GroupNetworkMediaState.FAILED, orchestrator.snapshot.networkMediaState)
        assertTrue(effects.none { it is GroupHybridEffect.JoinRtc })
        assertTrue(effects.none { it is GroupHybridEffect.RefreshRtcToken })
    }

    @Test
    fun memberLocalSelfRescueRetriesThreeTimesInTenSecondsAndHoldsIndefinitelyWhenCloudOffline() {
        var now = 50_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val localSnap = hybridSnapshot(
            revision = 1L,
            activeTransport = GroupTransport.LOCAL,
            epoch = 1L,
            members = members,
            localCode = "123456",
            rtcRoomId = null,
            localP2pSsid = "DIRECT-mc-abcd1234",
            localP2pPassphrase = "PassphraseTest12",
            localHostAddress = "192.168.49.1",
            localFrequencyMhz = 2437
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)
            )
        )
        val op = effects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op,
                CloudJoinSubmission.Admitted(CloudAdmittedSession(roomId, "sess-peer", now + 3_600_000L, 1L, localSnap, null))
            )
        )
        val initialJoin = effects.filterIsInstance<GroupHybridEffect.JoinLocalDirect>().single()
        assertEquals("DIRECT-mc-abcd1234", initialJoin.preset.networkName)
        assertEquals(2437, initialJoin.preset.frequencyMhz)

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkReady(1L, initialJoin.attempt, "192.168.49.1"))
        val connectDirect = effects.filterIsInstance<GroupHybridEffect.ConnectLocalDirect>().single()
        assertEquals("192.168.49.1", connectDirect.hostAddress)
        val ch1 = UUID.randomUUID()
        val authCtx = GroupAuthContext(GroupRoomKey(roomId, hostRun), GroupAuthEndpoint(hostId, hostRun), GroupAuthEndpoint(peerId, peerRun), UUID.randomUUID().toString())
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, ch1, authCtx))
        val roster = GroupRoster(
            key = GroupRoomKey(roomId, hostRun),
            hostId = hostId,
            publication = 1L,
            ended = false,
            members = members,
            links = emptyList(),
            names = mapOf(hostId to "队长", peerId to "队员")
        )
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalControl(1L, ch1, GroupControl.Roster(roster)))

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalChannelClosed(1L, ch1))
        assertTrue("Must NOT switch to CLOUD on first local disconnect", effects.none { it is GroupHybridEffect.SwitchTransport })
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.ConnectLocalDirect>().size)

        effects.clear()
        now += 3_000L
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkLost(1L, initialJoin.attempt))
        assertTrue("Must NOT switch to CLOUD on attempt 2", effects.none { it is GroupHybridEffect.SwitchTransport })
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.JoinLocalDirect>().size)

        effects.clear()
        now += 3_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue("Must NOT switch to CLOUD on attempt 3", effects.none { it is GroupHybridEffect.SwitchTransport })
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.JoinLocalDirect>().size)

        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.PollFailed(op, GroupCloudException(0, "NETWORK_ERROR", "无公网"))
        )
        assertTrue(orchestrator.snapshot.cloudOffline)
        now += 15_000L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue("Tunnel guardrail must NOT report LOCAL_LINK_LOST while cloudOffline", effects.none { it is GroupHybridEffect.SwitchTransport })
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.JoinLocalDirect>().size)

        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Resumed(op, CloudResumeResult(true, 1L, localSnap, null))
        )
        assertFalse(orchestrator.snapshot.cloudOffline)
        now += 1_000L
        effects.clear()
        orchestrator.dispatch(GroupSessionEvent.Tick)
        val sw = effects.filterIsInstance<GroupHybridEffect.SwitchTransport>().single()
        assertEquals(GroupTransport.CLOUD, sw.target)
        assertEquals("LOCAL_LINK_LOST", sw.reason)
    }

    @Test
    fun fastHostPeerDropGraceIsThirteenSecondsAndAudioJitterRestartsInPlaceWithoutSwitchingToCloud() {
        var now = 20_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {},
            supportsFastP2p = { true }
        )
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), role = GroupRole.OWNER, supportsFastP2p = true),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), supportsFastP2p = true)
        )
        val snap = hybridSnapshot(1L, GroupTransport.LOCAL, 1L, members = members, localCode = "123456", rtcRoomId = null,
            localP2pSsid = "DIRECT-mc-fast0001", localP2pPassphrase = "PassphraseFast1")
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val op = effects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Created(op, CloudAdmittedSession(roomId, "sess-host", now + 3_600_000L, 1L, snap, null)))
        val hostAttempt = effects.filterIsInstance<GroupHybridEffect.StartLocalHostDirect>().single().attempt
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalHostReady(1L, hostAttempt))
        val peerChannel = UUID.randomUUID()
        val peerCtx = GroupAuthContext(GroupRoomKey(roomId, hostRun), GroupAuthEndpoint(hostId, hostRun), GroupAuthEndpoint(peerId, peerRun), UUID.randomUUID().toString())
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, peerChannel, peerCtx))
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalControl(1L, peerChannel, GroupControl.Join("队员")))
        val initialLease = effects.filterIsInstance<GroupHybridEffect.OpenLocalMedia>().single().lease

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalMediaFailed(1L, initialLease))
        val lease2 = effects.filterIsInstance<GroupHybridEffect.OpenLocalMedia>().single().lease
        assertNotEquals(initialLease.link, lease2.link)
        assertTrue(effects.none { it is GroupHybridEffect.SwitchTransport })

        effects.clear()
        now += 2_000L
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalMediaFailed(1L, lease2))
        val lease3 = effects.filterIsInstance<GroupHybridEffect.OpenLocalMedia>().single().lease
        assertNotEquals(lease2.link, lease3.link)
        assertTrue(effects.none { it is GroupHybridEffect.SwitchTransport })

        effects.clear()
        now += 1_000L
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalMediaFailed(1L, lease3))
        assertTrue(effects.none { it is GroupHybridEffect.OpenLocalMedia })
        assertTrue(effects.none { it is GroupHybridEffect.SwitchTransport })

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalChannelClosed(1L, peerChannel))
        now += 12_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue("Host must wait full 13s grace before fallback", effects.none { it is GroupHybridEffect.SwitchTransport })
        now += 1_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        val sw = effects.filterIsInstance<GroupHybridEffect.SwitchTransport>().single()
        assertEquals(GroupTransport.CLOUD, sw.target)
        assertEquals("LOCAL_LINK_LOST", sw.reason)
    }

    @Test
    fun memberPreconnectFailureBacksOffWithoutInterruptingCloudAndSuccessfulPreconnectHandsOverWithCachedRoster() {
        var now = 40_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val cloudProbing = hybridSnapshot(
            revision = 1L,
            activeTransport = GroupTransport.CLOUD,
            epoch = 2L,
            reason = "LOCAL_LINK_LOST",
            members = members,
            localCode = null,
            rtcRoomId = "mcr-r5_e2_pub",
            cooldownUntilMs = now - 1_000L,
            localP2pSsid = "DIRECT-mc-ssid0001",
            recoveryStage = GroupHybridRecoveryStage.PROBING
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)
            )
        )
        val op = effects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op,
                CloudJoinSubmission.Admitted(
                    CloudAdmittedSession(roomId, "sess-peer", now + 3_600_000L, 1L, cloudProbing, tokenFor("mcr-r5_e2_pub", peerId, now))
                )
            )
        )
        val rtcJoin = effects.filterIsInstance<GroupHybridEffect.JoinRtc>().single()
        val scan = effects.filterIsInstance<GroupHybridEffect.StartProximityScan>().single()
        assertEquals("DIRECT-mc-ssid0001", scan.targetWifiSsid)
        orchestrator.dispatchInternal(GroupHybridInternalEvent.Rtc(GroupRtcEvent.Joined, rtcJoin.attemptId))

        val preconnect1 = hybridSnapshot(
            revision = 2L,
            activeTransport = GroupTransport.CLOUD,
            epoch = 2L,
            reason = "LOCAL_LINK_LOST",
            members = members,
            localCode = "112233",
            rtcRoomId = "mcr-r5_e2_pub",
            cooldownUntilMs = now - 1_000L,
            localP2pSsid = "DIRECT-mc-ssid0001",
            localP2pPassphrase = "PassphraseTest11",
            localHostAddress = "192.168.49.1",
            localFrequencyMhz = 2437,
            recoveryStage = GroupHybridRecoveryStage.PRECONNECTING
        )
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.SnapshotApplied(op, preconnect1))
        val joinPre1 = effects.filterIsInstance<GroupHybridEffect.JoinPreconnectDirect>().single()
        assertEquals(HybridMediaPhase.CLOUD_ACTIVE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.LeaveRtc })

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.PreconnectFailed(2L, joinPre1.attempt, "join failed"))
        assertTrue(effects.any { it is GroupHybridEffect.StopPreconnectNetwork })
        val failedReport = effects.filterIsInstance<GroupHybridEffect.ReportLocalStatus>().single()
        assertTrue(failedReport.preconnectFailed)
        assertEquals(HybridMediaPhase.CLOUD_ACTIVE, orchestrator.currentMediaPhase)
        assertTrue(effects.none { it is GroupHybridEffect.LeaveRtc })

        val preconnect2 = preconnect1.copy(
            view = GroupCloudRoomView(
                key = GroupRoomKey(roomId, roomEpoch),
                revision = 3L,
                ownerId = hostId,
                ended = false,
                members = members,
                maxMembers = 4,
                mode = GroupMode.HYBRID,
                hostDeviceId = null
            ),
            hybridTransport = preconnect1.hybridTransport!!.copy(
                localP2pSsid = "DIRECT-mc-ssid0002",
                localP2pPassphrase = "PassphraseTest22",
                localCode = "445566"
            )
        )
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.SnapshotApplied(op, preconnect2))
        val joinPre2 = effects.filterIsInstance<GroupHybridEffect.JoinPreconnectDirect>().single()
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.PreconnectNetworkReady(2L, joinPre2.attempt, "192.168.49.1"))
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.ConnectPreconnectDirect>().size)
        val preCh = UUID.randomUUID()
        val authCtx = GroupAuthContext(GroupRoomKey(roomId, hostRun), GroupAuthEndpoint(hostId, hostRun), GroupAuthEndpoint(peerId, peerRun), UUID.randomUUID().toString())
        orchestrator.dispatchInternal(GroupHybridInternalEvent.PreconnectAuthenticated(2L, preCh, authCtx))
        val controlOnlyRoster = GroupRoster(
            key = GroupRoomKey(roomId, hostRun),
            hostId = hostId,
            publication = 1L,
            ended = false,
            members = members,
            links = emptyList(),
            names = mapOf(hostId to "队长", peerId to "队员")
        )
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.PreconnectControl(2L, preCh, GroupControl.Roster(controlOnlyRoster)))
        val readyReport = effects.filterIsInstance<GroupHybridEffect.ReportLocalStatus>().single()
        assertTrue(readyReport.localControlReady)
        assertTrue(effects.none { it is GroupHybridEffect.OpenLocalMedia })

        val localHandover = hybridSnapshot(
            revision = 4L,
            activeTransport = GroupTransport.LOCAL,
            epoch = 3L,
            reason = "LOCAL_RECOVERED",
            members = members,
            localCode = "445566",
            rtcRoomId = null,
            localP2pSsid = "DIRECT-mc-ssid0002",
            localP2pPassphrase = "PassphraseTest22",
            localHostAddress = "192.168.49.1",
            handoverFromEpoch = 2L
        )
        val realLink = GroupLink(GroupLinkLease(GroupPair(hostId, peerId), 1L, 2L, 1L))
        val realRoster = GroupRoster(
            key = GroupRoomKey(roomId, hostRun),
            hostId = hostId,
            publication = 2L,
            ended = false,
            members = members,
            links = listOf(realLink),
            names = mapOf(hostId to "队长", peerId to "队员")
        )
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.PreconnectControl(2L, preCh, GroupControl.Roster(realRoster)))
        assertTrue("Must NOT open GroupAudio before CloudMediaDisposed!", effects.none { it is GroupHybridEffect.OpenLocalMedia })

        val offer = GroupMessage.Offer(realLink.lease, "offer-before-cloud-drain-completes")
        val offerFrame = GroupFrame(
            realRoster.key,
            members.first().lease,
            members.last().lease,
            1L,
            offer
        )
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.PreconnectControl(2L, preCh, GroupControl.Signal(offerFrame))
        )
        assertTrue(effects.none { it is GroupHybridEffect.LocalMediaSignal })
        orchestrator.dispatchInternal(GroupHybridInternalEvent.SnapshotApplied(op, localHandover))
        assertEquals(HybridMediaPhase.DRAINING_CLOUD, orchestrator.currentMediaPhase)
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.LeaveRtc>().size)
        assertTrue(effects.none { it is GroupHybridEffect.StopPreconnectNetwork })
        val ice = GroupMessage.Candidate(realLink.lease, "audio", 0, "candidate-during-cloud-drain")
        val iceFrame = GroupFrame(realRoster.key, members.first().lease, members.last().lease, 2L, ice)
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.PreconnectControl(2L, preCh, GroupControl.Signal(iceFrame))
        )
        assertTrue("Buffered preconnect signaling must not reach local media before the cloud barrier", effects.none {
            it is GroupHybridEffect.LocalMediaSignal
        })

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.CloudMediaDisposed(rtcJoin.attemptId))
        assertEquals(HybridMediaPhase.LOCAL_ACTIVE, orchestrator.currentMediaPhase)
        val promote = effects.filterIsInstance<GroupHybridEffect.PromotePreconnectToLocal>().single()
        assertEquals(2L, promote.fromEpoch)
        assertEquals(3L, promote.toEpoch)
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.OpenLocalMedia>().size)
        val replayed = effects.filterIsInstance<GroupHybridEffect.LocalMediaSignal>()
        assertEquals(listOf(offer, ice), replayed.map { it.message })
        assertTrue(replayed.all { it.epoch == 3L })
    }

    @Test
    fun preAndroidTenUsesLegacyDiscoveryEvenWhenFastP2pCredentialsExist() {
        verifyLegacyDiscoveryAndReconnect(memberSupportsPreset = false, hostProvidesPreset = true)
    }

    @Test
    fun modernMemberUsesLegacyDiscoveryAndReconnectForOlderHost() {
        verifyLegacyDiscoveryAndReconnect(memberSupportsPreset = true, hostProvidesPreset = false, cachedFailureCallback = true)
    }

    @Test
    fun legacyCachedNetworkAndRescanWithNoCallbacksEventuallySwitchToCloud() {
        verifyLegacyDiscoveryAndReconnect(memberSupportsPreset = false, hostProvidesPreset = true, rescanTimesOut = true)
    }

    private fun verifyLegacyDiscoveryAndReconnect(
        memberSupportsPreset: Boolean,
        hostProvidesPreset: Boolean,
        cachedFailureCallback: Boolean = false,
        rescanTimesOut: Boolean = false
    ) {
        var now = 50_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {},
            supportsFastP2p = { memberSupportsPreset }
        )
        val hostEffects = mutableListOf<GroupHybridEffect>()
        val host = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(hostId, hostRun), nickname = "队长", nowMs = { now },
            assertWriter = {}, effects = { hostEffects += it }, diagnostics = GroupDiagnostics {},
            supportsFastP2p = { hostProvidesPreset }
        )
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), role = GroupRole.OWNER, supportsFastP2p = hostProvidesPreset),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), supportsFastP2p = memberSupportsPreset)
        )
        val local = hybridSnapshot(
            revision = 1L,
            activeTransport = GroupTransport.LOCAL,
            epoch = 1L,
            members = members,
            localCode = "123456",
            rtcRoomId = null,
            localP2pSsid = if (hostProvidesPreset) "DIRECT-mc-legacy01" else null,
            localP2pPassphrase = if (hostProvidesPreset) "PassphraseLegacy1" else null
        )
        host.dispatchInternal(GroupHybridInternalEvent.Start(GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID)))
        val hostOp = hostEffects.filterIsInstance<GroupHybridEffect.CreateHybridRoom>().single().operation
        host.dispatchInternal(GroupHybridInternalEvent.Created(hostOp, CloudAdmittedSession(roomId, "sess-host", 3_650_000L, 1L, local, null)))
        val hostAttempt = if (hostProvidesPreset) hostEffects.filterIsInstance<GroupHybridEffect.StartLocalHostDirect>().single().attempt
            else hostEffects.filterIsInstance<GroupHybridEffect.StartLocalHost>().single().attempt
        host.dispatchInternal(GroupHybridInternalEvent.LocalHostReady(1L, hostAttempt))
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)
            )
        )
        val op = effects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op,
                CloudJoinSubmission.Admitted(
                    CloudAdmittedSession(roomId, "sess-peer", 3_650_000L, 1L, local, null)
                )
            )
        )
        val search = effects.filterIsInstance<GroupHybridEffect.StartLocalSearch>().single()
        val match = GroupBootstrapMatch(GroupDescriptor(GroupRoomKey(roomId, hostRun), GroupAuthEndpoint(hostId, hostRun), "房主"),
            GroupNetworkDescriptor(GroupWifiCredentials("DIRECT-test", "12345678"), "192.168.49.1", 8899))
        val oldHost = GroupAuthEndpoint(hostId, rogueRun)
        val stale = GroupBootstrapMatch(GroupDescriptor(GroupRoomKey(roomId, rogueRun), oldHost, "旧房主"), match.network)
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalFound(1L, search.attempt, listOf(stale, match)))
        val join = effects.filterIsInstance<GroupHybridEffect.JoinLocalNetwork>().single()
        assertEquals(match, join.match)
        val oldChannel = UUID.randomUUID()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkReady(1L, join.attempt))
        val auth = effects.filterIsInstance<GroupHybridEffect.ConnectLocalControl>().single().context
        val staleChannel = UUID.randomUUID()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, staleChannel, auth.copy(room = stale.descriptor.room, host = oldHost)))
        assertTrue(effects.contains(GroupHybridEffect.CloseLocalChannel(1L, staleChannel)))
        fun completeHandshake(channel: UUID, context: GroupAuthContext) {
            orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, channel, context))
            host.dispatchInternal(GroupHybridInternalEvent.LocalAuthenticated(1L, channel, context))
            val request = effects.filterIsInstance<GroupHybridEffect.SendLocal>().last { it.channel == channel }.build(1L)
            assertTrue(request is GroupControl.Join)
            host.dispatchInternal(GroupHybridInternalEvent.LocalControl(1L, channel, request))
            assertTrue(hostEffects.any { it is GroupHybridEffect.AdmitLocalChannel && it.channel == channel })
            val roster = hostEffects.filterIsInstance<GroupHybridEffect.SendLocal>().last { it.channel == channel }.build(1L)
            assertTrue(roster is GroupControl.Roster)
            orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalControl(1L, channel, roster))
        }
        completeHandshake(oldChannel, auth)
        host.dispatchInternal(GroupHybridInternalEvent.LocalChannelClosed(1L, oldChannel))
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkLost(1L, join.attempt))
        assertEquals(match, effects.filterIsInstance<GroupHybridEffect.JoinLocalNetwork>().single().match)
        assertEquals(oldChannel, effects.filterIsInstance<GroupHybridEffect.CloseLocalChannel>().single().channel)
        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalChannelClosed(1L, oldChannel))
        now += 12_001
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue(effects.none { it is GroupHybridEffect.JoinLocalNetwork || it is GroupHybridEffect.StartLocalSearch })
        if (cachedFailureCallback) {
            orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkFailed(1L, join.attempt, "凭据已变更"))
        } else {
            now += 7_998
            orchestrator.dispatch(GroupSessionEvent.Tick)
            host.dispatch(GroupSessionEvent.Tick)
            assertTrue(effects.none { it is GroupHybridEffect.StartLocalSearch || it is GroupHybridEffect.SwitchTransport })
            now += 1
            orchestrator.dispatch(GroupSessionEvent.Tick)
        }
        val retry = effects.filterIsInstance<GroupHybridEffect.StartLocalSearch>().single()
        assertEquals("123456", retry.code.digits)
        assertNotEquals(join.attempt, retry.attempt)
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkReady(1L, join.attempt))
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkFailed(1L, join.attempt, "旧网络的延迟回调"))
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalFound(1L, join.attempt, listOf(match)))
        assertTrue(effects.none { it is GroupHybridEffect.ConnectLocalControl || it is GroupHybridEffect.JoinLocalNetwork })
        if (rescanTimesOut) {
            now += 19_999
            host.dispatch(GroupSessionEvent.Tick)
            orchestrator.dispatch(GroupSessionEvent.Tick)
            assertTrue((hostEffects + effects).none { it is GroupHybridEffect.SwitchTransport })
            now += 1
            orchestrator.dispatch(GroupSessionEvent.Tick)
            assertEquals(GroupTransport.CLOUD, effects.filterIsInstance<GroupHybridEffect.SwitchTransport>().single().target)
            assertEquals(1, effects.filterIsInstance<GroupHybridEffect.StartLocalSearch>().size)
            return
        }
        now += 12_000
        orchestrator.dispatch(GroupSessionEvent.Tick)
        host.dispatch(GroupSessionEvent.Tick)
        assertTrue(hostEffects.none { it is GroupHybridEffect.SwitchTransport })
        assertTrue(effects.none { it is GroupHybridEffect.SwitchTransport })
        assertEquals(1, effects.filterIsInstance<GroupHybridEffect.StartLocalSearch>().size)
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalFound(1L, retry.attempt, listOf(match)))
        assertEquals(match, effects.filterIsInstance<GroupHybridEffect.JoinLocalNetwork>().single().match)
        assertTrue(effects.none { it is GroupHybridEffect.JoinLocalDirect })
        val newChannel = UUID.randomUUID()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkReady(1L, retry.attempt))
        val newAuth = effects.filterIsInstance<GroupHybridEffect.ConnectLocalControl>().single().context
        completeHandshake(newChannel, newAuth)
        now += 45_000
        host.dispatch(GroupSessionEvent.Tick)
        orchestrator.dispatch(GroupSessionEvent.Tick)
        assertTrue((hostEffects + effects).none { it is GroupHybridEffect.SwitchTransport })
        host.dispatchInternal(GroupHybridInternalEvent.LocalChannelClosed(1L, newChannel))
        now += 43_000
        host.dispatch(GroupSessionEvent.Tick)
        assertEquals("LOCAL_LINK_LOST", hostEffects.filterIsInstance<GroupHybridEffect.SwitchTransport>().single().reason)
    }

    @Test
    fun memberLocalRescueDropsStaleFrequencyOnSecondDirectAttempt() {
        var now = 20_000L
        val effects = mutableListOf<GroupHybridEffect>()
        val orchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { now },
            assertWriter = {},
            effects = { effects += it },
            diagnostics = GroupDiagnostics {}
        )
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val localWithFreq = hybridSnapshot(
            revision = 1L,
            activeTransport = GroupTransport.LOCAL,
            epoch = 1L,
            reason = "INITIAL",
            members = members,
            localCode = "123456",
            rtcRoomId = null,
            localP2pSsid = "DIRECT-mc-freq0001",
            localP2pPassphrase = "PassphraseFreq11",
            localHostAddress = "192.168.49.1",
            localFrequencyMhz = 2462
        )
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)
            )
        )
        val op = effects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        effects.clear()
        orchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op,
                CloudJoinSubmission.Admitted(
                    CloudAdmittedSession(roomId, "sess-peer", now + 3_600_000L, 1L, localWithFreq, null)
                )
            )
        )
        val firstJoin = effects.filterIsInstance<GroupHybridEffect.JoinLocalDirect>().single()
        assertEquals(2462, firstJoin.preset.frequencyMhz)
        assertTrue(firstJoin.preset.prefer2GhzBand)

        effects.clear()
        orchestrator.dispatchInternal(GroupHybridInternalEvent.LocalNetworkFailed(1L, firstJoin.attempt, "stale channel"))
        now += 3_000L
        orchestrator.dispatch(GroupSessionEvent.Tick)
        val retryJoin = effects.filterIsInstance<GroupHybridEffect.JoinLocalDirect>().single()
        assertNull("Retry attempt 2+ must clear cached frequencyMhz to scan full 2.4GHz band", retryJoin.preset.frequencyMhz)
        assertTrue(retryJoin.preset.prefer2GhzBand)
    }

    @Test
    fun enterAdmittedSessionUsesWallClockForInitialRtcValidity() {
        val monotonicNow = 10_000L
        val wallNow = 1_760_000_000_000L
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val cloudSnapshot = hybridSnapshot(
            revision = 1L,
            activeTransport = GroupTransport.CLOUD,
            epoch = 2L,
            reason = "LOCAL_LINK_LOST",
            members = members,
            localCode = null,
            rtcRoomId = "mcr-r5_e2_pub",
            cooldownUntilMs = wallNow + 15_000L
        )

        // Expired relative to wallNow (even though >> monotonicNow) MUST be rejected.
        val expiredEffects = mutableListOf<GroupHybridEffect>()
        val expiredOrchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { monotonicNow },
            wallNowMs = { wallNow },
            assertWriter = {},
            effects = { expiredEffects += it },
            diagnostics = GroupDiagnostics {}
        )
        expiredOrchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)
            )
        )
        val op1 = expiredEffects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        expiredEffects.clear()
        val expiredToken = CloudRtcToken("app-1", "mcr-r5_e2_pub", peerId, "tok-exp", wallNow - 1_000L)
        expiredOrchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op1,
                CloudJoinSubmission.Admitted(
                    CloudAdmittedSession(roomId, "sess-1", wallNow + 3_600_000L, 1L, cloudSnapshot, expiredToken)
                )
            )
        )
        assertEquals(GroupPhase.IDLE, expiredOrchestrator.snapshot.phase)
        assertTrue(expiredEffects.any { it is GroupHybridEffect.LeaveRoom })
        assertTrue(expiredEffects.none { it is GroupHybridEffect.JoinRtc })

        // Valid relative to wallNow MUST join CLOUD RTC immediately.
        val validEffects = mutableListOf<GroupHybridEffect>()
        val validOrchestrator = GroupHybridSessionOrchestrator(
            endpoint = GroupAuthEndpoint(peerId, peerRun),
            nickname = "队员",
            nowMs = { monotonicNow },
            wallNowMs = { wallNow },
            assertWriter = {},
            effects = { validEffects += it },
            diagnostics = GroupDiagnostics {}
        )
        validOrchestrator.dispatchInternal(
            GroupHybridInternalEvent.Start(
                GroupCloudStartRequest.Join("https://cloud.test", GroupCloudInviteCode("123456"), GroupMode.HYBRID)
            )
        )
        val op2 = validEffects.filterIsInstance<GroupHybridEffect.SubmitJoin>().single().operation
        validEffects.clear()
        val validToken = CloudRtcToken("app-1", "mcr-r5_e2_pub", peerId, "tok-ok", wallNow + 600_000L)
        validOrchestrator.dispatchInternal(
            GroupHybridInternalEvent.JoinSubmitted(
                op2,
                CloudJoinSubmission.Admitted(
                    CloudAdmittedSession(roomId, "sess-2", wallNow + 3_600_000L, 1L, cloudSnapshot, validToken)
                )
            )
        )
        assertEquals(GroupPhase.IN_ROOM, validOrchestrator.snapshot.phase)
        assertEquals(1, validEffects.filterIsInstance<GroupHybridEffect.JoinRtc>().size)
    }
}
