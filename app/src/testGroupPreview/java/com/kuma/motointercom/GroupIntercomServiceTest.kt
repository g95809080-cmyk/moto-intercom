package com.kuma.motointercom

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.group.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GroupIntercomServiceTest {
    private lateinit var identity: CompletableDeferred<String>
    private var savedNickname = "测试骑士"
    private val created = mutableListOf<FakeRuntime>()

    private class FakeRuntime(
        endpoint: GroupAuthEndpoint,
        val nickname: String,
        private val publish: (GroupSessionSnapshot) -> Unit,
        private val released: () -> Unit
    ) : GroupRuntimeHandle {
        private val writer = GroupSessionOrchestrator(endpoint, nickname, { 0 }, {}, {})
        private var overrideSnapshot: GroupSessionSnapshot? = null
        override val snapshot get() = overrideSnapshot ?: writer.snapshot
        var closed = false
        var deferCleanup = false
        override fun start(code: GroupJoinCode?) {
            overrideSnapshot = null
            writer.dispatch(if (code == null) GroupSessionEvent.Create else GroupSessionEvent.Search(code))
            publish(snapshot)
        }
        override fun dispatch(event: GroupSessionEvent) {
            overrideSnapshot = null
            writer.dispatch(event)
            publish(snapshot)
            if (event == GroupSessionEvent.Leave) close()
        }
        override fun route(value: AudioRouteSelection) = Unit
        override fun vox(value: Boolean) = Unit
        override fun close() {
            closed = true
            if (!deferCleanup) completeCleanup()
        }
        fun simulateSnapshot(value: GroupSessionSnapshot) {
            overrideSnapshot = value
            publish(value)
        }
        fun simulateTermination(value: GroupSessionSnapshot) {
            overrideSnapshot = value
            publish(value)
            close()
        }
        fun completeCleanup() = released()
    }

    internal class TestService : GroupIntercomService() {
        override fun available() = true
        override fun identityStore(): LocalIdentityStore = identityProvider()
        override fun createRuntime(endpoint: GroupAuthEndpoint, nickname: String,
            onSnapshot: (GroupSessionSnapshot) -> Unit, onLabel: (String) -> Unit,
            onReleased: () -> Unit): GroupRuntimeHandle = factory(endpoint, nickname, onSnapshot, onReleased)
        companion object {
            lateinit var identityProvider: () -> LocalIdentityStore
            lateinit var factory: (GroupAuthEndpoint, String, (GroupSessionSnapshot) -> Unit, () -> Unit) -> GroupRuntimeHandle
        }
    }

    @Before fun setup() {
        identity = CompletableDeferred()
        TestService.identityProvider = {
            object : LocalIdentityStore {
                override suspend fun getOrCreateDeviceId() = identity.await()
                override suspend fun getNickname() = savedNickname
                override suspend fun updateNickname(value: String) = Unit
            }
        }
        TestService.factory = { endpoint, nickname, publish, released ->
            FakeRuntime(endpoint, nickname, publish, released).also(created::add)
        }
    }

    private fun startIntent(code: String? = null) =
        Intent(ApplicationProvider.getApplicationContext(), TestService::class.java)
            .setAction(GroupIntercomService.ACTION_START_GROUP)
            .apply { if (code != null) putExtra(GroupIntercomService.EXTRA_GROUP_CODE, code) }

    @Test fun cancellingWhileIdentityLoadsCannotStartALateRoom() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        try {
            val service = controller.get()
            val states = mutableListOf<GroupServiceState>()
            service.addGroupListener(states::add)
            service.onStartCommand(startIntent(), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(states.last().busy)
            service.groupAction(GroupSessionEvent.Leave)
            identity.complete(UUID.randomUUID().toString())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(created.isEmpty())
            assertEquals("已取消", states.last().message)
            assertFalse(states.last().busy)
        } finally { controller.destroy() }
    }

    @Test fun nullIntentAndInvalidCodeNeverAcquireAMicrophoneOrCreateARoom() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            service.onStartCommand(startIntent("123"), 0, 2)
            identity.complete(UUID.randomUUID().toString())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(created.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun startingAgainDoesNotReplaceAnActiveRuntimeAndDestroyClosesIt() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        identity.complete(UUID.randomUUID().toString())
        service.onStartCommand(startIntent(), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, created.size)
        service.onStartCommand(startIntent(), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, created.size)
        controller.destroy()
        assertTrue(created.single().closed)
    }

    @Test fun newInstallUsesAStableDistinguishableMemberName() {
        savedNickname = ""
        val controller = Robolectric.buildService(TestService::class.java).create()
        try {
            identity.complete("00000000-0000-0000-0000-000000abc123")
            controller.get().onStartCommand(startIntent(), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("骑士 ABC123", created.single().nickname)
        } finally { controller.destroy() }
    }

    @Test fun endedRoomStaysBusyUntilAsynchronousCleanupCompletes() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        try {
            val service = controller.get()
            val states = mutableListOf<GroupServiceState>()
            service.addGroupListener(states::add)
            identity.complete(UUID.randomUUID().toString())
            service.onStartCommand(startIntent(), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val runtime = created.single()
            runtime.deferCleanup = true
            service.groupAction(GroupSessionEvent.Leave)
            assertEquals(GroupPhase.IDLE, runtime.snapshot.phase)
            assertTrue(states.last().busy)
            service.onStartCommand(startIntent(), 0, 2)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, created.size)
            runtime.completeCleanup()
            assertFalse(states.last().busy)
            assertEquals(GroupPhase.IDLE, states.last().snapshot?.phase)
        } finally { controller.destroy() }
    }

    @Test fun previewManifestExposesOnlyTheGroupLauncherAndService() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.packageManager
        val launcher = manager.getLaunchIntentForPackage(context.packageName)
        assertEquals(GroupActivity::class.java.name, launcher?.component?.className)
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            manager.getServiceInfo(ComponentName(context, IntercomService::class.java), 0)
        }
        assertFalse(manager.getServiceInfo(ComponentName(context, GroupIntercomService::class.java), 0).exported)
    }

    @Test fun foregroundNotificationReflectsChannelWithoutLeakingCodeAndTerminalNotificationsLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val controller = Robolectric.buildService(TestService::class.java).create()
        try {
            val service = controller.get()
            val hostId = UUID(0, 1).toString()
            val peerId = UUID(0, 2).toString()
            identity.complete(hostId)
            service.onStartCommand(startIntent(), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val runtime = created.single()
            val codeDigits = runtime.snapshot.code!!.digits

            val publicNotification = shadowOf(notificationManager).getNotification(GroupIntercomService.NOTIFICATION_ID)
            assertNotNull(publicNotification)
            assertEquals(Notification.VISIBILITY_PUBLIC, publicNotification.visibility)
            assertEquals("摩声群组 · 公共频道", publicNotification.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(publicNotification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains(codeDigits))
            assertFalse(publicNotification.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains(codeDigits))

            runtime.dispatch(GroupSessionEvent.HostReady(runtime.snapshot.operation!!))
            service.groupAction(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
            val riderNotification = shadowOf(notificationManager).getNotification(GroupIntercomService.NOTIFICATION_ID)
            assertEquals("摩声群组 · 领队频道", riderNotification.extras.getString(Notification.EXTRA_TITLE))

            val roomKey = runtime.snapshot.view!!.key
            val hostLease = runtime.snapshot.local!!
            val peerLease = GroupMemberLease(peerId, UUID(0, 22).toString(), 2, 1)
            val privateId = UUID(0, 99).toString()
            val privateRoster = GroupRoster(
                key = roomKey,
                hostId = hostId,
                publication = 2,
                ended = false,
                members = listOf(
                    GroupMember(hostLease, role = GroupRole.OWNER, channel = GroupChannel.Private(privateId), channelRevision = 3),
                    GroupMember(peerLease, role = GroupRole.MEMBER, channel = GroupChannel.Private(privateId), channelRevision = 3)
                ),
                links = emptyList(),
                names = mapOf(hostId to "测试骑士", peerId to "头车阿明")
            )
            val privateSnap = GroupSessionSnapshot(
                GroupPhase.IN_ROOM, runtime.snapshot.operation, null, privateRoster, hostLease,
                runtime.snapshot.participation, runtime.snapshot.code, true, "已与 头车阿明 建立 1:1 私聊",
                emptyList(), privateRoster.names, emptySet()
            )
            runtime.simulateSnapshot(privateSnap)
            val privateNotification = shadowOf(notificationManager).getNotification(GroupIntercomService.NOTIFICATION_ID)
            assertEquals("摩声群组 · 私聊（头车阿明）", privateNotification.extras.getString(Notification.EXTRA_TITLE))

            val reconnectingSnap = GroupSessionSnapshot(
                GroupPhase.RECONNECTING, runtime.snapshot.operation, null, privateRoster, hostLease,
                runtime.snapshot.participation, runtime.snapshot.code, true, "离线网络已断开，正在恢复原房间",
                emptyList(), privateRoster.names, emptySet()
            )
            runtime.simulateSnapshot(reconnectingSnap)
            val reconnectingNotification = shadowOf(notificationManager).getNotification(GroupIntercomService.NOTIFICATION_ID)
            assertEquals("摩声群组 · 私聊（头车阿明） · 恢复中", reconnectingNotification.extras.getString(Notification.EXTRA_TITLE))

            service.groupAction(GroupSessionEvent.Leave)
            assertNull(shadowOf(notificationManager).getNotification(GroupIntercomService.TERMINAL_NOTIFICATION_ID))

            val terminalCases = listOf(
                GroupTerminalReason.HOST_ENDED to "摩声群组 · 房间已结束",
                GroupTerminalReason.REMOVED to "摩声群组 · 你已被移出房间",
                GroupTerminalReason.RECOVERY_EXPIRED to "摩声群组 · 重连已超时"
            )
            terminalCases.forEachIndexed { index, (reason, expectedTitle) ->
                service.onStartCommand(startIntent(), 0, index + 2)
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(shadowOf(notificationManager).getNotification(GroupIntercomService.TERMINAL_NOTIFICATION_ID))
                val currentRuntime = created.last()
                val terminalSnap = GroupSessionSnapshot(
                    GroupPhase.IDLE, null, null, null, null, GroupParticipation(), null, false,
                    "终态说明", emptyList(), emptyMap(), emptySet(), terminalReason = reason
                )
                currentRuntime.simulateTermination(terminalSnap)
                val terminalNotification = shadowOf(notificationManager).getNotification(GroupIntercomService.TERMINAL_NOTIFICATION_ID)
                assertNotNull(terminalNotification)
                assertEquals(expectedTitle, terminalNotification.extras.getString(Notification.EXTRA_TITLE))
                assertEquals(Notification.VISIBILITY_PUBLIC, terminalNotification.visibility)
                assertEquals(0, terminalNotification.flags and Notification.FLAG_ONGOING_EVENT)
                assertTrue((terminalNotification.flags and Notification.FLAG_AUTO_CANCEL) != 0)
            }
        } finally { controller.destroy() }
    }
}
