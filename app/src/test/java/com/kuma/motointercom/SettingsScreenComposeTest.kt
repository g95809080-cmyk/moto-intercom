package com.kuma.motointercom

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nicknameInputAndSaveUseStateCallbacks() {
        var nickname = ""
        var saved = false
        composeRule.setContent {
            MotoComTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = nickname,
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        attemptFacts = "No attempt",
                        discoveryCandidates = "No candidates",
                        deviceStatus = "Ready",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0"
                    ),
                    onBack = {},
                    onNicknameChanged = { nickname = it },
                    onSaveNickname = { saved = true },
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onPlaceholder = {}
                )
            }
        }

        composeRule.onNodeWithTag("settings_nickname_input").performTextInput("Road Captain")
        composeRule.onNodeWithTag("settings_save_nickname_button").performClick()
        assertEquals("Road Captain", nickname)
        assertEquals(true, saved)
        composeRule.onNodeWithTag("settings_product_state").assertTextContains("Offline")
        composeRule.onNodeWithText("自动选择 LAN / Wi-Fi Direct").fetchSemanticsNode()
    }

    @Test
    fun voxControlsExposeRealSettingsAndExactRuntimeState() {
        var enabled: Boolean? = null
        var sensitivity: Int? = null
        composeRule.setContent {
            MotoComTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Connected",
                        attemptFacts = "Wi-Fi Direct",
                        discoveryCandidates = "No candidates",
                        deviceStatus = "Ready",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0",
                        voxEnabled = true,
                        voxSensitivity = 60,
                        voxState = VoxRuntimeState.HANGOVER
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onPlaceholder = {},
                    onVoxEnabledChanged = { enabled = it },
                    onVoxSensitivityChanged = { sensitivity = it }
                )
            }
        }

        composeRule.onNodeWithTag("settings_vox_button").performClick()
        composeRule.onNodeWithTag("settings_vox_sensitivity_button")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        composeRule.onNodeWithText("HANGOVER").assertTextContains("HANGOVER")

        composeRule.runOnIdle {
            assertEquals(false, enabled)
            assertEquals(80, sensitivity)
        }
    }
}
