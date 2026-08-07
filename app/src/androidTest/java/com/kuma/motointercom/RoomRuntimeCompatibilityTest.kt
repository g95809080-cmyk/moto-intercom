package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRuntimeCompatibilityTest {
    @Test
    fun liveDataTypeRequiredByRoomIsPackagedForLegacyRuntimeVerification() {
        assertNotNull(Class.forName("androidx.lifecycle.LiveData"))
    }
}
