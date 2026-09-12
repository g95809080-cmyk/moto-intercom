package com.kuma.motointercom

import android.content.Context

internal enum class OnboardingPhase { INTRO, TOUR, COMPLETE }

/** Tutorial progress is independent of runtime sessions and never implies a connection. */
internal class OnboardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("motocom_onboarding", Context.MODE_PRIVATE)

    fun load(): OnboardingPhase = if (preferences.getBoolean("hasShownGuide", false)) {
        OnboardingPhase.COMPLETE
    } else {
        OnboardingPhase.entries.firstOrNull { it.name == preferences.getString("phase", null) }
            ?.takeUnless { it == OnboardingPhase.COMPLETE } ?: OnboardingPhase.INTRO
    }

    fun save(phase: OnboardingPhase) {
        preferences.edit()
            .putString("phase", phase.name)
            .putBoolean("hasShownGuide", phase == OnboardingPhase.COMPLETE)
            .apply()
    }

    var permissionRequested: Boolean
        get() = preferences.getBoolean("permissionRequested", false)
        set(value) { preferences.edit().putBoolean("permissionRequested", value).apply() }
}

internal enum class GuideTarget { PERMISSION, PERMISSION_SETTINGS, WIFI, START, DISCOVER, SCAN }

internal data class GuideFacts(
    val state: IntercomState,
    val route: MainRoute,
    val canStart: Boolean,
    val permissionRequested: Boolean,
    val wifiUnavailable: Boolean,
    val incomingConfirmation: Boolean,
    val audioReady: Boolean
) {
    val isBusy: Boolean get() = incomingConfirmation ||
        (state !is IntercomState.Offline && state !is IntercomState.Discovering)

    fun target(): GuideTarget? {
        if (isBusy) return null
        if (state is IntercomState.Offline) {
            if (route != MainRoute.HOME) return null
            return when {
                !canStart && permissionRequested -> GuideTarget.PERMISSION_SETTINGS
                !canStart -> GuideTarget.PERMISSION
                wifiUnavailable -> GuideTarget.WIFI
                else -> GuideTarget.START
            }
        }
        return when (route) {
            MainRoute.HOME -> GuideTarget.DISCOVER
            MainRoute.DISCOVER -> if (wifiUnavailable) null else GuideTarget.SCAN
            else -> null
        }
    }
}
