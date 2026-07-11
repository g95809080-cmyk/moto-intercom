package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedLogBufferTest {
    @Test
    fun keepsNewestThreeEntries() {
        val log = BoundedLogBuffer(3)
        listOf("1", "2", "3", "4").forEach(log::append)
        assertEquals(listOf("2", "3", "4"), log.snapshot())
    }
}
