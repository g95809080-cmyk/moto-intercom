package com.kuma.motointercom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNsdManager

@Implements(NsdManager::class)
class NicknameNsdShadow : ShadowNsdManager() {
    var registered: NsdServiceInfo? = null
    @Implementation fun registerService(info: NsdServiceInfo, protocol: Int,
        listener: NsdManager.RegistrationListener) { registered = info }
    @Implementation fun discoverServices(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) = Unit
    @Implementation fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) = Unit
    @Implementation fun unregisterService(listener: NsdManager.RegistrationListener) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], shadows = [NicknameNsdShadow::class, RecordingP2pInputShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DiscoveryNicknameRegressionTest {
    @Test fun fullTxtAttributeLimitKeepsWholeUnicodeCodePoints() {
        for (key in listOf("name", "nickname")) {
            val budget = 255 - key.toByteArray(Charsets.UTF_8).size - 1
            assertEquals("a".repeat(budget), boundedDiscoveryTxtValue(key, "a".repeat(budget)))
            assertEquals("a".repeat(budget), boundedDiscoveryTxtValue(key, "a".repeat(budget + 1)))
            for (text in listOf("骑".repeat(64), "😀".repeat(64), "a骑😀".repeat(64))) {
                val encoded = boundedDiscoveryTxtValue(key, text)
                assertTrue(text.startsWith(encoded))
                assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= budget)
                assertEquals(encoded, String(encoded.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            }
        }
    }

    @Test fun acceptedEmojiNicknameCanBeAdvertisedViaLan() {
        val nickname = "😀".repeat(64)
        assertTrue(validateNickname(nickname) is NicknameValidation.Valid)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessions = SessionGeneration()
        val token = sessions.start()
        val failures = mutableListOf<Throwable>()
        val lan = LanDiscoveryCoordinator(context, token, sessions::isCurrent,
            "90000000-0000-4000-8000-000000000001",
            RuntimeSessionId("90000000-0000-4000-8000-000000000002"),
            nickname, "fixture", 2, {}, { _, lease -> lease.close() }, {}, failures::add)
        try {
            lan.javaClass.getDeclaredMethod("startNsdDiscovery").apply { isAccessible = true }.invoke(lan)
            val nsd: NicknameNsdShadow = Shadow.extract(context.getSystemService(Context.NSD_SERVICE))
            val info = checkNotNull(nsd.registered)
            val encoded = checkNotNull(info.attributes["name"])
            assertTrue("The entire name= attribute must fit one TXT entry", 5 + encoded.size <= 255)
            assertTrue(nickname.startsWith(String(encoded, Charsets.UTF_8)))
            assertEquals("90000000-0000-4000-8000-000000000001", String(info.attributes.getValue("id"), Charsets.UTF_8))
            assertTrue("LAN advertisement failed: $failures", failures.isEmpty())
        } finally { lan.close() }
    }

    @Test fun acceptedEmojiNicknameCanBeAdvertisedViaP2p() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tunnel = WifiDirectTunnel(context, onControlChannelReady = { _, lease -> lease.close() },
            localDeviceId = "90000000-0000-4000-8000-000000000001", localNickname = "😀".repeat(64),
            localDeviceName = "fixture", sessionId = RuntimeSessionId("90000000-0000-4000-8000-000000000002"))
        fun field(name: String) = tunnel.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String) = tunnel.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(tunnel)
        try {
            field("running").set(tunnel, true)
            call("initP2p")
            (field("setupRecoveryGate").get(tunnel) as WifiDirectSetupRecoveryGate).updateP2pEnabled(true)
            call("setupServiceDiscovery")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(field("serviceDiscoveryReady").get(tunnel) as Boolean)
        } finally { tunnel.close(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
