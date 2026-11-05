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
        running: Boolean
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
    }
}
