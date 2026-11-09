package com.kuma.motointercom

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Composable
    private fun ScrollableSettingsTheme(content: @Composable () -> Unit) {
        MotoComTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }

    @Test
    fun nicknameInputAndSaveUseStateCallbacks() {
        var nickname = ""
        var saved = false
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = nickname,
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
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
                    onHelp = {}
                )
            }
        }

        composeRule.onNodeWithTag("settings_nickname_input").performTextInput("Road Captain")
        composeRule.onNodeWithTag("settings_save_nickname_button").performClick()
        assertEquals("Road Captain", nickname)
        assertEquals(true, saved)
        composeRule.onNodeWithTag("settings_product_state", useUnmergedTree = true)
            .assertTextContains("Offline")
        composeRule.onNodeWithTag("settings_audio_route_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun saveActionExposesSingleAccessibleLabelWithoutHeaderBackButton() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0"
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {}
                )
            }
        }

        val saveLabel = context.getString(R.string.settings_save_nickname)
        composeRule.onAllNodesWithContentDescription(saveLabel).assertCountEquals(1)
        composeRule.onNodeWithTag("settings_back_button").assertDoesNotExist()
        composeRule.onNodeWithTag("motocom_top_bar")
            .assertContentDescriptionEquals("摩声")
    }

    @Test
    fun nicknameFieldKeepsEditableTextAndSelectionSemantics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nickname = mutableStateOf("")
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = nickname.value,
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0"
                    ),
                    onBack = {},
                    onNicknameChanged = { nickname.value = it },
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_nickname_label)).assertExists()
        val field = composeRule.onNodeWithTag("settings_nickname_input")
        field.performTextInput("Road Captain")
        composeRule.waitForIdle()

        assertEquals("Road Captain", nickname.value)
        val config = field.fetchSemanticsNode().config
        assertEquals("Road Captain", config[SemanticsProperties.EditableText].text)
        assertFalse(config.contains(SemanticsProperties.ContentDescription))
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))

        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 4, false) }
        composeRule.waitForIdle()
        assertEquals(
            TextRange(0, 4),
            field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        )
    }

    @Test
    fun voxControlsExposeRealSettingsAndExactRuntimeState() {
        var enabled: Boolean? = null
        var sensitivity: Int? = null
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Connected",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0",
                        voxEnabled = true,
                        voxSensitivity = 60
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {},
                    onVoxEnabledChanged = { enabled = it },
                    onVoxSensitivityChanged = { sensitivity = it }
                )
            }
        }

        composeRule.onNodeWithTag("settings_vox_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_vox_sensitivity_button")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        composeRule.onNodeWithText("60").fetchSemanticsNode()

        composeRule.runOnIdle {
            assertEquals(false, enabled)
            assertEquals(80, sensitivity)
        }
    }

    @Test
    fun audioRouteRowsExposePersistedSelectionAndRealCallbacks() {
        var selected: AudioRouteSelection? = null
        var helpCount = 0
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "当前音频源：手机听筒",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0",
                        preferredAudioRoute = AudioRouteSelection.EARPIECE
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = { helpCount++ },
                    onAudioRouteSelected = { selected = it }
                )
            }
        }

        composeRule.onAllNodesWithTag("settings_audio_route_button").onLast().assertIsNotSelected()
        composeRule.onAllNodesWithTag("settings_audio_earpiece_button").onLast().assertIsSelected()
        composeRule.onAllNodesWithTag("settings_audio_speaker_button").onLast()
            .assertIsNotSelected()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.runOnIdle {
            assertEquals(AudioRouteSelection.SPEAKER, selected)
            assertEquals(0, helpCount)
        }
    }

    @Test
    fun themeSelectorExposesBothThemesAndRoutesSelection() {
        var selected: MotoComThemeMode? = null
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0"
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {},
                    theme = MotoComThemeMode.DAYLIGHT,
                    onThemeSelected = { selected = it }
                )
            }
        }

        composeRule.onNodeWithTag("settings_theme_daylight")
            .performScrollTo()
            .assertIsSelected()
        composeRule.onNodeWithTag("settings_theme_nightride")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals(MotoComThemeMode.NIGHTRIDE, selected) }
    }

    @Test
    fun automaticReconnectExposesPersistedSwitchStateAndRealCallback() {
        var requested: Boolean? = null
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0",
                        automaticReconnectEnabled = false
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {},
                    onAutomaticReconnectChanged = { requested = it }
                )
            }
        }

        composeRule.onNodeWithTag("settings_reconnect_button")
            .assertIsOff()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.runOnIdle { assertEquals(true, requested) }
    }

    @Test
    fun switchesExposeOneLabelledToggleEach() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0",
                        automaticReconnectEnabled = true,
                        voxEnabled = true
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {}
                )
            }
        }

        val reconnectDescription = buildString {
            append(context.getString(R.string.settings_auto_reconnect))
            append("，")
            append(context.getString(R.string.settings_auto_reconnect_description))
        }
        composeRule.onAllNodesWithContentDescription(reconnectDescription).assertCountEquals(1)
        composeRule.onNodeWithTag("settings_reconnect_button").assertIsOn()

        val voxDescription = context.getString(R.string.settings_vox_switch_description)
        composeRule.onAllNodesWithContentDescription(voxDescription).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(voxDescription).assertIsOn()
    }

    @Test
    fun settingsActionsMeetMinimumTouchTargets() {
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = true,
                        version = "1.0"
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = {}
                )
            }
        }

        listOf(
            "settings_save_nickname_button",
            "settings_audio_route_button",
            "settings_vox_button",
            "settings_vox_sensitivity_button",
            "settings_optional_permission_button",
            "settings_logs_button"
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
            val height = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().size.height
            val minimum = 48f * composeRule.density.density
            assertTrue(
                "$tag must be at least 48dp high, was ${height}px",
                height.toFloat() >= minimum - 1f
            )
        }
    }

    @Test
    fun helpEntryRoutesARealHelpAction() {
        var helpRequests = 0
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = SettingsScreenUiState(
                        nickname = "Rider",
                        nicknameFeedback = "",
                        audioSource = "Audio standby",
                        productState = "Offline",
                        optionalPermissionNotice = null,
                        showOptionalPermissionCta = false,
                        version = "1.0"
                    ),
                    onBack = {},
                    onNicknameChanged = {},
                    onSaveNickname = {},
                    onOptionalPermission = {},
                    onLogs = {},
                    onAbout = {},
                    onHelp = { helpRequests++ }
                )
            }
        }

        composeRule.onNodeWithTag("settings_help_button")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        composeRule.runOnIdle { assertEquals(1, helpRequests) }
    }

    @Test
    fun betaNoiseSwitchUpdatesSelectionAndDisablesWhileRunning() {
        val state = mutableStateOf(SettingsScreenUiState(
            nickname = "Rider", nicknameFeedback = "", audioSource = "Audio standby",
            productState = "Offline", optionalPermissionNotice = null,
            showOptionalPermissionCta = false, version = "1.0", rnnoiseEnabled = true
        ))
        var requests = 0
        composeRule.setContent {
            ScrollableSettingsTheme {
                MotoComSettingsScreen(
                    state = state.value, onBack = {}, onNicknameChanged = {}, onSaveNickname = {},
                    onOptionalPermission = {}, onLogs = {}, onAbout = {}, onHelp = {},
                    onRnnoiseEnabledChanged = {
                        requests++
                        state.value = state.value.copy(rnnoiseEnabled = it)
                    }
                )
            }
        }
        val toggle = composeRule.onNodeWithTag("settings_rnnoise_switch")
        if (!BuildConfig.RNNOISE_BETA) {
            toggle.assertDoesNotExist()
            return
        }
        toggle.assertIsOn().performScrollTo().performClick()
        toggle.assertIsOff()
        composeRule.onNodeWithTag("settings_noise_mode").assertTextContains("WebRTC")
        composeRule.runOnIdle { state.value = state.value.copy(canChangeNoiseReduction = false) }
        toggle.assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, requests) }
    }
}
