package com.kuma.motointercom

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

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
}
