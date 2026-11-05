package com.kuma.motointercom

import android.app.Notification
import android.app.NotificationManager

/**
 * Platform and session capabilities the surface needs from its owner.
 *
 * Keeping this narrow is the point: the surface decides *when* a request is
 * shown, replayed or withdrawn, while the host decides whether that request is
 * still authoritative and performs every platform call.
 */
internal interface IncomingConfirmationHost {
    fun isPromptCurrent(prompt: IncomingConfirmationPrompt): Boolean
    fun isAppForeground(): Boolean

    /** Returns false when no foreground surface can currently receive the prompt. */
    fun showInApp(prompt: IncomingConfirmationPrompt): Boolean

    fun cancelInApp(nonce: String)
    fun notificationAvailable(): Boolean
    fun notificationManager(): NotificationManager?
    fun buildNotification(prompt: IncomingConfirmationPrompt): Notification
    fun onSurfaceUnavailable(prompt: IncomingConfirmationPrompt)
    fun onError(error: Throwable)
}

/**
 * Owns the single visible incoming-request surface and its deadline.
 *
 * Exactly one prompt may be outstanding. Publishing a new prompt replaces the
 * previous one only after the host confirms it is still authoritative; a
 * prompt that loses authority is never resurrected by a late replay.
 */
internal class IncomingConfirmationSurface(
    private val scheduler: IncomingConfirmationDeadlineScheduler,
    private val host: IncomingConfirmationHost
) {
    var activePrompt: IncomingConfirmationPrompt? = null
        internal set

    fun publish(prompt: IncomingConfirmationPrompt) {
        if (!host.isPromptCurrent(prompt)) return
        activePrompt = prompt
        scheduler.schedule(prompt)
        when (prompt.surface) {
            ConfirmationSurface.IN_APP -> publishInApp(prompt)
            ConfirmationSurface.NOTIFICATION -> publishNotification(prompt)
        }
    }

    fun cancel(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        actionNonce: String
    ) {
        scheduler.cancel(runtimeSessionId, attemptId, actionNonce)
        val active = activePrompt
            ?.takeIf { it.runtimeSessionId == runtimeSessionId }
            ?.takeIf { it.attemptId == attemptId }
            ?.takeIf { it.actionNonce == actionNonce }
            ?: return
        dismiss(active.actionNonce)
    }

    fun cancelAll() {
        scheduler.cancel()
        val nonce = activePrompt?.actionNonce
        activePrompt = null
        host.notificationManager()?.cancel(SessionNotificationFactory.INCOMING_NOTIFICATION_ID)
        if (nonce != null) host.cancelInApp(nonce)
    }

    /** Re-offers an in-app prompt to a listener attached after it was published. */
    fun replay(show: (IncomingConfirmationPrompt) -> Unit) {
        val prompt = activePrompt
            ?.takeIf { it.surface == ConfirmationSurface.IN_APP }
            ?.takeIf(host::isPromptCurrent)
            ?: return
        show(prompt)
    }

    private fun dismiss(nonce: String) {
        host.notificationManager()?.cancel(SessionNotificationFactory.INCOMING_NOTIFICATION_ID)
        activePrompt = null
        host.cancelInApp(nonce)
    }

    private fun publishInApp(prompt: IncomingConfirmationPrompt) {
        if (!host.isAppForeground()) {
            reportUnavailable(prompt)
            return
        }
        val shown = try {
            host.showInApp(prompt)
        } catch (t: Throwable) {
            host.onError(t)
            false
        }
        if (!shown) reportUnavailable(prompt)
    }

    private fun publishNotification(prompt: IncomingConfirmationPrompt) {
        if (!host.notificationAvailable()) {
            reportUnavailable(prompt)
            return
        }
        try {
            val manager = host.notificationManager()
                ?: throw IllegalStateException("NotificationManager is unavailable")
            manager.notify(
                SessionNotificationFactory.INCOMING_NOTIFICATION_ID,
                host.buildNotification(prompt)
            )
        } catch (t: Throwable) {
            host.onError(t)
            reportUnavailable(prompt)
        }
    }

    private fun reportUnavailable(prompt: IncomingConfirmationPrompt) {
        if (!host.isPromptCurrent(prompt)) return
        host.onSurfaceUnavailable(prompt)
    }
}
