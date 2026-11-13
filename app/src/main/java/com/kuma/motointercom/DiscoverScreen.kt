package com.kuma.motointercom

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
                colors.outline.copy(alpha = 0.85f),
                RoundedCornerShape(28.dp)
            )
            .padding(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 124.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "附近车友",
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        letterSpacing = 1.2.sp
                    )
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = onlineCount.toString(),
                        color = colors.text,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Black,
                            fontSize = 44.sp,
                            lineHeight = 48.sp,
                            letterSpacing = (-1.5).sp
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "位在线",
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)
                    )
                }
                Text(
                    text = state.stateText,
                    modifier = Modifier.testTag("discover_state_text"),
                    color = colors.text.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.supplementalText?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        modifier = Modifier.testTag("discover_status_supplemental"),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            MiniRadar(
                running = state.radarRunning && state.animationsEnabled,
                targetIds = state.presentation.orderedPresences.mapIndexedNotNull { index, presence ->
                    if (state.presentation.cards.getOrNull(index)?.connectVisible == true) {
                        "${presence.deviceId}:${presence.sessionId}"
                    } else null
                },
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
    targetIds: List<String>,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemeMode.NIGHTRIDE.colors
    val motion = rememberSonarMotion(running, targetIds)
    val targets = remember(targetIds) {
        val count = minOf(targetIds.size, 11)
        List(count) { index ->
            SonarTarget(-31f + index * 360f / count, 0.68f, 0.065f)
        }
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(colors.panelGlow, colors.panel, colors.backgroundDeep)))
            .border(1.5.dp, colors.outline.copy(alpha = 0.9f), CircleShape)
    ) {
        Canvas(Modifier.fillMaxSize().testTag("discover_sonar_field").semantics {
            contentDescription = "声纳目标 ${targetIds.size} 位"
        }) {
            val radius = size.minDimension * 0.44f
            listOf(1f, 0.68f, 0.36f).forEach { fraction ->
                drawCircle(
                    colors.accent.copy(alpha = 0.2f),
                    radius * fraction,
                    center,
                    style = Stroke(0.8.dp.toPx())
                )
            }
            drawActiveSonar(
                center, radius, 19.dp.toPx(),
                listOf(motion.waveFraction).filter { it > 0f },
                motion.pulse, targets, colors.accent
            )
            for (target in targets) {
                val angle = target.angleDegrees * PI.toFloat() / 180f
                val point = center + Offset(cos(angle), sin(angle)) * (target.distanceFraction * radius)
                val energy = sonarEchoEnergy(motion.waveFraction, target)
                drawCircle(colors.accent.copy(alpha = 0.15f + energy * 0.3f), 5.dp.toPx(), point)
                drawCircle(colors.accent, 2.dp.toPx(), point)
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.backgroundDeep)
                .border((1.2f + motion.pulse * 0.8f).dp, colors.accent.copy(alpha = 0.72f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_rider_24),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(11.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.72f))
            )
            Spacer(Modifier.width(7.dp))
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
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.panelRaised)
                    .border(1.dp, colors.outline.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = cards.size.toString().padStart(2, '0'),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
        MotoComPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            containerColor = colors.panel,
            borderColor = colors.outline.copy(alpha = 0.8f),
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
                        modifier = Modifier.padding(start = 66.dp, end = 14.dp),
                        color = colors.outline.copy(alpha = 0.65f),
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
            .defaultMinSize(minHeight = 68.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
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
                    .size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            if (online) {
                                colors.accent.copy(alpha = if (colors.isLight) 0.11f else 0.15f)
                            } else {
                                colors.panelRaised
                            }
                        )
                        .border(
                            1.dp,
                            if (online) {
                                colors.accent.copy(alpha = 0.36f)
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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(colors.panel)
                        .padding(2.dp)
                        .background(if (online) colors.accent else colors.muted, CircleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    color = if (online) colors.text else colors.muted,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        text = status,
                        color = if (online) colors.accent else colors.muted.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = " · ${card.deviceText}",
                        color = colors.muted.copy(alpha = if (online) 1f else 0.75f),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.panelRaised)
                            .border(1.dp, colors.outline, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.discover_manage_pairing),
                            color = colors.muted,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        )
                    }
                }
            }
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
                                    colors.accent.copy(alpha = if (colors.isLight) 0.13f else 0.18f)
                                } else {
                                    colors.panelRaised
                                }
                            )
                            .border(
                                1.dp,
                                if (card.connectEnabled) {
                                    colors.accent.copy(alpha = 0.34f)
                                } else {
                                    colors.outline
                                },
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = connectionLabel,
                            modifier = Modifier.testTag("discover_connect_${cardId}_text"),
                            color = if (card.connectEnabled) colors.accent else colors.muted,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.sp,
                                lineHeight = 15.sp
                            )
                        )
                    }
                }
            } else if (!card.paired) {
                Text(
                    text = status,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp)
                )
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
