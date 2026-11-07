package com.kuma.motointercom

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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
}
