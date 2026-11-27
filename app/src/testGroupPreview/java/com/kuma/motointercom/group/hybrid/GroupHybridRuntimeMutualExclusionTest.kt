package com.kuma.motointercom.group.hybrid

import android.content.Context
import android.os.Looper
import com.kuma.motointercom.AudioControlSettings
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupHybridRuntimeMutualExclusionTest {
    private fun resetOwnershipForTestIsolation() {
        val field = GroupRuntimeOwnership::class.java.getDeclaredField("owner").apply { isAccessible = true }
        field.set(GroupRuntimeOwnership, null)
    }

    @Test
    fun rapidLocalCloudFlipFlopAndDeferredLocalAudioDisposeNeverAllowsDualAudio() {
        val app = RuntimeEnvironment.getApplication()
        val hostId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
        val hostRun = UUID.fromString("11111111-1111-4111-8111-111111111112").toString()
        val peerId = UUID.fromString("22222222-2222-4222-8222-222222222221").toString()
        val peerRun = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
        val roomId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()
        val roomEpoch = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString()
        val now = System.currentTimeMillis()
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        fun makeSnap(rev: Long, transport: GroupTransport, epoch: Long): GroupCloudSnapshotPayload = GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(
                key = GroupRoomKey(roomId, roomEpoch),
                revision = rev,
                ownerId = hostId,
                ended = false,
                members = members,
                maxMembers = 4,
                mode = GroupMode.HYBRID,
                hostDeviceId = if (transport == GroupTransport.LOCAL) hostId else null,
                riderIncludesFormation = true
            ),
            names = mapOf(hostId to "队长", peerId to "队员"),
            inviteCode = "888800",
            inviteExpiresAtMs = now + 600_000L,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null,
            rtcRoomId = "mcr-hyb_pub",
            hybridTransport = GroupHybridTransportPayload(
                active = transport,
                epoch = epoch,
                reason = if (transport == GroupTransport.LOCAL) "INITIAL_LOCAL" else "LOCAL_LINK_LOST",
                localHostDeviceId = hostId,
                localRoomInstanceId = roomId,
                localCode = if (transport == GroupTransport.LOCAL) "123456" else null,
                changedAtMs = now,
                cooldownUntilMs = now + 60_000L
            )
        )

        val activeLocalAudioCount = AtomicInteger(0)
        val activeCloudRtcCount = AtomicInteger(0)
        var maxConcurrent = 0
        fun checkNoDualAudio() {
            val total = activeLocalAudioCount.get() + activeCloudRtcCount.get()
            if (activeLocalAudioCount.get() > 0 && activeCloudRtcCount.get() > 0) {
                fail("Dual audio detected: local=${activeLocalAudioCount.get()}, cloud=${activeCloudRtcCount.get()}")
            }
            maxConcurrent = maxOf(maxConcurrent, total)
        }

        var pendingAudioDispose: (() -> Unit)? = null
        var latestLocalDispatch: ((GroupSessionEvent) -> Unit)? = null
        var latestLocalHostAttempt: UUID? = null
        var latestRtcCallback: ((GroupRtcEvent) -> Unit)? = null
        var rtcJoinCalls = 0
        var localHostStarts = 0
        var released = false

        val idleApi = object : GroupCloudApi {
            override fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) = error("unused")
            override fun createHybridRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) =
                CloudAdmittedSession(roomId, "tok", now + 3_600_000L, 1L, makeSnap(1L, GroupTransport.LOCAL, 1L), null)
            override fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String) = error("unused")
            override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int) = error("unused")
            override fun cancelJoinRequest(requestId: String, requestSecret: String) = Unit
            override fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rotateInvite(token: String, roomId: String, expectedRevision: Long) = error("unused")
            override fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long) = error("unused")
            override fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long) = error("unused")
            override fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun leaveRoom(token: String, roomId: String) = true
            override fun endRoom(token: String, roomId: String, expectedRevision: Long) = Unit
            override fun resumeMember(token: String, roomId: String) = error("unused")
            override fun refreshRtcToken(token: String, roomId: String) = CloudRtcToken("app", "mcr-hyb_pub", hostId, "001tok2", now + 600_000L)
            override fun updateAudioAvailability(token: String, roomId: String, available: Boolean) = makeSnap(1L, GroupTransport.LOCAL, 1L)
            override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
                Thread.sleep(60_000L)
                return CloudEventBatch(false, afterSeq, null, emptyList())
            }
            override fun ackEvents(token: String, roomId: String, sequence: Long) = Unit
            override fun reportLocalStatus(
                token: String,
                roomId: String,
                transportEpoch: Long,
                localReady: Boolean,
                localReachable: Boolean,
                localProbeActive: Boolean,
                localRssiDbm: Int?,
                localControlReady: Boolean,
                localHostAddress: String?,
                localFrequencyMhz: Int?,
                preconnectFailed: Boolean
            ) =
                CloudLocalStatusResult(false, makeSnap(1L, GroupTransport.LOCAL, transportEpoch))
            override fun close() = Unit
        }

        val runtime = GroupHybridRuntime(
            context = app,
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            request = GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID),
            initialRoute = AudioRouteSelection.EARPIECE,
            initialControls = AudioControlSettings(),
            onSnapshot = {},
            onAudioLabel = {},
            onReleased = { released = true },
            pollApiFactory = { idleApi },
            actionApiFactory = { idleApi },
            localNetworkFactory = { _, dispatch ->
                latestLocalDispatch = dispatch
                object : GroupNetworkEffects {
                    override fun search(effect: GroupSessionEffect.Search) = Unit
                    override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
                        localHostStarts += 1
                        latestLocalHostAttempt = attempt
                    }
                    override fun join(effect: GroupSessionEffect.JoinNetwork) = Unit
                    override fun connect(effect: GroupSessionEffect.ConnectControl) = Unit
                    override fun send(effect: GroupSessionEffect.Send) = Unit
                    override fun closeChannel(id: UUID) = Unit
                    override fun admit(id: UUID) = Unit
                    override fun stop(flush: Boolean, done: () -> Unit) {
                        done()
                    }
                }
            },
            localAudioFactory = { _, _, lifetime ->
                activeLocalAudioCount.incrementAndGet()
                checkNoDualAudio()
                object : GroupAudioEffects {
                    override fun open(lease: GroupMediaLease, offerer: Boolean) = checkNoDualAudio()
                    override fun close(lease: GroupMediaLease) = Unit
                    override fun signal(lease: GroupMediaLease, message: GroupMessage) = Unit
                    override fun mute(value: Boolean) = Unit
                    override fun block(peer: String, value: Boolean) = Unit
                    override fun selectRoute(value: AudioRouteSelection) = Unit
                    override fun vox(value: Boolean) = Unit
                    override fun poll() = Unit
                    override fun close() {
                        pendingAudioDispose = {
                            activeLocalAudioCount.decrementAndGet()
                            lifetime.disposed()
                        }
                    }
                }
            },
            rtcMediaFactory = object : GroupRtcMediaFactory {
                override fun create(context: Context, onEvent: (GroupRtcEvent) -> Unit, onRouteLabel: (String) -> Unit): GroupRtcMedia {
                    latestRtcCallback = onEvent
                    return object : GroupRtcMedia {
                        override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
                            rtcJoinCalls += 1
                            activeCloudRtcCount.incrementAndGet()
                            checkNoDualAudio()
                        }
                        override fun updateToken(token: CloudRtcToken) = true
                        override fun setLocalMuted(muted: Boolean) = Unit
                        override fun setAllowedRemoteUsers(userIds: Set<String>) = Unit
                        override fun selectRoute(route: AudioRouteSelection) = Unit
                        override fun close() {
                            activeCloudRtcCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                        }
                    }
                }
            },
            proximityProbeFactory = object : GroupLocalProximityProbeFactory {
                override fun create(context: Context): GroupLocalProximityProbe = object : GroupLocalProximityProbe {
                    override fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit) = Unit
                    override fun startScan(roomInstanceId: String, targetWifiSsid: String?, onReport: (GroupProximityReport) -> Unit) = Unit
                    override fun close() = Unit
                }
            },
            acquireKeepAlive = { Closeable {} }
        )

        try {
            runtime.start(null)
            val op = runtime.snapshot.operation!!
            runtime.writer.dispatchInternal(
                GroupHybridInternalEvent.Created(
                    op,
                    CloudAdmittedSession(
                        roomId,
                        "tok",
                        now + 3_600_000L,
                        1L,
                        makeSnap(1L, GroupTransport.LOCAL, 1L),
                        null
                    )
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, localHostStarts)

            // Peer connects locally and opens local GroupAudio.
            latestLocalDispatch!!.invoke(GroupSessionEvent.HostReady(latestLocalHostAttempt!!))
            val peerChannel = UUID.randomUUID()
            val peerContext = GroupAuthContext(
                GroupRoomKey(roomId, hostRun),
                GroupAuthEndpoint(hostId, hostRun),
                GroupAuthEndpoint(peerId, peerRun),
                UUID.randomUUID().toString()
            )
            latestLocalDispatch!!.invoke(GroupSessionEvent.Authenticated(op, peerChannel, peerContext, now))
            latestLocalDispatch!!.invoke(
                GroupSessionEvent.Control(peerChannel, GroupControl.Join("队员"))
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, activeLocalAudioCount.get())
            assertEquals(0, activeCloudRtcCount.get())

            // Switch to CLOUD while local GroupAudio disposal is STILL pending async!
            runtime.writer.dispatchInternal(
                GroupHybridInternalEvent.SnapshotApplied(
                    op,
                    makeSnap(2L, GroupTransport.CLOUD, 2L),
                    rtc = CloudRtcToken("app", "mcr-hyb_pub", hostId, "001tok", now + 600_000L)
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, rtcJoinCalls)
            assertEquals(0, activeCloudRtcCount.get())
            assertNotNull(pendingAudioDispose)

            // Now local GroupAudio native engine finishes disposal -> Cloud RTC joins!
            pendingAudioDispose!!.invoke()
            pendingAudioDispose = null
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, activeLocalAudioCount.get())
            assertEquals(1, rtcJoinCalls)
            assertEquals(1, activeCloudRtcCount.get())
            val staleRtcCallback = latestRtcCallback!!
            staleRtcCallback(GroupRtcEvent.Joined)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(runtime.snapshot.voiceReady)

            // Switch back to LOCAL: stale Joined queued from disposed RTC must NEVER resurrect cloud audio!
            runtime.writer.dispatchInternal(GroupHybridInternalEvent.SnapshotApplied(op, makeSnap(3L, GroupTransport.LOCAL, 3L)))
            staleRtcCallback(GroupRtcEvent.Joined)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, activeCloudRtcCount.get())
            assertEquals(2, localHostStarts)
            assertEquals(HybridMediaPhase.LOCAL_ACTIVE, runtime.writer.currentMediaPhase)
            assertEquals(1, maxConcurrent)
        } finally {
            runtime.writer.dispatchInternal(GroupHybridInternalEvent.EndCompleted(runtime.snapshot.operation ?: UUID.randomUUID()))
            pendingAudioDispose?.invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(released)
            assertFalse(GroupRuntimeOwnership.hasOwner())
        }
    }

    @Test
    fun cloudCloseFailureBlocksLocalStartAndFailsClosed() {
        val app = RuntimeEnvironment.getApplication()
        val hostId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
        val hostRun = UUID.fromString("11111111-1111-4111-8111-111111111112").toString()
        val peerId = UUID.fromString("22222222-2222-4222-8222-222222222221").toString()
        val peerRun = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
        val roomId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()
        val roomEpoch = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString()
        val now = System.currentTimeMillis()
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        fun makeSnap(rev: Long, transport: GroupTransport, epoch: Long): GroupCloudSnapshotPayload = GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(
                key = GroupRoomKey(roomId, roomEpoch),
                revision = rev,
                ownerId = hostId,
                ended = false,
                members = members,
                maxMembers = 4,
                mode = GroupMode.HYBRID,
                hostDeviceId = if (transport == GroupTransport.LOCAL) hostId else null,
                riderIncludesFormation = true
            ),
            names = mapOf(hostId to "队长", peerId to "队员"),
            inviteCode = "888800",
            inviteExpiresAtMs = now + 600_000L,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null,
            rtcRoomId = if (transport == GroupTransport.CLOUD) "mcr-hyb_e${epoch}_pub" else null,
            hybridTransport = GroupHybridTransportPayload(
                active = transport,
                epoch = epoch,
                reason = if (transport == GroupTransport.LOCAL) "LOCAL_RECOVERED" else "MANUAL_CLOUD",
                localHostDeviceId = hostId,
                localRoomInstanceId = roomId,
                localCode = if (transport == GroupTransport.LOCAL) "123456" else null,
                changedAtMs = now,
                cooldownUntilMs = now + 60_000L
            )
        )

        var localHostStarts = 0
        var localAudioOpens = 0
        var rtcJoinCalls = 0
        var released = false

        val idleApi = object : GroupCloudApi {
            override fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) = error("unused")
            override fun createHybridRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) =
                CloudAdmittedSession(roomId, "tok", now + 3_600_000L, 1L, makeSnap(1L, GroupTransport.CLOUD, 2L), CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L))
            override fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String) = error("unused")
            override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int) = error("unused")
            override fun cancelJoinRequest(requestId: String, requestSecret: String) = Unit
            override fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rotateInvite(token: String, roomId: String, expectedRevision: Long) = error("unused")
            override fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long) = error("unused")
            override fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long) = error("unused")
            override fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun leaveRoom(token: String, roomId: String) = true
            override fun endRoom(token: String, roomId: String, expectedRevision: Long) = Unit
            override fun resumeMember(token: String, roomId: String) = error("unused")
            override fun refreshRtcToken(token: String, roomId: String) = CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L)
            override fun updateAudioAvailability(token: String, roomId: String, available: Boolean) = makeSnap(1L, GroupTransport.CLOUD, 2L)
            override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
                Thread.sleep(60_000L)
                return CloudEventBatch(false, afterSeq, null, emptyList())
            }
            override fun ackEvents(token: String, roomId: String, sequence: Long) = Unit
            override fun close() = Unit
        }

        val runtime = GroupHybridRuntime(
            context = app,
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            request = GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID),
            initialRoute = AudioRouteSelection.EARPIECE,
            initialControls = AudioControlSettings(),
            onSnapshot = {},
            onAudioLabel = {},
            onReleased = { released = true },
            pollApiFactory = { idleApi },
            actionApiFactory = { idleApi },
            localNetworkFactory = { _, _ ->
                object : GroupNetworkEffects {
                    override fun search(effect: GroupSessionEffect.Search) = Unit
                    override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) { localHostStarts += 1 }
                    override fun join(effect: GroupSessionEffect.JoinNetwork) = Unit
                    override fun connect(effect: GroupSessionEffect.ConnectControl) = Unit
                    override fun send(effect: GroupSessionEffect.Send) = Unit
                    override fun closeChannel(id: UUID) = Unit
                    override fun admit(id: UUID) = Unit
                    override fun stop(flush: Boolean, done: () -> Unit) = done()
                }
            },
            localAudioFactory = { _, _, _ ->
                localAudioOpens += 1
                error("Local GroupAudio must NEVER be opened when Cloud RTC close fails")
            },
            rtcMediaFactory = object : GroupRtcMediaFactory {
                override fun create(context: Context, onEvent: (GroupRtcEvent) -> Unit, onRouteLabel: (String) -> Unit): GroupRtcMedia =
                    object : GroupRtcMedia {
                        override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
                            rtcJoinCalls += 1
                        }
                        override fun updateToken(token: CloudRtcToken) = true
                        override fun setLocalMuted(muted: Boolean) = Unit
                        override fun setAllowedRemoteUsers(userIds: Set<String>) = Unit
                        override fun selectRoute(route: AudioRouteSelection) = Unit
                        override fun close() {
                            throw IllegalStateException("RTCEngine.destroyRTCEngine native failure")
                        }
                    }
            },
            proximityProbeFactory = object : GroupLocalProximityProbeFactory {
                override fun create(context: Context): GroupLocalProximityProbe = object : GroupLocalProximityProbe {
                    override fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit) = Unit
                    override fun startScan(roomInstanceId: String, targetWifiSsid: String?, onReport: (GroupProximityReport) -> Unit) = Unit
                    override fun close() = Unit
                }
            },
            acquireKeepAlive = { Closeable {} }
        )

        try {
            runtime.start(null)
            val op = runtime.snapshot.operation!!
            runtime.writer.dispatchInternal(
                GroupHybridInternalEvent.Created(
                    op,
                    CloudAdmittedSession(
                        roomId,
                        "tok",
                        now + 3_600_000L,
                        1L,
                        makeSnap(1L, GroupTransport.CLOUD, 2L),
                        CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L)
                    )
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, rtcJoinCalls)

            // Switch CLOUD -> LOCAL while fake VolcGroupRtcMedia.close() throws!
            runtime.writer.dispatchInternal(GroupHybridInternalEvent.SnapshotApplied(op, makeSnap(2L, GroupTransport.LOCAL, 3L)))
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, localHostStarts)
            assertEquals(0, localAudioOpens)
            assertEquals(HybridMediaPhase.BARRIER_FAILED, runtime.writer.currentMediaPhase)
            assertEquals(GroupPhase.IDLE, runtime.snapshot.phase)
            assertEquals(GroupTerminalReason.FAILED, runtime.snapshot.terminalReason)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertTrue(released)
            assertTrue(GroupRuntimeOwnership.hasOwner())
        } finally {
            resetOwnershipForTestIsolation()
        }
    }

    @Test
    fun localAsyncAndSyncDisposeFailuresBlockCloudRtcStartAndFailClosed() {
        for (throwSynchronously in listOf(false, true)) {
            val app = RuntimeEnvironment.getApplication()
            val hostId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
            val hostRun = UUID.fromString("11111111-1111-4111-8111-111111111112").toString()
            val peerId = UUID.fromString("22222222-2222-4222-8222-222222222221").toString()
            val peerRun = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
            val roomId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()
            val roomEpoch = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString()
            val now = System.currentTimeMillis()
            val members = listOf(
                GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
                GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
            )
            fun makeSnap(rev: Long, transport: GroupTransport, epoch: Long): GroupCloudSnapshotPayload = GroupCloudSnapshotPayload(
                view = GroupCloudRoomView(
                    key = GroupRoomKey(roomId, roomEpoch),
                    revision = rev,
                    ownerId = hostId,
                    ended = false,
                    members = members,
                    maxMembers = 4,
                    mode = GroupMode.HYBRID,
                    hostDeviceId = if (transport == GroupTransport.LOCAL) hostId else null,
                    riderIncludesFormation = true
                ),
                names = mapOf(hostId to "队长", peerId to "队员"),
                inviteCode = "888800",
                inviteExpiresAtMs = now + 600_000L,
                requireApproval = false,
                pendingRequests = emptyList(),
                removedIds = emptySet(),
                endedReason = null,
                rtcRoomId = if (transport == GroupTransport.CLOUD) "mcr-hyb_e${epoch}_pub" else null,
                hybridTransport = GroupHybridTransportPayload(
                    active = transport,
                    epoch = epoch,
                    reason = if (transport == GroupTransport.LOCAL) "INITIAL_LOCAL" else "LOCAL_LINK_LOST",
                    localHostDeviceId = hostId,
                    localRoomInstanceId = roomId,
                    localCode = if (transport == GroupTransport.LOCAL) "123456" else null,
                    changedAtMs = now,
                    cooldownUntilMs = now + 60_000L
                )
            )

            var latestLocalDispatch: ((GroupSessionEvent) -> Unit)? = null
            var latestLocalHostAttempt: UUID? = null
            var pendingDisposeFailure: (() -> Unit)? = null
            var rtcJoinCalls = 0
            var released = false

            val idleApi = object : GroupCloudApi {
                override fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) = error("unused")
                override fun createHybridRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) =
                    CloudAdmittedSession(roomId, "tok", now + 3_600_000L, 1L, makeSnap(1L, GroupTransport.LOCAL, 1L), null)
                override fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String) = error("unused")
                override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int) = error("unused")
                override fun cancelJoinRequest(requestId: String, requestSecret: String) = Unit
                override fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
                override fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
                override fun rotateInvite(token: String, roomId: String, expectedRevision: Long) = error("unused")
                override fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long) = error("unused")
                override fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long) = error("unused")
                override fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
                override fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
                override fun leaveRoom(token: String, roomId: String) = true
                override fun endRoom(token: String, roomId: String, expectedRevision: Long) = Unit
                override fun resumeMember(token: String, roomId: String) = error("unused")
                override fun refreshRtcToken(token: String, roomId: String) = CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L)
                override fun updateAudioAvailability(token: String, roomId: String, available: Boolean) = makeSnap(1L, GroupTransport.LOCAL, 1L)
                override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
                    Thread.sleep(60_000L)
                    return CloudEventBatch(false, afterSeq, null, emptyList())
                }
                override fun ackEvents(token: String, roomId: String, sequence: Long) = Unit
                override fun close() = Unit
            }

            val runtime = GroupHybridRuntime(
                context = app,
                endpoint = GroupAuthEndpoint(hostId, hostRun),
                nickname = "队长",
                request = GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID),
                initialRoute = AudioRouteSelection.EARPIECE,
                initialControls = AudioControlSettings(),
                onSnapshot = {},
                onAudioLabel = {},
                onReleased = { released = true },
                pollApiFactory = { idleApi },
                actionApiFactory = { idleApi },
                localNetworkFactory = { _, dispatch ->
                    latestLocalDispatch = dispatch
                    object : GroupNetworkEffects {
                        override fun search(effect: GroupSessionEffect.Search) = Unit
                        override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
                            latestLocalHostAttempt = attempt
                        }
                        override fun join(effect: GroupSessionEffect.JoinNetwork) = Unit
                        override fun connect(effect: GroupSessionEffect.ConnectControl) = Unit
                        override fun send(effect: GroupSessionEffect.Send) = Unit
                        override fun closeChannel(id: UUID) = Unit
                        override fun admit(id: UUID) = Unit
                        override fun stop(flush: Boolean, done: () -> Unit) = done()
                    }
                },
                localAudioFactory = { _, _, lifetime ->
                    object : GroupAudioEffects {
                        override fun open(lease: GroupMediaLease, offerer: Boolean) = Unit
                        override fun close(lease: GroupMediaLease) = Unit
                        override fun signal(lease: GroupMediaLease, message: GroupMessage) = Unit
                        override fun mute(value: Boolean) = Unit
                        override fun block(peer: String, value: Boolean) = Unit
                        override fun selectRoute(value: AudioRouteSelection) = Unit
                        override fun vox(value: Boolean) = Unit
                        override fun poll() = Unit
                        override fun close() {
                            if (throwSynchronously) {
                                throw IllegalStateException("sync GroupAudio close failure")
                            }
                            pendingDisposeFailure = {
                                lifetime.disposeFailed(IllegalStateException("async WebRTC ADM release failure"))
                            }
                        }
                    }
                },
                rtcMediaFactory = object : GroupRtcMediaFactory {
                    override fun create(context: Context, onEvent: (GroupRtcEvent) -> Unit, onRouteLabel: (String) -> Unit): GroupRtcMedia =
                        object : GroupRtcMedia {
                            override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
                                rtcJoinCalls += 1
                            }
                            override fun updateToken(token: CloudRtcToken) = true
                            override fun setLocalMuted(muted: Boolean) = Unit
                            override fun setAllowedRemoteUsers(userIds: Set<String>) = Unit
                            override fun selectRoute(route: AudioRouteSelection) = Unit
                            override fun close() = Unit
                        }
                },
                proximityProbeFactory = object : GroupLocalProximityProbeFactory {
                    override fun create(context: Context): GroupLocalProximityProbe = object : GroupLocalProximityProbe {
                        override fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit) = Unit
                        override fun startScan(roomInstanceId: String, targetWifiSsid: String?, onReport: (GroupProximityReport) -> Unit) = Unit
                        override fun close() = Unit
                    }
                },
                acquireKeepAlive = { Closeable {} }
            )

            try {
                runtime.start(null)
                val op = runtime.snapshot.operation!!
                runtime.writer.dispatchInternal(
                    GroupHybridInternalEvent.Created(
                        op,
                        CloudAdmittedSession(roomId, "tok", now + 3_600_000L, 1L, makeSnap(1L, GroupTransport.LOCAL, 1L), null)
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()
                latestLocalDispatch!!.invoke(GroupSessionEvent.HostReady(latestLocalHostAttempt!!))
                val peerChannel = UUID.randomUUID()
                val peerContext = GroupAuthContext(
                    GroupRoomKey(roomId, hostRun),
                    GroupAuthEndpoint(hostId, hostRun),
                    GroupAuthEndpoint(peerId, peerRun),
                    UUID.randomUUID().toString()
                )
                latestLocalDispatch!!.invoke(GroupSessionEvent.Authenticated(op, peerChannel, peerContext, now))
                latestLocalDispatch!!.invoke(GroupSessionEvent.Control(peerChannel, GroupControl.Join("队员")))
                shadowOf(Looper.getMainLooper()).idle()

                runtime.writer.dispatchInternal(
                    GroupHybridInternalEvent.SnapshotApplied(
                        op,
                        makeSnap(2L, GroupTransport.CLOUD, 2L),
                        rtc = CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L)
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()
                pendingDisposeFailure?.invoke()
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(0, rtcJoinCalls)
                assertEquals(HybridMediaPhase.BARRIER_FAILED, runtime.writer.currentMediaPhase)
                assertEquals(GroupPhase.IDLE, runtime.snapshot.phase)
                assertEquals(GroupTerminalReason.FAILED, runtime.snapshot.terminalReason)
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
                assertTrue(released)
                assertTrue(GroupRuntimeOwnership.hasOwner())
            } finally {
                resetOwnershipForTestIsolation()
            }
        }
    }

    @Test
    fun cloudPreconnectAndHandoverNeverCreatesDualAudioAndBlocksLocalAudioOnCloudDisposeFailure() {
        val app = RuntimeEnvironment.getApplication()
        val hostId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
        val hostRun = UUID.fromString("11111111-1111-4111-8111-111111111112").toString()
        val peerId = UUID.fromString("22222222-2222-4222-8222-222222222221").toString()
        val peerRun = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
        val roomId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()
        val roomEpoch = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString()
        val now = System.currentTimeMillis()
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        fun makeSnap(
            rev: Long,
            transport: GroupTransport,
            epoch: Long,
            stage: GroupHybridRecoveryStage = GroupHybridRecoveryStage.IDLE,
            handoverFrom: Long? = null
        ): GroupCloudSnapshotPayload = GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(
                key = GroupRoomKey(roomId, roomEpoch),
                revision = rev,
                ownerId = hostId,
                ended = false,
                members = members,
                maxMembers = 4,
                mode = GroupMode.HYBRID,
                hostDeviceId = if (transport == GroupTransport.LOCAL) hostId else null,
                riderIncludesFormation = true
            ),
            names = mapOf(hostId to "队长", peerId to "队员"),
            inviteCode = "888800",
            inviteExpiresAtMs = now + 600_000L,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null,
            rtcRoomId = if (transport == GroupTransport.CLOUD) "mcr-hyb_e${epoch}_pub" else null,
            hybridTransport = GroupHybridTransportPayload(
                active = transport,
                epoch = epoch,
                reason = if (transport == GroupTransport.LOCAL) "LOCAL_RECOVERED" else "LOCAL_LINK_LOST",
                localHostDeviceId = hostId,
                localRoomInstanceId = roomId,
                localCode = if (transport == GroupTransport.LOCAL || stage == GroupHybridRecoveryStage.PRECONNECTING) "654321" else null,
                changedAtMs = now,
                cooldownUntilMs = now - 1_000L,
                localP2pSsid = "DIRECT-mc-test1234",
                localP2pPassphrase = "PassphraseTest12",
                localHostAddress = "192.168.49.1",
                localFrequencyMhz = 2437,
                recoveryStage = stage,
                handoverFromEpoch = handoverFrom
            )
        )

        val activeLocalAudio = AtomicInteger(0)
        val activeCloudRtc = AtomicInteger(0)
        var preconnectHostStarts = 0
        var preconnectControlStarts = 0
        var preconnectStops = 0
        var localAudioOpens = 0
        var latestDispatch: ((GroupSessionEvent) -> Unit)? = null
        var latestHostAttempt: UUID? = null
        var rtcCloseThrows = false

        val stubApi = object : GroupCloudApi {
            override fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) = error("unused")
            override fun createHybridRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) =
                CloudAdmittedSession(
                    roomId,
                    "tok",
                    now + 3_600_000L,
                    1L,
                    makeSnap(1L, GroupTransport.CLOUD, 2L, stage = GroupHybridRecoveryStage.PROBING),
                    CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L)
                )
            override fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String) = error("unused")
            override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int) = error("unused")
            override fun cancelJoinRequest(requestId: String, requestSecret: String) = Unit
            override fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rotateInvite(token: String, roomId: String, expectedRevision: Long) = error("unused")
            override fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long) = error("unused")
            override fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long) = error("unused")
            override fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun leaveRoom(token: String, roomId: String) = false
            override fun endRoom(token: String, roomId: String, expectedRevision: Long) = Unit
            override fun resumeMember(token: String, roomId: String) = error("unused")
            override fun refreshRtcToken(token: String, roomId: String) = error("unused")
            override fun updateAudioAvailability(token: String, roomId: String, available: Boolean) = makeSnap(1L, GroupTransport.CLOUD, 2L)
            override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
                Thread.sleep(5_000)
                return CloudEventBatch(false, afterSeq, null, emptyList())
            }
            override fun ackEvents(token: String, roomId: String, sequence: Long) = Unit
            override fun reportLocalStatus(
                token: String,
                roomId: String,
                transportEpoch: Long,
                localReady: Boolean,
                localReachable: Boolean,
                localProbeActive: Boolean,
                localRssiDbm: Int?,
                localControlReady: Boolean,
                localHostAddress: String?,
                localFrequencyMhz: Int?,
                preconnectFailed: Boolean
            ) = CloudLocalStatusResult(false, makeSnap(1L, GroupTransport.CLOUD, 2L))
            override fun close() = Unit
        }

        fun runScenario(failCloudClose: Boolean) {
            resetOwnershipForTestIsolation()
            activeLocalAudio.set(0)
            activeCloudRtc.set(0)
            preconnectHostStarts = 0
            preconnectControlStarts = 0
            preconnectStops = 0
            localAudioOpens = 0
            rtcCloseThrows = failCloudClose
            var released = false

            val runtime = GroupHybridRuntime(
                context = app,
                endpoint = GroupAuthEndpoint(hostId, hostRun),
                nickname = "队长",
                request = GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID),
                initialRoute = AudioRouteSelection.EARPIECE,
                initialControls = AudioControlSettings(),
                onSnapshot = {},
                onAudioLabel = {},
                onReleased = { released = true },
                pollApiFactory = { stubApi },
                actionApiFactory = { stubApi },
                localNetworkFactory = { _, dispatch ->
                    latestDispatch = dispatch
                    object : GroupNetworkEffects {
                        override fun search(effect: GroupSessionEffect.Search) = Unit
                        override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) = Unit
                        override fun hostDirect(
                            attempt: UUID,
                            descriptor: GroupDescriptor,
                            code: GroupJoinCode?,
                            preset: com.kuma.motointercom.group.network.GroupWifiP2pPreset,
                            enableBleBootstrap: Boolean
                        ) {
                            preconnectHostStarts += 1
                            latestHostAttempt = attempt
                        }
                        override fun startDirectControlServer(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
                            preconnectControlStarts += 1
                            latestHostAttempt = attempt
                        }
                        override fun join(effect: GroupSessionEffect.JoinNetwork) = Unit
                        override fun connect(effect: GroupSessionEffect.ConnectControl) = Unit
                        override fun send(effect: GroupSessionEffect.Send) = Unit
                        override fun closeChannel(id: UUID) = Unit
                        override fun admit(id: UUID) = Unit
                        override fun stop(flush: Boolean, done: () -> Unit) {
                            preconnectStops += 1
                            done()
                        }
                    }
                },
                localAudioFactory = { _, _, lifetime ->
                    assertEquals("Cloud RTC MUST be closed before Local GroupAudio is created", 0, activeCloudRtc.get())
                    activeLocalAudio.incrementAndGet()
                    object : GroupAudioEffects {
                        override fun open(lease: GroupMediaLease, offerer: Boolean) {
                            localAudioOpens += 1
                        }
                        override fun close(lease: GroupMediaLease) = Unit
                        override fun signal(lease: GroupMediaLease, message: GroupMessage) = Unit
                        override fun mute(value: Boolean) = Unit
                        override fun block(peer: String, value: Boolean) = Unit
                        override fun selectRoute(value: AudioRouteSelection) = Unit
                        override fun vox(value: Boolean) = Unit
                        override fun poll() = Unit
                        override fun close() {
                            activeLocalAudio.decrementAndGet()
                            lifetime.disposed()
                        }
                    }
                },
                rtcMediaFactory = object : GroupRtcMediaFactory {
                    override fun create(context: Context, onEvent: (GroupRtcEvent) -> Unit, onRouteLabel: (String) -> Unit): GroupRtcMedia =
                        object : GroupRtcMedia {
                            override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
                                assertEquals("Local GroupAudio MUST be closed before Cloud RTC joins", 0, activeLocalAudio.get())
                                activeCloudRtc.incrementAndGet()
                                onEvent(GroupRtcEvent.Joined)
                            }
                            override fun updateToken(token: CloudRtcToken) = true
                            override fun setLocalMuted(muted: Boolean) = Unit
                            override fun setAllowedRemoteUsers(userIds: Set<String>) = Unit
                            override fun selectRoute(route: AudioRouteSelection) = Unit
                            override fun close() {
                                if (rtcCloseThrows) throw IllegalStateException("RTC destroy failed")
                                activeCloudRtc.decrementAndGet()
                            }
                        }
                },
                proximityProbeFactory = object : GroupLocalProximityProbeFactory {
                    override fun create(context: Context): GroupLocalProximityProbe = object : GroupLocalProximityProbe {
                        override fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit) = onStatus(true)
                        override fun startScan(roomInstanceId: String, targetWifiSsid: String?, onReport: (GroupProximityReport) -> Unit) = Unit
                        override fun close() = Unit
                    }
                },
                acquireKeepAlive = { Closeable {} }
            )

            try {
                runtime.start(null)
                val op = runtime.snapshot.operation!!
                runtime.writer.dispatchInternal(
                    GroupHybridInternalEvent.Created(
                        op,
                        CloudAdmittedSession(
                            roomId,
                            "tok",
                            now + 3_600_000L,
                            1L,
                            makeSnap(1L, GroupTransport.CLOUD, 2L, stage = GroupHybridRecoveryStage.PROBING),
                            CloudRtcToken("app", "mcr-hyb_e2_pub", hostId, "001tok", now + 600_000L)
                        )
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, activeCloudRtc.get())
                assertEquals(0, activeLocalAudio.get())
                assertEquals(1, preconnectHostStarts)

                latestDispatch!!.invoke(GroupSessionEvent.HostReady(latestHostAttempt!!, "192.168.49.1", 2437))
                shadowOf(Looper.getMainLooper()).idle()

                runtime.writer.dispatchInternal(
                    GroupHybridInternalEvent.SnapshotApplied(
                        op,
                        makeSnap(2L, GroupTransport.CLOUD, 2L, stage = GroupHybridRecoveryStage.PRECONNECTING)
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, preconnectControlStarts)
                latestDispatch!!.invoke(GroupSessionEvent.HostReady(latestHostAttempt!!, "192.168.49.1", 2437))
                val peerChannel = UUID.randomUUID()
                val peerContext = GroupAuthContext(
                    GroupRoomKey(roomId, hostRun),
                    GroupAuthEndpoint(hostId, hostRun),
                    GroupAuthEndpoint(peerId, peerRun),
                    UUID.randomUUID().toString()
                )
                latestDispatch!!.invoke(GroupSessionEvent.Authenticated(op, peerChannel, peerContext, now))
                latestDispatch!!.invoke(GroupSessionEvent.Control(peerChannel, GroupControl.Join("队员")))
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(1, activeCloudRtc.get())
                assertEquals(0, activeLocalAudio.get())
                assertEquals(0, localAudioOpens)

                runtime.writer.dispatchInternal(
                    GroupHybridInternalEvent.SnapshotApplied(
                        op,
                        makeSnap(3L, GroupTransport.LOCAL, 3L, handoverFrom = 2L)
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()

                if (!failCloudClose) {
                    assertEquals(0, activeCloudRtc.get())
                    assertEquals(1, activeLocalAudio.get())
                    assertTrue("Expected promoted preconnect to immediately open local WebRTC media", localAudioOpens >= 1)
                    assertEquals("Promoted preconnect network MUST NOT be torn down and recreated", 1, preconnectHostStarts)
                    assertEquals(0, preconnectStops)
                    runtime.close()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue("Service close must release a multi-member owner's local resources", released)
                    assertFalse(GroupRuntimeOwnership.hasOwner())
                    val reacquired = GroupRuntimeOwnership.acquire()
                    assertNotNull("Runtime ownership must be acquirable after service close", reacquired)
                    GroupRuntimeOwnership.release(reacquired!!)
                } else {
                    assertEquals(HybridMediaPhase.BARRIER_FAILED, runtime.writer.currentMediaPhase)
                    assertEquals(0, activeLocalAudio.get())
                    assertEquals(0, localAudioOpens)
                    assertTrue("Preconnect network MUST be stopped on barrier failure", preconnectStops >= 1)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
                    assertTrue(released)
                    assertTrue(GroupRuntimeOwnership.hasOwner())
                }
            } finally {
                resetOwnershipForTestIsolation()
            }
        }

        runScenario(failCloudClose = false)
        runScenario(failCloudClose = true)
    }

    @Test
    fun stoppingHybridRuntimeBeforeDeferredLocalAudioDisposeFailureStillReleasesServiceAndKeepAlive() {
        resetOwnershipForTestIsolation()
        val app = RuntimeEnvironment.getApplication()
        val hostId = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
        val hostRun = UUID.fromString("11111111-1111-4111-8111-111111111112").toString()
        val peerId = UUID.fromString("22222222-2222-4222-8222-222222222221").toString()
        val peerRun = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
        val roomId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()
        val roomEpoch = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString()
        val now = System.currentTimeMillis()
        val members = listOf(
            GroupMember(GroupMemberLease(hostId, hostRun, 1, 1), GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(GroupMemberLease(peerId, peerRun, 2, 1), GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val initialSnap = GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(
                key = GroupRoomKey(roomId, roomEpoch),
                revision = 1L,
                ownerId = hostId,
                ended = false,
                members = members,
                maxMembers = 4,
                mode = GroupMode.HYBRID,
                hostDeviceId = hostId,
                riderIncludesFormation = true
            ),
            names = mapOf(hostId to "队长", peerId to "队员"),
            inviteCode = "888811",
            inviteExpiresAtMs = now + 600_000L,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null,
            rtcRoomId = null,
            hybridTransport = GroupHybridTransportPayload(
                active = GroupTransport.LOCAL,
                epoch = 1L,
                reason = "INITIAL_LOCAL",
                localHostDeviceId = hostId,
                localRoomInstanceId = roomId,
                localCode = "123456",
                changedAtMs = now,
                cooldownUntilMs = 0L
            )
        )
        val stubApi = object : GroupCloudApi {
            override fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) = error("unused")
            override fun createHybridRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) =
                CloudAdmittedSession(roomId, "tok", now + 3_600_000L, 1L, initialSnap, null)
            override fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String) = error("unused")
            override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int) = error("unused")
            override fun cancelJoinRequest(requestId: String, requestSecret: String) = Unit
            override fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = error("unused")
            override fun rotateInvite(token: String, roomId: String, expectedRevision: Long) = error("unused")
            override fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long) = error("unused")
            override fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long) = error("unused")
            override fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = error("unused")
            override fun leaveRoom(token: String, roomId: String) = false
            override fun endRoom(token: String, roomId: String, expectedRevision: Long) = Unit
            override fun resumeMember(token: String, roomId: String) = error("unused")
            override fun refreshRtcToken(token: String, roomId: String) = error("unused")
            override fun updateAudioAvailability(token: String, roomId: String, available: Boolean) = initialSnap
            override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
                Thread.sleep(5_000)
                return CloudEventBatch(false, afterSeq, null, emptyList())
            }
            override fun ackEvents(token: String, roomId: String, sequence: Long) = Unit
            override fun reportLocalStatus(
                token: String,
                roomId: String,
                transportEpoch: Long,
                localReady: Boolean,
                localReachable: Boolean,
                localProbeActive: Boolean,
                localRssiDbm: Int?,
                localControlReady: Boolean,
                localHostAddress: String?,
                localFrequencyMhz: Int?,
                preconnectFailed: Boolean
            ) = CloudLocalStatusResult(false, initialSnap)
            override fun close() = Unit
        }

        var latestDispatch: ((GroupSessionEvent) -> Unit)? = null
        var latestHostAttempt: UUID? = null
        var deferredAudioLifetime: GroupAudioLifetime? = null
        var keepAliveClosed = false
        var released = false

        val runtime = GroupHybridRuntime(
            context = app,
            endpoint = GroupAuthEndpoint(hostId, hostRun),
            nickname = "队长",
            request = GroupCloudStartRequest.Create("https://cloud.test", false, GroupMode.HYBRID),
            initialRoute = AudioRouteSelection.EARPIECE,
            initialControls = AudioControlSettings(),
            onSnapshot = {},
            onAudioLabel = {},
            onReleased = { released = true },
            pollApiFactory = { stubApi },
            actionApiFactory = { stubApi },
            localNetworkFactory = { _, dispatch ->
                latestDispatch = dispatch
                object : GroupNetworkEffects {
                    override fun search(effect: GroupSessionEffect.Search) = Unit
                    override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) {
                        latestHostAttempt = attempt
                    }
                    override fun join(effect: GroupSessionEffect.JoinNetwork) = Unit
                    override fun connect(effect: GroupSessionEffect.ConnectControl) = Unit
                    override fun send(effect: GroupSessionEffect.Send) = Unit
                    override fun closeChannel(id: UUID) = Unit
                    override fun admit(id: UUID) = Unit
                    override fun stop(flush: Boolean, done: () -> Unit) = done()
                }
            },
            localAudioFactory = { _, _, lifetime ->
                deferredAudioLifetime = lifetime
                object : GroupAudioEffects {
                    override fun open(lease: GroupMediaLease, offerer: Boolean) = Unit
                    override fun close(lease: GroupMediaLease) = Unit
                    override fun signal(lease: GroupMediaLease, message: GroupMessage) = Unit
                    override fun mute(value: Boolean) = Unit
                    override fun block(peer: String, value: Boolean) = Unit
                    override fun selectRoute(value: AudioRouteSelection) = Unit
                    override fun vox(value: Boolean) = Unit
                    override fun poll() = Unit
                    override fun close() = Unit
                }
            },
            proximityProbeFactory = object : GroupLocalProximityProbeFactory {
                override fun create(context: Context): GroupLocalProximityProbe = object : GroupLocalProximityProbe {
                    override fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit) = Unit
                    override fun startScan(roomInstanceId: String, targetWifiSsid: String?, onReport: (GroupProximityReport) -> Unit) = Unit
                    override fun close() = Unit
                }
            },
            acquireKeepAlive = { Closeable { keepAliveClosed = true } }
        )

        try {
            runtime.start(null)
            val op = runtime.snapshot.operation!!
            runtime.writer.dispatchInternal(
                GroupHybridInternalEvent.Created(
                    op,
                    CloudAdmittedSession(roomId, "tok", now + 3_600_000L, 1L, initialSnap, null)
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            latestDispatch!!.invoke(GroupSessionEvent.HostReady(latestHostAttempt!!, "192.168.49.1", 2437))
            val peerChannel = UUID.randomUUID()
            val peerContext = GroupAuthContext(
                GroupRoomKey(roomId, hostRun),
                GroupAuthEndpoint(hostId, hostRun),
                GroupAuthEndpoint(peerId, peerRun),
                UUID.randomUUID().toString()
            )
            latestDispatch!!.invoke(GroupSessionEvent.Authenticated(op, peerChannel, peerContext, now))
            latestDispatch!!.invoke(GroupSessionEvent.Control(peerChannel, GroupControl.Join("队员")))
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("Expected local audio lease to be created", deferredAudioLifetime)

            runtime.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Must wait for deferred local audio disposal", released)
            assertFalse(keepAliveClosed)

            deferredAudioLifetime!!.disposeFailed(IllegalStateException("adm release failed"))
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue("Service release callback must complete when deferred disposal fails", released)
            assertTrue("KeepAlive must close when deferred disposal fails", keepAliveClosed)
            assertTrue("Runtime ownership must stay locked after native disposal failure", GroupRuntimeOwnership.hasOwner())
        } finally {
            resetOwnershipForTestIsolation()
        }
    }
}
