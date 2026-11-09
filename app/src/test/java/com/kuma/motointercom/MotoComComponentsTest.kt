package com.kuma.motointercom

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotoComComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun iconButtonExposesOneLabelAndRoutesClick() {
        var clicks = 0
        composeRule.setContent {
            MotoComTheme {
                MotoComIconButton(
                    icon = painterResource(R.drawable.ic_check_24),
                    description = "Save",
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("save")
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("Save").assertCountEquals(1)
        composeRule.onNodeWithTag("save").assertContentDescriptionEquals("Save").performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun surfaceCardKeepsCallerPlacementAndContent() {
        composeRule.setContent {
            MotoComTheme {
                MotoComSurfaceCard(modifier = Modifier.testTag("surface")) {
                    Text("Card content")
                }
            }
        }

        composeRule.onNodeWithTag("surface").assertExists()
        composeRule.onNodeWithText("Card content").assertExists()
    }

    @Test
    fun settingRowRendersEverySlot() {
        composeRule.setContent {
            MotoComTheme {
                MotoComSettingRow(
                    headlineContent = { Text("Headline") },
                    modifier = Modifier.testTag("setting"),
                    supportingContent = { Text("Supporting") },
                    leadingContent = { Text("Leading") },
                    trailingContent = { Text("Trailing") }
                )
            }
        }

        composeRule.onNodeWithTag("setting").assertExists()
        composeRule.onNodeWithText("Leading").assertExists()
        composeRule.onNodeWithText("Headline").assertExists()
        composeRule.onNodeWithText("Supporting").assertExists()
        composeRule.onNodeWithText("Trailing").assertExists()
    }

    @Test
    fun topBarHeadingAndSectionLabelsGrowWithLargeFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                MotoComTheme {
                    Column {
                        MotoComTopBar()
                        MotoComPageHeading("设置", Modifier.testTag("heading"))
                        MotoComSectionLabel("主题", Modifier.testTag("section_label"))
                    }
                }
            }
        }

        assertContainerCoversText("motocom_top_bar", "摩声")
        assertContainerCoversText("heading", "设置")
        assertContainerCoversText("section_label", "主题")
    }

    private fun assertContainerCoversText(containerTag: String, text: String) {
        val container = composeRule.onNodeWithTag(containerTag)
            .fetchSemanticsNode().boundsInRoot
        val textNode = composeRule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$containerTag height ${container.height} must cover text height ${textNode.height}",
            container.height >= textNode.height
        )
    }
}
