package com.kuma.motointercom

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
internal fun MotoComTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = colorResource(R.color.motocom_accent_green),
        onPrimary = colorResource(R.color.motocom_text_primary),
        primaryContainer = colorResource(R.color.motocom_accent_green_soft),
        onPrimaryContainer = colorResource(R.color.motocom_text_primary),
        background = colorResource(R.color.motocom_background),
        onBackground = colorResource(R.color.motocom_text_primary),
        surface = colorResource(R.color.motocom_surface),
        onSurface = colorResource(R.color.motocom_text_primary),
        surfaceVariant = colorResource(R.color.motocom_surface_soft),
        onSurfaceVariant = colorResource(R.color.motocom_text_secondary),
        outline = colorResource(R.color.motocom_border),
        outlineVariant = colorResource(R.color.motocom_border),
        scrim = colorResource(R.color.motocom_scrim),
        error = Color(0xFFB3261E),
        onError = Color.White
    )
    val typography = Typography(
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius)),
            small = RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius)),
            medium = RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius)),
            large = RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius)),
            extraLarge = RoundedCornerShape(dimensionResource(R.dimen.motocom_card_radius))
        ),
        content = content
    )
}
