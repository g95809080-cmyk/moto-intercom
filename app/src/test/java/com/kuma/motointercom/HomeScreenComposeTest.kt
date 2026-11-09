package com.kuma.motointercom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun baselineStateShowsFactsAndRoutesPrimaryAction() {
        val primaryClicked = AtomicBoolean(false)
        val state = HomeScreenUiState(
            primaryText = "点击下方启动摩声",
            supplementalText = null,
            peerText = "",
            primaryActionLabel = "启动",
            primaryActionEnabled = true,
            disabledReason = null,
            showPermissionGrantCta = false,
            showPermissionSettingsCta = false,
            showWifiSettingsCta = false,
            discoverCtaLabel = "查看附近车友",
            showDiscoverCta = false,
            audioSourceText = "当前音频源：待机",
            discovering = false,
            connected = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComHomeScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    audioLevel = 0f,
                    onPrimaryAction = { primaryClicked.set(true) },
                    onDiscover = {},
                    onPermissionGrant = {},
                    onPermissionSettings = {},
                    onWifiSettings = {},
                    onMute = {},
                    onAudioSettings = {},
                    onVox = {}
                )
            }
        }

        composeRule.onNodeWithTag("home_discovery_radar")
            .assertContentDescriptionEquals(state.primaryText)
        composeRule.onNodeWithText("摩声 MotoCom").assertDoesNotExist()
        composeRule.onNodeWithText("一对一对讲 · 无需网络").assertDoesNotExist()
        composeRule.onNodeWithText(state.primaryText).assertDoesNotExist()
        composeRule.onNodeWithText("待机").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_menu_button").assertDoesNotExist()
        composeRule.onNodeWithTag("home_settings_button").assertDoesNotExist()
        composeRule.onNodeWithText(state.primaryActionLabel)
            .performScrollTo().assertHasClickAction().performClick()

        composeRule.runOnIdle { assertTrue(primaryClicked.get()) }
    }

    @Test
    fun muteAndVoxExposeRealStateAndCallbacks() {
        var requestedMute: Boolean? = null
        var voxOpened = false
        composeRule.setContent {
            MotoComTheme {
                MotoComHomeScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = HomeScreenUiState(
                        primaryText = "已连接",
                        supplementalText = null,
                        peerText = "Road Captain",
                        primaryActionLabel = "断开",
                        primaryActionEnabled = true,
                        disabledReason = null,
                        showPermissionGrantCta = false,
                        showPermissionSettingsCta = false,
                        showWifiSettingsCta = false,
                        discoverCtaLabel = "",
                        showDiscoverCta = false,
                        audioSourceText = "蓝牙耳机",
                        discovering = false,
                        connected = true,
                        muted = true,
                        muteEnabled = true,
                        voxEnabled = true,
                        voxSensitivity = 70,
                        voxState = VoxRuntimeState.OPEN
                    ),
                    audioLevel = 0.5f,
                    onPrimaryAction = {},
                    onDiscover = {},
                    onPermissionGrant = {},
                    onPermissionSettings = {},
                    onWifiSettings = {},
                    onMute = { requestedMute = it },
                    onAudioSettings = {},
                    onVox = { voxOpened = true }
                )
            }
        }

        composeRule.onNodeWithTag("home_mute_button")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("灵敏度：70").fetchSemanticsNode()
        composeRule.onNodeWithText("VOX · 开麦").fetchSemanticsNode()
        composeRule.onNodeWithTag("home_vox_state_row").assertDoesNotExist()
        composeRule.onNodeWithTag("home_vox_card")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.runOnIdle {
            assertEquals(false, requestedMute)
            assertTrue(voxOpened)
        }
    }

    @Test
    fun radarPlacementsFitTheCanvasWithoutBubbleOverlap() {
        val radii = listOf(44f, 72f, 96f, 140f, 220f)
        radii.forEach { radius ->
            (1..30).forEach countLoop@ { count ->
                val placements = radarPeerPlacements(count, radius)
                val visible = visibleRadarPeerCount(count, radius)

                assertEquals(visible, placements.size)
                assertTrue(placements.size in 1..minOf(count, 11))
                assertTrue(
                    "radius=$radius count=$count bubble sizes must stay within 48–72dp",
                    placements.all { it.bubbleSizeDp in 48f..72f }
                )
                if (radius >= 96f) {
                    placements.forEach { placement ->
                        val centerClearance =
                            radius * placement.distanceFraction - placement.bubbleSizeDp / 2f
                        assertTrue(
                            "radius=$radius count=$count bubble must clear the 94dp center node",
                            centerClearance >= 47f
                        )
                    }
                }
                if (placements.size <= 1) return@countLoop

                val ringRadius = radius * placements.first().distanceFraction
                placements.forEachIndexed { index, placement ->
                    val next = placements[(index + 1) % placements.size]
                    val startAngle = Math.toRadians(placement.angleDegrees.toDouble())
                    val endAngle = Math.toRadians(next.angleDegrees.toDouble())
                    val distance = ringRadius * hypot(
                        cos(startAngle) - cos(endAngle),
                        sin(startAngle) - sin(endAngle)
                    )
                    val minimumDistance =
                        (placement.bubbleSizeDp + next.bubbleSizeDp) / 2f
                    assertTrue(
                        "radius=$radius count=$count bubbles $index and " +
                            "${(index + 1) % placements.size} overlap",
                        distance + 0.01f >= minimumDistance
                    )
                }
            }
        }
    }

    @Test
    fun radarOverflowIsAnnouncedAndTheFirstPeerRemainsClickable() {
        var clicked: RadarPreviewPeer? = null
        val peers = (1..14).map { index ->
            RadarPreviewPeer(id = "peer-$index", label = "Rider $index")
        }
        composeRule.setContent {
            MotoComTheme {
                MotoComHomeScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = HomeScreenUiState(
                        primaryText = "正在搜索附近车友",
                        supplementalText = null,
                        peerText = "正在搜索",
                        primaryActionLabel = "停止搜索",
                        primaryActionEnabled = true,
                        disabledReason = null,
                        showPermissionGrantCta = false,
                        showPermissionSettingsCta = false,
                        showWifiSettingsCta = false,
                        discoverCtaLabel = "",
                        showDiscoverCta = false,
                        audioSourceText = "当前音频源：待机",
                        discovering = true,
                        connected = false
                    ),
                    audioLevel = 0f,
                    onPrimaryAction = {},
                    onDiscover = {},
                    onPermissionGrant = {},
                    onPermissionSettings = {},
                    onWifiSettings = {},
                    onMute = {},
                    onAudioSettings = {},
                    onVox = {},
                    radarPreviewPeers = peers,
                    onRadarPreviewPeerClick = { clicked = it }
                )
            }
        }

        val radarBounds = composeRule.onNodeWithTag("home_discovery_radar")
            .fetchSemanticsNode().boundsInRoot
        val density = composeRule.density
        val radarWidthDp = with(density) { radarBounds.width.toDp().value }
        val radarHeightDp = with(density) { radarBounds.height.toDp().value }
        val scopeRadiusDp = minOf(radarWidthDp * 0.44f, radarHeightDp * 0.32f)
        val hiddenCount =
            peers.size - visibleRadarPeerCount(peers.size, scopeRadiusDp)
        composeRule.onNodeWithTag("home_radar_overflow")
            .assertContentDescriptionEquals("另有 $hiddenCount 位车友")
        composeRule.onNodeWithTag("home_radar_peer_peer-1")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.runOnIdle {
            assertEquals("peer-1", clicked?.id)
        }
    }

    @Test
    fun connectedRadarKeepsSingleBubbleOutsideCenterAndStatusAtCompactWidth() {
        composeRule.setContent {
            MotoComTheme {
                Box(Modifier.width(320.dp)) {
                    MotoComHomeScreen(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        state = HomeScreenUiState(
                            primaryText = "已连接",
                            supplementalText = null,
                            peerText = "Road Captain",
                            primaryActionLabel = "断开",
                            primaryActionEnabled = true,
                            disabledReason = null,
                            showPermissionGrantCta = false,
                            showPermissionSettingsCta = false,
                            showWifiSettingsCta = false,
                            discoverCtaLabel = "",
                            showDiscoverCta = false,
                            audioSourceText = "蓝牙耳机",
                            discovering = false,
                            connected = true
                        ),
                        audioLevel = 0f,
                        onPrimaryAction = {},
                        onDiscover = {},
                        onPermissionGrant = {},
                        onPermissionSettings = {},
                        onWifiSettings = {},
                        onMute = {},
                        onAudioSettings = {},
                        onVox = {}
                    )
                }
            }
        }

        val radar = composeRule.onNodeWithTag("home_discovery_radar")
            .fetchSemanticsNode().boundsInRoot
        val center = composeRule.onNodeWithTag("home_radar_center")
            .fetchSemanticsNode().boundsInRoot
        val bubble = composeRule.onNodeWithTag("home_radar_peer_connected")
            .fetchSemanticsNode().boundsInRoot
        val status = composeRule.onNodeWithTag("home_radar_status")
            .fetchSemanticsNode().boundsInRoot
        val density = composeRule.density
        val minBubble = with(density) { 48.dp.toPx() }
        val maxBubble = with(density) { 72.dp.toPx() }
        val bubbleWidthDp = with(density) { bubble.width.toDp().value }
        val centerDistance = hypot(
            bubble.center.x - center.center.x,
            bubble.center.y - center.center.y
        )

        assertTrue(
            "single bubble ${bubbleWidthDp}dp must stay within 48–72dp",
            bubble.width in minBubble..maxBubble
        )
        assertTrue(
            "single bubble must clear the center node",
            centerDistance >= center.width / 2f + bubble.width / 2f
        )
        assertTrue(
            "single bubble must stay inside the radar card",
            bubble.left >= radar.left && bubble.top >= radar.top &&
                bubble.right <= radar.right && bubble.bottom <= radar.bottom
        )
        assertTrue(
            "single bubble must not overlap the status pill",
            !rectsOverlap(bubble, status)
        )
    }

    private fun rectsOverlap(
        first: androidx.compose.ui.geometry.Rect,
        second: androidx.compose.ui.geometry.Rect
    ): Boolean =
        first.left < second.right &&
            first.right > second.left &&
            first.top < second.bottom &&
            first.bottom > second.top
}
