package com.kuma.motointercom

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupR4ScreenAndServiceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun preparationPageSwitchesToNetworkModeAndDispatchesCreateAndJoin() {
        var started: GroupCloudStartRequest? = null
        compose.setContent {
            MotoComTheme {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    onStartNetwork = { started = it },
                    initialServerUrl = "https://cloud.example.test"
                )
            }
        }
        compose.onNodeWithTag("mode_network").performClick()
        compose.onNodeWithTag("network_join").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("network_create").performScrollTo().performClick()
        val createReq = started as GroupCloudStartRequest.Create
        assertEquals("https://cloud.example.test", createReq.serverUrl)
        assertTrue(createReq.requireApproval)

        compose.onNodeWithTag("network_invite_input").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("network_join").performScrollTo().assertIsEnabled().performClick()
        val joinReq = started as GroupCloudStartRequest.Join
        assertEquals("123456", joinReq.inviteCode.value)
    }

    @Test
    fun preparationPageUsesBuiltInServerAndKeepsDebugOverrideInDeveloperSettings() {
        var started: GroupCloudStartRequest? = null
        var savedUrl: String? = null
        compose.setContent {
            MotoComTheme {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    onStartNetwork = { started = it },
                    initialServerUrl = GroupCloudPreferences.DEFAULT_SERVER_URL,
                    allowCustomServer = true,
                    onSaveCloudSettings = { savedUrl = it }
                )
            }
        }
        compose.onNodeWithTag("mode_network").performClick()
        compose.onNodeWithTag("network_server_input").assertDoesNotExist()
        compose.onNodeWithTag("developer_settings").performScrollTo().performClick()
        compose.onNodeWithTag("network_server_input").performTextClearance()
        compose.onNodeWithTag("network_server_input").performTextInput("http://127.0.0.1:8443")
        compose.onNodeWithTag("developer_server_save").performClick()
        compose.onNodeWithTag("network_server_input").assertDoesNotExist()

        compose.onNodeWithTag("network_create").performScrollTo().performClick()
        val created = started as GroupCloudStartRequest.Create
        assertEquals("http://127.0.0.1:8443", created.serverUrl)
        assertEquals("http://127.0.0.1:8443", savedUrl)
    }

    @Test
    fun productionScreenHidesDeveloperSettingsAndAlwaysUsesBuiltInServer() {
        var started: GroupCloudStartRequest? = null
        compose.setContent {
            MotoComTheme {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    onStartNetwork = { started = it },
                    initialServerUrl = GroupCloudPreferences.DEFAULT_SERVER_URL,
                    allowCustomServer = false
                )
            }
        }
        compose.onNodeWithTag("mode_network").performClick()
        compose.onNodeWithTag("developer_settings").assertDoesNotExist()
        compose.onNodeWithTag("network_server_input").assertDoesNotExist()
        compose.onNodeWithTag("network_create").performScrollTo().performClick()
        assertEquals(
            GroupCloudPreferences.DEFAULT_SERVER_URL,
            (started as GroupCloudStartRequest.Create).serverUrl
        )
    }

    @Test
    fun networkRoomShowsPendingApprovalAndOwnerTransferOrDisbandDialogWithoutRiderControls() {
        val actions = mutableListOf<GroupSessionEvent>()
        val owner = GroupMember(GroupMemberLease("dev-1", "run-1", 1, 1), role = GroupRole.OWNER)
        val peer = GroupMember(GroupMemberLease("dev-2", "run-2", 2, 1), role = GroupRole.MEMBER)
        val view = GroupCloudRoomView(
            key = GroupRoomKey("room-1", "epoch-1"),
            revision = 2,
            ownerId = "dev-1",
            ended = false,
            members = listOf(owner, peer)
        )
        val snap = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = UUID.randomUUID(),
            networkAttempt = null,
            view = view,
            local = owner.lease,
            participation = GroupParticipation().begin(view.key),
            code = null,
            host = true,
            message = "网络房间已创建",
            matches = emptyList(),
            names = mapOf("dev-1" to "队长", "dev-2" to "二号车"),
            removed = emptySet(),
            mode = GroupMode.NETWORK,
            inviteCode = "123456",
            requireApproval = true,
            pendingJoinRequests = listOf(GroupJoinRequest("req-3", "dev-3", "三号车", 9_999_999_999L)),
            networkMediaState = GroupNetworkMediaState.JOINED,
            activeSpeakerIds = setOf("dev-2")
        )
        compose.setContent {
            MotoComTheme {
                GroupScreen(
                    state = GroupServiceState(snapshot = snap, message = snap.message, busy = true),
                    onStart = {},
                    onAction = { actions += it },
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {}
                )
            }
        }
        // Network mode v1 must NOT show Rider or Private call controls
        compose.onNodeWithTag("channel_rider").assertDoesNotExist()
        compose.onNodeWithTag("private_call_dev-2").assertDoesNotExist()
        compose.onNodeWithTag("network_invite_code").assertTextContains("123456", substring = true)
        compose.onNodeWithText("成员列表（2/50）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("正在发言：二号车").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("频道：公共频道 · RTC 在线").assertCountEquals(1)
        compose.onAllNodesWithText("频道：公共频道 · 已加入").assertCountEquals(1)

        // Owner approves pending join request
        compose.onNodeWithTag("approve_req-3").performScrollTo().performClick()
        assertTrue(actions.last() is GroupSessionEvent.ApproveJoin)

        // Owner taps leave button -> mandatory Transfer-or-Disband dialog appears
        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_leave_dev-2").assertIsDisplayed().performClick()
        val transfer = actions.last() as GroupSessionEvent.TransferOwner
        assertEquals("dev-2", transfer.targetDeviceId)
        assertTrue(transfer.alsoLeave)
    }

    @Test
    fun serviceStartsNetworkModeWithoutWifiDirectAndRedactsInviteFromNotification() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val controller = Robolectric.buildService(TestNetworkService::class.java).create()
        val service = controller.get()
        assertFalse(service.available())
        assertTrue(service.networkAvailable())

        val intent = Intent(app, TestNetworkService::class.java)
            .setAction(GroupIntercomService.ACTION_START_NETWORK_GROUP)
            .putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, "https://cloud.example.test")
            .putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, true)
        service.onStartCommand(intent, 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val runtime = checkNotNull(service.lastRuntime)
        val owner = GroupMember(GroupMemberLease("dev-1", "run-1", 1, 1), role = GroupRole.OWNER)
        val view = GroupCloudRoomView(GroupRoomKey("room-1", "epoch-1"), 1, "dev-1", false, listOf(owner))
        runtime.emitSnapshot(
            GroupSessionSnapshot(
                phase = GroupPhase.IN_ROOM,
                operation = UUID.randomUUID(),
                networkAttempt = null,
                view = view,
                local = owner.lease,
                participation = GroupParticipation().begin(view.key),
                code = null,
                host = true,
                message = "网络房间已创建，分享邀请码给车友加入",
                matches = emptyList(),
                names = mapOf("dev-1" to "队长"),
                removed = emptySet(),
                mode = GroupMode.NETWORK,
                inviteCode = "889900",
                networkMediaState = GroupNetworkMediaState.WAITING_FOR_PEER
            )
        )

        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val fg = checkNotNull(nm.getNotification(GroupIntercomService.NOTIFICATION_ID))
        val title = fg.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = fg.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertEquals("摩声网络群组 · 公共频道", title)
        assertFalse("Notification must never leak invite code", title.contains("889900") || text.contains("889900"))

        // Terminal REFUSED notification posted on release
        runtime.emitSnapshot(
            GroupSessionSnapshot(
                phase = GroupPhase.IDLE,
                operation = null,
                networkAttempt = null,
                view = null,
                local = null,
                participation = GroupParticipation(),
                code = null,
                host = false,
                message = "群主已拒绝加入申请",
                matches = emptyList(),
                names = emptyMap(),
                removed = emptySet(),
                terminalReason = GroupTerminalReason.REFUSED,
                mode = GroupMode.NETWORK
            )
        )
        runtime.release()
        val terminal = checkNotNull(nm.getNotification(GroupIntercomService.TERMINAL_NOTIFICATION_ID))
        assertEquals("摩声网络群组 · 加入申请未通过", terminal.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        controller.destroy()
    }
}

internal class TestNetworkService : GroupIntercomService() {
    var lastRuntime: FakeCloudRuntimeHandle? = null
    override fun available(): Boolean = false
    override fun identityStore(): LocalIdentityStore = object : LocalIdentityStore {
        override suspend fun getOrCreateDeviceId(): String = "dev-1"
        override suspend fun getNickname(): String = "队长"
        override suspend fun updateNickname(value: String) = Unit
    }
    override fun createCloudRuntime(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        request: GroupCloudStartRequest,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = FakeCloudRuntimeHandle(onSnapshot, onReleased).also { lastRuntime = it }
}

internal class FakeCloudRuntimeHandle(
    private val onSnapshot: (GroupSessionSnapshot) -> Unit,
    private val onReleased: () -> Unit
) : GroupRuntimeHandle {
    override var snapshot: GroupSessionSnapshot = GroupSessionSnapshot(
        phase = GroupPhase.CREATING,
        operation = UUID.randomUUID(),
        networkAttempt = null,
        view = null,
        local = null,
        participation = GroupParticipation(),
        code = null,
        host = false,
        message = "连接中",
        matches = emptyList(),
        names = emptyMap(),
        removed = emptySet(),
        mode = GroupMode.NETWORK
    )
    override fun start(code: GroupJoinCode?) = Unit
    override fun dispatch(event: GroupSessionEvent) = Unit
    override fun route(value: AudioRouteSelection) = Unit
    override fun vox(value: Boolean) = Unit
    override fun close() = Unit
    fun emitSnapshot(next: GroupSessionSnapshot) {
        snapshot = next
        onSnapshot(next)
    }
    fun release() = onReleased()
}
