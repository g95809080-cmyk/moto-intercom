package com.kuma.motointercom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val animationsEnabled: Boolean = true
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
private const val RADAR_RING_FRACTION = 0.82f
private const val RADAR_CENTER_NODE_SIZE_DP = 94f
private const val RADAR_BUBBLE_MIN_DP = 48f
private const val RADAR_BUBBLE_MAX_DP = 72f
private const val RADAR_BUBBLE_GAP_DP = 6f

internal fun radarPeerPlacements(
    count: Int,
    scopeRadiusDp: Float
): List<RadarPeerPlacement> {
    if (count <= 0) return emptyList()
    val visible = visibleRadarPeerCount(count, scopeRadiusDp)
    if (visible == 1) {
        val requiredDistance = RADAR_CENTER_NODE_SIZE_DP / 2f +
            RADAR_BUBBLE_MAX_DP / 2f +
            RADAR_BUBBLE_GAP_DP
        val distanceFraction = (requiredDistance / scopeRadiusDp.coerceAtLeast(1f))
            .coerceIn(0.62f, 0.9f)
        return listOf(
            RadarPeerPlacement(
                angleDegrees = -31f,
                distanceFraction = distanceFraction,
                bubbleSizeDp = RADAR_BUBBLE_MAX_DP
            )
        )
    }
    val scopeRadius = scopeRadiusDp.coerceAtLeast(1f)
    val baseRadius = scopeRadius * RADAR_RING_FRACTION
    val chord = 2f * baseRadius * sin(PI.toFloat() / visible)
    val bubbleSize = (chord - RADAR_BUBBLE_GAP_DP)
        .coerceIn(RADAR_BUBBLE_MIN_DP, RADAR_BUBBLE_MAX_DP)
    val ringRadius = maxOf(
        baseRadius,
        RADAR_CENTER_NODE_SIZE_DP / 2f +
            bubbleSize / 2f +
            RADAR_BUBBLE_GAP_DP
    )
    val distanceFraction = ringRadius / scopeRadius
    val startAngle = -90f + 180f / visible
    return List(visible) { index ->
        RadarPeerPlacement(
            angleDegrees = startAngle + index * 360f / visible,
            distanceFraction = distanceFraction,
            bubbleSizeDp = bubbleSize
        )
    }
}

internal fun visibleRadarPeerCount(count: Int, scopeRadiusDp: Float): Int {
    if (count <= 0) return 0
    val maxVisible = count.coerceAtMost(RADAR_MAX_VISIBLE_PEERS)
    val ringRadius = scopeRadiusDp.coerceAtLeast(1f) * RADAR_RING_FRACTION
    val minimumChord = RADAR_BUBBLE_MIN_DP + RADAR_BUBBLE_GAP_DP
    for (visible in maxVisible downTo 2) {
        val chord = 2f * ringRadius * sin(PI.toFloat() / visible)
        if (chord >= minimumChord) return visible
    }
    return 1
}

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
    modifier: Modifier = Modifier,
    onGuideAnchor: (GuideTarget, Rect) -> Unit = { _, _ -> },
    radarPreviewPeers: List<RadarPreviewPeer> = emptyList(),
    onRadarPreviewPeerClick: (RadarPreviewPeer) -> Unit = {}
) {
    val colors = MotoComThemePalette.current
    val pageHorizontalPadding = dimensionResource(R.dimen.motocom_page_horizontal_padding)
    val pageVerticalPadding = dimensionResource(R.dimen.motocom_page_vertical_padding)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter
    ) {
        MotoComBackdrop(Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = dimensionResource(R.dimen.motocom_content_max_width))
                .padding(
                    start = pageHorizontalPadding,
                    top = pageVerticalPadding,
                    end = pageHorizontalPadding,
                    bottom = 20.dp
                )
        ) {
            MotoComTopBar(
                active = state.animationsEnabled && state.discovering
            )
            Spacer(Modifier.height(10.dp))
            RadarStatusCard(
                state = state,
                radarPeers = radarPreviewPeers,
                onRadarPeerClick = onRadarPreviewPeerClick
            )
            Spacer(Modifier.height(12.dp))
            ControlDeck(
                state = state,
                audioLevel = audioLevel,
                onPrimaryAction = onPrimaryAction,
                onMute = onMute,
                onAudioSettings = onAudioSettings,
                onVox = onVox,
                onGuideAnchor = onGuideAnchor
            )
            if (state.showPermissionGrantCta) {
                Spacer(Modifier.height(10.dp))
                SecondaryAction(
                    text = stringResource(R.string.home_permission_grant_cta),
                    onClick = onPermissionGrant,
                    testTag = "home_permission_grant_cta",
                    modifier = Modifier.guideAnchor(GuideTarget.PERMISSION, onGuideAnchor)
                )
            }
            if (state.showPermissionSettingsCta) {
                Spacer(Modifier.height(10.dp))
                SecondaryAction(
                    text = stringResource(R.string.home_permission_settings_cta),
                    onClick = onPermissionSettings,
                    testTag = "home_permission_settings_cta",
                    modifier = Modifier.guideAnchor(
                        GuideTarget.PERMISSION_SETTINGS,
                        onGuideAnchor
                    )
                )
            }
            if (state.showWifiSettingsCta) {
                Spacer(Modifier.height(10.dp))
                SecondaryAction(
                    text = stringResource(R.string.wifi_settings_cta),
                    onClick = onWifiSettings,
                    testTag = "home_wifi_settings_cta",
                    modifier = Modifier.guideAnchor(GuideTarget.WIFI, onGuideAnchor)
                )
            }
            if (state.showDiscoverCta) {
                Spacer(Modifier.height(10.dp))
                SecondaryAction(
                    text = state.discoverCtaLabel,
                    onClick = onDiscover,
                    testTag = "home_discover_cta"
                )
            }
            state.supplementalText?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_status_supplemental"),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
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
    onRadarPeerClick: (RadarPreviewPeer) -> Unit
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .clip(RoundedCornerShape(32.dp))
            .background(colors.panel)
            .border(1.dp, colors.hairline.copy(alpha = 0.075f), RoundedCornerShape(32.dp))
            .semantics {
                if (state.primaryText.isNotBlank()) {
                    contentDescription = state.primaryText
                }
            }
            .testTag("home_discovery_radar")
    ) {
        val scopeRadius = min(maxWidth.value * 0.44f, maxHeight.value * 0.32f).dp
        RadarCanvas(state = state, scopeRadius = scopeRadius)
        RadarStatusPill(
            label = homeStatusLabel(state),
            active = state.connected || state.discovering,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
        )
        RadarCenterNode(
            state = state,
            modifier = Modifier.align(Alignment.Center)
        )
        if (!state.connected) {
            state.peerText.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 38.dp)
                        .widthIn(max = 220.dp)
                        .testTag("home_peer_name"),
                    color = colors.text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
        val peers = when {
            state.connected && state.peerText.isNotBlank() -> listOf(
                RadarPreviewPeer(
                    id = "connected",
                    label = state.peerText,
                    selected = true
                )
            )
            state.discovering -> radarPeers
            else -> emptyList()
        }
        val placements = remember(peers.size, scopeRadius) {
            radarPeerPlacements(peers.size, scopeRadius.value)
        }
        val visiblePeers = peers.take(placements.size)
        visiblePeers.forEachIndexed { index, peer ->
            val placement = placements[index]
            val angle = placement.angleDegrees * PI.toFloat() / 180f
            val distance = scopeRadius * placement.distanceFraction
            PeerBubble(
                peer = peer,
                size = placement.bubbleSizeDp.dp,
                onClick = { onRadarPeerClick(peer) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offsetDp(
                        x = distance * cos(angle),
                        y = distance * sin(angle)
                    )
            )
        }
        val hiddenPeers = peers.size - visiblePeers.size
        if (hiddenPeers > 0) {
            RadarOverflowBadge(
                hiddenCount = hiddenPeers,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun RadarStatusPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.background.copy(alpha = 0.62f))
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
            color = if (active) colors.text else colors.muted,
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
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    Box(
        modifier
            .size(RADAR_CENTER_NODE_SIZE_DP.dp)
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
                .size(RADAR_CENTER_NODE_SIZE_DP.dp)
                .border(1.dp, colors.accent.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.background.copy(alpha = 0.94f))
                .border(1.dp, colors.accent.copy(alpha = 0.56f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rider_24),
                contentDescription = null,
                modifier = Modifier.size(31.dp),
                tint = colors.accent
            )
        }
    }
}

@Composable
private fun PeerBubble(
    peer: RadarPreviewPeer,
    size: Dp,
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
    Column(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .border(
                width = if (peer.selected) 1.7.dp else 1.dp,
                color = colors.accent.copy(alpha = if (peer.selected) 0.9f else 0.46f),
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
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.background.copy(alpha = 0.88f))
            .border(1.dp, colors.outline.copy(alpha = 0.72f), RoundedCornerShape(50))
            .testTag("home_radar_overflow")
            .clearAndSetSemantics {
                contentDescription = "另有 $hiddenCount 位车友"
            }
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
private fun RadarCanvas(state: HomeScreenUiState, scopeRadius: Dp) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val motion = rememberRadarMotion(state.discovering && state.animationsEnabled)
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = with(density) { scopeRadius.toPx() }
        val ringAlpha = when {
            state.discovering -> 0.33f
            state.connected -> 0.23f
            else -> 0.16f
        }

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(colors.panelGlow, colors.panel, colors.backgroundDeep),
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colors.accent.copy(alpha = 0.055f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.28f),
                radius = size.width * 0.72f
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width / 2f, size.height * 0.28f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.accent.copy(alpha = 0.115f),
                    colors.accent.copy(alpha = 0.025f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        listOf(1f, 0.73f, 0.47f).forEachIndexed { index, scale ->
            drawCircle(
                color = colors.accent.copy(alpha = ringAlpha * if (index == 0) 1f else 0.62f),
                radius = radius * scale,
                center = center,
                style = Stroke(if (index == 0) 1.3.dp.toPx() else 0.8.dp.toPx())
            )
        }
        drawLine(
            color = colors.accent.copy(alpha = 0.13f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 0.7.dp.toPx()
        )
        drawLine(
            color = colors.accent.copy(alpha = 0.13f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 0.7.dp.toPx()
        )
        repeat(36) { index ->
            val major = index % 3 == 0
            rotate(degrees = index * 10f, pivot = center) {
                drawLine(
                    color = colors.accent.copy(alpha = if (major) 0.36f else 0.16f),
                    start = Offset(center.x + radius * 0.91f, center.y),
                    end = Offset(
                        center.x + radius * if (major) 0.965f else 0.945f,
                        center.y
                    ),
                    strokeWidth = if (major) 1.dp.toPx() else 0.55.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        when {
            state.discovering -> {
                rotate(degrees = motion.sweep, pivot = center) {
                    val wedge = Path().apply {
                        moveTo(center.x, center.y)
                        arcTo(
                            rect = Rect(
                                left = center.x - radius,
                                top = center.y - radius,
                                right = center.x + radius,
                                bottom = center.y + radius
                            ),
                            startAngleDegrees = -42f,
                            sweepAngleDegrees = 42f,
                            forceMoveTo = false
                        )
                        close()
                    }
                    drawPath(
                        path = wedge,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colors.accent.copy(alpha = 0.22f),
                                colors.accent.copy(alpha = 0.055f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        )
                    )
                    drawLine(
                        color = colors.accent.copy(alpha = 0.88f),
                        start = center,
                        end = Offset(center.x + radius * 0.975f, center.y),
                        strokeWidth = 1.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                listOf(0f, 0.5f).forEach { offset ->
                    val progress = (motion.pulse + offset) % 1f
                    drawCircle(
                        color = colors.accent.copy(alpha = (1f - progress) * 0.22f),
                        radius = radius * (0.13f + progress * 0.81f),
                        center = center,
                        style = Stroke(1.dp.toPx())
                    )
                }
            }
            state.connected -> {
                val peerAngle = -31f * PI.toFloat() / 180f
                val peer = Offset(
                    center.x + cos(peerAngle) * radius * 0.62f,
                    center.y + sin(peerAngle) * radius * 0.62f
                )
                drawLine(
                    color = colors.accent.copy(alpha = 0.42f),
                    start = center,
                    end = peer,
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(7.dp.toPx(), 8.dp.toPx())
                    )
                )
                drawCircle(
                    color = colors.accent.copy(alpha = 0.12f),
                    radius = radius * 0.42f,
                    center = center,
                    style = Stroke(1.dp.toPx())
                )
                drawCircle(colors.accent.copy(alpha = 0.16f), 15.dp.toPx(), peer)
                drawCircle(colors.accent, 3.2.dp.toPx(), peer)
            }
            else -> {
                drawCircle(
                    color = colors.accent.copy(alpha = 0.1f),
                    radius = radius * 0.34f,
                    center = center,
                    style = Stroke(1.dp.toPx())
                )
                drawCircle(colors.muted.copy(alpha = 0.38f), 3.dp.toPx(), center)
            }
        }

        val frameInset = 13.dp.toPx()
        drawRoundRect(
            color = colors.accent.copy(alpha = 0.08f),
            topLeft = Offset(frameInset, frameInset),
            size = Size(size.width - frameInset * 2f, size.height - frameInset * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
            style = Stroke(0.8.dp.toPx())
        )
        val laneY = size.height - 23.dp.toPx()
        drawLine(
            color = colors.hairline.copy(alpha = 0.055f),
            start = Offset(size.width * 0.08f, laneY),
            end = Offset(size.width * 0.92f, laneY),
            strokeWidth = 1.dp.toPx()
        )
        repeat(5) { index ->
            val x = size.width * (0.27f + index * 0.115f)
            drawLine(
                color = colors.accent.copy(alpha = 0.13f),
                start = Offset(x, laneY - 4.dp.toPx()),
                end = Offset(x, laneY + 4.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

private data class RadarMotion(val sweep: Float, val pulse: Float)

@Composable
private fun rememberRadarMotion(active: Boolean): RadarMotion {
    if (!active) return RadarMotion(sweep = 0f, pulse = 0f)
    val transition = rememberInfiniteTransition(label = "home_radar")
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing)
        ),
        label = "home_radar_sweep"
    ).value
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "home_radar_pulse"
    ).value
    return RadarMotion(sweep = sweep, pulse = pulse)
}

@Composable
private fun ControlDeck(
    state: HomeScreenUiState,
    audioLevel: Float,
    onPrimaryAction: () -> Unit,
    onMute: (Boolean) -> Unit,
    onAudioSettings: () -> Unit,
    onVox: () -> Unit,
    onGuideAnchor: (GuideTarget, Rect) -> Unit
) {
    val colors = MotoComThemePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.panel.copy(alpha = 0.96f))
            .border(1.dp, colors.outline, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_headset_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.accent
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = audioSourceLabel(state.audioSourceText),
                color = colors.text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_audio_source"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            VoxStatus(
                state = state,
                audioLevel = audioLevel,
                onClick = onVox
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.outline.copy(alpha = 0.78f), thickness = 1.dp)
        Spacer(Modifier.height(12.dp))
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
                    onClick = { onMute(!state.muted) }
                )
                DeckPrimaryControl(
                    state = state,
                    onPrimaryAction = onPrimaryAction,
                    onGuideAnchor = onGuideAnchor
                )
                DeckSideControl(
                    icon = R.drawable.ic_tune_24,
                    label = stringResource(R.string.home_audio_label),
                    description = stringResource(R.string.audio_button_description),
                    tag = "home_audio_settings_button",
                    enabled = true,
                    selected = false,
                    onClick = onAudioSettings
                )
            }
        }
        state.disabledReason?.takeIf(String::isNotBlank)?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_disabled_reason")
            )
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
    val level = if (state.connected) {
        audioLevel.coerceIn(0f, 1f)
    } else {
        (state.voxSensitivity / 100f).coerceIn(0f, 1f)
    }
    Column(
        Modifier
            .testTag("home_vox_card")
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "VOX $stateLabel，灵敏度 ${state.voxSensitivity}，打开设置"
            },
        horizontalAlignment = Alignment.End
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_vox_pill, stateLabel),
                modifier = Modifier.testTag("home_vox_pill"),
                color = colors.text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
            )
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(5) { index ->
                    val lit = index < (level * 5f).roundToInt().coerceIn(0, 5)
                    Box(
                        Modifier
                            .width(5.dp)
                            .height(if (lit) 9.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (lit) colors.accent else colors.outline)
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_vox_sensitivity, state.voxSensitivity),
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp)
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
    onClick: () -> Unit
) {
    val colors = MotoComThemePalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(52.dp)
                .motoComPressScale(interactionSource, enabled)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        colors.accent.copy(alpha = 0.14f)
                    } else {
                        colors.panelRaised
                    }
                )
                .border(
                    1.dp,
                    if (selected) colors.accent.copy(alpha = 0.4f) else colors.outline,
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
                tint = if (enabled) colors.text else colors.muted.copy(alpha = 0.45f)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            color = if (enabled) colors.muted else colors.muted.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun DeckPrimaryControl(
    state: HomeScreenUiState,
    onPrimaryAction: () -> Unit,
    onGuideAnchor: (GuideTarget, Rect) -> Unit
) {
    val colors = MotoComThemePalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val disabledDescription = state.disabledReason?.let {
        stringResource(R.string.home_primary_disabled_description, state.primaryActionLabel, it)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(92.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(colors.accent.copy(alpha = 0.16f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f,
                    center = center
                )
                drawCircle(
                    color = colors.accent.copy(alpha = 0.2f),
                    radius = size.minDimension * 0.4f,
                    center = center,
                    style = Stroke(1.dp.toPx())
                )
            }
            Button(
                onClick = onPrimaryAction,
                enabled = state.primaryActionEnabled,
                modifier = Modifier
                    .size(76.dp)
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
                    Icon(
                        painter = painterResource(R.drawable.ic_mic_24),
                        contentDescription = null,
                        modifier = Modifier.size(27.dp),
                        tint = colors.accentInk
                    )
                    Text(
                        text = state.primaryActionLabel,
                        color = colors.accentInk,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            lineHeight = 11.sp
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1
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
