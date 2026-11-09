package com.kuma.motointercom

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class LogsScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun followsNewLogsOnlyWhileAtBottomAndResumesAfterReturningToBottom() {
        val lines = mutableStateOf((1..100).joinToString("\n") { "line $it" })
        composeRule.setContent {
            MotoComTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    MotoComLogsScreen(LogsScreenUiState("scope", lines.value, true), {}, {}, {})
                }
            }
        }
        val node = composeRule.onNodeWithTag("logs_text")
        fun range() = node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        fun scroll(delta: Float) {
            node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            composeRule.waitForIdle()
        }
        fun append(first: Int) {
            composeRule.runOnIdle { lines.value += (first until first + 50).joinToString("\n", prefix = "\n") { "line $it" } }
            composeRule.waitForIdle()
        }
        composeRule.waitForIdle()
        scroll(100_000f)
        val initialBottom = range().value()
        assertTrue(initialBottom > 0f)
        append(101)
        assertTrue(range().maxValue() > initialBottom + 1)
        assertEquals(range().maxValue(), range().value(), 1f)
        scroll(-500f)
        val readingOffset = range().value()
        assertTrue(readingOffset < range().maxValue() - 1)
        append(151)
        assertEquals(readingOffset, range().value(), 1f)
        scroll(100_000f)
        append(201)
        assertEquals(range().maxValue(), range().value(), 1f)
        composeRule.runOnIdle { lines.value = "cleared" }
        composeRule.waitForIdle()
        append(251)
        assertEquals(range().maxValue(), range().value(), 1f)
    }

    @Test
    fun logViewportAndActionsAreAvailable() {
        composeRule.setContent {
            MotoComTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    MotoComLogsScreen(
                        state = LogsScreenUiState("Current session", "first\nsecond", true),
                        onBack = {},
                        onCopy = {},
                        onClose = {}
                    )
                }
            }
        }

        val logText = composeRule.onNodeWithTag("logs_text").fetchSemanticsNode().config[SemanticsProperties.Text]
            ?.joinToString(separator = "") { it.text }
            .orEmpty()
        assertTrue(logText.contains("first"))
        composeRule.onNodeWithTag("logs_copy_button").assertHasClickAction()
        composeRule.onNodeWithTag("logs_close_button").assertHasClickAction()
        composeRule.onNodeWithTag("logs_export_button").assertHasClickAction()
    }

    @Test
    fun logActionsMeetMinimumTouchTargets() {
        composeRule.setContent {
            MotoComTheme {
                Box(Modifier.size(360.dp, 800.dp)) {
                    MotoComLogsScreen(
                        state = LogsScreenUiState("Current session", "first\nsecond", true),
                        onBack = {},
                        onCopy = {},
                        onClose = {}
                    )
                }
            }
        }

        listOf(
            "logs_back_button",
            "logs_export_button",
            "logs_copy_button",
            "logs_close_button"
        ).forEach { tag ->
            val height = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().size.height
            val minimum = 48f * composeRule.density.density
            assertTrue(
                "$tag must be at least 48dp high, was ${height}px",
                height.toFloat() >= minimum - 1f
            )
        }
    }
}
