package com.kuma.motointercom

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val menuClicked = AtomicBoolean(false)
        val state = HomeScreenUiState(
            primaryText = "点击下方启动摩声",
            detailText = "一对一对讲 · 无需网络",
            supplementalText = "请点击下方启动对讲",
            peerText = "等待车友加入",
            primaryActionLabel = "启动",
            primaryActionEnabled = true,
            disabledReason = null,
            showPermissionGrantCta = false,
            showPermissionSettingsCta = false,
            showWifiSettingsCta = false,
            discoverCtaLabel = "查看附近车友",
            showDiscoverCta = false,
            audioSourceText = "当前音频源：待机",
            plannedTransportText = "待建立",
            connectedTransportText = "未连接",
            webRtcText = "未连接",
            bluetoothText = "蓝牙状态不可用",
            voxText = "IDLE",
            discovering = false,
            connected = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComHomeScreen(
                    state = state,
                    audioLevel = 0f,
                    onMenu = { menuClicked.set(true) },
                    onSettings = {},
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

        composeRule.onNodeWithText(state.primaryText).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.home_transport_plan, state.plannedTransportText)
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.menu_button_description)
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(state.primaryActionLabel)
            .assertHasClickAction()

        composeRule.runOnIdle { assertTrue(menuClicked.get()) }
    }

    @Test
    fun muteAndVoxExposeRealStateAndCallbacks() {
        var requestedMute: Boolean? = null
        var voxOpened = false
        composeRule.setContent {
            MotoComTheme {
                MotoComHomeScreen(
                    state = HomeScreenUiState(
                        primaryText = "已连接",
                        detailText = "对讲可用",
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
                        audioSourceText = "当前音频源：蓝牙耳机",
                        plannedTransportText = "Wi-Fi Direct",
                        connectedTransportText = "Wi-Fi Direct",
                        webRtcText = "已连接",
                        bluetoothText = "已连接",
                        voxText = "OPEN",
                        discovering = false,
                        connected = true,
                        muted = true,
                        muteEnabled = true,
                        voxEnabled = true,
                        voxSensitivity = 70,
                        voxState = VoxRuntimeState.OPEN
                    ),
                    audioLevel = 0.5f,
                    onMenu = {},
                    onSettings = {},
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
        composeRule.onNodeWithText("开麦\nOPEN").fetchSemanticsNode()
        composeRule.onNodeWithTag("home_vox_card")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.runOnIdle {
            assertEquals(false, requestedMute)
            assertTrue(voxOpened)
        }
    }
}
