package com.kuma.motointercom

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

private const val MotoComColorAnimationMillis = 180
private const val MotoComPressedScale = 0.94f

@Composable
internal fun Modifier.motoComPressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = MotoComPressedScale
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "motoComPressedScale"
    )
    return this.then(Modifier.scale(scale))
}

@Composable
internal fun MotoComSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = MaterialTheme.colorScheme.outline,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable () -> Unit
) {
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(MotoComColorAnimationMillis),
        label = "surfaceContainerColor"
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor ?: Color.Transparent,
        animationSpec = tween(MotoComColorAnimationMillis),
        label = "surfaceBorderColor"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(animatedContainerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, animatedBorderColor, shape)
                } else {
                    Modifier
                }
            )
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
    val interactionSource = remember { MutableInteractionSource() }
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor ?: Color.Transparent,
        animationSpec = tween(MotoComColorAnimationMillis),
        label = "iconButtonContainerColor"
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor ?: Color.Transparent,
        animationSpec = tween(MotoComColorAnimationMillis),
        label = "iconButtonBorderColor"
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(MotoComColorAnimationMillis),
        label = "iconButtonContentColor"
    )
    Row(
        modifier = modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .motoComPressScale(interactionSource, enabled)
            .clip(CircleShape)
            .then(if (containerColor != null) Modifier.background(animatedContainerColor) else Modifier)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, animatedBorderColor, CircleShape)
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = description
                selected?.let { this.selected = it }
                if (!enabled) disabled()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = animatedContentColor
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
