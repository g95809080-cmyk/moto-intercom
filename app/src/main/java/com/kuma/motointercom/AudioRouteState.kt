package com.kuma.motointercom

internal enum class AudioRouteSelection {
    BLUETOOTH,
    EARPIECE,
    SPEAKER
}

internal data class VersionedAudioRouteSelection(
    val revision: Long,
    val selection: AudioRouteSelection
)

internal fun audioRouteSelectionFromPersisted(value: String?): AudioRouteSelection =
    AudioRouteSelection.entries.firstOrNull { it.name == value }
        ?: AudioRouteSelection.BLUETOOTH
