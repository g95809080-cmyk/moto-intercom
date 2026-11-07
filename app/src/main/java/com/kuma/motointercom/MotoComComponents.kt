package com.kuma.motointercom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun MotoComSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = MaterialTheme.colorScheme.outline,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .padding(contentPadding)
    ) {
        content()
    }
}

@Composable
internal fun MotoComIconButton(
    icon: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    containerColor: Color? = MaterialTheme.colorScheme.surface,
    borderColor: Color? = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 23.dp
) {
    Row(
        modifier = modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .clip(CircleShape)
            .then(if (containerColor != null) Modifier.background(containerColor) else Modifier)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape) else Modifier)
            .semantics {
                contentDescription = description
                selected?.let { this.selected = it }
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor
        )
    }
}

@Composable
internal fun MotoComSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
internal fun MotoComSettingRow(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            headlineContent()
            if (supportingContent != null) {
                Spacer(Modifier.height(2.dp))
                supportingContent()
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(10.dp))
            trailingContent()
        }
    }
}
