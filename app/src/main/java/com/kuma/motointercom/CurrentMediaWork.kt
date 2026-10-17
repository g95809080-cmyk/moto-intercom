package com.kuma.motointercom

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Native callbacks may race executor shutdown after their first lease check. */
internal fun submitCurrentMediaWork(
    executor: Executor,
    isCurrent: () -> Boolean,
    onError: (Throwable) -> Unit,
    work: () -> Unit
) {
    if (!isCurrent()) return
    try {
        executor.execute {
            if (isCurrent()) try { work() } catch (error: Throwable) { if (isCurrent()) onError(error) }
        }
    } catch (error: RejectedExecutionException) {
        if (isCurrent()) onError(error)
    }
}
