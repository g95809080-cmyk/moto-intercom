package com.kuma.motointercom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class SettingsScreenUiState(
    val nickname: String,
    val nicknameFeedback: String,
    val audioSource: String,
    val productState: String,
    val optionalPermissionNotice: String?,
    val showOptionalPermissionCta: Boolean,
    val version: String,
    val voxEnabled: Boolean = true,
    val voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY,
    val preferredAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    val automaticReconnectEnabled: Boolean = true,
    val rnnoiseEnabled: Boolean = false,
    val canChangeNoiseReduction: Boolean = true,
    val cloudVisible: Boolean = false,
    val cloudSummary: String = "",
    val cloudAudioActive: Boolean = false,
    val earpieceAvailable: Boolean = !cloudAudioActive
)

@Composable
internal fun MotoComSettingsScreen(
    state: SettingsScreenUiState,
    onBack: () -> Unit,
    onNicknameChanged: (String) -> Unit,
    onSaveNickname: () -> Unit,
    onOptionalPermission: () -> Unit,
    onLogs: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    onVoxEnabledChanged: (Boolean) -> Unit = {},
    onVoxSensitivityChanged: (Int) -> Unit = {},
    onAudioRouteSelected: (AudioRouteSelection) -> Unit = {},
    onRnnoiseEnabledChanged: (Boolean) -> Unit = {},
    onAutomaticReconnectChanged: (Boolean) -> Unit = {},
    onCloudSettings: () -> Unit = {},
    onAudioSectionPositioned: (Int) -> Unit = {},
    theme: MotoComThemeMode = MotoComThemeMode.DAYLIGHT,
    onThemeSelected: (MotoComThemeMode) -> Unit = {},
    onGuide: () -> Unit = {}
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedBackCallback = onBack
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
            MotoComTopBar()
            MotoComPageHeading("设置", Modifier.testTag("settings_title")) {
                MotoComDevicePill(audioSourceLabel(state.audioSource))
            }
            IdentityCard(
                state = state,
                onNicknameChanged = onNicknameChanged,
                onSaveNickname = onSaveNickname
            )
            MotoComSectionLabel("主题")
            MotoComThemeSelector(
                selectedTheme = theme,
                onThemeSelected = onThemeSelected
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .testTag("settings_audio_section")
                    .onGloballyPositioned {
                        onAudioSectionPositioned(it.positionInRoot().y.roundToInt())
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AudioCard(
                    state = state,
                    onAudioRouteSelected = onAudioRouteSelected,
                    modifier = Modifier.weight(1f)
                )
                VoxCard(
                    state = state,
                    onVoxEnabledChanged = onVoxEnabledChanged,
                    onVoxSensitivityChanged = onVoxSensitivityChanged,
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.cloudAudioActive) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.panelRaised.copy(alpha = 0.78f))
                        .border(1.dp, colors.outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "云端使用 GME 语音门控；RNNoise 仅用于本地对讲。",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                    )
                }
            }
            if (BuildConfig.RNNOISE_BETA && !state.cloudAudioActive) {
                MotoComSectionLabel(stringResource(R.string.rnnoise_beta_section))
                SettingsSwitchCard(
                    icon = R.drawable.ic_mic_24,
                    title = stringResource(R.string.rnnoise_beta_switch),
                    description = stringResource(R.string.rnnoise_beta_switch_description),
                    tag = "settings_rnnoise_switch",
                    checked = state.rnnoiseEnabled,
                    enabled = state.canChangeNoiseReduction,
                    onCheckedChange = onRnnoiseEnabledChanged
                ) {
                    Text(
                        text = stringResource(
                            if (state.rnnoiseEnabled) {
                                R.string.rnnoise_selected
                            } else {
                                R.string.webrtc_selected
                            }
                        ),
                        modifier = Modifier.testTag("settings_noise_mode"),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                    )
                }
            }
            MotoComSectionLabel(stringResource(R.string.settings_connection_device))
            SettingsSwitchCard(
                icon = R.drawable.ic_refresh_24,
                title = stringResource(R.string.settings_auto_reconnect),
                description = stringResource(R.string.settings_auto_reconnect_description),
                tag = "settings_reconnect_button",
                checked = state.automaticReconnectEnabled,
                onCheckedChange = onAutomaticReconnectChanged
            ) {
                Text(
                    text = state.productState,
                    modifier = Modifier.testTag("settings_product_state"),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                )
            }
            if (state.optionalPermissionNotice != null || state.showOptionalPermissionCta) {
                MotoComSectionLabel(stringResource(R.string.settings_permission_section))
                MotoComPanel(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.panel.copy(alpha = 0.92f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(13.dp)
                ) {
                    state.optionalPermissionNotice?.let {
                        Text(
                            text = it,
                            modifier = Modifier.testTag("settings_optional_permission_notice"),
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.showOptionalPermissionCta) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.accent.copy(alpha = 0.12f))
                                .border(
                                    1.dp,
                                    colors.accent.copy(alpha = 0.25f),
                                    RoundedCornerShape(14.dp)
                                )
                                .testTag("settings_optional_permission_button")
                                .clickable(role = Role.Button, onClick = onOptionalPermission),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.settings_optional_permission_grant),
                                color = colors.accent,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
            MotoComSectionLabel(stringResource(R.string.section_advanced_settings))
            if (state.cloudVisible) {
                MotoComPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 58.dp)
                        .testTag("settings_cloud_button")
                        .clickable(role = Role.Button, onClick = onCloudSettings),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = colors.panel.copy(alpha = 0.94f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 13.dp,
                        vertical = 11.dp
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colors.accent.copy(alpha = 0.12f))
                                .border(1.dp, colors.accent.copy(alpha = 0.28f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_globe_24),
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = colors.accent
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "云端对讲",
                                color = colors.text,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp
                                )
                            )
                            if (state.cloudSummary.isNotBlank()) {
                                Text(
                                    text = state.cloudSummary,
                                    color = colors.muted,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                )
                            }
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right_24),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.muted
                        )
                    }
                }
            }
            MoreGrid(
                onLogs = onLogs,
                onHelp = onHelp,
                onAbout = onAbout,
                onGuide = onGuide
            )
            Text(
                text = state.version,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .testTag("settings_version_text"),
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun IdentityCard(
    state: SettingsScreenUiState,
    onNicknameChanged: (String) -> Unit,
    onSaveNickname: () -> Unit
) {
    val colors = MotoComThemePalette.current
    MotoComPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp),
        shape = RoundedCornerShape(22.dp),
        containerColor = colors.panel.copy(alpha = 0.94f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 13.dp,
            vertical = 10.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.14f))
                    .border(1.dp, colors.accent.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_rider_24),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = colors.accent
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_nickname_label),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                )
                BasicTextField(
                    value = state.nickname,
                    onValueChange = onNicknameChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .testTag("settings_nickname_input"),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.text,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 20.sp
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    decorationBox = { innerTextField ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.panelRaised.copy(alpha = 0.65f))
                                .border(
                                    1.dp,
                                    colors.outline.copy(alpha = 0.58f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (state.nickname.isBlank()) {
                                Text(
                                    text = stringResource(R.string.edit_text_hint),
                                    color = colors.muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (state.nicknameFeedback.isNotBlank()) {
                    Text(
                        text = state.nicknameFeedback,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .testTag("settings_nickname_feedback"),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .testTag("settings_save_nickname_button")
                    .semantics {
                        contentDescription = "保存昵称"
                    }
                    .clickable(role = Role.Button, onClick = onSaveNickname),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f))
                        .border(1.dp, colors.accent.copy(alpha = 0.42f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_24),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = colors.accent
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioCard(
    state: SettingsScreenUiState,
    onAudioRouteSelected: (AudioRouteSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    val routes = listOf(
        Triple(AudioRouteSelection.BLUETOOTH, "耳机", "settings_audio_route_button"),
        Triple(AudioRouteSelection.EARPIECE, "听筒", "settings_audio_earpiece_button"),
        Triple(AudioRouteSelection.SPEAKER, "外放", "settings_audio_speaker_button")
    ).filter {state.earpieceAvailable || it.first!=AudioRouteSelection.EARPIECE}
    MotoComPanel(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.panel.copy(alpha = 0.94f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_headset_24),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.accent
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = "音频",
                color = colors.muted,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = audioSourceLabel(state.audioSource),
            modifier = Modifier.testTag("settings_audio_source"),
            color = colors.text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.sp,
                lineHeight = 19.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            routes.forEach { (route, label, tag) ->
                val selected = state.preferredAudioRoute == route
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(tag)
                        .semantics {
                            this.selected = selected
                            contentDescription = "音频路由：$label"
                        }
                        .clickable(role = Role.RadioButton) { onAudioRouteSelected(route) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(31.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) {
                                    colors.accent.copy(alpha = 0.18f)
                                } else {
                                    colors.panelRaised.copy(alpha = 0.78f)
                                }
                            )
                            .border(
                                1.dp,
                                if (selected) {
                                    colors.accent.copy(alpha = 0.52f)
                                } else {
                                    colors.outline.copy(alpha = 0.65f)
                                },
                                RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) colors.accent else colors.muted,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoxCard(
    state: SettingsScreenUiState,
    onVoxEnabledChanged: (Boolean) -> Unit,
    onVoxSensitivityChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    val voxSensitivityDescription = stringResource(
        R.string.settings_vox_sensitivity_description,
        state.voxSensitivity
    )
    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(colors.panelGlow.copy(alpha = 0.94f), colors.panel)
                )
            )
            .border(1.dp, colors.accent.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "VOX",
                color = colors.muted,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )
            )
            Spacer(Modifier.weight(1f))
            MotoComToggle(
                checked = state.voxEnabled,
                onCheckedChange = onVoxEnabledChanged,
                modifier = Modifier.testTag("settings_vox_button"),
                contentDescription = stringResource(R.string.settings_vox_switch_description)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.voxSensitivity.toString(),
                color = if (state.voxEnabled) colors.accent else colors.muted,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 31.sp,
                    letterSpacing = (-1).sp
                )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "灵敏度",
                modifier = Modifier.padding(bottom = 4.dp),
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_vox_sensitivity_button")
                .semantics {
                    contentDescription = voxSensitivityDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = state.voxSensitivity.toFloat(),
                        range = 0f..100f,
                        steps = 9
                    )
                    if (state.voxEnabled) {
                        setProgress { target ->
                            onVoxSensitivityChanged(
                                target.roundToInt().coerceIn(0, 100)
                            )
                            true
                        }
                    } else {
                        disabled()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = state.voxSensitivity.toFloat(),
                onValueChange = { onVoxSensitivityChanged(it.roundToInt().coerceIn(0, 100)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clearAndSetSemantics {},
                enabled = state.voxEnabled,
                valueRange = 0f..100f,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.outline.copy(alpha = 0.72f),
                    disabledThumbColor = colors.muted,
                    disabledActiveTrackColor = colors.outline,
                    disabledInactiveTrackColor = colors.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    icon: Int,
    title: String,
    description: String? = null,
    tag: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    footer: @Composable (() -> Unit)? = null
) {
    val colors = MotoComThemePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel.copy(alpha = 0.92f))
            .border(
                1.dp,
                colors.hairline.copy(alpha = 0.055f),
                RoundedCornerShape(20.dp)
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics {
                contentDescription = buildString {
                    append(title)
                    description?.let {
                        append("，")
                        append(it)
                    }
                }
            }
            .testTag(tag)
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f))
                    .border(1.dp, colors.accent.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.accent
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                )
                description?.let {
                    Text(
                        text = it,
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            MotoComToggle(
                checked = checked,
                enabled = enabled,
                onCheckedChange = {},
                interactive = false
            )
        }
        footer?.let {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outline.copy(alpha = 0.42f))
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (checked) colors.accent.copy(alpha = 0.85f)
                            else colors.muted.copy(alpha = 0.45f)
                        )
                )
                Spacer(Modifier.width(6.dp))
                it()
            }
        }
    }
}

@Composable
private fun MoreGrid(
    onLogs: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onGuide: () -> Unit
) {
    val actions = listOf(
        Triple(R.drawable.ic_clipboard_24, "日志诊断", "settings_logs_button") to onLogs,
        Triple(R.drawable.ic_help_24, "帮助", "settings_help_button") to onHelp,
        Triple(R.drawable.ic_info_24, "关于", "settings_about_button") to onAbout,
        Triple(R.drawable.ic_rider_24, "引导", "settings_guide_button") to onGuide
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowActions.forEach { (action, onClick) ->
                    val (icon, label, tag) = action
                    val colors = MotoComThemePalette.current
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.panel.copy(alpha = 0.94f))
                            .border(
                                1.dp,
                                colors.outline.copy(alpha = 0.65f),
                                RoundedCornerShape(18.dp)
                            )
                            .testTag(tag)
                            .semantics { contentDescription = label }
                            .clickable(role = Role.Button, onClick = onClick)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.accent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(icon),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = colors.accent
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            color = colors.text,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right_24),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = colors.muted
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenPreview() {
    MotoComTheme {
        MotoComSettingsScreen(
            state = SettingsScreenUiState(
                nickname = "夜航 K",
                nicknameFeedback = "",
                audioSource = "蓝牙耳机 · M200 Pro",
                productState = "待机",
                optionalPermissionNotice = null,
                showOptionalPermissionCta = false,
                version = "v1.2.0"
            ),
            onBack = {},
            onNicknameChanged = {},
            onSaveNickname = {},
            onOptionalPermission = {},
            onLogs = {},
            onAbout = {},
            onHelp = {}
        )
    }
}
