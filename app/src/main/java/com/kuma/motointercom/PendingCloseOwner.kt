package com.kuma.motointercom

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class PendingCloseOwner<T>(
    private val closeResource: (T, () -> Unit) -> Unit
) {
    private class Pending<T>(
        val resource: T,
        val callbacks: MutableList<() -> Unit>
    ) {
        val completed = AtomicBoolean(false)
    }

    private val pending = IdentityHashMap<T, Pending<T>>()

    val hasPending: Boolean
        get() = synchronized(this) { pending.isNotEmpty() }

    fun close(resource: T, onComplete: () -> Unit) {
        val (next, shouldStart) = synchronized(this) {
            val current = pending[resource]
            if (current != null) {
                current.callbacks += onComplete
                current to false
            } else {
                Pending(resource, mutableListOf(onComplete)).also {
                    pending[resource] = it
                } to true
            }
        }
        if (!shouldStart) return

        val complete: () -> Unit = {
            val callbacks = synchronized(this) {
                if (!next.completed.compareAndSet(false, true)) {
                    null
                } else {
                    if (pending[next.resource] === next) pending.remove(next.resource)
                    next.callbacks.toList().also { next.callbacks.clear() }
                }
            }
            if (callbacks != null) callbacks.forEach { it() }
        }

        try {
            closeResource(resource, complete)
        } catch (failure: Throwable) {
            synchronized(this) {
                if (pending[next.resource] === next) pending.remove(next.resource)
            }
            throw failure
        }
    }

    fun closeAll(
        additionalResources: Collection<T>,
        onError: (Throwable) -> Unit = {},
        onComplete: () -> Unit
    ) {
        val resources = synchronized(this) {
            val unique = IdentityHashMap<T, Boolean>()
            pending.keys.forEach { unique[it] = true }
            additionalResources.forEach { unique[it] = true }
            unique.keys.toList()
        }
        if (resources.isEmpty()) {
            onComplete()
            return
        }

        val remaining = AtomicInteger(resources.size)
        val released = AtomicBoolean(false)
        resources.forEach { resource ->
            val counted = AtomicBoolean(false)
            val completeOne = {
                if (
                    counted.compareAndSet(false, true) &&
                    remaining.decrementAndGet() == 0 &&
                    released.compareAndSet(false, true)
                ) {
                    onComplete()
                }
            }
            try {
                close(resource, completeOne)
            } catch (caught: Throwable) {
                try {
                    onError(caught)
                } finally {
                    completeOne()
                }
            }
        }
    }
}
