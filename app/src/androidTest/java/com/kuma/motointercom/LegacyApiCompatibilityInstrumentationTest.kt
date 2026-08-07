package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyApiCompatibilityInstrumentationTest {
    @Test
    fun mainActivityDoesNotExposeApi33BackCallbackToLegacyVerifier() {
        val api33CallbackType = "android.window.OnBackInvokedCallback"
        val api33Fields = MainActivity::class.java.declaredFields.filter {
            it.type.name == api33CallbackType
        }

        assertTrue(
            "MainActivity exposes $api33CallbackType in ${api33Fields.map { it.name }}",
            api33Fields.isEmpty()
        )
    }
}
