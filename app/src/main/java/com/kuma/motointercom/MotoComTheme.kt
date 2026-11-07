package com.kuma.motointercom

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MotoComTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = colorResource(R.color.motocom_accent_green_dark),
        onPrimary = Color.White,
        primaryContainer = colorResource(R.color.motocom_accent_green_soft),
        onPrimaryContainer = colorResource(R.color.motocom_text_primary),
        secondary = colorResource(R.color.motocom_accent_green_dark),
        onSecondary = Color.White,
        secondaryContainer = colorResource(R.color.motocom_surface_soft),
        onSecondaryContainer = colorResource(R.color.motocom_text_primary),
        tertiary = colorResource(R.color.motocom_accent_green_dark),
        onTertiary = Color.White,
        tertiaryContainer = colorResource(R.color.motocom_accent_green_soft),
        onTertiaryContainer = colorResource(R.color.motocom_text_primary),
        surfaceContainer = colorResource(R.color.motocom_surface),
        surfaceContainerHigh = colorResource(R.color.motocom_surface_soft),
        surfaceContainerHighest = colorResource(R.color.motocom_surface_soft),
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
        headlineLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 38.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 30.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 26.sp
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 24.sp
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 22.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 18.sp
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(26.dp)
        ),
        content = content
    )
}
