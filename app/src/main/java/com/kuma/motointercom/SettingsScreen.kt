package com.kuma.motointercom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    val voxEnabled: Boolean = true,
    val voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY,
    val voxState: VoxRuntimeState = VoxRuntimeState.IDLE,
    val preferredAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    val automaticReconnectEnabled: Boolean = true,
    val rnnoiseEnabled: Boolean = false,
    val canChangeNoiseReduction: Boolean = true
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
    onAudioSectionPositioned: (Int) -> Unit = {}
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

        SettingsSectionHeader(stringResource(R.string.settings_personal_section))
        MotoComSurfaceCard {
            val nicknameLabel = stringResource(R.string.settings_nickname_label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    nicknameLabel,
                    color = colorResource(R.color.motocom_text_primary),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = state.nickname,
                    onValueChange = onNicknameChanged,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .testTag("settings_nickname_input"),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                        textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (state.nickname.isBlank()) {
                                Text(
                                    stringResource(R.string.edit_text_hint),
                                    color = colorResource(R.color.motocom_text_muted_accessible),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.End
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                MotoComIconButton(
                    modifier = Modifier.testTag("settings_save_nickname_button"),
                    description = stringResource(R.string.settings_save_nickname),
                    icon = painterResource(R.drawable.ic_check_24),
                    onClick = onSaveNickname
                )
            }
            if (state.nicknameFeedback.isNotBlank()) {
                Text(
                    state.nicknameFeedback,
                    Modifier.padding(top = 6.dp).testTag("settings_nickname_feedback"),
                    color = colorResource(R.color.motocom_text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (BuildConfig.RNNOISE_BETA) {
            SettingsSectionHeader(stringResource(R.string.rnnoise_beta_section))
            MotoComSurfaceCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.rnnoise_beta_switch),
                    description = stringResource(R.string.rnnoise_beta_switch_description),
                    icon = R.drawable.ic_mic_24,
                    tag = "settings_rnnoise_switch",
                    checked = state.rnnoiseEnabled,
                    enabled = state.canChangeNoiseReduction,
                    onCheckedChange = onRnnoiseEnabledChanged
                )
                SettingsFact(
                    stringResource(if (state.rnnoiseEnabled) R.string.rnnoise_selected else R.string.webrtc_selected),
                    "settings_noise_mode"
                )
                SettingsFact(stringResource(R.string.rnnoise_beta_hint), "settings_noise_hint")
            }
        }

        SettingsSectionHeader(stringResource(R.string.section_vox))
        MotoComSurfaceCard {
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

        SettingsSectionHeader(
            stringResource(R.string.section_audio_output),
            Modifier.testTag("settings_audio_section").onGloballyPositioned {
                onAudioSectionPositioned(it.positionInRoot().y.roundToInt())
            }
        )
        MotoComSurfaceCard {
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

        SettingsSectionHeader(stringResource(R.string.settings_connection_device))
        MotoComSurfaceCard {
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

        SettingsSectionHeader(stringResource(R.string.settings_device_status))
        MotoComSurfaceCard {
            SettingsFact(state.deviceStatus, "settings_device_status_summary")
            state.optionalPermissionNotice?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp).testTag("settings_optional_permission_notice"),
                    color = colorResource(R.color.motocom_text_secondary),
                    style = MaterialTheme.typography.bodySmall
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

        SettingsSectionHeader(stringResource(R.string.section_advanced_settings))
        MotoComSurfaceCard {
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
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    val backDescription = stringResource(R.string.back_button_description)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MotoComIconButton(
            modifier = Modifier.testTag("settings_back_button"),
            description = backDescription,
            icon = painterResource(R.drawable.ic_arrow_back_24),
            onClick = onBack
        )
        Text(
            stringResource(R.string.nav_settings),
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .testTag("settings_title"),
            color = colorResource(R.color.motocom_text_primary),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(dimensionResource(R.dimen.motocom_icon_button_size)))
    }
}

@Composable
private fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    MotoComSectionHeader(
        text,
        modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 22.dp, bottom = 10.dp)
    )
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
        style = MaterialTheme.typography.bodySmall,
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
        shape = MaterialTheme.shapes.small
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
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
    MotoComSettingRow(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .testTag(tag),
        leadingContent = {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(21.dp),
                tint = colorResource(R.color.motocom_text_muted_accessible)
            )
        },
        headlineContent = {
            Text(
                text,
                color = colorResource(R.color.motocom_text_primary),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = { RadioMark(selected) }
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    icon: Int,
    tag: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    MotoComSettingRow(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics {
                contentDescription = description
            }
            .testTag(tag),
        leadingContent = {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(21.dp),
                tint = colorResource(R.color.motocom_text_muted_accessible)
            )
        },
        headlineContent = {
            Text(
                title,
                color = colorResource(R.color.motocom_text_primary),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    )
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
                style = MaterialTheme.typography.bodyMedium
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
            Text("低", color = colorResource(R.color.motocom_text_secondary), style = MaterialTheme.typography.labelMedium)
            Text("中", color = colorResource(R.color.motocom_text_secondary), style = MaterialTheme.typography.labelMedium)
            Text("高", color = colorResource(R.color.motocom_text_secondary), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingsVoxStateRow(state: VoxRuntimeState) {
    val description = stringResource(R.string.settings_vox_state_description, state.name)
    MotoComSettingRow(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .semantics {
                contentDescription = description
            }
            .testTag("settings_vox_state_button"),
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_info_24),
                null,
                Modifier.size(21.dp),
                tint = colorResource(R.color.motocom_text_muted_accessible)
            )
        },
        headlineContent = {
            Text(
                stringResource(R.string.settings_vox_state),
                color = colorResource(R.color.motocom_text_primary),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = {
            Text(
                state.name,
                color = colorResource(R.color.motocom_text_muted_accessible),
                style = MaterialTheme.typography.labelMedium
            )
        }
    )
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
    MotoComSettingRow(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        leadingContent = {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(21.dp),
                tint = colorResource(R.color.motocom_text_primary)
            )
        },
        headlineContent = {
            Text(
                text,
                color = colorResource(R.color.motocom_text_primary),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = {
            Icon(
                painterResource(R.drawable.ic_chevron_right_24),
                null,
                Modifier.size(18.dp),
                tint = colorResource(R.color.motocom_text_secondary)
            )
        }
    )
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
