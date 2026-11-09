package com.kuma.motointercom

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

        composeRule.onNodeWithTag("motocom_top_bar")
            .assertContentDescriptionEquals("摩声")
        val topBarHeight = composeRule.onNodeWithTag("motocom_top_bar")
            .fetchSemanticsNode().boundsInRoot.height
        val minimumTopBarHeight = with(composeRule.density) { 48.dp.toPx() }
        assertTrue(
            "top bar height $topBarHeight must keep its 48dp touch target",
            topBarHeight >= minimumTopBarHeight
        )
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
