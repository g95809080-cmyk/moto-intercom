package com.kuma.motointercom

internal sealed interface AttemptMilestone {
    val attempt: ConnectionAttempt
    val scheduledAt: MonotonicTimestamp

    data class FallbackTransport(
        override val attempt: ConnectionAttempt,
        val transport: Transport,
        override val scheduledAt: MonotonicTimestamp
    ) : AttemptMilestone {
        init {
            require(attempt.channelPlan.fallbackTransport == transport) {
                "Fallback milestone must match the immutable channel plan"
            }
            require(scheduledAt.elapsedRealtimeMs <= attempt.deadlineElapsedRealtimeMs) {
                "Fallback milestone cannot exceed the total attempt deadline"
            }
        }
    }

    data class MediaOptimization(
        override val attempt: ConnectionAttempt,
        val wireRequestKey: WireRequestKey,
        override val scheduledAt: MonotonicTimestamp
    ) : AttemptMilestone {
        init {
            require(wireRequestKey.attemptId == attempt.id) {
                "Optimization milestone must belong to the attempt wire request"
            }
            require(scheduledAt.elapsedRealtimeMs <= attempt.deadlineElapsedRealtimeMs) {
                "Optimization milestone cannot exceed the total attempt deadline"
            }
        }
    }
}

internal enum class AttemptMilestoneKind {
    FALLBACK_TRANSPORT,
    MEDIA_OPTIMIZATION
}

internal val AttemptMilestone.kind: AttemptMilestoneKind
    get() = when (this) {
        is AttemptMilestone.FallbackTransport -> AttemptMilestoneKind.FALLBACK_TRANSPORT
        is AttemptMilestone.MediaOptimization -> AttemptMilestoneKind.MEDIA_OPTIMIZATION
    }
