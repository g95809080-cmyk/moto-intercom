package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveWindowTest {
    @Test
    fun requiredWindowMatrixUsesCurrentWindowWidthClasses() {
        val expected = mapOf(
            360 to MainWindowWidthClass.Compact,
            412 to MainWindowWidthClass.Compact,
            915 to MainWindowWidthClass.Expanded,
            700 to MainWindowWidthClass.Medium,
            840 to MainWindowWidthClass.Expanded,
            1200 to MainWindowWidthClass.Expanded
        )

        expected.forEach { (widthDp, expectedClass) ->
            assertEquals(
                "width=${widthDp}dp",
                expectedClass,
                mainWindowWidthClass(widthDp, heightDp = 900)
            )
        }
    }

    @Test
    fun widthClassUsesDpThresholdsAndIgnoresHeight() {
        assertEquals(MainWindowWidthClass.Compact, mainWindowWidthClass(360, 640))
        assertEquals(MainWindowWidthClass.Compact, mainWindowWidthClass(360, 1920))
        assertEquals(MainWindowWidthClass.Expanded, mainWindowWidthClass(1200, 840))
        assertEquals(MainWindowWidthClass.Expanded, mainWindowWidthClass(1200, 2400))
    }
}
