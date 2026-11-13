package com.kuma.motointercom

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
class DiscoverScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyDiscoveryHasNoSyntheticSonarTargets() {
        val state = mutableStateOf(DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "正在搜索",
            supplementalText = null,
            radarRunning = true,
            animationsEnabled = false
        ))
        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    state = state.value,
                    onBack = {}, onHelp = {}, onStart = {}, onWifiSettings = {},
                    onRescan = {}, onSelectPresence = {}, onConnect = {}, onManagePairing = {}
                )
            }
        }
        composeRule.onNodeWithTag("discover_sonar_field")
            .assertContentDescriptionEquals("声纳目标 0 位")
    }

    @Test
    fun offlinePairingCopyWaitsForAvailabilityRatherThanClaimingIdentityVerification() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("离线", context.getString(R.string.discover_status_pending))
    }

    @Test
    fun rescanIsEnabledOnlyForActiveDiscoveryAndRoutesTheRealAction() {
        var rescans = 0
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "Choose a rider",
            supplementalText = null,
            radarRunning = true,
            rescanEnabled = true
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = { rescans++ },
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        composeRule.onNodeWithTag("discover_rescan_button")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, rescans)
    }

    @Test
    fun rescanIsDisabledOutsideActiveDiscovery() {
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(true, false, null, emptyList(), emptyList()),
            stateText = "Start discovery",
            supplementalText = null,
            radarRunning = false,
            rescanEnabled = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        composeRule.onNodeWithTag("discover_rescan_button").assertIsNotEnabled()
    }

    @Test
    fun discoveryTopBarIsBrandOnly() {
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "Choose a rider",
            supplementalText = null,
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        composeRule.onNodeWithTag("motocom_top_bar")
            .assertContentDescriptionEquals("摩声")
        composeRule.onNodeWithTag("discover_back_button").assertDoesNotExist()
        composeRule.onNodeWithTag("discover_help_button").assertDoesNotExist()
    }

    @Test
    fun discoveryPageHeadingAndStatusRemainVisible() {
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "Choose a rider",
            supplementalText = null,
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        composeRule.onNodeWithTag("discover_title").assertIsDisplayed()
        composeRule.onNodeWithText("发现").assertIsDisplayed()
        composeRule.onNodeWithText("等待车友").assertIsDisplayed()
    }

    @Test
    fun offlineStateRoutesStartAction() {
        var started = false
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(true, false, null, emptyList(), emptyList()),
            stateText = "Start discovery",
            supplementalText = null,
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
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
            radarRunning = true
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
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
        composeRule.onNodeWithText("在线").assertIsDisplayed()
        composeRule.onNodeWithText("具备稳定 MotoCom 发现信息").assertDoesNotExist()
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
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
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
            .performScrollTo()
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

    @Test
    fun discoveryActionsMeetMinimumTouchTargets() {
        val paired = selectablePresence("touch-device").copy(
            pairing = PairingRecord(
                remoteDeviceId = "touch-device",
                remoteNickname = "Touch Rider",
                deviceName = "Touch Phone",
                localAlias = "Touch Rider",
                shortCode = "2468",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = false,
                lastTransport = "LAN",
                failureCount = 0
            )
        )
        val state = DiscoverScreenUiState(
            presentation = discoverPresentation(
                state = IntercomState.Discovering(RuntimeSessionId("runtime-touch-targets")),
                presences = listOf(paired)
            ),
            stateText = "Choose a rider",
            supplementalText = null,
            radarRunning = true,
            rescanEnabled = true
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = {}
                )
            }
        }

        listOf(
            "discover_rescan_button",
            "discover_connect_touch-device",
            "discover_manage_touch-device"
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
    fun reducedMotionStopsStatusPulseWithoutHidingDiscovery() {
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "Scanning",
            supplementalText = null,
            radarRunning = true,
            animationsEnabled = false
        )

        assertFalse(discoverStatusPulseActive(state))
        assertTrue(state.radarRunning)
        assertTrue(discoverStatusPulseActive(state.copy(animationsEnabled = true)))
    }

    @Test
    fun discoveryHeroGrowsAtLargeFontScale() {
        val state = DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "等待车友加入",
            supplementalText = "保持屏幕常亮",
            radarRunning = true
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                MotoComTheme {
                    MotoComDiscoverScreen(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        state = state,
                        onBack = {},
                        onHelp = {},
                        onStart = {},
                        onWifiSettings = {},
                        onRescan = {},
                        onSelectPresence = {},
                        onConnect = {},
                        onManagePairing = {}
                    )
                }
            }
        }

        val heroHeight = composeRule.onNodeWithTag("discover_hero")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "discover_hero must grow past its 124dp minimum at 2x font scale",
            heroHeight > 124f * composeRule.density.density
        )
        composeRule.onNodeWithTag("discover_state_text").assertIsDisplayed()
    }

    @Test
    fun offlinePairedRiderKeepsLocalManagementWithoutOfferingAConnection() {
        val offline = selectablePresence("offline-paired").copy(
            candidates = listOf(
                PresenceTransportCandidate(
                    transport = Transport.LAN,
                    endpointId = "expired-offline-paired",
                    address = "127.0.0.1",
                    port = 1234,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = false
                )
            ),
            pairing = PairingRecord(
                remoteDeviceId = "offline-paired",
                remoteNickname = "Offline Rider",
                deviceName = "Offline Phone",
                localAlias = "Offline Rider",
                shortCode = "4321",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = false,
                lastTransport = "LAN",
                failureCount = 0
            )
        )
        var managed = false
        val state = DiscoverScreenUiState(
            presentation = discoverPresentation(
                state = IntercomState.Discovering(RuntimeSessionId("runtime-offline-management")),
                presences = listOf(offline)
            ),
            stateText = "Choose a rider",
            supplementalText = null,
            radarRunning = false
        )

        composeRule.setContent {
            MotoComTheme {
                MotoComDiscoverScreen(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    state = state,
                    onBack = {},
                    onHelp = {},
                    onStart = {},
                    onWifiSettings = {},
                    onRescan = {},
                    onSelectPresence = {},
                    onConnect = {},
                    onManagePairing = { managed = it.deviceId == "offline-paired" }
                )
            }
        }

        composeRule.onNodeWithTag("discover_manage_offline-paired")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(
            composeRule.onAllNodesWithTag("discover_connect_offline-paired")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty()
        )
        assertTrue(managed)
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
