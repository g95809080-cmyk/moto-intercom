package com.kuma.motointercom

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoverScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun offlinePairingCopyWaitsForAvailabilityRatherThanClaimingIdentityVerification() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("等待上线", context.getString(R.string.discover_status_pending))
    }

    @Test
    fun offlineStateRoutesStartAction() {
        var started = false
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(true, false, null, emptyList(), emptyList()),
            stateText = "Start discovery",
            supplementalText = null,
            emptyText = "No riders",
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = { started = true },
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        composeRule.onNodeWithTag("discover_state_text").assertIsDisplayed()
        composeRule.onNodeWithTag("discover_offline_start_button").performClick()
        assertTrue(started)
    }

    @Test
    fun selectablePresenceRoutesConnectAction() {
        val presence = RiderPresence(
            deviceId = "device-a",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "Road Captain",
            deviceName = "Pixel",
            protocolVersion = 2,
            lastSeenElapsedRealtimeMs = 1L,
            candidates = listOf(
                PresenceTransportCandidate(
                    transport = Transport.LAN,
                    endpointId = "endpoint-a",
                    address = "127.0.0.1",
                    port = 1234,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = true
                )
            ),
            pairing = null
        )
        var selected = false
        val state = DiscoverScreenUiState(
            presentation = discoverPresentation(
                state = IntercomState.Discovering(RuntimeSessionId("runtime-a")),
                presences = listOf(presence)
            ),
            stateText = "Choose a rider",
            supplementalText = null,
            emptyText = "No riders",
            radarRunning = true
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = { selected = it.deviceId == "device-a" },
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        composeRule.onNodeWithTag("discover_card_device-a").assertIsDisplayed()
        composeRule.onNodeWithTag("discover_select_device-a").performClick()
        composeRule.onNodeWithTag("discover_connect_device-a").assertIsEnabled().assertHasClickAction()
        composeRule.onNodeWithText("发现信息可用").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("具备稳定 MotoCom 发现信息")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(selected)
    }

    @Test
    fun pairingManagementIsExposedOnlyForPairedRidersAndRoutesTheExactPresence() {
        val paired = selectablePresence("paired-device").copy(
            pairing = PairingRecord(
                remoteDeviceId = "paired-device",
                remoteNickname = "Paired Rider",
                deviceName = "Paired Phone",
                localAlias = "Paired Rider",
                shortCode = "1234",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = false,
                lastTransport = "LAN",
                failureCount = 0
            )
        )
        val nearby = selectablePresence("nearby-device")
        var managedDeviceId: String? = null
        val state = DiscoverScreenUiState(
            presentation = discoverPresentation(
                state = IntercomState.Discovering(RuntimeSessionId("runtime-pairing-management")),
                presences = listOf(paired, nearby)
            ),
            stateText = "Choose a rider",
            supplementalText = null,
            emptyText = "No riders",
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = { managedDeviceId = it.deviceId }
                )
            }
        }

        composeRule.onNodeWithTag("discover_manage_paired-device")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertTrue(
            composeRule.onAllNodesWithTag("discover_manage_nearby-device")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty()
        )
        assertEquals("paired-device", managedDeviceId)
    }

    private fun selectablePresence(deviceId: String): RiderPresence = RiderPresence(
        deviceId = deviceId,
        sessionId = RuntimeSessionId("session-$deviceId"),
        nickname = "Rider $deviceId",
        deviceName = "Phone $deviceId",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = Transport.LAN,
                endpointId = "endpoint-$deviceId",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = null
    )
}
