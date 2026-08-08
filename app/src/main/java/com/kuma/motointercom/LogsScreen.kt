package com.kuma.motointercom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape

internal data class LogsScreenUiState(val scopeText: String, val logText: String, val copyEnabled: Boolean)

@Composable
internal fun MotoComLogsScreen(
    state: LogsScreenUiState,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.widthIn(max = dimensionResource(R.dimen.motocom_content_max_width)).fillMaxWidth().padding(horizontal = dimensionResource(R.dimen.motocom_page_horizontal_padding), vertical = dimensionResource(R.dimen.motocom_page_vertical_padding)).padding(bottom = 32.dp)
    ) {
        LogsButton(stringResource(R.string.logs_back_settings), "logs_back_button", onBack)
        Column(Modifier.fillMaxWidth().padding(top = dimensionResource(R.dimen.motocom_gap)).background(colorResource(R.color.motocom_surface), RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius))).padding(dimensionResource(R.dimen.motocom_card_padding))) {
            Text(stringResource(R.string.logs_title), color = colorResource(R.color.motocom_text_primary), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(state.scopeText, Modifier.padding(top = 6.dp).testTag("logs_scope_text"), color = colorResource(R.color.motocom_text_secondary), fontSize = 14.sp)
            Text(
                state.logText,
                Modifier.fillMaxWidth().padding(top = dimensionResource(R.dimen.motocom_gap)).height(dimensionResource(R.dimen.motocom_logs_viewport_height)).background(colorResource(R.color.motocom_surface_soft), RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius))).verticalScroll(rememberScrollState()).padding(12.dp).testTag("logs_text"),
                color = colorResource(R.color.motocom_text_primary), fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
            LogsButton(stringResource(R.string.logs_copy_all), "logs_copy_button", onCopy, enabled = state.copyEnabled, primary = true)
            LogsButton(stringResource(R.string.logs_close), "logs_close_button", onClose)
        }
    }
}

@Composable private fun LogsButton(text: String, tag: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) = Button(onClick, Modifier.fillMaxWidth().padding(top = dimensionResource(R.dimen.motocom_gap)).testTag(tag), enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = if (primary) colorResource(R.color.motocom_accent_green) else colorResource(R.color.motocom_surface_soft), contentColor = colorResource(R.color.motocom_text_primary)), shape = RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius))) { Text(text, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal) }

@Preview(showBackground = true, widthDp = 360)
@Composable private fun LogsScreenPreview() { MotoComTheme { MotoComLogsScreen(LogsScreenUiState("本次界面会话日志", "暂无日志", false), {}, {}, {}) } }
