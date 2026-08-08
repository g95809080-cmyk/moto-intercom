package com.kuma.motointercom

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogsScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun logViewportAndActionsAreAvailable() {
        composeRule.setContent {
            MotoComTheme {
                MotoComLogsScreen(
                    state = LogsScreenUiState("Current session", "first\nsecond", true),
                    onBack = {},
                    onCopy = {},
                    onClose = {}
                )
            }
        }

        val logText = composeRule.onNodeWithTag("logs_text").fetchSemanticsNode().config[SemanticsProperties.Text]
            ?.joinToString(separator = "") { it.text }
            .orEmpty()
        assertTrue(logText.contains("first"))
        composeRule.onNodeWithTag("logs_copy_button").assertHasClickAction()
        composeRule.onNodeWithTag("logs_close_button").assertHasClickAction()
    }
}
