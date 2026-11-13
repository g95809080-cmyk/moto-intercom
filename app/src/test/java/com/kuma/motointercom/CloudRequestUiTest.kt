package com.kuma.motointercom

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CloudRequestUiTest {
    @Test fun promptOnlyAppearsWhileActivityVisibleAndConsentPrecedesPermission() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val prefs=CloudPreferences(activity.get())
        activity.get().getSharedPreferences("gme_cloud_preferences",0).edit().clear().commit()
        var micRequests=0; var settings=0
        val ui=CloudRequestUi(activity.get(),prefs,{true},{_,_,_,_->true},{false},{micRequests++},{settings++})
        try {
            val invite=CloudInvitePrompt(CloudInvitation("invite_test","ride_test","a","b",System.currentTimeMillis()+30_000),"车友","nonce_1234567890123")
            ui.invitation(invite); ui.connected(); ui.resume()
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing); assertFalse(prefs.enabled); assertEquals(0,micRequests)
            assertTrue(shadowOf(dialog).message.toString().contains("腾讯云 GME"))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(prefs.enabled); assertEquals(1,settings); assertEquals(1,micRequests)
            ui.stop(); assertFalse(dialog.isShowing)
        } finally { ui.stop(); activity.pause().stop().destroy() }
    }
    @Test fun staleNotificationNonceCannotAcceptTheCurrentInvitation() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val prefs=CloudPreferences(activity.get()); prefs.accept()
        var micRequests=0
        var responses=0
        val ui=CloudRequestUi(activity.get(),prefs,{true},{_,_,_,_->responses++;true},{false},{micRequests++},{})
        try {
            ui.invitation(CloudInvitePrompt(CloudInvitation("invite_new","ride_new","a","b",System.currentTimeMillis()+30_000),"车友","new_nonce_123456789"))
            ui.intent(IntercomService.cloudUiActionIntent(activity.get(),"invite","invite_new","old_nonce_123456789",true))
            ui.connected()
            ui.resume()
            assertEquals(0,micRequests)
            assertEquals(0,responses)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        } finally { ui.stop(); activity.pause().stop().destroy() }
    }
    @Test fun tappingNotificationBodyOpensPromptAndOnlyAcceptActionCarriesAcceptance() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context=activity.get()
            val accept=PendingIntent.getActivity(context,1,IntercomService.cloudUiActionIntent(context,"invite","invite_test","nonce_1234567890123",true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val reject=PendingIntent.getActivity(context,2,IntercomService.cloudUiActionIntent(context,"invite","invite_test","nonce_1234567890123",false),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n=SessionNotificationFactory(context).cloudRequestNotification("车友",false,System.currentTimeMillis()+30_000,accept,reject)
            assertNull(shadowOf(n.contentIntent).savedIntent.action)
            assertEquals(Uri.parse("motointercom://cloud/requests"),shadowOf(n.contentIntent).savedIntent.data)
            assertTrue(shadowOf(n.actions[1].actionIntent).savedIntent.getBooleanExtra(IntercomService.EXTRA_CLOUD_ACCEPTED,false))
            assertFalse(shadowOf(n.actions[0].actionIntent).savedIntent.getBooleanExtra(IntercomService.EXTRA_CLOUD_ACCEPTED,true))
            assertNotEquals(n.contentIntent,accept)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun pairingNotificationActionWaitsForTheWholeServiceSnapshot() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val prefs=CloudPreferences(activity.get()); prefs.accept()
        val responses=mutableListOf<Boolean>()
        val ui=CloudRequestUi(activity.get(),prefs,{true},{_,_,_,accept->responses.add(accept);true},{true},{},{})
        try {
            ui.intent(IntercomService.cloudUiActionIntent(activity.get(),"pair","pair_test","nonce_1234567890123",false))
            ui.resume(); ui.invitation(null)
            ui.pairing(CloudPairingPrompt("pair_test","a","车友","nonce_1234567890123",System.currentTimeMillis()+30_000))
            ui.connected()
            assertEquals(listOf(false),responses)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog()?.isShowing != true)
        } finally { ui.stop(); activity.pause().stop().destroy() }
    }
}
