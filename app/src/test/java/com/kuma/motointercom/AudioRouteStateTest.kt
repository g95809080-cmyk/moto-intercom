package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteStateTest {
    @Test
    fun persistedValuesRoundTripAndUnknownValuesFallBackToBluetooth() {
        AudioRouteSelection.entries.forEach { selection ->
            assertEquals(selection, audioRouteSelectionFromPersisted(selection.name))
        }

        assertEquals(AudioRouteSelection.BLUETOOTH, audioRouteSelectionFromPersisted(null))
        assertEquals(AudioRouteSelection.BLUETOOTH, audioRouteSelectionFromPersisted("unknown"))
    }
}
