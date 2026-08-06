package com.kuma.motointercom

import android.app.Activity
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.layout.WindowMetricsCalculator

internal enum class MainWindowWidthClass {
    Compact,
    Medium,
    Expanded
}

internal data class MainWindowInfo(
    val widthDp: Int,
    val heightDp: Int,
    val widthClass: MainWindowWidthClass
)

internal fun mainWindowInfo(activity: Activity): MainWindowInfo {
    val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
    val widthDp = metrics.widthDp.toInt().coerceAtLeast(0)
    val heightDp = metrics.heightDp.toInt().coerceAtLeast(0)
    return MainWindowInfo(
        widthDp = widthDp,
        heightDp = heightDp,
        widthClass = mainWindowWidthClass(widthDp, heightDp)
    )
}

internal fun mainWindowWidthClass(widthDp: Int, heightDp: Int): MainWindowWidthClass {
    val windowSizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)
    return when {
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
        ) -> MainWindowWidthClass.Expanded
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
        ) -> MainWindowWidthClass.Medium
        else -> MainWindowWidthClass.Compact
    }
}
