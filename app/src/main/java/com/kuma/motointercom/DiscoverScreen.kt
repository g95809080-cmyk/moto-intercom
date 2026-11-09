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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class DiscoverScreenUiState(
    val presentation: DiscoverPresentation,
    val stateText: String,
    val supplementalText: String?,
    val radarRunning: Boolean,
    val rescanEnabled: Boolean = false,
    val animationsEnabled: Boolean = true,
    val statusLabel: String = ""
)

@Composable
internal fun MotoComDiscoverScreen(
    state: DiscoverScreenUiState,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onStart: () -> Unit,
    onWifiSettings: () -> Unit,
    onRescan: () -> Unit,
    onSelectPresence: (RiderPresence) -> Unit,
    onConnect: (RiderPresence) -> Unit,
    onManagePairing: (RiderPresence) -> Unit,
    modifier: Modifier = Modifier,
    onGuideAnchor: (GuideTarget, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> }
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedNavigationCallbacks = onBack to onHelp
    val colors = MotoComThemePalette.current
    val presentation = state.presentation
    val pageHorizontalPadding = dimensionResource(R.dimen.motocom_page_horizontal_padding)
    val pageVerticalPadding = dimensionResource(R.dimen.motocom_page_vertical_padding)
    val rescanDescription = stringResource(
        if (state.rescanEnabled) {
            R.string.rescan_description
        } else {
            R.string.rescan_unavailable_description
        }
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter
    ) {
        MotoComBackdrop(Modifier.matchParentSize())
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = dimensionResource(R.dimen.motocom_content_max_width))
                .padding(
                    start = pageHorizontalPadding,
                    top = pageVerticalPadding,
                    end = pageHorizontalPadding,
                    bottom = 20.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MotoComTopBar(
                active = state.animationsEnabled && state.radarRunning
            )
            MotoComPageHeading("发现", Modifier.testTag("discover_title")) {
                MotoComStatusPill(
                    label = discoverPillLabel(state),
                    active = state.radarRunning,
                    animated = discoverStatusPulseActive(state)
                )
            }
            DiscoveryHero(
                state = state,
                onStart = onStart,
                onWifiSettings = onWifiSettings
            )
            DiscoverGroup(
                tag = "discover_paired_container",
                cards = presentation.cards.withIndex().filter {
                    !it.value.offlinePaired && (it.value.paired || it.value.preferred)
                },
                orderedPresences = presentation.orderedPresences,
                onSelectPresence = onSelectPresence,
                onConnect = onConnect,
                onManagePairing = onManagePairing
            )
            DiscoverGroup(
                tag = "discover_nearby_container",
                cards = presentation.cards.withIndex().filter {
                    !it.value.preferred && !it.value.paired && !it.value.offlinePaired
                },
                orderedPresences = presentation.orderedPresences,
                onSelectPresence = onSelectPresence,
                onConnect = onConnect,
                onManagePairing = onManagePairing
            )
            DiscoverGroup(
                tag = "discover_offline_paired_container",
                cards = presentation.cards.withIndex().filter { it.value.offlinePaired },
                orderedPresences = presentation.orderedPresences,
                onSelectPresence = onSelectPresence,
                onConnect = onConnect,
                onManagePairing = onManagePairing
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (state.rescanEnabled) {
                            colors.panel.copy(alpha = 0.92f)
                        } else {
                            colors.panelRaised.copy(alpha = 0.65f)
                        }
                    )
                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                    .testTag("discover_rescan_button")
                    .guideAnchor(GuideTarget.SCAN, onGuideAnchor)
                    .semantics { contentDescription = rescanDescription }
                    .clickable(
                        enabled = state.rescanEnabled,
                        role = Role.Button,
                        onClick = onRescan
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (state.rescanEnabled) colors.accent else colors.muted
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.discover_rescan_label),
                        color = if (state.rescanEnabled) colors.text else colors.muted,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

private fun discoverPillLabel(state: DiscoverScreenUiState): String {
    val presentation = state.presentation
    return when {
        presentation.wifiSettingsVisible -> "Wi-Fi 未开启"
        presentation.offlineStartVisible -> "待机中"
        state.radarRunning -> "扫描中"
        presentation.readOnlyReason != null -> state.statusLabel.ifBlank { "连接中" }
        presentation.cards.any { it.connectVisible } -> "可选车友"
        else -> "等待车友"
    }
}

internal fun discoverStatusPulseActive(state: DiscoverScreenUiState): Boolean =
    state.animationsEnabled && state.radarRunning

@Composable
private fun DiscoveryHero(
    state: DiscoverScreenUiState,
    onStart: () -> Unit,
    onWifiSettings: () -> Unit
) {
    val colors = MotoComThemePalette.current
    val onlineCount = state.presentation.cards.count { it.connectVisible }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("discover_hero")
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.panelGlow, colors.panel, colors.backgroundDeep)
                )
            )
            .border(
                1.dp,
                colors.hairline.copy(alpha = 0.07f),
                RoundedCornerShape(28.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 124.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "附近车友",
                    color = colors.accent,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp
                    )
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = onlineCount.toString(),
                        color = colors.text,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 46.sp,
                            lineHeight = 48.sp,
                            letterSpacing = (-2).sp
                        )
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "位在线",
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp)
                    )
                }
                Text(
                    text = state.stateText,
                    modifier = Modifier.testTag("discover_state_text"),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.supplementalText?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        modifier = Modifier.testTag("discover_status_supplemental"),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            MiniRadar(
                running = state.radarRunning && state.animationsEnabled,
                modifier = Modifier.size(118.dp)
            )
        }
        if (state.presentation.offlineStartVisible) {
            DiscoverAction(
                text = stringResource(R.string.discover_start),
                tag = "discover_offline_start_button",
                primary = true,
                onClick = onStart
            )
        }
        if (state.presentation.wifiSettingsVisible) {
            DiscoverAction(
                text = stringResource(R.string.wifi_settings_cta),
                tag = "discover_wifi_settings_button",
                primary = false,
                onClick = onWifiSettings
            )
        }
    }
}

@Composable
private fun MiniRadar(
    running: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val sweep = if (running) {
        val transition = rememberInfiniteTransition(label = "discover_mini_radar")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing)
            ),
            label = "discover_mini_sweep"
        ).value
    } else {
        0f
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.43f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors.accent.copy(alpha = 0.075f), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
            listOf(1f, 0.68f, 0.36f).forEachIndexed { index, scale ->
                drawCircle(
                    color = colors.accent.copy(
                        alpha = if (index == 0) 0.34f else 0.16f
                    ),
                    radius = radius * scale,
                    center = center,
                    style = Stroke(if (index == 0) 1.dp.toPx() else 0.7.dp.toPx())
                )
            }
            repeat(24) { index ->
                rotate(degrees = index * 15f, pivot = center) {
                    drawLine(
                        color = colors.accent.copy(
                            alpha = if (index % 3 == 0) 0.32f else 0.13f
                        ),
                        start = Offset(center.x + radius * 0.91f, center.y),
                        end = Offset(
                            center.x + radius * if (index % 3 == 0) 0.97f else 0.95f,
                            center.y
                        ),
                        strokeWidth = if (index % 3 == 0) 0.9.dp.toPx() else 0.55.dp.toPx()
                    )
                }
            }
            if (running) {
                rotate(degrees = sweep, pivot = center) {
                    drawArc(
                        color = colors.accent.copy(alpha = 0.13f),
                        startAngle = -38f,
                        sweepAngle = 38f,
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f)
                    )
                    drawLine(
                        color = colors.accent.copy(alpha = 0.72f),
                        start = center,
                        end = Offset(center.x + radius * 0.96f, center.y),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            listOf(-28f to 0.72f, 72f to 0.54f, 156f to 0.76f).forEach { point ->
                val radians = point.first * PI.toFloat() / 180f
                val dot = Offset(
                    center.x + cos(radians) * radius * point.second,
                    center.y + sin(radians) * radius * point.second
                )
                drawCircle(colors.accent.copy(alpha = 0.18f), 6.dp.toPx(), dot)
                drawCircle(colors.accent, 2.dp.toPx(), dot)
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.backgroundDeep)
                .border(1.dp, colors.accent.copy(alpha = 0.63f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rider_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.accent
            )
        }
    }
}

@Composable
private fun DiscoverAction(
    text: String,
    tag: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    val colors = MotoComThemePalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (primary) {
                    colors.accent
                } else {
                    colors.panelRaised.copy(alpha = 0.8f)
                }
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else colors.outline,
                RoundedCornerShape(16.dp)
            )
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (primary) colors.accentInk else colors.text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun DiscoverGroup(
    tag: String,
    cards: List<IndexedValue<DiscoverCardPresentation>>,
    orderedPresences: List<RiderPresence>,
    onSelectPresence: (RiderPresence) -> Unit,
    onConnect: (RiderPresence) -> Unit,
    onManagePairing: (RiderPresence) -> Unit
) {
    if (cards.isEmpty()) return
    val colors = MotoComThemePalette.current
    Column(
        modifier = Modifier.testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 18.dp)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    when (tag) {
                        "discover_paired_container" -> R.string.discover_paired_title
                        "discover_offline_paired_container" ->
                            R.string.discover_offline_paired_title
                        else -> R.string.discover_nearby_title
                    }
                ),
                modifier = Modifier.testTag("${tag}_label"),
                color = colors.muted,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp
                )
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = cards.size.toString().padStart(2, '0'),
                color = colors.outline,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp)
            )
        }
        MotoComPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.panel.copy(alpha = 0.9f),
            borderColor = colors.hairline.copy(alpha = 0.055f),
            contentPadding = PaddingValues(0.dp)
        ) {
            cards.forEachIndexed { index, indexed ->
                DiscoverRiderRow(
                    card = indexed.value,
                    presence = orderedPresences[indexed.index],
                    onSelectPresence = onSelectPresence,
                    onConnect = onConnect,
                    onManagePairing = onManagePairing
                )
                if (index != cards.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 62.dp, end = 12.dp),
                        color = colors.outline.copy(alpha = 0.62f),
                        thickness = 1.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverRiderRow(
    card: DiscoverCardPresentation,
    presence: RiderPresence,
    onSelectPresence: (RiderPresence) -> Unit,
    onConnect: (RiderPresence) -> Unit,
    onManagePairing: (RiderPresence) -> Unit
) {
    val colors = MotoComThemePalette.current
    val cardId = presence.deviceId ?: card.title
    val online = presence.isSelectableForUi()
    val status = when {
        card.offlinePaired -> stringResource(R.string.discover_status_pending)
        !online -> stringResource(R.string.discover_status_unavailable)
        card.preferred -> stringResource(R.string.discover_status_recommended)
        card.paired -> stringResource(R.string.discover_status_paired)
        else -> stringResource(R.string.discover_status_online)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("discover_card_$cardId"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .testTag("discover_select_$cardId")
                .semantics { contentDescription = card.title }
                .clickable(role = Role.Button) { onSelectPresence(presence) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            if (online) {
                                colors.accent.copy(alpha = 0.1f)
                            } else {
                                colors.panelRaised
                            }
                        )
                        .border(
                            1.dp,
                            if (online) {
                                colors.accent.copy(alpha = 0.32f)
                            } else {
                                colors.outline
                            },
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = card.title.firstOrNull()?.uppercase() ?: "·",
                        color = if (online) colors.accent else colors.muted,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.panel)
                        .padding(2.dp)
                        .background(if (online) colors.accent else colors.muted, CircleShape)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    color = if (online) colors.text else colors.muted,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        text = status,
                        color = colors.muted.copy(alpha = if (online) 1f else 0.7f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1
                    )
                    Text(
                        text = " · ${card.deviceText}",
                        color = colors.muted.copy(alpha = if (online) 1f else 0.7f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (card.connectVisible) {
                val connectionLabel = stringResource(
                    if (card.connectEnabled) {
                        R.string.discover_connect
                    } else {
                        R.string.discover_connect_pending
                    }
                )
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("discover_connect_$cardId")
                        .clickable(
                            enabled = card.connectEnabled,
                            role = Role.Button
                        ) { onConnect(presence) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (card.connectEnabled) {
                                    colors.accent.copy(alpha = 0.13f)
                                } else {
                                    colors.panelRaised
                                }
                            )
                            .border(
                                1.dp,
                                if (card.connectEnabled) {
                                    colors.accent.copy(alpha = 0.22f)
                                } else {
                                    colors.outline
                                },
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connectionLabel,
                            modifier = Modifier.testTag("discover_connect_${cardId}_text"),
                            color = if (card.connectEnabled) colors.accent else colors.muted,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        )
                    }
                }
            } else {
                Text(
                    text = status,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
            if (card.paired) {
                val manageDescription = stringResource(
                    R.string.discover_manage_pairing_description,
                    card.title
                )
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("discover_manage_$cardId")
                        .semantics { contentDescription = manageDescription }
                        .clickable(role = Role.Button) { onManagePairing(presence) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.discover_manage_pairing),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DiscoverScreenPreview() {
    MotoComTheme {
        MotoComDiscoverScreen(
            state = DiscoverScreenUiState(
                presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
                stateText = "正在搜索",
                supplementalText = null,
                radarRunning = true,
                rescanEnabled = true
            ),
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
