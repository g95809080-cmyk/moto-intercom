package com.kuma.motointercom

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.group.GroupAuthEndpoint
import com.kuma.motointercom.group.GroupJoinCode
import com.kuma.motointercom.group.GroupMember
import com.kuma.motointercom.group.GroupMemberLease
import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.GroupParticipation
import com.kuma.motointercom.group.GroupPhase
import com.kuma.motointercom.group.GroupRole
import com.kuma.motointercom.group.GroupRoomKey
import com.kuma.motointercom.group.GroupRuntimeHandle
import com.kuma.motointercom.group.GroupServiceState
import com.kuma.motointercom.group.GroupSessionEvent
import com.kuma.motointercom.group.GroupSessionSnapshot
import com.kuma.motointercom.group.GroupTransport
import com.kuma.motointercom.group.cloud.GroupCloudPreferences
import com.kuma.motointercom.group.cloud.GroupCloudRoomView
import com.kuma.motointercom.group.cloud.GroupCloudStartRequest
import com.kuma.motointercom.group.ui.DaylightGroupGlassColors
import com.kuma.motointercom.group.ui.GlassBottomSheetOverlay
import com.kuma.motointercom.group.ui.GlassSixDigitCodeInput
import com.kuma.motointercom.group.ui.GroupGlassTheme
import com.kuma.motointercom.group.ui.NightRideGroupGlassColors
import com.kuma.motointercom.group.ui.copyRedactedDiagnosticLogs
import com.kuma.motointercom.group.ui.groupSystemBarStyle
import com.kuma.motointercom.group.ui.redactDiagnosticExportFile
import com.kuma.motointercom.group.ui.redactDiagnosticLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupGlassUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lobbyThemeToggleAndSettingsNavigationWorkWithProductionGuards() {
        val themes = mutableListOf<MotoComThemeMode>()
        var savedNickname: String? = null
        var selectedRoute: AudioRouteSelection? = null
        var exported = 0
        var copied = 0

        compose.setContent {
            GroupScreen(
                state = GroupServiceState(route = AudioRouteSelection.BLUETOOTH, vox = true),
                onStart = {},
                onAction = {},
                onRoute = { selectedRoute = it },
                onVox = {},
                onBack = {},
                onSettings = {},
                onCopy = {},
                allowCustomServer = false,
                themeMode = MotoComThemeMode.DAYLIGHT,
                onThemeChange = { themes += it },
                nickname = "老K",
                onSaveNickname = { savedNickname = it },
                logLines = listOf(
                    "14:02:18.90 [R5·LOCAL] host code=849216 token=secret123",
                    "14:03:06.89 [CLOUD·RTC] invite 612904 ok"
                ),
                onExportLogs = { exported++ },
                onCopyLogs = { copied++ }
            )
        }

        compose.onNodeWithTag("theme_toggle").performClick()
        compose.runOnIdle { assertEquals(listOf(MotoComThemeMode.NIGHTRIDE), themes) }

        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithTag("settings_developer_override").assertDoesNotExist()

        compose.onNodeWithTag("settings_nickname_input").performScrollTo().performTextClearance()
        compose.onNodeWithTag("settings_nickname_input").performTextInput("头车阿哲")
        compose.onNodeWithTag("settings_nickname_save").performClick()
        compose.runOnIdle { assertEquals("头车阿哲", savedNickname) }

        compose.onNodeWithTag("theme_option_daylight").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MotoComThemeMode.DAYLIGHT, themes.last()) }

        compose.onNodeWithTag("settings_route_SPEAKER").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AudioRouteSelection.SPEAKER, selectedRoute) }

        compose.onNodeWithTag("settings_open_logs").performScrollTo().performClick()
        compose.onNodeWithTag("logs_screen").assertIsDisplayed()
        compose.onNodeWithText("849216", substring = true).assertDoesNotExist()
        compose.onNodeWithText("612904", substring = true).assertDoesNotExist()
        compose.onNodeWithText("secret123", substring = true).assertDoesNotExist()

        compose.onNodeWithTag("logs_export").performScrollTo().performClick()
        compose.onNodeWithTag("logs_copy").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, exported)
            assertEquals(1, copied)
        }
    }

    @Test
    fun inCallSettingsLocksNicknameAndAudioOverlayDispatchesRouteAndVox() {
        val ownerLease = GroupMemberLease("dev-a", "run-a", 1, 1)
        val peerLease = GroupMemberLease("dev-b", "run-b", 2, 1)
        val view = GroupCloudRoomView(
            key = GroupRoomKey("room-1", "epoch-1"),
            revision = 1L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(
                GroupMember(ownerLease, role = GroupRole.OWNER),
                GroupMember(peerLease, role = GroupRole.HEAD)
            ),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = "dev-a",
            riderIncludesFormation = true
        )
        val snapshot = GroupSessionSnapshot(
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
            names = mapOf("dev-a" to "老K", "dev-b" to "阿哲"),
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "849216",
            activeTransport = GroupTransport.LOCAL
        )
        var selectedRoute: AudioRouteSelection? = null
        var voxChanged: Boolean? = null

        compose.setContent {
            GroupScreen(
                state = GroupServiceState(snapshot = snapshot, busy = true, vox = true),
                onStart = {},
                onAction = {},
                onRoute = { selectedRoute = it },
                onVox = { voxChanged = it },
                onBack = {},
                onSettings = {},
                onCopy = {},
                nickname = "老K"
            )
        }

        compose.onNodeWithTag("open_audio_sheet").performScrollTo().performClick()
        compose.onNodeWithTag("sheet_route_EARPIECE").performClick()
        compose.onNodeWithTag("sheet_vox_toggle").performClick()
        compose.runOnIdle {
            assertEquals(AudioRouteSelection.EARPIECE, selectedRoute)
            assertEquals(false, voxChanged)
        }
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("open_governance_sheet").performScrollTo().performClick()
        compose.onNodeWithText("房间治理与成员管理").assertIsDisplayed()
    }

    @Test
    fun inCallSettingsLocksNicknameInput() {
        val ownerLease = GroupMemberLease("dev-a", "run-a", 1, 1)
        val view = GroupCloudRoomView(
            key = GroupRoomKey("room-2", "epoch-1"),
            revision = 1L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(GroupMember(ownerLease, role = GroupRole.OWNER)),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = "dev-a",
            riderIncludesFormation = true
        )
        val snapshot = GroupSessionSnapshot(
            phase = GroupPhase.JOINING,
            operation = UUID.randomUUID(),
            networkAttempt = null,
            view = null,
            local = ownerLease,
            participation = GroupParticipation().begin(view.key),
            code = null,
            host = true,
            message = "正在建立连接",
            matches = emptyList(),
            names = mapOf("dev-a" to "老K"),
            removed = emptySet(),
            mode = GroupMode.HYBRID
        )
        compose.setContent {
            GroupScreen(
                state = GroupServiceState(snapshot = snapshot, busy = true),
                onStart = {},
                onAction = {},
                onRoute = {},
                onVox = {},
                onBack = {},
                onSettings = {},
                onCopy = {},
                nickname = "老K"
            )
        }
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithText("通话中锁定").assertIsDisplayed()
        compose.onNodeWithTag("settings_nickname_input").assertIsNotEnabled()
    }

    @Test
    fun redactDiagnosticLineMasksInviteCodesAndTokens() {
        val lines = listOf(
            "14:02:18 [R5] Authorization: Bearer eyJhbGci.secret.sig",
            "14:02:19 [R5] Authorization: [redacted] leakedJwt.payload.sig",
            "14:02:20 [R5] Proxy-Authorization: Basic dXNlcjpwYXNz==",
            "14:02:21 [R5] Authorization: Digest username=\"Mufasa\", realm=\"test\", nonce=\"dcd98b7102dd\", uri=\"/dir\", qop=auth, nc=00000001, cnonce=\"0a4f113b\", response=\"6629fae49393\", opaque=\"5ccc069c\"",
            "14:02:22 [R5] Authorization: Digest username*=UTF-8''J%C3%A4s%C3%B8n%20Doe, realm=\"rfc7616\", nonce=\"rfc7616nonce99\", uri=\"/v1/rooms\", qop=auth, nc=00000002, cnonce=\"rfc7616cnonce\", response=\"rfc7616resp8877\", opaque=\"rfc7616opaque\"",
            "14:02:23 [R5] Authorization:\\tDigest\\tusername*=UTF-8''Tab%20User\\t,\\tnonce=\"tabNonce9110\"\\t,\\tresponse=\"tabResp9110\"",
            "14:02:24 [R5] Authorization:[redacted] [redacted] username*=UTF-8''Mix%20User, nonce=\"mixNonce\", response=\"mixResp\"",
            "14:02:25 [R5] Authorization: [redacted] username=\"Mufasa2\", nonce=\"aa11bb22cc\", response=\"ff99ee88dd\"",
            "14:02:26 [R5] Authorization: HMAC-SHA256 Credential=AK123secret/20261126/cn/rtc/request, SignedHeaders=host, Signature=deadbeef1234",
            "14:02:27 [R5] {\"Authorization\": \"Digest username*=UTF-8''JsonUser, response=\\\"jsonResp\\\"\", \"statusCode\": 200}",
            "14:02:27 [R5] headers=[Authorization: [redacted] leakedInBracket] keepBracketTail=ok",
            "14:02:27 [R5] Authorization: Digest username=\"u]1}\", response=\"respAfterBrace\"",
            "14:02:27 [R5] statusCode=401 authorization failed; Authorization: Bearer secretAfterProse",
            "14:02:27 [R5] Authorization: Digest username=\"" + "p".repeat(4040) + "]leakedTruncatedDigestTail",
            "14:02:27 [R5] Authorization: Digest username=\"" + "p".repeat(4030) + "\\\\nleakedEscapedBackslashN",
            "14:02:27 [R5] Authorization: Digest username=\"" + "p".repeat(4030) + "\\\\rleakedEscapedBackslashR",
            "14:02:27 [R5] Authorization: Digest username*=UTF-8''user\\\\nleakedUnquotedBackslashN",
            "14:02:27 [R5] {\"Authorization\": \"Bearer " + "q".repeat(4030) + "\\\\nleakedTruncatedJsonBackslash",
            "14:02:27 [R5] Authorization: Bearer " + "x".repeat(4096) + "tailLongAuthSecret",
            "14:02:27 [R5] rtcToken=\"" + "y".repeat(4096) + "tailLongQuotedToken\" statusCode=200",
            "14:02:27 [R5] rtcToken=" + "z".repeat(4096) + "tailLongPlainToken",
            "14:02:27 [R5] rtcToken=" + "\\t".repeat(2200) + "tailLongEscapedTabsToken",
            "14:02:27 [R5] rtcToken=" + "\\".repeat(4096) + "tailLongBackslashToken",
            "14:02:27 [R5] rtcToken=\"" + "\\t".repeat(2200) + "tailLongQuotedTabsToken\" statusCode=200",
            "14:02:28 [R5] localP2pPassphrase=MotoP2pSecret99 rtcToken=001volc.secret code=849216 invite=612904 Bearer=abc.def room 123456 statusCode=200"
        )
        val redacted = lines.joinToString("\n", transform = ::redactDiagnosticLine)
        assertFalse(redacted.contains("eyJhbGci"))
        assertFalse(redacted.contains("secret.sig"))
        assertFalse(redacted.contains("leakedJwt"))
        assertFalse(redacted.contains("dXNlcjpwYXNz"))
        assertFalse(redacted.contains("Mufasa"))
        assertFalse(redacted.contains("dcd98b7102dd"))
        assertFalse(redacted.contains("0a4f113b"))
        assertFalse(redacted.contains("6629fae49393"))
        assertFalse(redacted.contains("5ccc069c"))
        assertFalse(redacted.contains("J%C3%A4s%C3%B8n"))
        assertFalse(redacted.contains("rfc7616nonce99"))
        assertFalse(redacted.contains("rfc7616cnonce"))
        assertFalse(redacted.contains("rfc7616resp8877"))
        assertFalse(redacted.contains("rfc7616opaque"))
        assertFalse(redacted.contains("Tab%20User"))
        assertFalse(redacted.contains("tabNonce9110"))
        assertFalse(redacted.contains("tabResp9110"))
        assertFalse(redacted.contains("Mix%20User"))
        assertFalse(redacted.contains("mixNonce"))
        assertFalse(redacted.contains("mixResp"))
        assertFalse(redacted.contains("JsonUser"))
        assertFalse(redacted.contains("jsonResp"))
        assertFalse(redacted.contains("leakedInBracket"))
        assertFalse(redacted.contains("respAfterBrace"))
        assertFalse(redacted.contains("secretAfterProse"))
        assertFalse(redacted.contains("leakedTruncatedDigestTail"))
        assertFalse(redacted.contains("leakedEscapedBackslashN"))
        assertFalse(redacted.contains("leakedEscapedBackslashR"))
        assertFalse(redacted.contains("leakedUnquotedBackslashN"))
        assertFalse(redacted.contains("leakedTruncatedJsonBackslash"))
        assertFalse(redacted.contains("tailLongAuthSecret"))
        assertFalse(redacted.contains("tailLongQuotedToken"))
        assertFalse(redacted.contains("tailLongPlainToken"))
        assertFalse(redacted.contains("tailLongEscapedTabsToken"))
        assertFalse(redacted.contains("tailLongBackslashToken"))
        assertFalse(redacted.contains("tailLongQuotedTabsToken"))
        assertFalse(redacted.contains("aa11bb22cc"))
        assertFalse(redacted.contains("ff99ee88dd"))
        assertFalse(redacted.contains("AK123secret"))
        assertFalse(redacted.contains("deadbeef1234"))
        assertFalse(redacted.contains("MotoP2pSecret99"))
        assertFalse(redacted.contains("001volc.secret"))
        assertFalse(redacted.contains("849216"))
        assertFalse(redacted.contains("612904"))
        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("123456"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("\"statusCode\": 200"))
        assertTrue(redacted.contains("keepBracketTail=ok"))
        assertTrue(redacted.contains("statusCode=401 authorization failed; Authorization: [REDACTED]"))
        assertTrue(redacted.contains("statusCode=200"))
    }

    @Test
    fun governanceSheetDisablesActionsWhenSelectedMemberLeaves() {
        val ownerLease = GroupMemberLease("dev-a", "run-a", 1, 1)
        val peerBLease = GroupMemberLease("dev-b", "run-b", 2, 1)
        val peerCLease = GroupMemberLease("dev-c", "run-c", 3, 1)
        val initialView = GroupCloudRoomView(
            key = GroupRoomKey("room-gov", "epoch-1"),
            revision = 1L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(
                GroupMember(ownerLease, role = GroupRole.OWNER),
                GroupMember(peerBLease, role = GroupRole.HEAD),
                GroupMember(peerCLease, role = GroupRole.MEMBER)
            ),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = "dev-a",
            riderIncludesFormation = true
        )
        val afterBLeavesView = GroupCloudRoomView(
            key = GroupRoomKey("room-gov", "epoch-1"),
            revision = 2L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(
                GroupMember(ownerLease, role = GroupRole.OWNER),
                GroupMember(peerCLease, role = GroupRole.MEMBER)
            ),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = "dev-a",
            riderIncludesFormation = true
        )
        val initialSnapshot = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = UUID.randomUUID(),
            networkAttempt = null,
            view = initialView,
            local = ownerLease,
            participation = GroupParticipation().begin(initialView.key),
            code = null,
            host = true,
            message = "双模本地直连已连接",
            matches = emptyList(),
            names = mapOf("dev-a" to "老K", "dev-b" to "阿哲", "dev-c" to "小七"),
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "849216",
            activeTransport = GroupTransport.LOCAL
        )
        val afterBLeavesSnapshot = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = initialSnapshot.operation,
            networkAttempt = null,
            view = afterBLeavesView,
            local = ownerLease,
            participation = initialSnapshot.participation,
            code = null,
            host = true,
            message = "双模本地直连已连接",
            matches = emptyList(),
            names = mapOf("dev-a" to "老K", "dev-c" to "小七"),
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "849216",
            activeTransport = GroupTransport.LOCAL
        )
        val rejoinedBLease = GroupMemberLease("dev-b", "run-b-rejoined", 4, 1)
        val afterBRejoinsView = GroupCloudRoomView(
            key = GroupRoomKey("room-gov", "epoch-1"),
            revision = 3L,
            ownerId = "dev-a",
            ended = false,
            members = listOf(
                GroupMember(ownerLease, role = GroupRole.OWNER),
                GroupMember(rejoinedBLease, role = GroupRole.MEMBER),
                GroupMember(peerCLease, role = GroupRole.MEMBER)
            ),
            maxMembers = 4,
            mode = GroupMode.HYBRID,
            hostDeviceId = "dev-a",
            riderIncludesFormation = true
        )
        val afterBRejoinsSnapshot = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = initialSnapshot.operation,
            networkAttempt = null,
            view = afterBRejoinsView,
            local = ownerLease,
            participation = initialSnapshot.participation,
            code = null,
            host = true,
            message = "双模本地直连已连接",
            matches = emptyList(),
            names = mapOf("dev-a" to "老K", "dev-b" to "阿哲", "dev-c" to "小七"),
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "849216",
            activeTransport = GroupTransport.LOCAL
        )
        var currentState by mutableStateOf(GroupServiceState(snapshot = initialSnapshot, busy = true, vox = true))
        val dispatched = mutableListOf<GroupSessionEvent>()

        compose.setContent {
            GroupScreen(
                state = currentState,
                onStart = {},
                onAction = { dispatched += it },
                onRoute = {},
                onVox = {},
                onBack = {},
                onSettings = {},
                onCopy = {},
                nickname = "老K"
            )
        }

        compose.onNodeWithTag("open_governance_sheet").performScrollTo().performClick()
        compose.onNodeWithTag("governance_select_dev-b").performClick()
        compose.onNodeWithText("移出房间").assertIsDisplayed()
        compose.onNodeWithText("设为新群主").assertIsDisplayed()

        compose.runOnUiThread {
            currentState = currentState.copy(snapshot = afterBLeavesSnapshot)
        }
        compose.waitForIdle()

        compose.onNodeWithTag("governance_target_invalidated").assertIsDisplayed()
        compose.onNodeWithText("移出房间").assertDoesNotExist()
        compose.onNodeWithText("设为新群主").assertDoesNotExist()
        assertTrue(dispatched.isEmpty())

        compose.runOnUiThread {
            currentState = currentState.copy(snapshot = afterBRejoinsSnapshot)
        }
        compose.waitForIdle()

        compose.onNodeWithTag("governance_target_invalidated").assertIsDisplayed()
        compose.onNodeWithText("移出房间").assertDoesNotExist()
        compose.onNodeWithText("设为新群主").assertDoesNotExist()
        assertTrue(dispatched.isEmpty())

        compose.onNodeWithTag("governance_select_dev-c").performClick()
        compose.onNodeWithText("移出房间").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, dispatched.size)
            val remove = dispatched.single() as GroupSessionEvent.Remove
            assertEquals("dev-c", remove.peerId)
        }
    }

    @Test
    fun idleRouteSelectionPersistsToFirstRoomAndInCallVoxSensitivityAppliesLive() {
        val controller = Robolectric.buildService(RecordingAudioService::class.java).create()
        try {
            val service = controller.get()
            val states = mutableListOf<GroupServiceState>()
            service.addGroupListener(states::add)
            service.groupRoute(AudioRouteSelection.EARPIECE)
            service.groupVox(false)

            val startIntent = Intent(service, RecordingAudioService::class.java).apply {
                action = GroupIntercomService.ACTION_START_HYBRID_GROUP
                putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, GroupCloudPreferences.DEFAULT_SERVER_URL)
                putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, true)
            }
            service.onStartCommand(startIntent, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(AudioRouteSelection.EARPIECE, states.last().route)
            assertEquals(false, states.last().vox)
            assertEquals(AudioRouteSelection.EARPIECE, service.capturedInitialRoute)
            assertEquals(false, service.capturedInitialControls?.voxEnabled)
            assertNotNull(service.fakeRuntime)

            val beforePreview = AudioControlPreferences(service).load().voxSensitivity
            service.previewVoxSensitivity(75)
            assertEquals(beforePreview, AudioControlPreferences(service).load().voxSensitivity)
            assertEquals(listOf(75), service.fakeRuntime!!.voxSensitivities)

            service.groupVoxSensitivity(82)
            assertEquals(82, GroupAudioControlStore.load(service).voxSensitivity)
            GroupAudioControlStore.awaitPendingWrites()
            assertEquals(82, AudioControlPreferences(service).load().voxSensitivity)
            assertEquals(listOf(75, 82), service.fakeRuntime!!.voxSensitivities)
        } finally {
            controller.destroy()
        }

        var currentSensitivity by mutableStateOf(50)
        val previewValues = mutableListOf<Int>()
        val committedValues = mutableListOf<Int>()
        compose.setContent {
            GroupScreen(
                state = GroupServiceState(vox = true),
                onStart = {},
                onAction = {},
                onRoute = {},
                onVox = {},
                onBack = {},
                onSettings = {},
                onCopy = {},
                voxSensitivity = currentSensitivity,
                onVoxSensitivityChange = {
                    currentSensitivity = it
                    previewValues += it
                },
                onVoxSensitivityCommit = { committedValues += it }
            )
        }
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_vox_slider").performScrollTo().performTouchInput {
            swipe(
                start = Offset(x = width * 0.50f, y = height * 0.5f),
                end = Offset(x = width * 0.85f, y = height * 0.5f),
                durationMillis = 300L
            )
        }
        compose.runOnIdle {
            assertTrue("Slider drag should emit live preview values", previewValues.isNotEmpty())
            assertEquals("Slider release should commit exactly once", 1, committedValues.size)
            assertEquals(previewValues.last(), committedValues.single())
        }
    }

    @Test
    fun redactionUnifiesDisplayClipboardAndExportedFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = Files.createTempDirectory("motocom-redaction-test").toFile()
        try {
            val storeDir = File(tempDir, "store")
            val store = PersistentLogStore(storeDir, "test-origin")
            val now = 1_795_700_000_000L
            val syntheticMessages = listOf(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.superSecretJwt.sig",
                "Proxy-Authorization: Basic dXNlcjpzZWNyZXRQYXNz==",
                "Authorization: Digest username=\"Mufasa\", realm=\"motocom\", nonce=\"dcd98b7102dd2f0e\", uri=\"/v1/rooms\", qop=auth, nc=00000001, cnonce=\"0a4f113b\", response=\"6629fae49393a053\", opaque=\"5ccc069c403e\"",
                "Authorization: Digest username*=UTF-8''J%C3%A4s%C3%B8n%20Doe, realm=\"rfc7616\", nonce=\"extNonce7616\", uri=\"/v1/rooms\", qop=auth, nc=00000002, cnonce=\"extCnonce7616\", response=\"extResp7616\", opaque=\"extOpaque7616\"",
                "Authorization:\tDigest\tusername*=UTF-8''Tab%20User\t,\trealm=\"ows\",\tnonce=\"owsTabNonce\",\r\n\tresponse=\"owsTabResp\",\topaque=\"owsTabOpaque\"",
                "Authorization:\t Digest username*=UTF-8''MixedOws%20User, realm=\"ows\", nonce=\"mixedOwsNonce\", response=\"mixedOwsResp\"",
                "Authorization:\tDigest\tusername=\"QuotedTabUser\",\tnonce=\"quotedTabNonce\",\tresponse=\"quotedTabResp\"",
                "Authorization: HMAC-SHA256 Credential=AK987secret/20261126/cn/rtc/request, SignedHeaders=host, Signature=cafebabe9876",
                "localP2pPassphrase=MotoP2pPass99 ssid=DIRECT-1A",
                "rtcToken=001volc.secret.token statusCode=200",
                "inviteCode=849216 code=612904 room 123456"
            )
            syntheticMessages.forEachIndexed { idx, raw ->
                store.append(now + idx * 1000L, "I", "R5", PersistentLogStore.clean(raw))
            }
            val doubleCleanedMixedOws = PersistentLogStore.clean(
                PersistentLogStore.clean(
                    "Authorization:\t Digest username*=UTF-8''DoubleClean%20User, realm=\"ows\", nonce=\"doubleCleanNonce\", response=\"doubleCleanResp\""
                )
            )
            assertTrue(
                "Double PersistentLogStore.clean on mixed OWS should produce repeated [redacted] placeholders",
                doubleCleanedMixedOws.contains("[redacted] [redacted]")
            )
            store.append(now + syntheticMessages.size * 1000L, "I", "R5", doubleCleanedMixedOws)
            val truncatedDigestRaw =
                "Authorization: Digest username=\"" + "a".repeat(4040) + "]truncatedSecretTail_cut_past_4096\""
            val doubleCleanedTruncatedDigest = PersistentLogStore.clean(
                PersistentLogStore.clean(truncatedDigestRaw)
            )
            assertFalse(
                "Truncated Digest line must lose its closing quote at 4096 chars",
                doubleCleanedTruncatedDigest.endsWith("\"")
            )
            assertTrue(
                "Truncated Digest line must still retain the secret tail after ] prior to UI redaction",
                doubleCleanedTruncatedDigest.contains("]truncatedSecretTail")
            )
            store.append(now + (syntheticMessages.size + 1) * 1000L, "I", "R5", doubleCleanedTruncatedDigest)
            val doubleCleanedQuotedBackslashToken = PersistentLogStore.clean(
                PersistentLogStore.clean("rtcToken=\"a " + "\\".repeat(3600) + "backslashLeakTail\"")
            )
            store.append(now + (syntheticMessages.size + 2) * 1000L, "I", "R5", doubleCleanedQuotedBackslashToken)
            val singleCleanedTabsToken = PersistentLogStore.clean(
                "rtcToken=" + "\t".repeat(1900) + "tabsLeakTail"
            )
            store.append(now + (syntheticMessages.size + 3) * 1000L, "I", "R5", singleCleanedTabsToken)
            val doubleCleanedTabsToken = PersistentLogStore.clean(singleCleanedTabsToken)
            store.append(now + (syntheticMessages.size + 4) * 1000L, "I", "R5", doubleCleanedTabsToken)
            val truncatedEscapedBackslashDigestRaw =
                "Authorization: Digest username=\"" + "b".repeat(4040) + "\\\\nbsNLeak\\\\rbsRLeak_cut_past_4096\""
            val doubleCleanedEscapedBackslashDigest = PersistentLogStore.clean(
                PersistentLogStore.clean(truncatedEscapedBackslashDigestRaw)
            )
            assertFalse(
                "Truncated escaped-backslash Digest line must lose closing quote at 4096 chars",
                doubleCleanedEscapedBackslashDigest.endsWith("\"")
            )
            assertTrue(
                "Truncated escaped-backslash Digest line must retain \\\\n/\\\\r secret tail prior to UI redaction",
                doubleCleanedEscapedBackslashDigest.contains("bsNLeak") &&
                    doubleCleanedEscapedBackslashDigest.contains("bsRLeak")
            )
            store.append(now + (syntheticMessages.size + 5) * 1000L, "I", "R5", doubleCleanedEscapedBackslashDigest)

            val recentLines = store.recent(now + 20_000L)
            assertTrue(recentLines.isNotEmpty())

            compose.setContent {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    logLines = recentLines
                )
            }
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("settings_open_logs").performScrollTo().performClick()
            compose.onNodeWithTag("logs_screen").assertIsDisplayed()

            val forbiddenSecrets = listOf(
                "superSecretJwt",
                "dXNlcjpzZWNyZXRQYXNz",
                "Mufasa",
                "dcd98b7102dd2f0e",
                "0a4f113b",
                "6629fae49393a053",
                "5ccc069c403e",
                "J%C3%A4s%C3%B8n",
                "extNonce7616",
                "extCnonce7616",
                "extResp7616",
                "extOpaque7616",
                "Tab%20User",
                "owsTabNonce",
                "owsTabResp",
                "owsTabOpaque",
                "MixedOws%20User",
                "mixedOwsNonce",
                "mixedOwsResp",
                "DoubleClean%20User",
                "doubleCleanNonce",
                "doubleCleanResp",
                "truncatedSecretTail",
                "bsNLeak",
                "bsRLeak",
                "backslashLeakTail",
                "tabsLeakTail",
                "QuotedTabUser",
                "quotedTabNonce",
                "quotedTabResp",
                "AK987secret",
                "cafebabe9876",
                "MotoP2pPass99",
                "001volc.secret.token",
                "849216",
                "612904",
                "123456"
            )
            forbiddenSecrets.forEach { secret ->
                compose.onNodeWithText(secret, substring = true).assertDoesNotExist()
            }

            assertTrue(copyRedactedDiagnosticLogs(context, recentLines))
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipboardText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertTrue(clipboardText.isNotBlank())
            assertTrue(clipboardText.contains("statusCode=200"))
            forbiddenSecrets.forEach { secret ->
                assertFalse("Clipboard leaked $secret: $clipboardText", clipboardText.contains(secret))
            }

            val exportFile = File(tempDir, "motocom-export.txt")
            exportFile.outputStream().use { out ->
                store.export(now + 20_000L, out, "MotoCom local diagnostics\nApp at export: 2.3.0-r5.preview.7")
            }
            redactDiagnosticExportFile(exportFile)
            val exportedText = exportFile.readText(Charsets.UTF_8)
            assertTrue(exportedText.contains("MotoCom local diagnostics"))
            assertTrue(exportedText.contains("statusCode=200"))
            forbiddenSecrets.forEach { secret ->
                assertFalse("Export file leaked $secret: $exportedText", exportedText.contains(secret))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sixDigitCodeInputSupportsClickFormattedPasteAndMiddleBackspace() {
        var code by mutableStateOf("")
        compose.setContent {
            GroupGlassTheme(themeMode = MotoComThemeMode.DAYLIGHT) {
                Box(modifier = Modifier.width(360.dp)) {
                    GlassSixDigitCodeInput(
                        value = code,
                        onValueChange = { code = it },
                        inputTag = "test_six_digit"
                    )
                }
            }
        }

        val field = compose.onNodeWithTag("test_six_digit")
        field.performClick()
        field.assertIsFocused()
        field.performTextInput("邀请码 123-456")
        compose.runOnIdle { assertEquals("123456", code) }

        field.performTouchInput {
            click(Offset(x = width * (2.5f / 6f), y = height / 2f))
        }
        field.performKeyInput { pressKey(Key.Backspace) }
        compose.runOnIdle { assertEquals("12456", code) }

        field.performTextInput("9")
        compose.runOnIdle { assertEquals("129456", code) }

        field.performTouchInput {
            click(Offset(x = width * (0.5f / 6f), y = height / 2f))
        }
        field.performKeyInput { pressKey(Key.Backspace) }
        compose.runOnIdle { assertEquals("29456", code) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sixDigitCodeInputMapsRtlTouchCoordinatesToLogicalCells() {
        var code by mutableStateOf("")
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                GroupGlassTheme(themeMode = MotoComThemeMode.DAYLIGHT) {
                    Box(modifier = Modifier.width(360.dp)) {
                        GlassSixDigitCodeInput(
                            value = code,
                            onValueChange = { code = it },
                            inputTag = "test_six_digit_rtl"
                        )
                    }
                }
            }
        }

        val field = compose.onNodeWithTag("test_six_digit_rtl")
        field.performClick()
        field.assertIsFocused()
        field.performTextInput("123456")
        compose.runOnIdle { assertEquals("123456", code) }

        field.performTouchInput {
            click(Offset(x = width * (5.5f / 6f), y = height / 2f))
        }
        field.performKeyInput { pressKey(Key.Backspace) }
        compose.runOnIdle { assertEquals("23456", code) }

        field.performTouchInput {
            click(Offset(x = width * (3.5f / 6f), y = height / 2f))
        }
        field.performKeyInput { pressKey(Key.Backspace) }
        compose.runOnIdle { assertEquals("2356", code) }
    }

    @Test
    fun interleavedVoxSensitivityAsyncAndVoxToggleNeverClobberEachOther() {
        val controller = Robolectric.buildService(RecordingAudioService::class.java).create()
        val defaultWriter = GroupAudioControlStore.diskWriter
        try {
            val service = controller.get()
            service.groupVox(true)
            GroupAudioControlStore.updateVoxSensitivity(service, 50)
            GroupAudioControlStore.awaitPendingWrites()

            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val releasedExplicitly = AtomicBoolean(false)
            val timedOutWaiting = AtomicBoolean(false)
            GroupAudioControlStore.diskWriter = { context, settings ->
                writeEntered.countDown()
                val unblocked = releaseWrite.await(5, TimeUnit.SECONDS)
                if (!unblocked) timedOutWaiting.set(true)
                releasedExplicitly.set(unblocked)
                defaultWriter(context, settings)
            }

            GroupAudioControlStore.updateVoxSensitivityAsync(service, 72)
            assertTrue("Slow background disk commit should have started", writeEntered.await(5, TimeUnit.SECONDS))

            // While the background commit() is blocked, foreground updates and reads must not block.
            GroupAudioControlStore.updateVoxSensitivityAsync(service, 84)
            service.groupVox(false)
            val immediateWhileDiskBlocked = GroupAudioControlStore.load(service)
            assertEquals(false, immediateWhileDiskBlocked.voxEnabled)
            assertEquals(84, immediateWhileDiskBlocked.voxSensitivity)
            assertEquals("Foreground ops must finish before releasing background commit", 1L, releaseWrite.count)
            assertFalse("Background commit timed out instead of remaining blocked during foreground ops", timedOutWaiting.get())

            releaseWrite.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            assertTrue("Background commit must have been released explicitly by test", releasedExplicitly.get())

            val persistedAfterSlowCommit = AudioControlPreferences(service).load()
            assertEquals("VOX toggle must survive slow in-flight sensitivity commit", false, persistedAfterSlowCommit.voxEnabled)
            assertEquals("Latest sensitivity must survive slow in-flight commit", 84, persistedAfterSlowCommit.voxSensitivity)

            GroupAudioControlStore.diskWriter = defaultWriter

            repeat(20) { iteration ->
                val targetSensitivity = 60 + iteration
                service.groupVox(true)
                GroupAudioControlStore.updateVoxSensitivityAsync(service, targetSensitivity - 5)
                GroupAudioControlStore.updateVoxSensitivityAsync(service, targetSensitivity)
                service.groupVox(false)

                val immediate = GroupAudioControlStore.load(service)
                assertEquals(false, immediate.voxEnabled)
                assertEquals(targetSensitivity, immediate.voxSensitivity)

                GroupAudioControlStore.awaitPendingWrites()
                val persisted = AudioControlPreferences(service).load()
                assertEquals("VOX toggle must not be reverted by async sensitivity save", false, persisted.voxEnabled)
                assertEquals("Latest VOX sensitivity must persist after toggle", targetSensitivity, persisted.voxSensitivity)
            }
        } finally {
            GroupAudioControlStore.diskWriter = defaultWriter
            controller.destroy()
        }
    }

    @Test
    fun coldStartGroupActivityDoesNotBlockMainThreadOnAudioPreferencesLoad() {
        val defaultLoader = GroupAudioControlStore.diskLoader
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val releasedExplicitly = AtomicBoolean(false)
        val timedOutWaiting = AtomicBoolean(false)
        GroupAudioControlStore.diskLoader = { _ ->
            loadEntered.countDown()
            val unblocked = releaseLoad.await(5, TimeUnit.SECONDS)
            if (!unblocked) timedOutWaiting.set(true)
            releasedExplicitly.set(unblocked)
            AudioControlSettings(voxEnabled = false, voxSensitivity = 78, rnnoiseEnabled = false)
        }
        val controller = Robolectric.buildActivity(GroupActivity::class.java)
        try {
            controller.setup()
            assertTrue("Background cold-start audio load should have started", loadEntered.await(5, TimeUnit.SECONDS))
            assertEquals("Activity cold start must finish before releasing slow disk loader", 1L, releaseLoad.count)
            assertFalse("Slow disk loader timed out while waiting for Activity cold start", timedOutWaiting.get())

            val activity = controller.get()
            GroupAudioControlStore.updateVoxEnabled(activity, true)
            assertEquals("Early foreground edit must not block on cold-start disk load", 1L, releaseLoad.count)

            releaseLoad.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Cold-start disk loader must have been released explicitly", releasedExplicitly.get())

            val merged = GroupAudioControlStore.peek(activity)
            assertTrue("Cold-start store should be hydrated after disk loader finishes", GroupAudioControlStore.isHydrated(activity))
            assertEquals("Early foreground VOX toggle must survive cold-start hydration", true, merged.voxEnabled)
            assertEquals("Unedited sensitivity from disk must hydrate cleanly", 78, merged.voxSensitivity)
            assertEquals("Unedited RNNoise flag from disk must hydrate cleanly", false, merged.rnnoiseEnabled)
            val persisted = AudioControlPreferences(activity).load()
            assertEquals("Merged VOX toggle should persist after cold-start hydration", true, persisted.voxEnabled)
            assertEquals("Merged disk sensitivity must not be clobbered by default during cold start", 78, persisted.voxSensitivity)
        } finally {
            releaseLoad.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            GroupAudioControlStore.diskLoader = defaultLoader
            controller.pause().stop().destroy()
        }
    }

    private fun assertColdStartRoomCreationNonBlockingAndReconciles(startAction: String) {
        val defaultLoader = GroupAudioControlStore.diskLoader
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val releasedExplicitly = AtomicBoolean(false)
        val timedOutWaiting = AtomicBoolean(false)
        GroupAudioControlStore.diskLoader = { _ ->
            loadEntered.countDown()
            val unblocked = releaseLoad.await(5, TimeUnit.SECONDS)
            if (!unblocked) timedOutWaiting.set(true)
            releasedExplicitly.set(unblocked)
            AudioControlSettings(voxEnabled = false, voxSensitivity = 78, rnnoiseEnabled = false)
        }
        val controller = Robolectric.buildService(RecordingAudioService::class.java)
        try {
            val service = controller.create().get()
            val states = mutableListOf<GroupServiceState>()
            service.addGroupListener(states::add)
            assertTrue("Background cold-start audio load should have started", loadEntered.await(5, TimeUnit.SECONDS))
            assertEquals("Service create must finish before releasing slow disk loader", 1L, releaseLoad.count)

            val startIntent = Intent(service, RecordingAudioService::class.java).apply {
                action = startAction
                if (startAction == GroupIntercomService.ACTION_START_HYBRID_GROUP) {
                    putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, GroupCloudPreferences.DEFAULT_SERVER_URL)
                    putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, true)
                }
            }
            service.onStartCommand(startIntent, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            assertNotNull("Runtime should start without waiting on slow disk loader ($startAction)", service.fakeRuntime)
            assertEquals("Room start must complete before releasing slow disk loader ($startAction)", 1L, releaseLoad.count)
            assertFalse("Slow disk loader timed out while starting room ($startAction)", timedOutWaiting.get())

            releaseLoad.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Cold-start disk loader must have been released explicitly ($startAction)", releasedExplicitly.get())

            assertEquals("UI state.vox must reconcile to hydrated disk value after room start ($startAction)", false, states.last().vox)
            assertEquals("Running runtime must receive hydrated VOX enabled flag ($startAction)", false, service.fakeRuntime!!.voxValues.last())
            assertEquals("Running runtime must receive hydrated VOX sensitivity ($startAction)", 78, service.fakeRuntime!!.voxSensitivities.last())
        } finally {
            releaseLoad.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            GroupAudioControlStore.diskLoader = defaultLoader
            controller.destroy()
        }
    }

    @Test
    fun coldStartHybridRoomCreationDoesNotBlockMainThreadAndReconcilesVoxState() {
        assertColdStartRoomCreationNonBlockingAndReconciles(GroupIntercomService.ACTION_START_HYBRID_GROUP)
    }

    @Test
    fun coldStartLocalRoomCreationDoesNotBlockMainThreadAndReconcilesVoxState() {
        assertColdStartRoomCreationNonBlockingAndReconciles(GroupIntercomService.ACTION_START_GROUP)
    }

    @Test
    fun preStartVoxSensitivityPreviewAppliesImmediatelyToNewRuntimeAndSurvivesHydration() {
        val defaultLoader = GroupAudioControlStore.diskLoader
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val releasedExplicitly = AtomicBoolean(false)
        val timedOutWaiting = AtomicBoolean(false)
        GroupAudioControlStore.diskLoader = { _ ->
            loadEntered.countDown()
            val unblocked = releaseLoad.await(5, TimeUnit.SECONDS)
            if (!unblocked) timedOutWaiting.set(true)
            releasedExplicitly.set(unblocked)
            AudioControlSettings(voxEnabled = false, voxSensitivity = 78, rnnoiseEnabled = false)
        }
        val controller = Robolectric.buildService(RecordingAudioService::class.java)
        try {
            val service = controller.create().get()
            val states = mutableListOf<GroupServiceState>()
            service.addGroupListener(states::add)
            assertTrue("Background cold-start audio load should have started", loadEntered.await(5, TimeUnit.SECONDS))

            service.previewVoxSensitivity(85)
            val startIntent = Intent(service, RecordingAudioService::class.java).apply {
                action = GroupIntercomService.ACTION_START_HYBRID_GROUP
                putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, GroupCloudPreferences.DEFAULT_SERVER_URL)
                putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, true)
            }
            service.onStartCommand(startIntent, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            assertNotNull("Runtime should start without blocking on slow disk loader", service.fakeRuntime)
            assertEquals("Pre-start slider preview must take effect immediately on runtime creation", 85, service.capturedInitialControls?.voxSensitivity)
            assertEquals("Room start must finish before releasing slow disk loader", 1L, releaseLoad.count)
            assertFalse("Slow disk loader timed out before release", timedOutWaiting.get())

            releaseLoad.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Cold-start disk loader must have been released explicitly", releasedExplicitly.get())

            assertEquals("UI state.vox must still reconcile to hydrated disk value", false, states.last().vox)
            assertEquals("Running runtime must receive hydrated VOX enabled flag", false, service.fakeRuntime!!.voxValues.last())
            assertTrue("Hydration must not overwrite active pre-start preview sensitivity", service.fakeRuntime!!.voxSensitivities.isEmpty())
        } finally {
            releaseLoad.countDown()
            GroupAudioControlStore.awaitPendingWrites()
            GroupAudioControlStore.diskLoader = defaultLoader
            controller.destroy()
        }
    }

    @Test
    fun logsPollingStopsWhenLifecycleStopsAndResumesOnStart() {
        val fakeOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        fakeOwner.registry.currentState = Lifecycle.State.RESUMED
        var refreshCount = 0

        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides fakeOwner) {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    onRefreshLogs = { refreshCount++ }
                )
            }
        }

        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_open_logs").performScrollTo().performClick()
        compose.waitForIdle()
        val afterOpen = refreshCount
        assertTrue("Expected initial log refresh", afterOpen >= 1)

        compose.mainClock.advanceTimeBy(2_100L)
        compose.waitForIdle()
        assertTrue("Expected periodic refresh while STARTED", refreshCount > afterOpen)

        compose.runOnUiThread {
            fakeOwner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.waitForIdle()
        val countWhenStopped = refreshCount

        compose.mainClock.advanceTimeBy(6_500L)
        compose.waitForIdle()
        assertEquals("Logs polling must stop while backgrounded", countWhenStopped, refreshCount)

        compose.runOnUiThread {
            fakeOwner.registry.currentState = Lifecycle.State.STARTED
        }
        compose.waitForIdle()
        assertTrue("Logs polling should resume on foreground", refreshCount > countWhenStopped)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w840dp-h640dp-mdpi")
    fun bottomSheetScrimBlocksWideScreenSideTouchesAndKeepsSheetInteractive() {
        var underlyingClicked = false
        var dismissed = false
        var insideClicked = false

        compose.setContent {
            GroupGlassTheme(themeMode = MotoComThemeMode.DAYLIGHT) {
                Box(modifier = Modifier.size(width = 800.dp, height = 600.dp)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .size(width = 720.dp, height = 200.dp)
                            .clickable { underlyingClicked = true }
                            .testTag("underlying_wide_target")
                    )
                    GlassBottomSheetOverlay(onDismissRequest = { dismissed = true }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .testTag("sheet_body")
                        ) {
                            Text(
                                text = "内部按钮",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clickable { insideClicked = true }
                                    .testTag("sheet_inner_button")
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("sheet_inner_button").performClick()
        compose.runOnIdle {
            assertTrue(insideClicked)
            assertFalse(dismissed)
            assertFalse(underlyingClicked)
        }

        compose.onNodeWithTag("sheet_body").performTouchInput {
            click(Offset(x = 12f, y = 12f))
        }
        compose.runOnIdle {
            assertFalse("Tapping inside sheet surface should not dismiss it", dismissed)
            assertFalse(underlyingClicked)
        }

        compose.onNodeWithTag("glass_bottom_sheet_scrim").performTouchInput {
            click(Offset(x = 65f, y = height - 60f))
        }
        compose.runOnIdle {
            assertTrue("Clicking left margin on wide screen should dismiss sheet", dismissed)
            assertFalse("Left margin click must not leak to underlying wide content", underlyingClicked)
        }

        dismissed = false
        compose.onNodeWithTag("glass_bottom_sheet_scrim").performTouchInput {
            click(Offset(x = width - 65f, y = height - 60f))
        }
        compose.runOnIdle {
            assertTrue("Clicking right margin on wide screen should dismiss sheet", dismissed)
            assertFalse("Right margin click must not leak to underlying wide content", underlyingClicked)
        }
    }

    @Test
    fun subpageNavigationPreservesSettingsScrollPosition() {
        compose.setContent {
            Box(modifier = Modifier.size(width = 400.dp, height = 420.dp)) {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    nickname = "老K"
                )
            }
        }

        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_open_logs").performScrollTo()
        val yBeforeOpeningLogs = compose.onNodeWithTag("settings_open_logs").fetchSemanticsNode().positionInRoot.y

        compose.onNodeWithTag("settings_open_logs").performClick()
        compose.onNodeWithTag("logs_screen").assertIsDisplayed()
        compose.onNodeWithTag("logs_back").performClick()
        compose.onNodeWithTag("settings_open_logs").assertIsDisplayed()

        val yAfterReturning = compose.onNodeWithTag("settings_open_logs").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "Settings scroll position should be preserved across logs navigation (before=$yBeforeOpeningLogs, after=$yAfterReturning)",
            abs(yBeforeOpeningLogs - yAfterReturning) < 4f
        )
    }

    @Test
    @Config(sdk = [34])
    fun legacyAndroidSystemBarColorsAndIconsFollowDaylightAndNightRideThemes() {
        val preOreoLight = groupSystemBarStyle(MotoComThemeMode.DAYLIGHT, sdkInt = 26)
        assertEquals(DaylightGroupGlassColors.canvas.toArgb(), preOreoLight.statusBarColor)
        assertEquals(NightRideGroupGlassColors.canvas.toArgb(), preOreoLight.navigationBarColor)
        assertTrue(preOreoLight.isLightStatusBars)
        assertFalse(preOreoLight.isLightNavigationBars)

        val controller = Robolectric.buildActivity(GroupActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.applySystemBarAppearance(MotoComThemeMode.DAYLIGHT)
            @Suppress("DEPRECATION")
            assertEquals(DaylightGroupGlassColors.canvas.toArgb(), activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(DaylightGroupGlassColors.canvas.toArgb(), activity.window.navigationBarColor)
            val lightController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertTrue(lightController.isAppearanceLightStatusBars)
            assertTrue(lightController.isAppearanceLightNavigationBars)

            activity.applySystemBarAppearance(MotoComThemeMode.NIGHTRIDE)
            @Suppress("DEPRECATION")
            assertEquals(NightRideGroupGlassColors.canvas.toArgb(), activity.window.statusBarColor)
            @Suppress("DEPRECATION")
            assertEquals(NightRideGroupGlassColors.canvas.toArgb(), activity.window.navigationBarColor)
            val darkController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(darkController.isAppearanceLightStatusBars)
            assertFalse(darkController.isAppearanceLightNavigationBars)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w420dp-h900dp")
    fun lobbyRemovesCollapseButtonAndPinsBottomAudioBar() {
        compose.setContent {
            Box(modifier = Modifier.width(412.dp).height(900.dp)) {
                GroupScreen(
                    state = GroupServiceState(
                        snapshot = null,
                        busy = false,
                        route = AudioRouteSelection.BLUETOOTH,
                        vox = true
                    ),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onCopy = {},
                    onSettings = {}
                )
            }
        }

        compose.onNodeWithText("收起对讲").assertDoesNotExist()
        compose.onNodeWithContentDescription("摩声骑行头盔").assertIsDisplayed()
        compose.onNodeWithTag("lobby_audio_quick_bar").performScrollTo().assertIsDisplayed()
        val barTop = compose.onNodeWithTag("lobby_audio_quick_bar").fetchSemanticsNode().boundsInRoot.top
        val createBottom = compose.onNodeWithTag("group_join").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "Lobby audio bar should anchor below cards near bottom of 900dp viewport (barTop=$barTop, createBottom=$createBottom)",
            barTop > createBottom
        )
        compose.onNodeWithTag("lobby_audio_quick_bar").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test
    fun roomCodeHeroCardUnifiesAndTogglesAcrossModes() {
        val ownerId = java.util.UUID(0, 101).toString()
        val peerId = java.util.UUID(0, 102).toString()
        val roomKey = GroupRoomKey(java.util.UUID(0, 110).toString(), java.util.UUID(0, 111).toString())
        val ownerLease = GroupMemberLease(ownerId, java.util.UUID(0, 111).toString(), 1, 1)
        val peerLease = GroupMemberLease(peerId, java.util.UUID(0, 112).toString(), 2, 1)
        val roster = com.kuma.motointercom.group.GroupRoster(
            key = roomKey,
            hostId = ownerId,
            publication = 1,
            ended = false,
            members = listOf(
                GroupMember(ownerLease, role = GroupRole.OWNER),
                GroupMember(peerLease, role = GroupRole.HEAD)
            ),
            links = emptyList(),
            names = mapOf(ownerId to "老K", peerId to "头车阿明")
        )
        val participation = GroupParticipation().begin(roomKey)
        var copied = ""
        var currentMode by mutableStateOf(GroupMode.LOCAL)
        compose.setContent {
            val snap = when (currentMode) {
                GroupMode.LOCAL -> GroupSessionSnapshot(
                    phase = GroupPhase.IN_ROOM,
                    operation = null,
                    networkAttempt = null,
                    view = roster,
                    local = ownerLease,
                    participation = participation,
                    code = GroupJoinCode("849216"),
                    host = true,
                    message = "",
                    matches = emptyList(),
                    names = roster.names,
                    removed = emptySet(),
                    mode = GroupMode.LOCAL
                )
                GroupMode.HYBRID -> GroupSessionSnapshot(
                    phase = GroupPhase.IN_ROOM,
                    operation = null,
                    networkAttempt = null,
                    view = roster,
                    local = ownerLease,
                    participation = participation,
                    code = null,
                    host = true,
                    message = "",
                    matches = emptyList(),
                    names = roster.names,
                    removed = emptySet(),
                    mode = GroupMode.HYBRID,
                    inviteCode = "527904",
                    activeTransport = GroupTransport.LOCAL
                )
                GroupMode.NETWORK -> GroupSessionSnapshot(
                    phase = GroupPhase.IN_ROOM,
                    operation = null,
                    networkAttempt = null,
                    view = roster,
                    local = ownerLease,
                    participation = participation,
                    code = null,
                    host = true,
                    message = "",
                    matches = emptyList(),
                    names = roster.names,
                    removed = emptySet(),
                    mode = GroupMode.NETWORK,
                    inviteCode = "618390"
                )
            }
            Box(modifier = Modifier.width(412.dp).height(900.dp)) {
                GroupScreen(
                    state = GroupServiceState(snap, busy = true),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onCopy = { copied = it },
                    onSettings = {}
                )
            }
        }

        // LOCAL mode: unified 3+3 hero code + collapsible + inline private shortcut
        compose.onNodeWithTag("local_room_code").assertIsDisplayed().assertTextContains("房间码 849216")
        compose.onNodeWithTag("network_rotate_invite").assertDoesNotExist()
        compose.onNodeWithTag("private_call_$peerId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("member_role_$peerId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("room_code_copy").performScrollTo().performClick()
        assertEquals("849216", copied)
        compose.onNodeWithTag("room_code_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("local_room_code").assertIsDisplayed().assertTextContains("房间码 849216")

        // HYBRID mode: same slot, invite code + rotate + inline private shortcut
        currentMode = GroupMode.HYBRID
        compose.waitForIdle()
        compose.onNodeWithTag("network_invite_code").assertIsDisplayed().assertTextContains("527904")
        compose.onNodeWithTag("network_rotate_invite").assertIsDisplayed()
        compose.onNodeWithTag("private_call_$peerId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("room_code_copy").performScrollTo().performClick()
        assertEquals("527904", copied)

        // NETWORK mode: same slot, invite code + rotate, NO private shortcut, bottom dock pinned
        currentMode = GroupMode.NETWORK
        compose.waitForIdle()
        compose.onNodeWithTag("network_invite_code").assertIsDisplayed().assertTextContains("618390")
        compose.onNodeWithTag("network_rotate_invite").assertIsDisplayed()
        compose.onNodeWithTag("private_call_$peerId").assertDoesNotExist()
        compose.onNodeWithTag("group_leave").performScrollTo().assertIsDisplayed()
        val dockBottom = compose.onNodeWithTag("group_leave").fetchSemanticsNode().boundsInRoot.bottom
        val listBottom = compose.onNodeWithTag("group_member_count").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Bottom riding dock must stay below member list (dockBottom=$dockBottom, listBottom=$listBottom)", dockBottom > listBottom)
    }

    @Test
    fun hybridCloudDockHidesLocalVoxAndSurvivesNarrowLargeFont() {
        val ownerId = java.util.UUID(0, 201).toString()
        val peerId = java.util.UUID(0, 202).toString()
        val roomKey = GroupRoomKey(java.util.UUID(0, 210).toString(), java.util.UUID(0, 211).toString())
        val ownerLease = GroupMemberLease(ownerId, java.util.UUID(0, 211).toString(), 1, 1)
        val peerLease = GroupMemberLease(peerId, java.util.UUID(0, 212).toString(), 2, 1)
        val roster = com.kuma.motointercom.group.GroupRoster(
            key = roomKey,
            hostId = ownerId,
            publication = 1,
            ended = false,
            members = listOf(
                GroupMember(ownerLease, role = GroupRole.OWNER),
                GroupMember(peerLease, role = GroupRole.HEAD)
            ),
            links = emptyList(),
            names = mapOf(ownerId to "老K", peerId to "头车阿明")
        )
        val hybridCloudSnap = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = null,
            networkAttempt = null,
            view = roster,
            local = ownerLease,
            participation = GroupParticipation().begin(roomKey),
            code = null,
            host = true,
            message = "",
            matches = emptyList(),
            names = roster.names,
            removed = emptySet(),
            mode = GroupMode.HYBRID,
            inviteCode = "527904",
            activeTransport = GroupTransport.CLOUD,
            activeSpeakerIds = setOf(peerId)
        )

        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1.5f)) {
                Box(modifier = Modifier.width(320.dp).height(760.dp)) {
                    GroupScreen(
                        state = GroupServiceState(
                            snapshot = hybridCloudSnap,
                            busy = true,
                            route = AudioRouteSelection.BLUETOOTH,
                            vox = true
                        ),
                        onStart = {},
                        onAction = {},
                        onRoute = {},
                        onVox = {},
                        onBack = {},
                        onCopy = {},
                        onSettings = {}
                    )
                }
            }
        }

        // P1-1: Hybrid CLOUD must NOT report VOX active even when local vox preference is true
        compose.onNodeWithTag("open_audio_sheet").performScrollTo().assertTextContains("云端直通", substring = true)
        compose.onNodeWithTag("group_mic_toggle").performScrollTo().assertTextContains("麦克风开", substring = true)
        compose.onNodeWithText("声控已开").assertDoesNotExist()
        compose.onNodeWithText("VOX 自动开麦").assertDoesNotExist()

        // P1-2: On 320dp + 1.5x fontScale, Dock mic CTA stacks full-width so icon & label stay legible
        val micBounds = compose.onNodeWithTag("group_mic_toggle").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue(
            "Primary mic button must retain ample width on 320dp@1.5x (widthDp=${micBounds.width / density})",
            micBounds.width / density >= 200f
        )

        // P2-1: Collapsed cloud invite code keeps full row width so all 6 digits remain visible
        compose.onNodeWithTag("room_code_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("network_invite_code").performScrollTo().assertIsDisplayed().assertTextContains("527904")
        val collapsedCodeBounds = compose.onNodeWithTag("network_invite_code").fetchSemanticsNode().boundsInRoot
        val collapsedToggleBounds = compose.onNodeWithTag("room_code_toggle").fetchSemanticsNode().boundsInRoot
        val collapsedCopyBounds = compose.onNodeWithTag("room_code_copy").fetchSemanticsNode().boundsInRoot
        val separatedVertically = collapsedToggleBounds.top >= collapsedCodeBounds.bottom
        val separatedHorizontally = collapsedToggleBounds.left >= collapsedCodeBounds.right
        assertTrue(
            "Collapsed invite code must never overlap actions on 320dp@1.5x (code=$collapsedCodeBounds, toggle=$collapsedToggleBounds)",
            separatedVertically || separatedHorizontally
        )
        assertTrue(
            "Collapsed copy action must fit inside 320dp viewport (copyRightDp=${collapsedCopyBounds.right / density})",
            collapsedCopyBounds.right / density <= 320f
        )

        // P2-2: Local/Hybrid peer member name stays legible and unobstructed above action chips
        compose.onNodeWithText("头车阿明 · 头车").performScrollTo().assertIsDisplayed()
        val peerNameTextBounds = compose.onNodeWithText("头车阿明 · 头车", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Peer display name text itself must stay legible on 320dp@1.5x (widthDp=${peerNameTextBounds.width / density})",
            peerNameTextBounds.width / density >= 120f
        )
        compose.onNodeWithTag("private_call_$peerId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("member_role_$peerId").performScrollTo().assertIsDisplayed()
        val muteRightDp = compose.onNodeWithTag("member_mute_$peerId").performScrollTo().assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.right / density
        assertTrue("Peer action row must fit inside 320dp viewport (muteRightDp=$muteRightDp)", muteRightDp <= 320f)

        // Verify no duplicate network_active_speakers tag in Hybrid CLOUD mode
        compose.onNodeWithTag("network_active_speakers").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w480dp-h900dp")
    fun collapsedCloudInviteCodeAvoidsTruncationAt374dpAnd412dp() {
        val ownerId = java.util.UUID(0, 301).toString()
        val roomKey = GroupRoomKey(java.util.UUID(0, 310).toString(), java.util.UUID(0, 311).toString())
        val ownerLease = GroupMemberLease(ownerId, java.util.UUID(0, 311).toString(), 1, 1)
        val roster = com.kuma.motointercom.group.GroupRoster(
            key = roomKey,
            hostId = ownerId,
            publication = 1,
            ended = false,
            members = listOf(GroupMember(ownerLease, role = GroupRole.OWNER)),
            links = emptyList(),
            names = mapOf(ownerId to "老K")
        )
        val netSnap = GroupSessionSnapshot(
            phase = GroupPhase.IN_ROOM,
            operation = null,
            networkAttempt = null,
            view = roster,
            local = ownerLease,
            participation = GroupParticipation().begin(roomKey),
            code = null,
            host = true,
            message = "",
            matches = emptyList(),
            names = roster.names,
            removed = emptySet(),
            mode = GroupMode.NETWORK,
            inviteCode = "618390"
        )
        var viewportWidth by mutableStateOf(374.dp)
        var testFontScale by mutableStateOf(1.0f)
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = testFontScale)) {
                Box(modifier = Modifier.width(viewportWidth).height(800.dp)) {
                    GroupScreen(
                        state = GroupServiceState(netSnap, busy = true),
                        onStart = {},
                        onAction = {},
                        onRoute = {},
                        onVox = {},
                        onBack = {},
                        onCopy = {},
                        onSettings = {}
                    )
                }
            }
        }

        val density = compose.density.density
        // Verify expanded 220dp @ 1.0x digits stay strictly within card inner width before toggling collapsed
        viewportWidth = 220.dp
        testFontScale = 1.0f
        compose.waitForIdle()
        val expandedDigits220 = compose.onNodeWithTag("network_invite_code_expanded_digits", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expanded 220dp@1.0x digits must stay within card inner width (rightDp=${expandedDigits220.right / density})",
            expandedDigits220.right / density <= 220f - 18f - 14f + 0.5f
        )
        compose.onNodeWithTag("network_invite_code").assertTextContains("618390")

        viewportWidth = 374.dp
        compose.waitForIdle()
        compose.onNodeWithTag("room_code_toggle").performScrollTo().performClick()

        fun assertCollapsedFitsWithoutOverlapOrOverflow(
            widthDp: Float,
            expectedDigitsWidth: Float,
            expectedCopyWidth: Float,
            label: String
        ) {
            val codeBounds = compose.onNodeWithTag("network_invite_code").fetchSemanticsNode().boundsInRoot
            val labelBounds = compose.onNodeWithTag("network_invite_code_label", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val digitsBounds = compose.onNodeWithTag("network_invite_code_digits", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val toggleBounds = compose.onNodeWithTag("room_code_toggle").fetchSemanticsNode().boundsInRoot
            val rotateBounds = compose.onNodeWithTag("network_rotate_invite").fetchSemanticsNode().boundsInRoot
            val copyBounds = compose.onNodeWithTag("room_code_copy").fetchSemanticsNode().boundsInRoot
            val cardInnerRightDp = widthDp - 18f - 14f + 0.5f

            assertEquals(
                "$label: collapsed 6-digit invite code must preserve exact natural untruncated width",
                expectedDigitsWidth,
                digitsBounds.width,
                1f
            )
            assertEquals(
                "$label: copy chip must preserve exact natural untruncated width",
                expectedCopyWidth,
                copyBounds.width,
                1f
            )

            val labelAndDigitsSingleRow = digitsBounds.left >= labelBounds.right + 6f * density &&
                kotlin.math.abs(digitsBounds.center.y - labelBounds.center.y) < 16f * density
            val labelAndDigitsStacked = digitsBounds.top >= labelBounds.bottom
            assertTrue(
                "$label: short label and 6-digit code must not overlap (label=$labelBounds, digits=$digitsBounds)",
                labelAndDigitsSingleRow || labelAndDigitsStacked
            )

            val stacked = toggleBounds.top >= codeBounds.bottom
            val singleRowNoOverlap = toggleBounds.left >= codeBounds.right + 7f * density &&
                kotlin.math.abs(toggleBounds.center.y - codeBounds.center.y) < 20f * density
            assertTrue(
                "$label: collapsed code and actions must either fit single-row with gap or stack cleanly (code=$codeBounds, toggle=$toggleBounds)",
                stacked || singleRowNoOverlap
            )

            assertTrue(
                "$label: toggle and rotate buttons must not overlap (toggle=$toggleBounds, rotate=$rotateBounds)",
                rotateBounds.left >= toggleBounds.right + 5f * density
            )
            val copyInline = copyBounds.left >= rotateBounds.right + 5f * density &&
                kotlin.math.abs(copyBounds.center.y - rotateBounds.center.y) < 20f * density
            val copyWrapped = copyBounds.top >= rotateBounds.bottom
            assertTrue(
                "$label: copy chip must sit cleanly beside or below icon buttons (rotate=$rotateBounds, copy=$copyBounds)",
                copyInline || copyWrapped
            )

            assertTrue(
                "$label: all collapsed elements must stay within card inner width ${cardInnerRightDp}dp (digitsRight=${digitsBounds.right / density}, rotateRight=${rotateBounds.right / density}, copyRight=${copyBounds.right / density})",
                labelBounds.right / density <= cardInnerRightDp &&
                    digitsBounds.right / density <= cardInnerRightDp &&
                    codeBounds.right / density <= cardInnerRightDp &&
                    toggleBounds.right / density <= cardInnerRightDp &&
                    rotateBounds.right / density <= cardInnerRightDp &&
                    copyBounds.right / density <= cardInnerRightDp
            )
        }

        // Measure natural single-row width at generous width, then verify narrower/scaled viewports never shrink the code digits
        viewportWidth = 480.dp
        testFontScale = 1.0f
        compose.waitForIdle()
        val naturalDigitsWidth1x = compose.onNodeWithTag("network_invite_code_digits", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val naturalCopyWidth1x = compose.onNodeWithTag("room_code_copy").fetchSemanticsNode().boundsInRoot.width

        viewportWidth = 412.dp
        testFontScale = 1.0f
        compose.waitForIdle()
        assertCollapsedFitsWithoutOverlapOrOverflow(412f, naturalDigitsWidth1x, naturalCopyWidth1x, "412dp@1.0x")

        viewportWidth = 374.dp
        testFontScale = 1.0f
        compose.waitForIdle()
        assertCollapsedFitsWithoutOverlapOrOverflow(374f, naturalDigitsWidth1x, naturalCopyWidth1x, "374dp@1.0x")

        // Measure natural 1.15x baseline at wide width, then verify 404dp and ultra-narrow 220dp never clip digits or actions
        viewportWidth = 480.dp
        testFontScale = 1.15f
        compose.waitForIdle()
        val naturalDigitsWidth115x = compose.onNodeWithTag("network_invite_code_digits", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val naturalCopyWidth115x = compose.onNodeWithTag("room_code_copy").fetchSemanticsNode().boundsInRoot.width

        viewportWidth = 404.dp
        testFontScale = 1.15f
        compose.waitForIdle()
        assertCollapsedFitsWithoutOverlapOrOverflow(404f, naturalDigitsWidth115x, naturalCopyWidth115x, "404dp@1.15x")

        viewportWidth = 220.dp
        testFontScale = 1.15f
        compose.waitForIdle()
        assertCollapsedFitsWithoutOverlapOrOverflow(220f, naturalDigitsWidth115x, naturalCopyWidth115x, "220dp@1.15x")
        val code220 = compose.onNodeWithTag("network_invite_code").fetchSemanticsNode().boundsInRoot
        val toggle220 = compose.onNodeWithTag("room_code_toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Tight width must automatically stack actions below collapsed code (code=$code220, toggle=$toggle220)",
            toggle220.top >= code220.bottom
        )

        // Verify 220dp @ 1.5x exercises both label-over-digits wrap and icon-over-copy wrap with zero truncation
        viewportWidth = 480.dp
        testFontScale = 1.5f
        compose.waitForIdle()
        val naturalDigitsWidth15x = compose.onNodeWithTag("network_invite_code_digits", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val naturalCopyWidth15x = compose.onNodeWithTag("room_code_copy").fetchSemanticsNode().boundsInRoot.width

        viewportWidth = 220.dp
        testFontScale = 1.5f
        compose.waitForIdle()
        assertCollapsedFitsWithoutOverlapOrOverflow(220f, naturalDigitsWidth15x, naturalCopyWidth15x, "220dp@1.5x")
        val code220x15 = compose.onNodeWithTag("network_invite_code").fetchSemanticsNode().boundsInRoot
        val toggle220x15 = compose.onNodeWithTag("room_code_toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "220dp@1.5x must stack actions below collapsed code (code=$code220x15, toggle=$toggle220x15)",
            toggle220x15.top >= code220x15.bottom
        )

        // Verify collapsed 220dp @ 2.0x remains within card inner bounds and preserves semantic 6-digit code
        viewportWidth = 220.dp
        testFontScale = 2.0f
        compose.waitForIdle()
        val digits220x2 = compose.onNodeWithTag("network_invite_code_digits", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Collapsed 220dp@2.0x digits must stay within card inner width (rightDp=${digits220x2.right / density})",
            digits220x2.right / density <= 220f - 18f - 14f + 0.5f
        )
        compose.onNodeWithTag("network_invite_code").assertTextContains("618390")
    }

    @Test
    fun resolveFittedRoomCodeLayoutPreventsDigitClippingInExpandedAnd200PercentCollapsed() {
        val singleLineText = "618 · 390"
        val stackedText = com.kuma.motointercom.group.ui.formatStackedThreePlusThree("618390")
        assertEquals("618\n390", stackedText)

        val expandedBase = com.kuma.motointercom.group.ui.GroupGlassTypography.metric.copy(letterSpacing = 2.sp)
        val collapsedBase = com.kuma.motointercom.group.ui.GroupGlassTypography.cardTitle.copy(
            fontFamily = com.kuma.motointercom.group.ui.GroupGlassTypography.metric.fontFamily,
            letterSpacing = 1.sp
        )
        // Android 15 monospace width model matching reviewer's measurement across both "618 · 390" (9 chars) and "618" (3 chars):
        // Expanded 28sp + 2sp @ 1.0x -> ~167dp; Collapsed 16sp + 1sp @ 2.0x -> ~167dp; "618" @ 10sp 200% -> ~34dp
        fun android15ExpandedWidth(sample: String, style: androidx.compose.ui.text.TextStyle): Int {
            val count = sample.length
            val glyphWidth = count * style.fontSize.value * 0.60f
            val spacing = (count - 1).coerceAtLeast(0) * style.letterSpacing.value
            return kotlin.math.ceil((glyphWidth + spacing).toDouble()).toInt()
        }
        fun android15Collapsed200Width(sample: String, style: androidx.compose.ui.text.TextStyle): Int {
            val count = sample.length
            val scaledSize = style.fontSize.value * 1.72f
            val glyphWidth = count * scaledSize * 0.62f
            val spacing = (count - 1).coerceAtLeast(0) * style.letterSpacing.value * 1.72f
            return kotlin.math.ceil((glyphWidth + spacing).toDouble()).toInt()
        }

        // Normal wide phone (332dp expanded inner width): exact baseStyle untouched
        val expandedNormal = com.kuma.motointercom.group.ui.resolveFittedRoomCodeLayout(
            singleLineText = singleLineText,
            stackedText = stackedText,
            baseStyle = expandedBase,
            maxWidthPx = 332,
            measureTextWidthPx = ::android15ExpandedWidth
        )
        assertTrue(expandedNormal.singleLineFits)
        assertEquals(singleLineText, expandedNormal.displayText)
        assertEquals(1, expandedNormal.maxLines)
        assertEquals(28f, expandedNormal.style.fontSize.value, 0.01f)
        assertEquals(2f, expandedNormal.style.letterSpacing.value, 0.01f)

        // 220dp @ 100% expanded (card inner 156dp minus 16dp Surface horizontal padding = 140dp; base needs ~167dp)
        assertTrue(android15ExpandedWidth(singleLineText, expandedBase) > 140)
        val expandedNarrow = com.kuma.motointercom.group.ui.resolveFittedRoomCodeLayout(
            singleLineText = singleLineText,
            stackedText = stackedText,
            baseStyle = expandedBase,
            maxWidthPx = 140,
            measureTextWidthPx = ::android15ExpandedWidth
        )
        assertTrue(expandedNarrow.singleLineFits)
        assertEquals(singleLineText, expandedNarrow.displayText)
        assertTrue(android15ExpandedWidth(expandedNarrow.displayText, expandedNarrow.style) <= 140)
        assertTrue(expandedNarrow.style.fontSize.value < 28f && expandedNarrow.style.fontSize.value >= 10f)

        // 220dp @ 200% collapsed (card inner 156dp; base needs ~167dp)
        assertTrue(android15Collapsed200Width(singleLineText, collapsedBase) > 156)
        val collapsed200 = com.kuma.motointercom.group.ui.resolveFittedRoomCodeLayout(
            singleLineText = singleLineText,
            stackedText = stackedText,
            baseStyle = collapsedBase,
            maxWidthPx = 156,
            measureTextWidthPx = ::android15Collapsed200Width
        )
        assertTrue(collapsed200.singleLineFits)
        assertEquals(singleLineText, collapsed200.displayText)
        assertTrue(android15Collapsed200Width(collapsed200.displayText, collapsed200.style) <= 156)
        assertTrue(collapsed200.style.fontSize.value < 16f && collapsed200.style.fontSize.value >= 10f)

        // Extreme narrow 46dp @ 200%: drops middle dot and renders explicit 2-line "618\n390" where each 3-digit line fits <= 46dp
        val narrow46Fallback = com.kuma.motointercom.group.ui.resolveFittedRoomCodeLayout(
            singleLineText = singleLineText,
            stackedText = stackedText,
            baseStyle = collapsedBase,
            maxWidthPx = 46,
            measureTextWidthPx = ::android15Collapsed200Width
        )
        assertFalse(narrow46Fallback.singleLineFits)
        assertEquals("618\n390", narrow46Fallback.displayText)
        assertEquals(10f, narrow46Fallback.style.fontSize.value, 0.01f)
        assertEquals(2, narrow46Fallback.maxLines)
        assertFalse(narrow46Fallback.softWrap)
        assertTrue(
            narrow46Fallback.displayText.split('\n').all { line ->
                android15Collapsed200Width(line, narrow46Fallback.style) <= 46
            }
        )

        // Ultra-extreme 24dp @ 200% where even 3 digits at 10sp exceed width: uncaps maxLines with softWrap so no digit is ever clipped
        val ultraNarrow24Fallback = com.kuma.motointercom.group.ui.resolveFittedRoomCodeLayout(
            singleLineText = singleLineText,
            stackedText = stackedText,
            baseStyle = collapsedBase,
            maxWidthPx = 24,
            measureTextWidthPx = ::android15Collapsed200Width
        )
        assertFalse(ultraNarrow24Fallback.singleLineFits)
        assertEquals("618\n390", ultraNarrow24Fallback.displayText)
        assertEquals(Int.MAX_VALUE, ultraNarrow24Fallback.maxLines)
        assertTrue(ultraNarrow24Fallback.softWrap)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w480dp-h900dp")
    fun adaptiveTrailingRowPreventsTruncationUnderAndroid15NarrowMetrics() {
        var cardInnerWidth by mutableStateOf(356.dp)
        compose.setContent {
            Box(modifier = Modifier.width(cardInnerWidth)) {
                com.kuma.motointercom.group.ui.AdaptiveTrailingRow(
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                    leading = {
                        com.kuma.motointercom.group.ui.AdaptiveTrailingRow(
                            horizontalSpacing = 7.dp,
                            verticalSpacing = 2.dp,
                            fillMaxWidth = false,
                            alignTrailingEnd = false,
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .width(57.dp)
                                        .height(16.dp)
                                        .testTag("metric_label")
                                )
                            },
                            trailing = {
                                Box(
                                    modifier = Modifier
                                        .width(107.dp)
                                        .height(22.dp)
                                        .testTag("metric_digits")
                                )
                            }
                        )
                    },
                    trailing = {
                        com.kuma.motointercom.group.ui.AdaptiveTrailingRow(
                            horizontalSpacing = 6.dp,
                            verticalSpacing = 6.dp,
                            fillMaxWidth = false,
                            alignTrailingEnd = true,
                            alignLeadingEndWhenStacked = true,
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .width(102.dp)
                                        .height(48.dp)
                                        .testTag("metric_icons")
                                )
                            },
                            trailing = {
                                Box(
                                    modifier = Modifier
                                        .width(68.dp)
                                        .height(36.dp)
                                        .testTag("metric_copy")
                                )
                            }
                        )
                    }
                )
            }
        }

        val density = compose.density.density
        fun readBounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

        // 356dp inner width (generous phone): single-row code + single-row actions
        cardInnerWidth = 356.dp
        compose.waitForIdle()
        val label356 = readBounds("metric_label")
        val digits356 = readBounds("metric_digits")
        val icons356 = readBounds("metric_icons")
        val copy356 = readBounds("metric_copy")
        assertEquals(107f * density, digits356.width, 1f)
        assertEquals(68f * density, copy356.width, 1f)
        assertTrue(digits356.left >= label356.right + 6.5f * density)
        assertTrue(icons356.left >= digits356.right + 7.5f * density)
        assertTrue(copy356.left >= icons356.right + 5.5f * density)
        assertEquals(356f * density, copy356.right, 1f)

        // 340dp inner width (404dp @ 1.15x): actions stack below code, code & actions each keep single row
        cardInnerWidth = 340.dp
        compose.waitForIdle()
        val label340 = readBounds("metric_label")
        val digits340 = readBounds("metric_digits")
        val icons340 = readBounds("metric_icons")
        val copy340 = readBounds("metric_copy")
        assertEquals(107f * density, digits340.width, 1f)
        assertEquals(68f * density, copy340.width, 1f)
        assertTrue(digits340.left >= label340.right + 6.5f * density)
        assertTrue(icons340.top >= digits340.bottom)
        assertTrue(copy340.left >= icons340.right + 5.5f * density)
        assertEquals(340f * density, copy340.right, 1f)

        // 156dp inner width (220dp @ 1.15x): label stacks above 107dp digits, icons stack above 68dp copy, zero truncation
        cardInnerWidth = 156.dp
        compose.waitForIdle()
        val label156 = readBounds("metric_label")
        val digits156 = readBounds("metric_digits")
        val icons156 = readBounds("metric_icons")
        val copy156 = readBounds("metric_copy")
        assertEquals(57f * density, label156.width, 1f)
        assertEquals(107f * density, digits156.width, 1f)
        assertEquals(102f * density, icons156.width, 1f)
        assertEquals(68f * density, copy156.width, 1f)
        assertTrue("220dp@1.15x must stack digits below label", digits156.top >= label156.bottom)
        assertTrue("220dp@1.15x must stack actions below digits", icons156.top >= digits156.bottom)
        assertTrue("220dp@1.15x must stack copy below icon pair", copy156.top >= icons156.bottom)
        assertTrue("All elements must stay within 156dp card inner width", digits156.right <= 156f * density + 1f)
        assertEquals(156f * density, icons156.right, 1f)
        assertEquals(156f * density, copy156.right, 1f)
    }
}

internal class RecordingRuntimeHandle : GroupRuntimeHandle {
    override val snapshot: GroupSessionSnapshot = GroupSessionSnapshot(
        phase = GroupPhase.IDLE,
        operation = null,
        networkAttempt = null,
        view = null,
        local = null,
        participation = GroupParticipation(),
        code = null,
        host = false,
        message = "idle",
        matches = emptyList(),
        names = emptyMap(),
        removed = emptySet()
    )
    val voxValues = mutableListOf<Boolean>()
    val voxSensitivities = mutableListOf<Int>()
    override fun start(code: GroupJoinCode?) = Unit
    override fun dispatch(event: GroupSessionEvent) = Unit
    override fun route(value: AudioRouteSelection) = Unit
    override fun vox(value: Boolean) { voxValues += value }
    override fun voxSensitivity(value: Int) { voxSensitivities += value }
    override fun close() = Unit
}

internal class RecordingAudioService : GroupIntercomService() {
    var capturedInitialRoute: AudioRouteSelection? = null
    var capturedInitialControls: AudioControlSettings? = null
    var fakeRuntime: RecordingRuntimeHandle? = null

    override fun available(): Boolean = true
    override fun identityStore(): LocalIdentityStore = object : LocalIdentityStore {
        override suspend fun getOrCreateDeviceId(): String = "dev-test-audio"
        override suspend fun getNickname(): String = "老K"
        override suspend fun updateNickname(value: String) = Unit
    }

    override fun createLocalRuntimeInstance(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        initialRoute: AudioRouteSelection,
        initialControls: AudioControlSettings,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle {
        capturedInitialRoute = initialRoute
        capturedInitialControls = initialControls
        return RecordingRuntimeHandle().also { fakeRuntime = it }
    }

    override fun createHybridRuntimeInstance(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        request: GroupCloudStartRequest,
        initialRoute: AudioRouteSelection,
        initialControls: AudioControlSettings,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle {
        capturedInitialRoute = initialRoute
        capturedInitialControls = initialControls
        return RecordingRuntimeHandle().also { fakeRuntime = it }
    }
}
