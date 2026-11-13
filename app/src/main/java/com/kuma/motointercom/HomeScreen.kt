package com.kuma.motointercom

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

internal data class HomeScreenUiState(
    val primaryText: String,
    val supplementalText: String?,
    val peerText: String,
    val primaryActionLabel: String,
    val primaryActionEnabled: Boolean,
    val disabledReason: String?,
    val showPermissionGrantCta: Boolean,
    val showPermissionSettingsCta: Boolean,
    val showWifiSettingsCta: Boolean,
    val discoverCtaLabel: String,
    val showDiscoverCta: Boolean,
    val audioSourceText: String,
    val discovering: Boolean,
    val connected: Boolean,
    val statusLabel: String = "",
    val muted: Boolean = false,
    val muteEnabled: Boolean = false,
    val voxEnabled: Boolean = true,
    val voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY,
    val voxState: VoxRuntimeState = VoxRuntimeState.IDLE,
    val cloudStatusText: String = "",
    val cloudActionLabel: String = "云端对讲",
    val cloudActionEnabled: Boolean = true,
    val cloudActive: Boolean = false,
    val animationsEnabled: Boolean = true,
    val cloudCardTitle: String = "云端对讲"
)

internal data class RadarPreviewPeer(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true
)

internal data class RadarPeerPlacement(
    val angleDegrees: Float,
    val distanceFraction: Float,
    val bubbleSizeDp: Float
)

private const val RADAR_MAX_VISIBLE_PEERS = 11
private const val RADAR_CENTER_NODE_SIZE_DP = 94f
private const val RADAR_BUBBLE_MIN_DP = 48f
private const val RADAR_BUBBLE_MAX_DP = 72f
private const val RADAR_BUBBLE_GAP_DP = 6f

internal fun radarPeerPlacements(
    count: Int,
    scopeRadiusDp: Float,
    centerNodeSizeDp: Float = RADAR_CENTER_NODE_SIZE_DP
): List<RadarPeerPlacement> {
    if (count <= 0) return emptyList()
    val maximumBubble = min(RADAR_BUBBLE_MAX_DP, scopeRadiusDp - centerNodeSizeDp / 2f - RADAR_BUBBLE_GAP_DP)
    if (maximumBubble < RADAR_BUBBLE_MIN_DP) return emptyList()
    for (visible in minOf(count, RADAR_MAX_VISIBLE_PEERS) downTo 1) {
        val bubbleSize = if (visible == 1) maximumBubble else {
            val chord = 2f * (scopeRadiusDp - maximumBubble / 2f) * sin(PI.toFloat() / visible)
            min(maximumBubble, chord - RADAR_BUBBLE_GAP_DP)
        }
        if (bubbleSize < RADAR_BUBBLE_MIN_DP) continue
        val distanceFraction = (scopeRadiusDp - bubbleSize / 2f) / scopeRadiusDp
        val startAngle = if (visible == 1) -31f else -90f + 180f / visible
        return List(visible) { index ->
            RadarPeerPlacement(startAngle + index * 360f / visible, distanceFraction, bubbleSize)
        }
    }
    return emptyList()
}

internal fun visibleRadarPeerCount(count: Int, scopeRadiusDp: Float): Int =
    radarPeerPlacements(count, scopeRadiusDp).size

@Composable
internal fun MotoComHomeScreen(
    state: HomeScreenUiState,
    audioLevel: Float,
    onPrimaryAction: () -> Unit,
    onDiscover: () -> Unit,
    onPermissionGrant: () -> Unit,
    onPermissionSettings: () -> Unit,
    onWifiSettings: () -> Unit,
    onMute: (Boolean) -> Unit,
    onAudioSettings: () -> Unit,
    onVox: () -> Unit,
    onCloudAction: () -> Unit = {},
    modifier: Modifier = Modifier,
    onGuideAnchor: (GuideTarget, Rect) -> Unit = { _, _ -> },
    radarPreviewPeers: List<RadarPreviewPeer> = emptyList(),
    onRadarPreviewPeerClick: (RadarPreviewPeer) -> Unit = {}
) {
    val colors = MotoComThemePalette.current
    val pageHorizontalPadding = dimensionResource(R.dimen.motocom_page_horizontal_padding)
    var showStatusDetails by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.TopCenter
    ) {
        MotoComBackdrop(Modifier.matchParentSize())
        val landscape = maxWidth >= 520.dp && maxWidth > maxHeight * 1.2f
        val compact = maxHeight < 620.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            Modifier
                .then(if (landscape) Modifier else Modifier.widthIn(max = 560.dp))
                .fillMaxSize()
                .padding(horizontal = pageHorizontalPadding, vertical = 8.dp)
                .testTag("home_screen"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MotoComTopBar(
                    active = state.animationsEnabled && state.discovering,
                    modifier = Modifier.weight(1f),
                    alignment = Alignment.CenterStart
                )
                RadarStatusPill(
                    label = homeStatusLabel(state),
                    active = state.discovering || state.connected
                )
            }
            val sonar: @Composable (Modifier) -> Unit = { sonarModifier ->
                RadarStatusCard(
                    state = state,
                    radarPeers = radarPreviewPeers,
                    onRadarPeerClick = onRadarPreviewPeerClick,
                    onDiscover = onDiscover,
                    modifier = sonarModifier
                ) {
                    HomeStatusFooter(
                        state = state,
                        peerCount = radarPreviewPeers.size,
                        onDiscover = onDiscover,
                        onPermissionGrant = onPermissionGrant,
                        onPermissionSettings = onPermissionSettings,
                        onWifiSettings = onWifiSettings,
                        onDetails = { showStatusDetails = true },
                        onGuideAnchor = onGuideAnchor
                    )
                }
            }
            val controls: @Composable () -> Unit = {
                ControlDeck(
                    state = state,
                    audioLevel = audioLevel,
                    onPrimaryAction = onPrimaryAction,
                    onMute = onMute,
                    onAudioSettings = onAudioSettings,
                    onVox = onVox,
                    onGuideAnchor = onGuideAnchor,
                    compact = compact
                )
                if (state.cloudStatusText.isNotBlank()) {
                    CloudIntercomCard(state = state, onClick = onCloudAction)
                }
            }
            if (landscape) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sonar(Modifier.weight(1.1f).fillMaxHeight())
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                    ) { controls() }
                }
            } else {
                sonar(Modifier.weight(1f).fillMaxWidth())
                controls()
            }
        }
    }
    if (showStatusDetails) {
        AlertDialog(
            onDismissRequest = { showStatusDetails = false },
            title = { Text("当前状态") },
            text = {
                Text(
                    listOfNotNull(state.disabledReason, state.supplementalText)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString("\n")
                )
            },
            confirmButton = {
                TextButton(onClick = { showStatusDetails = false }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun HomeStatusFooter(
    state: HomeScreenUiState,
    peerCount: Int,
    onDiscover: () -> Unit,
    onPermissionGrant: () -> Unit,
    onPermissionSettings: () -> Unit,
    onWifiSettings: () -> Unit,
    onDetails: () -> Unit,
    onGuideAnchor: (GuideTarget, Rect) -> Unit
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val hasNotice = !state.disabledReason.isNullOrBlank() || !state.supplementalText.isNullOrBlank()
    val action = when {
        state.showPermissionGrantCta -> Triple("授权", "home_permission_grant_cta", onPermissionGrant)
        state.showPermissionSettingsCta -> Triple("去设置", "home_permission_settings_cta", onPermissionSettings)
        state.showWifiSettingsCta -> Triple("开启 Wi-Fi", "home_wifi_settings_cta", onWifiSettings)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (hasNotice && !state.discovering && !state.connected && state.peerText.isNotBlank()) {
                Text(
                    state.peerText,
                    Modifier.testTag("home_peer_name"),
                    color = colors.text,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.disabledReason?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    Modifier.testTag("home_disabled_reason"),
                    color = colors.amber,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.supplementalText?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    Modifier.testTag("home_status_supplemental"),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!hasNotice) {
                val label = when {
                    state.connected -> "正在与${state.peerText.ifBlank { "摩友" }}对讲"
                    state.discovering && peerCount == 0 -> "尚未发现摩友"
                    state.discovering -> "点击摩友开始对讲"
                    state.peerText.isNotBlank() -> state.peerText
                    else -> "启动后搜索附近摩友"
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(
                            if (state.showDiscoverCta) Modifier
                                .testTag("home_discover_cta")
                                .clickable(role = Role.Button, onClick = onDiscover)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        Modifier.testTag("home_peer_name"),
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (hasNotice) {
            TextButton(
                onClick = onDetails,
                modifier = Modifier.heightIn(min = 48.dp).testTag("home_status_details")
            ) { Text("详情", color = colors.text) }
        }
        if (action != null) {
            val guideTarget = when (action.second) {
                "home_permission_grant_cta" -> GuideTarget.PERMISSION
                "home_permission_settings_cta" -> GuideTarget.PERMISSION_SETTINGS
                else -> GuideTarget.WIFI
            }
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accent.copy(alpha = 0.16f))
                    .testTag(action.second)
                    .guideAnchor(guideTarget, onGuideAnchor)
                    .clickable(role = Role.Button, onClick = action.third)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(action.first, color = colors.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CloudIntercomCard(
    state: HomeScreenUiState,
    onClick: () -> Unit
) {
    val colors = MotoComThemePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (state.cloudActive) {
                    Brush.linearGradient(listOf(colors.panelGlow, colors.panel))
                } else {
                    Brush.linearGradient(listOf(colors.panel, colors.panel))
                }
            )
            .border(
                1.dp,
                if (state.cloudActive) {
                    colors.accent.copy(alpha = 0.48f)
                } else {
                    colors.outline.copy(alpha = 0.85f)
                },
                RoundedCornerShape(22.dp)
            )
            .testTag("home_cloud_card")
            .clickable(
                enabled = state.cloudActionEnabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (state.cloudActive) {
                        colors.accent.copy(alpha = 0.16f)
                    } else {
                        colors.panelRaised
                    }
                )
                .border(
                    1.dp,
                    if (state.cloudActive) {
                        colors.accent.copy(alpha = 0.36f)
                    } else {
                        colors.outline
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_globe_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (state.cloudActive || state.cloudActionEnabled) colors.accent else colors.muted
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.cloudCardTitle,
                color = colors.text,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.cloudStatusText,
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (state.cloudActionEnabled) {
                        colors.accent.copy(alpha = if (colors.isLight) 0.12f else 0.16f)
                    } else {
                        colors.panelRaised
                    }
                )
                .border(
                    1.dp,
                    if (state.cloudActionEnabled) {
                        colors.accent.copy(alpha = 0.32f)
                    } else {
                        colors.outline
                    },
                    RoundedCornerShape(50)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.cloudActionLabel,
                color = if (state.cloudActionEnabled) colors.accent else colors.muted,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                ),
                textAlign = TextAlign.End,
                maxLines = 1
            )
        }
    }
}

private fun homeStatusLabel(state: HomeScreenUiState): String = when {
    state.statusLabel.isNotBlank() -> state.statusLabel
    state.connected -> "已连接"
    state.discovering -> "搜索中"
    else -> "待机中"
}

@Composable
private fun RadarStatusCard(
    state: HomeScreenUiState,
    radarPeers: List<RadarPreviewPeer>,
    onRadarPeerClick: (RadarPreviewPeer) -> Unit,
    onDiscover: () -> Unit,
    modifier: Modifier,
    footer: @Composable () -> Unit
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val peers = when {
        state.connected && state.peerText.isNotBlank() -> listOf(
            RadarPreviewPeer("connected", state.peerText, selected = true)
        )
        state.discovering -> radarPeers
        else -> emptyList()
    }
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(colors.panelGlow, colors.panel, colors.backgroundDeep)))
            .border(1.dp, colors.outline, RoundedCornerShape(28.dp))
            .semantics { if (state.primaryText.isNotBlank()) contentDescription = state.primaryText }
            .testTag("home_discovery_radar")
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.connected) "当前通话" else "附近摩友",
                Modifier.weight(1f),
                color = colors.muted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            Text(
                "${peers.size} 位",
                Modifier.testTag("home_sonar_target_count"),
                color = colors.text,
                style = MaterialTheme.typography.labelMedium
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val fieldSide = min(maxWidth.value, maxHeight.value)
            val scopeRadius = (fieldSide * 0.44f).dp
            val centerSize = when {
                fieldSide < 210f -> 48.dp
                fieldSide < 260f -> 56.dp
                else -> RADAR_CENTER_NODE_SIZE_DP.dp
            }
            val placements = remember(peers.size, scopeRadius, centerSize) {
                radarPeerPlacements(peers.size, scopeRadius.value, centerSize.value)
            }
            val visiblePeers = peers.take(placements.size)
            val targets = remember(placements, scopeRadius) {
                placements.map {
                    SonarTarget(it.angleDegrees, it.distanceFraction, it.bubbleSizeDp / (2f * scopeRadius.value))
                }
            }
            val manualPing = remember(state.discovering, state.connected, visiblePeers.map { it.id }) { Animatable(0f) }
            val coroutineScope = rememberCoroutineScope()
            val automatic = rememberSonarMotion(
                (state.discovering || state.connected) && state.animationsEnabled,
                visiblePeers.map { it.id }
            )
            val manualActive = manualPing.isRunning && state.animationsEnabled
            val manualWave = if (manualActive) 0.14f + manualPing.value * 1.6f else 0f
            val pulse = maxOf(
                automatic.pulse,
                if (manualActive) (1f - manualPing.value / 0.18f).coerceAtLeast(0f).pow(1.6f) else 0f
            )
            val waves = listOf(automatic.waveFraction, manualWave).filter { it > 0f }
            RadarCanvas(scopeRadius, centerSize, targets, waves, pulse)
            RadarCenterNode(
                state = state,
                pingPulse = pulse,
                nodeSize = centerSize,
                onManualPing = {
                    if (state.animationsEnabled) coroutineScope.launch {
                        manualPing.snapTo(0f)
                        manualPing.animateTo(1f, tween(2400, easing = LinearEasing))
                    }
                },
                modifier = Modifier.align(Alignment.Center)
            )
            visiblePeers.forEachIndexed { index, peer ->
                val placement = placements[index]
                val angle = placement.angleDegrees * PI.toFloat() / 180f
                val distance = scopeRadius * placement.distanceFraction
                PeerBubble(
                    peer = peer,
                    size = placement.bubbleSizeDp.dp,
                    echoStrength = waves.maxOfOrNull { sonarEchoEnergy(it, targets[index]) } ?: 0f,
                    onClick = { onRadarPeerClick(peer) },
                    modifier = Modifier.align(Alignment.Center).offsetDp(distance * cos(angle), distance * sin(angle))
                )
            }
            val hiddenPeers = peers.size - visiblePeers.size
            if (hiddenPeers > 0) {
                RadarOverflowBadge(
                    hiddenCount = hiddenPeers,
                    onClick = onDiscover,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        footer()
    }
}

@Composable
private fun RadarStatusPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) colors.accent.copy(alpha = 0.12f) else colors.panelRaised)
            .border(1.dp, colors.outline.copy(alpha = 0.72f), RoundedCornerShape(50))
            .testTag("home_radar_status")
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .clearAndSetSemantics { contentDescription = "状态：$label" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(13.dp)
                .background(
                    colors.accent.copy(alpha = if (active) 0.16f else 0f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(if (active) 6.dp else 5.dp)
                    .background(if (active) colors.accent else colors.muted, CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = if (active) colors.accent else colors.text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 2.sp
            )
        )
    }
}

@Composable
private fun RadarCenterNode(
    state: HomeScreenUiState,
    pingPulse: Float = 0f,
    nodeSize: Dp = RADAR_CENTER_NODE_SIZE_DP.dp,
    onManualPing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val active = state.connected || state.discovering || pingPulse > 0.01f
    Box(
        modifier
            .size(nodeSize)
            .clickable(enabled = state.animationsEnabled, role = Role.Button, onClick = onManualPing)
            .testTag("home_radar_center")
            .semantics {
                contentDescription = when {
                    state.connected -> "正在与${state.peerText.ifBlank { "车友" }}对讲"
                    state.discovering -> "正在搜索附近车友"
                    else -> "待机中"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(nodeSize)
                .clip(CircleShape)
                .background(
                    colors.accent.copy(
                        alpha = if (active) (0.08f + pingPulse * 0.16f) else 0.03f
                    )
                )
                .border(
                    width = (1f + pingPulse * 0.8f).dp,
                    color = colors.accent.copy(
                        alpha = if (active) (0.28f + pingPulse * 0.48f) else 0.14f
                    ),
                    CircleShape
                )
        )
        Box(
            Modifier
                .size(nodeSize * 0.76f)
                .clip(CircleShape)
                .background(colors.background.copy(alpha = 0.94f))
                .border(
                    width = (1.5f + pingPulse * 0.7f).dp,
                    color = colors.accent.copy(
                        alpha = if (active) (0.72f + pingPulse * 0.28f) else 0.48f
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rider_24),
                contentDescription = null,
                modifier = Modifier.size(nodeSize * 0.33f),
                tint = colors.accent
            )
        }
    }
}

@Composable
private fun PeerBubble(
    peer: RadarPreviewPeer,
    size: Dp,
    echoStrength: Float = 0f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val compact = size < 70.dp
    val innerPadding = if (compact) 4.dp else 7.dp
    val iconBoxSize = if (compact) 20.dp else 29.dp
    val iconSize = if (compact) 12.dp else 17.dp
    val labelTopPadding = if (compact) 2.dp else 4.dp
    val labelMaxWidth = if (compact) 40.dp else 62.dp
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = if (compact) 8.sp else 10.sp,
        lineHeight = if (compact) 10.sp else 13.sp
    )
    val container = if (peer.selected) {
        colors.panelGlow.copy(alpha = 0.97f)
    } else {
        colors.background.copy(alpha = 0.93f)
    }
    val borderAlpha = (if (peer.selected) 0.9f else 0.46f + echoStrength * 0.52f).coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .border(
                width = if (peer.selected || echoStrength > 0.35f) 1.8.dp else 1.1.dp,
                color = colors.accent.copy(alpha = borderAlpha),
                shape = CircleShape
            )
            .testTag("home_radar_peer_${peer.id}")
            .clickable(
                enabled = peer.enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(innerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(iconBoxSize)
                .background(
                    color = if (peer.selected) {
                        colors.accent.copy(alpha = 0.2f)
                    } else {
                        colors.hairline.copy(alpha = 0.055f)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_person_outline_24),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = if (peer.selected) colors.accent else colors.text
            )
        }
        Text(
            text = peer.label,
            modifier = Modifier
                .padding(top = labelTopPadding)
                .widthIn(max = labelMaxWidth),
            color = if (peer.selected) colors.accent else colors.text,
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RadarOverflowBadge(
    hiddenCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.background.copy(alpha = 0.88f))
            .border(1.dp, colors.outline.copy(alpha = 0.72f), RoundedCornerShape(50))
            .heightIn(min = 48.dp)
            .testTag("home_radar_overflow")
            .semantics { contentDescription = "另有 $hiddenCount 位车友" }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "+$hiddenCount",
            color = colors.accent,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp
            )
        )
    }
}

private fun Modifier.offsetDp(x: Dp, y: Dp): Modifier =
    this.then(Modifier.offset(x = x, y = y))

@Composable
private fun RadarCanvas(
    scopeRadius: Dp,
    centerSize: Dp,
    targets: List<SonarTarget>,
    waves: List<Float>,
    pulse: Float
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    Canvas(Modifier.fillMaxSize().testTag("home_sonar_field").semantics {
        contentDescription = "声纳目标 ${targets.size} 位"
    }) {
        val origin = center
        val radius = scopeRadius.toPx()
        if (radius <= 0f) return@Canvas
        drawCircle(
            Brush.radialGradient(
                listOf(colors.accent.copy(alpha = 0.08f + pulse * 0.08f), Color.Transparent),
                center = origin,
                radius = radius
            ),
            radius,
            origin
        )
        listOf(1f, 0.73f, 0.47f).forEachIndexed { index, fraction ->
            drawCircle(
                colors.accent.copy(alpha = if (index == 0) 0.28f else 0.14f),
                radius * fraction,
                origin,
                style = Stroke(if (index == 0) 1.2.dp.toPx() else 0.8.dp.toPx())
            )
        }
        drawLine(colors.accent.copy(alpha = 0.1f), origin - Offset(radius, 0f), origin + Offset(radius, 0f))
        drawLine(colors.accent.copy(alpha = 0.1f), origin - Offset(0f, radius), origin + Offset(0f, radius))
        repeat(36) { index ->
            rotate(index * 10f, origin) {
                drawLine(
                    colors.accent.copy(alpha = if (index % 3 == 0) 0.34f else 0.16f),
                    origin + Offset(radius * 0.93f, 0f),
                    origin + Offset(radius * 0.985f, 0f),
                    strokeWidth = 0.9.dp.toPx()
                )
            }
        }
        drawActiveSonar(origin, radius, centerSize.toPx() / 2f, waves, pulse, targets, colors.accent)
    }
}

@Composable
private fun ControlDeck(
    state: HomeScreenUiState,
    audioLevel: Float,
    onPrimaryAction: () -> Unit,
    onMute: (Boolean) -> Unit,
    onAudioSettings: () -> Unit,
    onVox: () -> Unit,
    onGuideAnchor: (GuideTarget, Rect) -> Unit,
    compact: Boolean = false
) {
    val colors = MotoComThemePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.panel)
            .border(1.dp, colors.outline.copy(alpha = 0.85f), RoundedCornerShape(28.dp))
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = if (colors.isLight) 0.1f else 0.14f))
                    .border(1.dp, colors.accent.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_headset_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.accent
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = audioSourceLabel(state.audioSourceText),
                color = colors.text,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_audio_source"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            VoxStatus(
                state = state,
                audioLevel = audioLevel,
                onClick = onVox
            )
        }
        Spacer(Modifier.height(if (compact) 4.dp else 9.dp))
        HorizontalDivider(color = colors.outline.copy(alpha = 0.68f), thickness = 1.dp)
        Spacer(Modifier.height(if (compact) 5.dp else 10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("home_main_control_section"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier
                    .fillMaxWidth(0.92f)
                    .testTag("home_main_control_row"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeckSideControl(
                    icon = R.drawable.ic_mute_24,
                    label = stringResource(
                        if (state.muted) R.string.home_unmute_label else R.string.home_mute_label
                    ),
                    description = when {
                        state.muted -> stringResource(R.string.unmute_description)
                        state.muteEnabled -> stringResource(R.string.mute_description)
                        else -> stringResource(R.string.mute_unavailable_description)
                    },
                    tag = "home_mute_button",
                    enabled = state.muteEnabled,
                    selected = state.muted,
                    onClick = { onMute(!state.muted) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
                DeckPrimaryControl(
                    state = state,
                    onPrimaryAction = onPrimaryAction,
                    onGuideAnchor = onGuideAnchor,
                    compact = compact
                )
                DeckSideControl(
                    icon = R.drawable.ic_tune_24,
                    label = stringResource(R.string.home_audio_label),
                    description = stringResource(R.string.audio_button_description),
                    tag = "home_audio_settings_button",
                    enabled = true,
                    selected = false,
                    onClick = onAudioSettings,
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }
        }

    }
}

@Composable
private fun VoxStatus(
    state: HomeScreenUiState,
    audioLevel: Float,
    onClick: () -> Unit
) {
    val colors = MotoComThemePalette.current
    val stateLabel = when (state.voxState) {
        VoxRuntimeState.IDLE -> stringResource(R.string.vox_state_idle)
        VoxRuntimeState.DISABLED -> stringResource(R.string.vox_state_disabled)
        VoxRuntimeState.MUTED -> stringResource(R.string.vox_state_muted)
        VoxRuntimeState.LISTENING -> stringResource(R.string.vox_state_listening)
        VoxRuntimeState.OPEN -> stringResource(R.string.vox_state_open)
        VoxRuntimeState.HANGOVER -> stringResource(R.string.vox_state_hangover)
    }
    val level = if (state.connected) audioLevel.coerceIn(0f, 1f) else 0f
    val activeTransmit = state.connected && (
        state.voxState == VoxRuntimeState.OPEN ||
            state.voxState == VoxRuntimeState.HANGOVER
    )
    Column(
        Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelRaised.copy(alpha = 0.78f))
            .border(1.dp, colors.outline.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .testTag("home_vox_card")
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = "VOX $stateLabel，灵敏度 ${state.voxSensitivity}，打开设置"
            },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_vox_pill, stateLabel),
                modifier = Modifier.testTag("home_vox_pill"),
                color = colors.text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            )
            Spacer(Modifier.width(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    val lit = index < (level * 5f).roundToInt().coerceIn(0, 5)
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(if (lit) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !lit -> colors.outline
                                    activeTransmit -> colors.accent
                                    else -> colors.accent.copy(alpha = 0.52f)
                                }
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_vox_sensitivity, state.voxSensitivity),
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        )
    }
}

@Composable
private fun DeckSideControl(
    icon: Int,
    label: String,
    description: String,
    tag: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val colors = MotoComThemePalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(if (compact) 48.dp else 56.dp)
                .motoComPressScale(interactionSource, enabled)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        colors.accent.copy(alpha = if (colors.isLight) 0.14f else 0.2f)
                    } else {
                        colors.panelRaised
                    }
                )
                .border(
                    1.dp,
                    if (selected) colors.accent.copy(alpha = 0.48f) else colors.outline,
                    CircleShape
                )
                .testTag(tag)
                .semantics {
                    contentDescription = description
                    if (!enabled) disabled()
                }
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when {
                    !enabled -> colors.muted.copy(alpha = 0.45f)
                    selected -> colors.accent
                    else -> colors.text
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = when {
                !enabled -> colors.muted.copy(alpha = 0.45f)
                selected -> colors.accent
                else -> colors.muted
            },
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DeckPrimaryControl(
    state: HomeScreenUiState,
    onPrimaryAction: () -> Unit,
    onGuideAnchor: (GuideTarget, Rect) -> Unit,
    compact: Boolean = false
) {
    val colors = MotoComThemePalette.current
    val actionLabel = if (state.discovering) "停止搜索" else state.primaryActionLabel
    val displayedLabel = if (compact && LocalDensity.current.fontScale > 1.6f && actionLabel.length == 4) {
        actionLabel.take(2) + "\n" + actionLabel.drop(2)
    } else actionLabel
    val interactionSource = remember { MutableInteractionSource() }
    val disabledDescription = state.disabledReason?.let {
        stringResource(R.string.home_primary_disabled_description, state.primaryActionLabel, it)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (compact) 88.dp else 94.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(colors.accent.copy(alpha = 0.2f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f,
                    center = center
                )
                drawCircle(
                    color = colors.accent.copy(alpha = 0.26f),
                    radius = size.minDimension * 0.46f,
                    center = center,
                    style = Stroke(1.2.dp.toPx())
                )
            }
            Button(
                onClick = onPrimaryAction,
                enabled = state.primaryActionEnabled,
                modifier = Modifier
                    .size(if (compact) 76.dp else 78.dp)
                    .motoComPressScale(interactionSource, state.primaryActionEnabled)
                    .testTag("home_primary_button")
                    .guideAnchor(GuideTarget.START, onGuideAnchor)
                    .semantics(mergeDescendants = true) {
                        if (!state.primaryActionEnabled) {
                            disabled()
                            disabledDescription?.let { contentDescription = it }
                        }
                    },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.accentInk,
                    disabledContainerColor = colors.outline,
                    disabledContentColor = colors.muted
                ),
                contentPadding = PaddingValues(0.dp),
                interactionSource = interactionSource
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (state.discovering || state.connected) {
                        Box(
                            Modifier
                                .size(if (compact) 18.dp else 21.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.accentInk)
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic_24),
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 20.dp else 25.dp),
                            tint = colors.accentInk
                        )
                        }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = displayedLabel,
                        color = colors.accentInk,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = if (compact) 10.sp else 11.sp,
                            lineHeight = if (compact) 12.sp else 14.sp
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.panel.copy(alpha = 0.9f))
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = colors.text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotoComHomeScreenPreview() {
    MotoComTheme {
        MotoComHomeScreen(
            state = HomeScreenUiState(
                primaryText = "点击下方启动摩声",
                supplementalText = null,
                peerText = "",
                primaryActionLabel = "启动",
                primaryActionEnabled = true,
                disabledReason = null,
                showPermissionGrantCta = false,
                showPermissionSettingsCta = false,
                showWifiSettingsCta = false,
                discoverCtaLabel = "查看附近车友",
                showDiscoverCta = false,
                audioSourceText = "待机",
                discovering = false,
                connected = false,
                voxState = VoxRuntimeState.IDLE
            ),
            audioLevel = 0f,
            onPrimaryAction = {},
            onDiscover = {},
            onPermissionGrant = {},
            onPermissionSettings = {},
            onWifiSettings = {},
            onMute = {},
            onAudioSettings = {},
            onVox = {}
        )
    }
}
