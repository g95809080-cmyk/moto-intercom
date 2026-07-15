package com.kuma.motointercom

internal enum class ConfirmationSurface {
    IN_APP,
    NOTIFICATION
}

internal data class ConfirmationAvailability(
    val appForeground: Boolean,
    val notificationAvailable: Boolean
) {
    fun preferredSurface(): ConfirmationSurface? = when {
        appForeground -> ConfirmationSurface.IN_APP
        notificationAvailable -> ConfirmationSurface.NOTIFICATION
        else -> null
    }

    companion object {
        val UNAVAILABLE = ConfirmationAvailability(
            appForeground = false,
            notificationAvailable = false
        )
    }
}

internal data class IncomingRequestPolicy(
    val paired: Boolean,
    val confirmationAvailability: ConfirmationAvailability
)

internal data class IncomingConfirmationPrompt(
    val runtimeSessionId: RuntimeSessionId,
    val attemptId: ConnectionAttemptId,
    val channelId: ControlChannelId,
    val actionNonce: String,
    val peer: PeerIdentity,
    val decisionDeadlineElapsedMs: Long,
    val surface: ConfirmationSurface
) {
    init {
        require(actionNonce.isNotBlank()) { "Confirmation action nonce must not be blank" }
        require(decisionDeadlineElapsedMs >= 0L) {
            "Confirmation decision deadline must not be negative"
        }
    }
}

internal class IncomingConfirmationDeadlineScheduler(
    private val elapsedRealtime: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onTimedOut: (IncomingConfirmationPrompt) -> Unit
) {
    private data class ScheduledPrompt(
        val prompt: IncomingConfirmationPrompt,
        val callback: Runnable
    )

    private var scheduled: ScheduledPrompt? = null

    fun schedule(prompt: IncomingConfirmationPrompt) {
        cancel()
        lateinit var callback: Runnable
        callback = Runnable {
            val current = scheduled
            if (current?.prompt != prompt || current.callback !== callback) return@Runnable
            scheduled = null
            onTimedOut(prompt)
        }
        scheduled = ScheduledPrompt(prompt, callback)
        postDelayed(
            callback,
            (prompt.decisionDeadlineElapsedMs - elapsedRealtime()).coerceAtLeast(0L)
        )
    }

    fun cancel(
        runtimeSessionId: RuntimeSessionId? = null,
        attemptId: ConnectionAttemptId? = null,
        actionNonce: String? = null
    ) {
        val current = scheduled ?: return
        if (runtimeSessionId != null && current.prompt.runtimeSessionId != runtimeSessionId) return
        if (attemptId != null && current.prompt.attemptId != attemptId) return
        if (actionNonce != null && current.prompt.actionNonce != actionNonce) return
        scheduled = null
        removeCallbacks(current.callback)
    }
}
