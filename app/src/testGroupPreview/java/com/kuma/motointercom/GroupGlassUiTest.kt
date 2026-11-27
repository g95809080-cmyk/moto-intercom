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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
