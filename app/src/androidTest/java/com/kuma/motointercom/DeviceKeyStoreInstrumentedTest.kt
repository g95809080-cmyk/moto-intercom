package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

// Run on an Android device/emulator. JVM crypto tests cannot prove Keystore isolation.
@RunWith(AndroidJUnit4::class)
class DeviceKeyStoreInstrumentedTest {
    @Test fun p256PrivateKeyIsNonExportableAndStableAcrossAdapterRecreation() {
        val alias="motocom_gme_test_"+UUID.randomUUID()
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val first=DeviceKeyStore(alias); val public=first.publicKeyBase64()
            assertEquals(public,DeviceKeyStore(alias).publicKeyBase64())
            assertEquals(256,CloudCrypto.publicKey(public).params.curve.field.fieldSize)
            assertNull((store.getEntry(alias,null) as KeyStore.PrivateKeyEntry).privateKey.encoded)
            val bytes="keystore-instrumentation".toByteArray()
            assertTrue(CloudCrypto.verify(public,bytes,first.sign(bytes)))
            assertFalse(CloudCrypto.verify(public,"changed".toByteArray(),first.sign(bytes)))
        } finally { if(store.containsAlias(alias)) store.deleteEntry(alias) }
    }
}
