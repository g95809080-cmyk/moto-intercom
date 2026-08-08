package com.kuma.motointercom

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
    val menuVisible: Boolean = true
)

/** Compose equivalent of the former screen_home.xml hierarchy. */
@Composable
internal fun MotoComHomeScreen(
    state: HomeScreenUiState,
    audioLevel: Float,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDiscover: () -> Unit,
    onPermissionGrant: () -> Unit,
    onPermissionSettings: () -> Unit,
    onWifiSettings: () -> Unit,
    onMute: () -> Unit,
    onAudioSettings: () -> Unit,
    onVox: () -> Unit,
    modifier: Modifier = Modifier
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
            HomeHeader(
                menuVisible = state.menuVisible,
                onMenu = onMenu,
                onSettings = onSettings
            )
            Spacer(Modifier.height(gapLarge))
            StatusCard(state = state, onVox = onVox)
            Spacer(Modifier.height(gap))
            AudioCard(state = state, audioLevel = audioLevel)
            Spacer(Modifier.height(gap))
            MainControls(
                state = state,
                onPrimaryAction = onPrimaryAction,
                onMute = onMute,
                onAudioSettings = onAudioSettings
            )
            if (state.showPermissionGrantCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(
                    stringResource(R.string.home_permission_grant_cta),
                    onPermissionGrant,
                    "home_permission_grant_cta"
                )
            }
            if (state.showPermissionSettingsCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(
                    stringResource(R.string.home_permission_settings_cta),
                    onPermissionSettings,
                    "home_permission_settings_cta"
                )
            }
            if (state.showWifiSettingsCta) {
                Spacer(Modifier.height(gap))
                SecondaryAction(
                    stringResource(R.string.wifi_settings_cta),
                    onWifiSettings,
                    "home_wifi_settings_cta"
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
private fun HomeHeader(
    menuVisible: Boolean,
    onMenu: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (menuVisible) {
            CircleIconButton(
                painter = painterResource(R.drawable.ic_menu_24),
                description = stringResource(R.string.menu_button_description),
                testTag = "home_menu_button",
                onClick = onMenu,
                surface = false
            )
        } else {
            Spacer(Modifier.size(dimensionResource(R.dimen.motocom_icon_button_size)))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = dimensionResource(R.dimen.motocom_gap)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.brand_name),
                color = colorResource(R.color.motocom_text_primary),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(colorResource(R.color.motocom_accent_green_soft))
                    .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SignalMark()
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.brand_tagline),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 13.sp
                )
            }
        }
        CircleIconButton(
            painter = painterResource(R.drawable.ic_settings_24),
            description = stringResource(R.string.settings_button_description),
            testTag = "home_settings_button",
            onClick = onSettings,
            surface = false
        )
    }
}

@Composable
private fun SignalMark() {
    val color = colorResource(R.color.motocom_accent_green)
    Canvas(Modifier.size(width = 18.dp, height = 16.dp)) {
        val heights = floatArrayOf(0.42f, 0.74f, 1f, 0.62f, 0.38f)
        val gap = size.width / (heights.size - 1)
        heights.forEachIndexed { index, height ->
            val x = index * gap
            val center = size.height / 2f
            drawLine(
                color = color,
                start = Offset(x, center - size.height * height / 2f),
                end = Offset(x, center + size.height * height / 2f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun CircleIconButton(
    painter: Painter,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    surface: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .clip(CircleShape)
            .then(
                if (surface) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                } else {
                    Modifier
                }
            )
            .testTag(testTag)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = description,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun LabeledIconButton(
    painter: Painter,
    description: String,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircleIconButton(
            painter = painter,
            description = description,
            testTag = testTag,
            onClick = onClick
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
private fun StatusCard(state: HomeScreenUiState, onVox: () -> Unit) {
    val statusColor = if (state.connected) {
        colorResource(R.color.motocom_accent_green_dark)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(dimensionResource(R.dimen.motocom_home_card_padding)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(dimensionResource(R.dimen.motocom_gap)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.connected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.Unspecified
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = state.primaryText,
                color = statusColor,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("home_status_title")
            )
        }
        if (state.detailText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.detailText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!state.connected) state.supplementalText.orEmpty().let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                color = colorResource(R.color.motocom_text_muted_accessible),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("home_status_supplemental")
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.motocom_gap)))
        if (state.connected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusWaveform(colorResource(R.color.motocom_accent_green_alt))
                RiderAvatar(discovering = false, connected = true)
                StatusWaveform(Color(0xFFFFA31A))
            }
        } else {
            RiderAvatar(discovering = state.discovering, connected = false)
        }
        Text(
            text = state.peerText,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("home_peer_name")
        )
        if (state.connected) state.supplementalText.orEmpty().let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = colorResource(R.color.motocom_text_muted_accessible),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("home_status_supplemental")
            )
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.motocom_gap_large)))
        FactRow(
            first = stringResource(R.string.home_transport_plan, state.plannedTransportText),
            second = stringResource(R.string.home_webrtc_state, state.webRtcText),
            firstIcon = painterResource(R.drawable.ic_wifi_24),
            secondIcon = painterResource(R.drawable.ic_globe_24)
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.motocom_home_compact_gap)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FactPill(
                text = stringResource(R.string.home_vox_pill, state.voxText),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = dimensionResource(R.dimen.motocom_control_min_height))
                    .clickable(role = Role.Button, onClick = onVox),
                leadingIcon = painterResource(R.drawable.ic_mic_24),
                contentDescription = stringResource(R.string.vox_developing_description),
                testTag = "home_vox_pill"
            )
            FactPill(
                text = stringResource(R.string.home_bluetooth_state, state.bluetoothText),
                modifier = Modifier.weight(1f),
                leadingIcon = painterResource(R.drawable.ic_bluetooth_24)
            )
        }
    }
}

@Composable
private fun RiderAvatar(discovering: Boolean, connected: Boolean) {
    val pulse = if (discovering) {
        val transition = rememberInfiniteTransition(label = "rider-ripple")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
            label = "rider-ripple-progress"
        ).value
    } else {
        0f
    }
    Box(
        modifier = Modifier.size(dimensionResource(R.dimen.motocom_avatar_size)),
        contentAlignment = Alignment.Center
    ) {
        if (discovering) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0x3378D900),
                    radius = size.minDimension * pulse / 2f,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
        Image(
            painter = painterResource(R.drawable.rider_helmet_avatar),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .border(
                    width = if (connected) 3.dp else 0.dp,
                    color = colorResource(R.color.motocom_accent_green),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun StatusWaveform(color: Color) {
    Canvas(Modifier.size(width = 46.dp, height = 34.dp)) {
        val center = size.height / 2f
        val bars = floatArrayOf(0.28f, 0.52f, 0.86f, 0.45f, 0.7f, 0.36f, 0.58f)
        val gap = size.width / (bars.size - 1)
        bars.forEachIndexed { index, height ->
            val x = index * gap
            drawLine(
                color = color,
                start = Offset(x, center - size.height * height / 2f),
                end = Offset(x, center + size.height * height / 2f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun FactRow(
    first: String,
    second: String,
    firstIcon: Painter? = null,
    secondIcon: Painter? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FactPill(first, Modifier.weight(1f), leadingIcon = firstIcon)
        FactPill(second, Modifier.weight(1f), leadingIcon = secondIcon)
    }
}

@Composable
private fun FactPill(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: Painter? = null,
    contentDescription: String? = null,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .padding(dimensionResource(R.dimen.motocom_home_fact_padding)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            Image(painter = it, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AudioCard(state: HomeScreenUiState, audioLevel: Float) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
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
        }
        if (state.connected) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.motocom_accent_green))
            )
        }
    }
}

@Composable
private fun MainControls(
    state: HomeScreenUiState,
    onPrimaryAction: () -> Unit,
    onMute: () -> Unit,
    onAudioSettings: () -> Unit
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
                description = stringResource(R.string.mute_developing_description),
                label = stringResource(R.string.home_mute_label),
                testTag = "home_mute_button",
                onClick = onMute
            )
            Spacer(Modifier.width(dimensionResource(R.dimen.motocom_main_control_gap)))
            val controlContainer = if (state.connected) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primary
            }
            val controlContent = if (state.connected) {
                colorResource(R.color.motocom_accent_green_dark)
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
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
    val voxDescription = stringResource(R.string.vox_developing_description)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimensionResource(R.dimen.motocom_control_min_height))
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .testTag("home_vox_card")
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = voxDescription }
            .padding(dimensionResource(R.dimen.motocom_card_padding))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VOX 状态",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "灵敏度：待接入",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        VoxTimeline()
        Row(
            modifier = Modifier.fillMaxWidth().testTag("home_vox_state_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VoxState(stringResource(R.string.home_vox_listening_placeholder), Modifier.weight(1f))
            VoxState(stringResource(R.string.home_vox_open_placeholder), Modifier.weight(1f))
            VoxState(stringResource(R.string.home_vox_hangover_placeholder), Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoxTimeline() {
    val track = colorResource(R.color.motocom_border)
    val green = colorResource(R.color.motocom_accent_green)
    val orange = Color(0xFFFFA31A)
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val y = size.height / 2f
        val left = 8.dp.toPx()
        val right = size.width - left
        drawLine(track, Offset(left, y), Offset(right, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(green, Offset(left, y), Offset(right * 0.68f, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color(0xFFCBD5E1), radius = 6.dp.toPx(), center = Offset(left + 42.dp.toPx(), y))
        drawCircle(green, radius = 6.dp.toPx(), center = Offset(right * 0.55f, y))
        drawCircle(orange, radius = 6.dp.toPx(), center = Offset(right - 42.dp.toPx(), y))
    }
}

@Composable
private fun VoxState(text: String, modifier: Modifier) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit, testTag: String) {
    Box(
        modifier = Modifier
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
                voxText = "状态接口待接入",
                discovering = false,
                connected = false
            ),
            audioLevel = 0f,
            onMenu = {},
            onSettings = {},
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
