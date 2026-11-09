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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class LogsScreenUiState(
    val scopeText: String,
    val logText: String,
    val copyEnabled: Boolean,
    val exportEnabled: Boolean = false,
    val exporting: Boolean = false,
    val errorText: String? = null
)

@Composable
internal fun MotoComLogsScreen(
    state: LogsScreenUiState,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onExport: () -> Unit = {}
) {
    val colors = MotoComThemePalette.current
    val logScroll = rememberScrollState()
    LaunchedEffect(logScroll) {
        var previousMax = 0
        snapshotFlow { logScroll.value to logScroll.maxValue }.collect { (offset, max) ->
            if (max != previousMax) {
                val following = offset >= previousMax
                previousMax = max
                if (following) logScroll.scrollTo(max)
            }
        }
    }
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
                    horizontal = dimensionResource(R.dimen.motocom_page_horizontal_padding),
                    vertical = dimensionResource(R.dimen.motocom_page_vertical_padding)
                )
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MotoComTopBar()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("logs_back_button")
                        .clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.panelRaised)
                            .border(1.dp, colors.outline, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_24),
                            contentDescription = stringResource(R.string.logs_back_settings),
                            modifier = Modifier.size(18.dp),
                            tint = colors.text
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.logs_title),
                    color = colors.text,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 28.sp,
                        lineHeight = 34.sp
                    )
                )
            }
            MotoComPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                containerColor = colors.panel.copy(alpha = 0.94f)
            ) {
                Text(
                    text = state.scopeText,
                    modifier = Modifier.testTag("logs_scope_text"),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
            Text(
                text = state.logText,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.motocom_logs_viewport_height))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MotoComThemeMode.NIGHTRIDE.colors.backgroundDeep)
                    .border(
                        1.dp,
                        MotoComThemeMode.NIGHTRIDE.colors.outline,
                        RoundedCornerShape(20.dp)
                    )
                    .verticalScroll(logScroll)
                    .padding(12.dp)
                    .testTag("logs_text"),
                color = MotoComThemeMode.NIGHTRIDE.colors.muted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace
            )
            state.errorText?.let {
                Text(
                    text = it,
                    modifier = Modifier.testTag("logs_error_text"),
                    color = colors.amber,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            LogsButton(
                text = stringResource(
                    if (state.exporting) R.string.logs_export_preparing else R.string.logs_export_all
                ),
                tag = "logs_export_button",
                onClick = onExport,
                enabled = state.exportEnabled && !state.exporting,
                primary = true
            )
            LogsButton(
                text = stringResource(R.string.logs_copy_all),
                tag = "logs_copy_button",
                onClick = onCopy,
                enabled = state.copyEnabled
            )
            LogsButton(
                text = stringResource(R.string.logs_close),
                tag = "logs_close_button",
                onClick = onClose
            )
        }
    }
}

@Composable
private fun LogsButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val colors = MotoComThemePalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    !enabled -> colors.panelRaised.copy(alpha = 0.55f)
                    primary -> colors.accent
                    else -> colors.panel.copy(alpha = 0.92f)
                }
            )
            .border(
                1.dp,
                if (primary && enabled) Color.Transparent else colors.outline,
                RoundedCornerShape(16.dp)
            )
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> colors.muted
                primary -> colors.accentInk
                else -> colors.text
            },
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun LogsScreenPreview() {
    MotoComTheme {
        MotoComLogsScreen(
            state = LogsScreenUiState(LOGS_SCOPE_TEXT, "暂无日志", false),
            onBack = {},
            onCopy = {},
            onClose = {}
        )
    }
}
