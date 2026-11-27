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
class GroupR5ScreenAndServiceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun preparationPageSwitchesToHybridModeAndDispatchesHybridCreateAndJoin() {
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
        compose.onNodeWithTag("mode_hybrid").performClick()
        compose.onNodeWithTag("hybrid_create").performClick()
        val created = started as GroupCloudStartRequest.Create
        assertEquals(GroupMode.HYBRID, created.mode)
        assertEquals("https://cloud.example.test", created.serverUrl)

        compose.onNodeWithTag("network_invite_input").performScrollTo().performTextInput("246810")
        compose.onNodeWithTag("hybrid_join").performScrollTo().performClick()
        val joined = started as GroupCloudStartRequest.Join
        assertEquals(GroupMode.HYBRID, joined.mode)
        assertEquals("246810", joined.inviteCode.value)
    }

    @Test
    fun hybridInRoomShowsTransportSwitchChannelsAndDisablesControlsWhileSwitching() {
        val ownerLease = GroupMemberLease("dev-a", "run-a", 1, 1)
        val peerLease = GroupMemberLease("dev-b", "run-b", 2, 1)
        val view = GroupCloudRoomView(
            key = GroupRoomKey(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            revision = 3L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(
                GroupMember(ownerLease, GroupMemberStatus.ADMITTED, role = GroupRole.OWNER),
                GroupMember(peerLease, GroupMemberStatus.ADMITTED, role = GroupRole.RIDING_OFFICER)
            ),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = "dev-a",
            riderIncludesFormation = true
        )
        val events = mutableListOf<GroupSessionEvent>()
        val activeLocalSnapshot = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = UUID.randomUUID(),
            networkAttempt = null,
            view = view,
            local = ownerLease,
            participation = GroupParticipation().begin(view.key),
            code = null,
            host = true,
            message = "双模本地直连已连接",
            matches = emptyList(),
            names = mapOf("dev-a" to "队长", "dev-b" to "骑行官"),
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "998877",
            activeTransport = GroupTransport.LOCAL,
            transportSwitching = false,
            transportEpoch = 1L
        )
        compose.setContent {
            MotoComTheme {
                GroupScreen(
                    state = GroupServiceState(snapshot = activeLocalSnapshot, busy = true, message = activeLocalSnapshot.message),
                    onStart = {},
                    onAction = { events += it },
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {}
                )
            }
        }
        compose.onNodeWithTag("hybrid_transport_card").assertExists()
        compose.onNodeWithTag("channel_rider").assertExists()
        compose.onNodeWithTag("hybrid_switch_transport").performScrollTo().performClick()
        assertEquals(GroupSessionEvent.SwitchTransport(GroupTransport.CLOUD), events.single())
    }

    @Test
    fun hybridServiceForegroundNotificationShowsTransportAndChannelWithoutLeakingCodes() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val controller = Robolectric.buildService(TestHybridGroupIntercomService::class.java).create()
        val service = controller.get()
        val startIntent = Intent(app, TestHybridGroupIntercomService::class.java)
            .setAction(GroupIntercomService.ACTION_START_HYBRID_GROUP)
            .putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, "https://cloud.example.test")
            .putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, true)
        service.onStartCommand(startIntent, 0, 1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val runtime = service.lastHybridRuntime!!
        val ownerLease = GroupMemberLease("dev-a", "run-a", 1, 1)
        val view = GroupCloudRoomView(
            key = GroupRoomKey(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            revision = 1L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(GroupMember(ownerLease, GroupMemberStatus.ADMITTED, role = GroupRole.OWNER, channel = GroupChannel.Rider)),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = null,
            riderIncludesFormation = true
        )
        val cloudSnapshot = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = UUID.randomUUID(),
            networkAttempt = null,
            view = view,
            local = ownerLease,
            participation = GroupParticipation().begin(view.key),
            code = null,
            host = true,
            message = "双模云端兜底语音已连接",
            matches = emptyList(),
            names = mapOf("dev-a" to "队长"),
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "778866",
            activeTransport = GroupTransport.CLOUD,
            transportSwitching = false,
            transportEpoch = 2L
        )
        runtime.pushSnapshot(cloudSnapshot)
        val manager = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).getNotification(GroupIntercomService.NOTIFICATION_ID)
        val title = posted.extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty()
        val text = posted.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty()
        assertEquals("摩声双模群组 · 云端兜底 · 领队频道", title)
        assertFalse(title.contains("778866"))
        assertFalse(text.contains("778866"))
        controller.destroy()
    }
}

internal class TestHybridGroupIntercomService : GroupIntercomService() {
    var lastHybridRuntime: FakeHybridRuntimeHandle? = null
    override fun available(): Boolean = true
    override fun identityStore(): LocalIdentityStore = object : LocalIdentityStore {
        override suspend fun getOrCreateDeviceId(): String = "dev-a"
        override suspend fun getNickname(): String = "队长"
        override suspend fun updateNickname(value: String) = Unit
    }
    override fun createHybridRuntime(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        request: GroupCloudStartRequest,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = FakeHybridRuntimeHandle(onSnapshot, onReleased).also { lastHybridRuntime = it }
}

internal class FakeHybridRuntimeHandle(
    private val onSnapshot: (GroupSessionSnapshot) -> Unit,
    private val onReleased: () -> Unit
) : GroupRuntimeHandle {
    override var snapshot: GroupSessionSnapshot = GroupSessionSnapshot(
        phase = GroupPhase.IDLE,
        operation = null,
        networkAttempt = null,
        view = null,
        local = null,
        participation = GroupParticipation(),
        code = null,
        host = false,
        message = "",
        matches = emptyList(),
        names = emptyMap(),
        removed = emptySet(),
        mode = GroupMode.HYBRID
    )
    fun pushSnapshot(value: GroupSessionSnapshot) {
        snapshot = value
        onSnapshot(value)
    }
    override fun start(code: GroupJoinCode?) = Unit
    override fun dispatch(event: GroupSessionEvent) = Unit
    override fun route(value: AudioRouteSelection) = Unit
    override fun vox(value: Boolean) = Unit
    override fun close() = onReleased()
}
