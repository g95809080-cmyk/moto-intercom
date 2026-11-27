package com.kuma.motointercom

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.kuma.motointercom.group.GroupServiceState
import com.kuma.motointercom.group.ui.GroupGlassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupEntitlementUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activationDialogSubmitsTypedCode() {
        var submittedCode = ""
        compose.setContent {
            GroupGlassTheme {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    hasEntitlementToken = false,
                    showActivationDialog = true,
                    onRedeemActivationCode = { code, _ -> submittedCode = code }
                )
            }
        }
        compose.onNodeWithTag("activation_code_input").assertIsDisplayed()
        compose.onNodeWithTag("activation_code_input").performTextInput("MOTO-7K9P-4X2M-Q8VN")
        compose.onNodeWithTag("activation_code_submit").performClick()
        assertEquals("MOTO-7K9P-4X2M-Q8VN", submittedCode)
    }

    @Test
    fun networkLobbyBannerOpensActivationDialog() {
        var opened = false
        compose.setContent {
            GroupGlassTheme {
                GroupScreen(
                    state = GroupServiceState(),
                    onStart = {},
                    onAction = {},
                    onRoute = {},
                    onVox = {},
                    onBack = {},
                    onSettings = {},
                    onCopy = {},
                    hasEntitlementToken = false,
                    showActivationDialog = false,
                    onOpenActivationDialog = { opened = true }
                )
            }
        }
        compose.onNodeWithTag("mode_network").performClick()
        compose.onNodeWithTag("entitlement_banner").performScrollTo().performClick()
        assertTrue(opened)
    }
}
