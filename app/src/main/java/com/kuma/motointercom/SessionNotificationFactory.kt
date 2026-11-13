package com.kuma.motointercom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Builds every session notification from an immutable snapshot.
 *
 * The service keeps foreground promotion, cancellation and PendingIntent wiring;
 * this factory owns text, channel and priority policy so that surface decisions
 * stay free of service lifecycle state.
 */
internal class SessionNotificationFactory(private val context: Context) {

    fun statusNotification(
        state: IntercomState,
        status: String,
        interruption: AudioInterruptionState,
        running: Boolean,
        stopIntent: PendingIntent? = null
    ): Notification {
        ensureStatusChannel()
        val text = foregroundNotificationText(state, status, interruption)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            android.content.Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder(STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("摩声")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(running)
            .setContentIntent(contentIntent)
            .apply { if (stopIntent != null) addAction(0,"结束对讲",stopIntent) }
            .build()
    }

    fun incomingConfirmationNotification(
        prompt: IncomingConfirmationPrompt,
        contentIntent: PendingIntent,
        acceptIntent: PendingIntent,
        rejectIntent: PendingIntent
    ): Notification {
        ensureIncomingChannel()
        val riderName = prompt.peer.nickname.ifBlank { "附近车友" }
        return builder(INCOMING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$riderName 请求加入对讲")
            .setContentText("请在 15 秒内接受或拒绝")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    incomingConfirmationNotificationMessage(prompt.peer.deviceName)
                )
            )
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .addAction(0, "拒绝本次", rejectIntent)
            .addAction(0, "接受", acceptIntent)
            .build()
    }

    fun cloudRequestNotification(name: String,pairing: Boolean,expiresAt: Long,acceptIntent: PendingIntent,rejectIntent: PendingIntent): Notification {
        ensureIncomingChannel()
        val title=if(pairing) "$name 请求云端重新配对" else "$name 邀请云端对讲"
        val openIntent=PendingIntent.getActivity(context,0,
            android.content.Intent(context,MainActivity::class.java)
                .setData(android.net.Uri.parse("motointercom://cloud/requests"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder(INCOMING_CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title)
            .setContentText(if(pairing) "确认后交换设备公钥并建立云端配对" else "接受后才进入云端语音房间")
            .setCategory(Notification.CATEGORY_CALL).setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(false).setOnlyAlertOnce(true).setContentIntent(openIntent)
            .addAction(0,"拒绝",rejectIntent).addAction(0,"接受",acceptIntent)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTimeoutAfter((expiresAt-System.currentTimeMillis()).coerceAtLeast(1)) }
            .build()
    }

    /**
     * Reports whether the dedicated incoming-request channel can currently be
     * shown: runtime permission, app-level switch and channel importance.
     */
    fun incomingConfirmationAvailable(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(INCOMING_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    private fun builder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    private fun ensureStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(STATUS_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                "对讲状态提示",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun ensureIncomingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(INCOMING_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                INCOMING_CHANNEL_ID,
                "车友连接请求",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "陌生车友的接受与拒绝操作"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val STATUS_CHANNEL_ID = "intercom_status"
        const val INCOMING_CHANNEL_ID = "incoming_confirmation"
        const val STATUS_NOTIFICATION_ID = 2601
        const val INCOMING_NOTIFICATION_ID = 2602
        const val CLOUD_INVITE_NOTIFICATION_ID = 2603
        const val CLOUD_PAIRING_NOTIFICATION_ID = 2604
    }
}
