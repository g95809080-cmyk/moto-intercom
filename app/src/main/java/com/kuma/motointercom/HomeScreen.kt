package com.kuma.motointercom

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class HomeScreenUiState(
    val primaryText: String,
    val detailText: String,
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
    val plannedTransportText: String,
    val connectedTransportText: String,
    val webRtcText: String,
    val bluetoothText: String,
    val voxText: String,
    val discovering: Boolean,
    val connected: Boolean,
    val muted: Boolean = false,
    val muteEnabled: Boolean = false,
    val voxEnabled: Boolean = true,
    val voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY,
    val voxState: VoxRuntimeState = VoxRuntimeState.IDLE,
    val animationsEnabled: Boolean = true
)

/** Compose equivalent of the former screen_home.xml hierarchy. */
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
    onGuideAnchor: (GuideTarget, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onOpenGroup: () -> Unit = {}
) {
    val pageHorizontalPadding = dimensionResource(R.dimen.motocom_page_horizontal_padding)
    val pageVerticalPadding = dimensionResource(R.dimen.motocom_page_vertical_padding)
    val gap = dimensionResource(R.dimen.motocom_gap)
    val gapLarge = dimensionResource(R.dimen.motocom_gap_large)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = dimensionResource(R.dimen.motocom_content_max_width))
                .padding(
                    start = pageHorizontalPadding,
                    top = pageVerticalPadding,
                    end = pageHorizontalPadding,
                    bottom = 32.dp
                )
        ) {
            HomeHeader()
            Button(onClick = onOpenGroup, modifier = Modifier.fillMaxWidth().testTag("home_group")) { Text("四人离线对讲") }
            Spacer(Modifier.height(gapLarge))
            StatusCard(state = state)
            Spacer(Modifier.height(gap))
            AudioCard(state = state, audioLevel = audioLevel)
            Spacer(Modifier.height(gap))
            MainControls(
                state = state,
                onPrimaryAction = onPrimaryAction,
                onMute = onMute,
                onAudioSettings = onAudioSettings,
                onGuideAnchor = onGuideAnchor
            )
            if (state.showPermissionGrantCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(
                    stringResource(R.string.home_permission_grant_cta),
                    onPermissionGrant,
                    "home_permission_grant_cta",
                    Modifier.guideAnchor(GuideTarget.PERMISSION, onGuideAnchor)
                )
            }
            if (state.showPermissionSettingsCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(
                    stringResource(R.string.home_permission_settings_cta),
                    onPermissionSettings,
                    "home_permission_settings_cta",
                    Modifier.guideAnchor(GuideTarget.PERMISSION_SETTINGS, onGuideAnchor)
                )
            }
            if (state.showWifiSettingsCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(
                    stringResource(R.string.wifi_settings_cta),
                    onWifiSettings,
                    "home_wifi_settings_cta",
                    Modifier.guideAnchor(GuideTarget.WIFI, onGuideAnchor)
                )
            }
            Spacer(Modifier.height(gap))
            VoxCard(state = state, onClick = onVox)
            if (state.showDiscoverCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(state.discoverCtaLabel, onDiscover, "home_discover_cta")
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.brand_name), color = MaterialTheme.colorScheme.onBackground,
                fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
            Text(stringResource(R.string.brand_tagline), Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CircleIconButton(
    painter: Painter,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    surface: Boolean = true,
    enabled: Boolean = true,
    isSelected: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .clip(CircleShape)
            .then(
                if (surface) {
                    Modifier
                        .background(
                            if (isSelected) {
                                colorResource(R.color.motocom_accent_green_soft)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                } else {
                    Modifier
                }
            )
            .testTag(testTag)
            .semantics {
                contentDescription = description
                if (isSelected) selected = true
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painter = painter, contentDescription = null, modifier = Modifier.size(23.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
    }
}

@Composable
private fun LabeledIconButton(
    painter: Painter,
    description: String,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isSelected: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircleIconButton(
            painter = painter,
            description = description,
            testTag = testTag,
            onClick = onClick,
            enabled = enabled,
            isSelected = isSelected
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatusCard(state: HomeScreenUiState) {
    val accent = colorResource(R.color.motocom_accent_green)
    val muted = colorResource(R.color.motocom_on_console_secondary)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(colorResource(R.color.motocom_console)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (state.connected) accent else muted, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(state.primaryText, Modifier.weight(1f).testTag("home_status_title"),
                color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        Text(state.peerText, Modifier.fillMaxWidth().padding(top = 20.dp).testTag("home_peer_name"),
            color = Color.White, fontSize = 30.sp, lineHeight = 38.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, textAlign = TextAlign.Center)
        if (state.detailText.isNotBlank() && state.detailText != stringResource(R.string.brand_tagline)) Text(state.detailText, Modifier.padding(top = 5.dp),
            color = muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        state.supplementalText.orEmpty().let {
            Text(it, Modifier.then(if (it.isBlank()) Modifier.height(0.dp) else Modifier.padding(top = 5.dp)).testTag("home_status_supplemental"),
                color = if (state.connected) accent else muted,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        CommunicationDial(state.discovering && state.animationsEnabled, state.connected)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ConsoleFact(stringResource(R.string.home_transport_plan, state.plannedTransportText),
                R.drawable.ic_wifi_24, Modifier.weight(1f))
            ConsoleFact(stringResource(R.string.home_webrtc_state, state.webRtcText),
                R.drawable.ic_globe_24, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConsoleFact(text: String, icon: Int, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(17.dp), tint = colorResource(R.color.motocom_accent_green))
        Spacer(Modifier.width(7.dp))
        Text(text, color = colorResource(R.color.motocom_on_console_secondary),
            fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CommunicationDial(discovering: Boolean, connected: Boolean) {
    val accent = colorResource(R.color.motocom_accent_green)
    val pulse = if (discovering) {
        val transition = rememberInfiniteTransition(label = "communication")
        transition.animateFloat(0.35f, 0.8f,
            infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "signal").value
    } else if (connected) 0.7f else 0.3f
    Box(Modifier.padding(vertical = 12.dp).size(130.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent)), radius)
            listOf(1f, 0.8f, 0.59f).forEachIndexed { index, scale ->
                drawCircle(accent.copy(alpha = pulse * (0.25f + index * 0.25f)), radius * scale,
                    style = Stroke(if (index == 2) 1.5.dp.toPx() else 0.8.dp.toPx()))
            }
            if (connected || discovering) drawCircle(accent, 3.dp.toPx(),
                Offset(center.x + radius * 0.56f, center.y - radius * 0.56f))
        }
        Icon(painterResource(R.drawable.ic_mic_24), null, Modifier.size(40.dp), tint = Color.White)
    }
}

@Composable
private fun AudioCard(state: HomeScreenUiState, audioLevel: Float) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_headset_24),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.section_audio_output),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = state.audioSourceText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("home_audio_source")
            )
            Text(
                text = stringResource(R.string.home_bluetooth_state, state.bluetoothText),
                modifier = Modifier.padding(top = 3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }

    }
}

@Composable
private fun MainControls(
    state: HomeScreenUiState,
    onPrimaryAction: () -> Unit,
    onMute: (Boolean) -> Unit,
    onAudioSettings: () -> Unit,
    onGuideAnchor: (GuideTarget, androidx.compose.ui.geometry.Rect) -> Unit
) {
    val disabledDescription = state.disabledReason?.let {
        stringResource(
            R.string.home_primary_disabled_description,
            state.primaryActionLabel,
            it
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("home_main_control_section"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.testTag("home_main_control_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabeledIconButton(
                painter = painterResource(R.drawable.ic_mute_24),
                description = when {
                    state.muted -> stringResource(R.string.unmute_description)
                    state.muteEnabled -> stringResource(R.string.mute_description)
                    else -> stringResource(R.string.mute_unavailable_description)
                },
                label = stringResource(
                    if (state.muted) R.string.home_unmute_label else R.string.home_mute_label
                ),
                testTag = "home_mute_button",
                onClick = { onMute(!state.muted) },
                enabled = state.muteEnabled,
                isSelected = state.muted
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.motocom_main_control_gap)))
            val controlContainer = colorResource(R.color.motocom_accent_green)
            val controlContent = MaterialTheme.colorScheme.onSurface
            Button(
                onClick = onPrimaryAction,
                enabled = state.primaryActionEnabled,
                modifier = Modifier
                    .size(dimensionResource(R.dimen.motocom_main_control_size))
                    .then(
                        if (state.connected) {
                            Modifier.border(
                                BorderStroke(3.dp, colorResource(R.color.motocom_accent_green)),
                                CircleShape
                            )
                        } else {
                            Modifier
                        }
                    )
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
                    containerColor = controlContainer,
                    contentColor = controlContent,
                    disabledContainerColor = colorResource(R.color.motocom_disabled),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic_24),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = controlContent
                        )
                        Text(
                            text = state.primaryActionLabel,
                            color = controlContent,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            Spacer(Modifier.width(dimensionResource(R.dimen.motocom_main_control_gap)))
            LabeledIconButton(
                painter = painterResource(R.drawable.ic_tune_24),
                description = stringResource(R.string.audio_button_description),
                label = stringResource(R.string.home_audio_label),
                testTag = "home_audio_settings_button",
                onClick = onAudioSettings
            )
        }
        state.disabledReason?.takeIf(String::isNotBlank)?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("home_disabled_reason")
            )
        }
    }
}

@Composable
private fun VoxCard(state: HomeScreenUiState, onClick: () -> Unit) {
    val description = stringResource(R.string.home_vox_card_description, state.voxText, state.voxSensitivity)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
        .testTag("home_vox_card").clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_vox_pill, state.voxText),
                Modifier.weight(1f).testTag("home_vox_pill"),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("灵敏度：${state.voxSensitivity}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("home_vox_state_row"),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VoxState("LISTENING", state.voxState == VoxRuntimeState.LISTENING, Modifier.weight(1f))
            VoxState("OPEN", state.voxState == VoxRuntimeState.OPEN, Modifier.weight(1f))
            VoxState("HANGOVER", state.voxState == VoxRuntimeState.HANGOVER, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoxState(text: String, active: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        val title = when (text) {
            "LISTENING" -> "待机"
            "OPEN" -> "开麦"
            else -> "保持"
        }
        Text(
            text = "$title\n$text",
            color = if (active) {
                colorResource(R.color.motocom_accent_green_dark)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 10.sp,
            lineHeight = 15.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimensionResource(R.dimen.motocom_control_min_height))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
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
                detailText = "一对一对讲 · 无需网络",
                supplementalText = "请点击下方启动对讲",
                peerText = "等待车友加入",
                primaryActionLabel = "启动",
                primaryActionEnabled = true,
                disabledReason = null,
                showPermissionGrantCta = false,
                showPermissionSettingsCta = false,
                showWifiSettingsCta = false,
                discoverCtaLabel = "查看附近车友",
                showDiscoverCta = false,
                audioSourceText = "当前音频源：待机",
                plannedTransportText = "待建立",
                connectedTransportText = "未连接",
                webRtcText = "未连接",
                bluetoothText = "蓝牙状态不可用",
                voxText = "IDLE",
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
