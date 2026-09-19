package com.kuma.motointercom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt

internal data class SettingsScreenUiState(
    val nickname: String,
    val nicknameFeedback: String,
    val audioSource: String,
    val productState: String,
    val attemptFacts: String,
    val discoveryCandidates: String,
    val deviceStatus: String,
    val optionalPermissionNotice: String?,
    val showOptionalPermissionCta: Boolean,
    val version: String,
    val backgroundAllowed: Boolean = false,
    val voxEnabled: Boolean = true,
    val voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY,
    val voxState: VoxRuntimeState = VoxRuntimeState.IDLE,
    val preferredAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    val automaticReconnectEnabled: Boolean = true
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
    onAutomaticReconnectChanged: (Boolean) -> Unit = {},
    onAudioSectionPositioned: (Int) -> Unit = {},
    onBackgroundSettings: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .widthIn(max = dimensionResource(R.dimen.motocom_content_max_width))
            .fillMaxWidth()
            .padding(
                horizontal = dimensionResource(R.dimen.motocom_page_horizontal_padding),
                vertical = dimensionResource(R.dimen.motocom_page_vertical_padding)
            )
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.Top
    ) {
        SettingsHeader(onBack)

        SettingsSectionLabel(stringResource(R.string.settings_personal_section))
        SettingsPanel {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_nickname_label),
                    color = colorResource(R.color.motocom_text_primary),
                    fontSize = 15.sp
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = state.nickname,
                    onValueChange = onNicknameChanged,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .testTag("settings_nickname_input"),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = 15.sp,
                        textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (state.nickname.isBlank()) {
                                Text(
                                    stringResource(R.string.edit_text_hint),
                                    color = colorResource(R.color.motocom_text_muted),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.End
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                SettingsIconButton(
                    tag = "settings_save_nickname_button",
                    description = stringResource(R.string.settings_save_nickname),
                    icon = R.drawable.ic_check_24,
                    onClick = onSaveNickname
                )
            }
            if (state.nicknameFeedback.isNotBlank()) {
                Text(
                    state.nicknameFeedback,
                    Modifier.padding(top = 6.dp).testTag("settings_nickname_feedback"),
                    color = colorResource(R.color.motocom_text_secondary),
                    fontSize = 13.sp
                )
            }
        }

        SettingsSectionLabel(stringResource(R.string.section_vox))
        SettingsPanel {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_vox_switch),
                description = stringResource(R.string.settings_vox_switch_description),
                icon = R.drawable.ic_mic_24,
                tag = "settings_vox_button",
                checked = state.voxEnabled,
                onCheckedChange = onVoxEnabledChanged
            )
            SettingsVoxSlider(
                value = state.voxSensitivity,
                enabled = state.voxEnabled,
                onValueChange = onVoxSensitivityChanged
            )
            SettingsVoxStateRow(state.voxState)
        }

        SettingsSectionLabel(
            stringResource(R.string.section_audio_output),
            Modifier.testTag("settings_audio_section").onGloballyPositioned {
                onAudioSectionPositioned(it.positionInRoot().y.roundToInt())
            }
        )
        SettingsPanel {
            SettingsFact(state.audioSource, "settings_audio_source")
            SettingsAudioRouteRow(
                text = stringResource(R.string.settings_audio_bluetooth),
                tag = "settings_audio_route_button",
                description = stringResource(R.string.audio_route_description),
                selected = state.preferredAudioRoute == AudioRouteSelection.BLUETOOTH,
                onClick = { onAudioRouteSelected(AudioRouteSelection.BLUETOOTH) },
                icon = R.drawable.ic_headset_24
            )
            SettingsAudioRouteRow(
                text = stringResource(R.string.settings_audio_earpiece),
                tag = "settings_audio_earpiece_button",
                description = stringResource(R.string.audio_earpiece_description),
                selected = state.preferredAudioRoute == AudioRouteSelection.EARPIECE,
                onClick = { onAudioRouteSelected(AudioRouteSelection.EARPIECE) },
                icon = R.drawable.ic_audio_24
            )
            SettingsAudioRouteRow(
                text = stringResource(R.string.settings_audio_speaker),
                tag = "settings_audio_speaker_button",
                description = stringResource(R.string.audio_speaker_description),
                selected = state.preferredAudioRoute == AudioRouteSelection.SPEAKER,
                onClick = { onAudioRouteSelected(AudioRouteSelection.SPEAKER) },
                icon = R.drawable.ic_audio_24
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_connection_device))
        SettingsPanel {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_reconnect),
                description = stringResource(R.string.settings_auto_reconnect_description),
                icon = R.drawable.ic_refresh_24,
                tag = "settings_reconnect_button",
                checked = state.automaticReconnectEnabled,
                onCheckedChange = onAutomaticReconnectChanged
            )
            SettingsFact(
                stringResource(R.string.settings_transport_policy),
                "settings_transport_policy"
            )
            SettingsFact(state.attemptFacts, "settings_attempt_facts")
            SettingsFact(state.productState, "settings_product_state")
            SettingsFact(state.discoveryCandidates, "settings_discovery_candidates")
        }

        SettingsSectionLabel(stringResource(R.string.settings_device_status))
        SettingsPanel {
            SettingsFact(state.deviceStatus, "settings_device_status_summary")
            state.optionalPermissionNotice?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp).testTag("settings_optional_permission_notice"),
                    color = colorResource(R.color.motocom_text_secondary),
                    fontSize = 13.sp
                )
            }
            if (state.showOptionalPermissionCta) {
                SettingsSecondaryButton(
                    stringResource(R.string.settings_optional_permission_grant),
                    "settings_optional_permission_button",
                    onOptionalPermission
                )
            }
        }

        SettingsSectionLabel("后台运行")
        SettingsPanel {
            SettingsFact(
                if (state.backgroundAllowed) "系统电池优化：已豁免" else "系统电池优化：未豁免",
                "settings_background_status"
            )
            SettingsFact("为保持锁屏通话，建议允许后台运行。厂商应用省电和启动管理需在手机设置中确认。", "settings_background_hint")
            SettingsSecondaryButton("设置后台运行", "settings_background_button", onBackgroundSettings)
        }

        SettingsSectionLabel(stringResource(R.string.section_advanced_settings))
        SettingsPanel {
            val helpDescription = stringResource(R.string.help_description)
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsAdvancedAction(
                    icon = R.drawable.ic_clipboard_24,
                    text = stringResource(R.string.settings_logs),
                    tag = "settings_logs_button",
                    description = stringResource(R.string.settings_logs),
                    onClick = onLogs
                )
                SettingsAdvancedAction(
                    icon = R.drawable.ic_help_24,
                    text = stringResource(R.string.settings_help),
                    tag = "settings_help_button",
                    description = helpDescription,
                    onClick = onHelp
                )
                SettingsAdvancedAction(
                    icon = R.drawable.ic_info_24,
                    text = stringResource(R.string.settings_about),
                    tag = "settings_about_button",
                    description = stringResource(R.string.settings_about),
                    onClick = onAbout
                )
            }
            Text(
                state.version,
                Modifier.fillMaxWidth().padding(top = 6.dp).testTag("settings_version_text"),
                color = colorResource(R.color.motocom_text_secondary),
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    val backDescription = stringResource(R.string.back_button_description)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsIconButton(
            tag = "settings_back_button",
            description = backDescription,
            icon = R.drawable.ic_arrow_back_24,
            onClick = onBack
        )
        Text(
            stringResource(R.string.nav_settings),
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .testTag("settings_title"),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(dimensionResource(R.dimen.motocom_icon_button_size)))
    }
}

@Composable
private fun SettingsIconButton(tag: String, description: String, icon: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), description, Modifier.size(24.dp), tint = colorResource(R.color.motocom_text_primary))
    }
}

@Composable
private fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 2.dp, top = 22.dp, bottom = 10.dp),
        color = colorResource(R.color.motocom_text_primary),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colorResource(R.color.motocom_border), shape)
            .clip(shape)
            .background(colorResource(R.color.motocom_surface))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsFact(
    text: String,
    tag: String,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .testTag(tag),
        color = colorResource(R.color.motocom_text_secondary),
        fontSize = 13.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SettingsSecondaryButton(text: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .defaultMinSize(minHeight = 52.dp)
            .testTag(tag),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.motocom_surface_soft),
            contentColor = colorResource(R.color.motocom_text_primary)
        ),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text) }
}

@Composable
private fun SettingsAudioRouteRow(
    text: String,
    tag: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(icon),
            null,
            Modifier.size(21.dp),
            tint = colorResource(R.color.motocom_text_muted_accessible)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            Modifier.weight(1f),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 14.sp
        )
        RadioMark(selected)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    icon: Int,
    tag: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics {
                contentDescription = description
            }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(icon),
            null,
            Modifier.size(21.dp),
            tint = colorResource(R.color.motocom_text_muted_accessible)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            Modifier.weight(1f),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 14.sp
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsVoxSlider(
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    val normalized = value.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
    val description = stringResource(R.string.settings_vox_sensitivity_description, normalized)
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_tune_24),
                null,
                Modifier.size(21.dp),
                tint = colorResource(R.color.motocom_text_muted_accessible)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.settings_vox_sensitivity, normalized),
                color = colorResource(R.color.motocom_text_primary),
                fontSize = 14.sp
            )
        }
        Slider(
            value = normalized.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 31.dp)
                .semantics {
                    contentDescription = description
                }
                .testTag("settings_vox_sensitivity_button"),
            enabled = enabled,
            valueRange = MIN_VOX_SENSITIVITY.toFloat()..MAX_VOX_SENSITIVITY.toFloat(),
            steps = 9
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("低", color = colorResource(R.color.motocom_text_secondary), fontSize = 11.sp)
            Text("中", color = colorResource(R.color.motocom_text_secondary), fontSize = 11.sp)
            Text("高", color = colorResource(R.color.motocom_text_secondary), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsVoxStateRow(state: VoxRuntimeState) {
    val description = stringResource(R.string.settings_vox_state_description, state.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .semantics {
                contentDescription = description
            }
            .testTag("settings_vox_state_button"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_info_24),
            null,
            Modifier.size(21.dp),
            tint = colorResource(R.color.motocom_text_muted_accessible)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.settings_vox_state),
            Modifier.weight(1f),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 14.sp
        )
        Text(
            state.name,
            color = colorResource(R.color.motocom_text_muted_accessible),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colorResource(R.color.motocom_accent_green) else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (selected) colorResource(R.color.motocom_accent_green) else colorResource(R.color.motocom_text_muted),
                shape = CircleShape
            )
            .then(
                if (!selected) Modifier
                    .background(Color.Transparent)
                    .testTag("settings_radio_unselected") else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Box(Modifier.size(10.dp).background(colorResource(R.color.motocom_accent_green_dark), CircleShape))
    }
}

@Composable
private fun SettingsAdvancedAction(icon: Int, text: String, tag: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = colorResource(R.color.motocom_text_primary))
        Text(text, Modifier.weight(1f).padding(start = 12.dp), color = colorResource(R.color.motocom_text_primary), fontSize = 14.sp)
        Icon(painterResource(R.drawable.ic_chevron_right_24), null, Modifier.size(18.dp), tint = colorResource(R.color.motocom_text_secondary))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenPreview() {
    MotoComTheme {
        MotoComSettingsScreen(
            SettingsScreenUiState(
                nickname = "骑行者 A",
                nicknameFeedback = "",
                audioSource = "当前音频源：未连接",
                productState = "当前状态：离线",
                attemptFacts = "计划通道：无 · 当前通道：无",
                discoveryCandidates = "可用发现候选：无",
                deviceStatus = "蓝牙：未连接",
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
