package com.kuma.motointercom

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAudioPlatformRobolectricTest {
    @Test
    @Config(sdk = [28, 35])
    fun abandonedFocusCallbacksCannotAuthorizeOrInterruptTheNextMediaRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadow = shadowOf(manager)
        val focus = AndroidIntercomAudioFocus(context)
        val events = mutableListOf<Int>()
        focus.setListener(events::add)
        try {
            focus.request()
            val first = requireNotNull(shadow.lastAudioFocusRequest?.audioFocusRequest)
            val firstListener = first.javaClass.getMethod("getOnAudioFocusChangeListener")
                .invoke(first) as AudioManager.OnAudioFocusChangeListener
            Handler(Looper.getMainLooper()).post {
                firstListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                firstListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            }
            focus.abandon()
            focus.request()
            val second = requireNotNull(shadow.lastAudioFocusRequest?.audioFocusRequest)
            val secondListener = second.javaClass.getMethod("getOnAudioFocusChangeListener")
                .invoke(second) as AudioManager.OnAudioFocusChangeListener
            assertNotSame(firstListener, secondListener)

            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<Int>(), events)

            secondListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            secondListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(
                listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN), events
            )
            focus.abandon()
            secondListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(2, events.size)
        } finally {
            focus.close()
        }
    }

    @Test
    fun focusUsesTheSameRequestForRequestAndAbandonWithoutChangingMusicVolume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudioManager = shadowOf(audioManager)
        val musicVolumeBefore = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val focus = AndroidIntercomAudioFocus(context)

        try {
            assertEquals(AudioFocusResult.GRANTED, focus.request())
            focus.abandon()

            val requested = shadowAudioManager.lastAudioFocusRequest?.audioFocusRequest
            val abandoned = shadowAudioManager.lastAbandonedAudioFocusRequest
            assertNotNull("Audio focus request was not recorded", requested)
            assertSame("request and abandon must use the same AudioFocusRequest", requested, abandoned)
            assertEquals(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                requested!!.focusGain
            )
            assertEquals(
                android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION,
                requested.audioAttributes.usage
            )
            assertEquals(
                android.media.AudioAttributes.CONTENT_TYPE_SPEECH,
                requested.audioAttributes.contentType
            )
            assertEquals(musicVolumeBefore, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            focus.close()
        }
    }

    @Test
    fun modernTelephonyCallbackPublishesRingingOffhookAndIdle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val shadowTelephonyManager = shadowOf(telephonyManager)
        val phone = AndroidIntercomPhoneState(context)
        val states = mutableListOf<PhoneCallState>()

        phone.start(states::add)
        try {
            shadowTelephonyManager.setCallState(TelephonyManager.CALL_STATE_RINGING)
            shadowTelephonyManager.setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
            shadowTelephonyManager.setCallState(TelephonyManager.CALL_STATE_IDLE)
        } finally {
            phone.close()
        }

        assertEquals(
            listOf(PhoneCallState.RINGING, PhoneCallState.OFFHOOK, PhoneCallState.IDLE),
            states
        )
    }

    @Test
    fun missingPhonePermissionLeavesPhoneStateUnregistered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val phone = AndroidIntercomPhoneState(context)
        val states = mutableListOf<PhoneCallState>()

        phone.start(states::add)
        try {
            assertEquals(PhoneCallState.IDLE, phone.currentState())
            assertEquals(emptyList<PhoneCallState>(), states)
        } finally {
            phone.close()
        }
    }

    @Test
    @Config(sdk = [29])
    fun legacyPhoneStateListenerPublishesRingingOffhookAndIdle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val shadowTelephonyManager = shadowOf(telephonyManager)
        val phone = AndroidIntercomPhoneState(context)
        val states = mutableListOf<PhoneCallState>()

        phone.start(states::add)
        try {
            shadowTelephonyManager.setCallState(TelephonyManager.CALL_STATE_RINGING)
            shadowTelephonyManager.setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
            shadowTelephonyManager.setCallState(TelephonyManager.CALL_STATE_IDLE)
        } finally {
            phone.close()
        }

        assertEquals(
            listOf(PhoneCallState.RINGING, PhoneCallState.OFFHOOK, PhoneCallState.IDLE),
            states
        )
    }
}
