package com.kuma.motointercom.group.cloud

import android.content.Context
import android.os.Looper
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.group.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
class GroupCloudRuntimeRtcGenerationTest {
    @Config(sdk = [35])
    @Test
    fun queuedLateJoinedAfterConnectionLostIsDroppedByRuntimeGenerationGate() {
        withJoinedRuntime { runtime, media ->
            media.emit(GroupRtcEvent.ConnectionLost("网络断开"))
            media.emit(GroupRtcEvent.Joined)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(media.closed)
            assertNotEquals(GroupNetworkMediaState.JOINED, runtime.snapshot.networkMediaState)
            assertFalse(runtime.snapshot.voiceReady)
        }
    }

    @Config(sdk = [35])
    @Test
    fun nativeTokenRejectionRetriesOnTheSameRtcInstance() {
        var now = 10_000L
        withJoinedRuntime(nowMs = { now }) { runtime, media ->
            media.emit(GroupRtcEvent.Joined)
            media.acceptTokenUpdates = false
            media.emit(GroupRtcEvent.TokenWillExpire)
            waitForCondition { media.tokenUpdates == 1 }
            assertEquals(RTC_TOKEN_RETRY_MESSAGE, runtime.snapshot.message)
            media.acceptTokenUpdates = true
            now += 2_000L
            runtime.dispatch(GroupSessionEvent.Tick)
            waitForCondition { media.tokenUpdates == 2 }
            now += 3_000L
            runtime.dispatch(GroupSessionEvent.Tick)
            assertEquals(2, media.tokenUpdates)
            assertTrue(runtime.snapshot.voiceReady)
            assertFalse(media.closed)
        }
    }

    @Config(sdk = [35])
    @Test
    fun disposalFailureKeepsOwnershipOnBothConnectionLossAndExplicitClose() {
        for (loseConnection in listOf(true, false)) {
            var keepAliveClosed = false
            var releaseReported = false
            withJoinedRuntime(onKeepAliveClosed = { keepAliveClosed = true }, onReleased = { releaseReported = true }) { runtime, media ->
                media.failClose = true
                if (loseConnection) {
                    media.emit(GroupRtcEvent.ConnectionLost("网络断开"))
                    media.emit(GroupRtcEvent.Joined)
                } else {
                    runtime.close()
                }
                waitForCondition { keepAliveClosed }
                if (loseConnection) {
                    assertEquals(GroupPhase.IDLE, runtime.snapshot.phase)
                    assertFalse(runtime.snapshot.voiceReady)
                }
                assertTrue(media.closed)
                assertTrue(GroupRuntimeOwnership.hasOwner())
                assertNull(GroupRuntimeOwnership.acquire())
                assertTrue(releaseReported)
            }
        }
    }

    private fun withJoinedRuntime(
        nowMs: () -> Long = ::groupNowMs,
        onKeepAliveClosed: () -> Unit = {},
        onReleased: () -> Unit = {},
        verify: (GroupCloudRuntime, RecordingRtcMedia) -> Unit
    ) {
        val app = RuntimeEnvironment.getApplication()
        val endpoint = GroupAuthEndpoint("dev-a", "run-a")
        val roomKey = GroupRoomKey("room-gen-1", "epoch-1")
        val now = System.currentTimeMillis()
        val twoMembers = listOf(
            GroupMember(lease = GroupMemberLease("dev-a", "run-a", 1, 1), status = GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
            GroupMember(lease = GroupMemberLease("dev-b", "run-b", 1, 1), status = GroupMemberStatus.ADMITTED, role = GroupRole.MEMBER)
        )
        val payload = GroupCloudSnapshotPayload(
            view = GroupCloudRoomView(roomKey, 1L, "dev-a", false, twoMembers),
            names = mapOf("dev-a" to "队长", "dev-b" to "队员"),
            inviteCode = "123456",
            inviteExpiresAtMs = now + 600_000L,
            requireApproval = false,
            pendingRequests = emptyList(),
            removedIds = emptySet(),
            endedReason = null
        )
        val session = CloudAdmittedSession(
            roomId = roomKey.instanceId,
            sessionToken = "secret-session",
            tokenExpiresAtMs = now + 3_600_000L,
            latestSeq = 1L,
            snapshot = payload,
            rtc = CloudRtcToken("000000000000000000000001", "mcr-gen1", "dev-a", "001token", now + 600_000L)
        )

        val createdMedias = mutableListOf<RecordingRtcMedia>()
        val runtime = GroupCloudRuntime(
            context = app,
            endpoint = endpoint,
            nickname = "队长",
            request = GroupCloudStartRequest.Create("http://127.0.0.1:8443", requireApproval = false),
            initialRoute = AudioRouteSelection.EARPIECE,
            onSnapshot = {},
            onAudioLabel = {},
            onReleased = onReleased,
            pollApiFactory = { StubCloudApi(session) },
            actionApiFactory = { StubCloudApi(session) },
            rtcMediaFactory = object : GroupRtcMediaFactory {
                override fun create(
                    context: Context,
                    onEvent: (GroupRtcEvent) -> Unit,
                    onRouteLabel: (String) -> Unit
                ): GroupRtcMedia = RecordingRtcMedia(onEvent).also { createdMedias += it }
            },
            nowMs = nowMs,
            acquireKeepAlive = { Closeable { onKeepAliveClosed() } }
        )

        try {
            runtime.start(null)
            waitForCondition { createdMedias.size == 1 && createdMedias[0].joinedCalled }
            verify(runtime, createdMedias.single())
        } finally {
            runtime.close()
            shadowOf(Looper.getMainLooper()).idle()
            // A simulated native disposal failure retains this runtime's lease until process restart.
            val field = GroupCloudRuntime::class.java.getDeclaredField("ownership").apply { isAccessible = true }
            field.get(runtime)?.let(GroupRuntimeOwnership::release)
        }
    }

    private fun waitForCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for runtime condition", predicate())
    }

    private class RecordingRtcMedia(
        private val onEvent: (GroupRtcEvent) -> Unit
    ) : GroupRtcMedia {
        @Volatile var joinedCalled = false
        @Volatile var closed = false
        var failClose = false
        var acceptTokenUpdates = true
        var tokenUpdates = 0
        override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
            joinedCalled = true
        }
        override fun updateToken(token: CloudRtcToken): Boolean {
            tokenUpdates++
            return acceptTokenUpdates
        }
        override fun setLocalMuted(muted: Boolean) = Unit
        override fun setAllowedRemoteUsers(userIds: Set<String>) = Unit
        override fun selectRoute(route: AudioRouteSelection) = Unit
        override fun close() {
            closed = true
            if (failClose) error("模拟 RTC 释放失败")
        }
        fun emit(event: GroupRtcEvent) = onEvent(event)
    }

    private class StubCloudApi(
        private val session: CloudAdmittedSession
    ) : GroupCloudApi {
        override fun createRoom(deviceId: String, runtimeId: String, nickname: String, requireApproval: Boolean) = session
        override fun submitJoinRequest(inviteCode: GroupCloudInviteCode, deviceId: String, runtimeId: String, nickname: String) =
            CloudJoinSubmission.Admitted(session)
        override fun pollJoinRequest(requestId: String, requestSecret: String, waitMs: Int) =
            CloudJoinPollResult.Admitted(session)
        override fun cancelJoinRequest(requestId: String, requestSecret: String) = Unit
        override fun approveJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = session.snapshot
        override fun rejectJoinRequest(token: String, roomId: String, requestId: String, expectedRevision: Long) = session.snapshot
        override fun rotateInvite(token: String, roomId: String, expectedRevision: Long) = session.snapshot
        override fun setRequireApproval(token: String, roomId: String, requireApproval: Boolean, expectedRevision: Long) = session.snapshot
        override fun transferOwner(token: String, roomId: String, targetDeviceId: String, alsoLeave: Boolean, expectedRevision: Long) =
            CloudTransferResult(alsoLeave, if (alsoLeave) null else session.snapshot)
        override fun removeMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = session.snapshot
        override fun unblockMember(token: String, roomId: String, targetDeviceId: String, expectedRevision: Long) = session.snapshot
        override fun leaveRoom(token: String, roomId: String): Boolean = true
        override fun endRoom(token: String, roomId: String, expectedRevision: Long) = Unit
        override fun resumeMember(token: String, roomId: String) = CloudResumeResult(false, 1L, session.snapshot, session.rtc)
        override fun refreshRtcToken(token: String, roomId: String) = checkNotNull(session.rtc)
        override fun updateAudioAvailability(token: String, roomId: String, available: Boolean) = session.snapshot
        override fun pollEvents(token: String, roomId: String, afterSeq: Long, waitMs: Int): CloudEventBatch {
            Thread.sleep(50)
            return CloudEventBatch(false, 1L, null, emptyList())
        }
        override fun ackEvents(token: String, roomId: String, sequence: Long) = Unit
        override fun close() = Unit
    }
}
