package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCloseOwnerTest {
    @Test
    fun synchronousCloseStartsOnceAndCompletesOnce() {
        var closeCalls = 0
        var completions = 0
        val owner = PendingCloseOwner<Any> { _, complete ->
            closeCalls++
            complete()
            complete()
        }

        owner.close(Any()) { completions++ }

        assertEquals(1, closeCalls)
        assertEquals(1, completions)
        assertFalse(owner.hasPending)
    }

    @Test
    fun closeAllWaitsForPendingRecoveryAndReleasesExactlyOnce() {
        val callbacks = mutableListOf<() -> Unit>()
        val owner = PendingCloseOwner<Any> { _, complete -> callbacks += complete }
        var recoveryResumed = 0
        var keepAliveReleased = 0
        val resource = Any()

        owner.close(resource) { recoveryResumed++ }
        owner.closeAll(emptyList()) { keepAliveReleased++ }

        assertTrue(owner.hasPending)
        assertEquals(0, keepAliveReleased)

        callbacks.single().invoke()
        callbacks.single().invoke()

        assertFalse(owner.hasPending)
        assertEquals(1, recoveryResumed)
        assertEquals(1, keepAliveReleased)
    }

    @Test
    fun closeAllWaitsForCurrentAndAlreadyClosingResources() {
        val callbacks = mutableListOf<() -> Unit>()
        val owner = PendingCloseOwner<Any> { _, complete -> callbacks += complete }
        var released = 0
        val oldResource = Any()
        val currentResource = Any()

        owner.close(oldResource) {}
        owner.closeAll(listOf(currentResource)) { released++ }

        assertEquals(2, callbacks.size)
        callbacks.removeAt(0).invoke()
        assertEquals(0, released)
        callbacks.removeAt(0).invoke()
        assertEquals(1, released)
    }

    @Test
    fun closeAllReleasesOnNoResourceAndThrownClose() {
        var noResourceRelease = 0
        PendingCloseOwner<Any> { _, _ -> error("unused") }
            .closeAll(emptyList()) { noResourceRelease++ }
        assertEquals(1, noResourceRelease)

        var thrownRelease = 0
        val errors = mutableListOf<Throwable>()
        val owner = PendingCloseOwner<Any> { _, _ ->
            throw IllegalStateException("close failed")
        }
        owner.closeAll(listOf(Any()), onError = errors::add) { thrownRelease++ }

        assertEquals(1, thrownRelease)
        assertEquals(1, errors.size)
        assertFalse(owner.hasPending)
    }

    @Test
    fun thrownCurrentCloseStillWaitsForAlreadyPendingResource() {
        val callbacks = mutableMapOf<Any, () -> Unit>()
        val oldResource = Any()
        val currentResource = Any()
        val owner = PendingCloseOwner<Any> { resource, complete ->
            if (resource === currentResource) {
                throw IllegalStateException("current close failed")
            }
            callbacks[resource] = complete
        }
        val errors = mutableListOf<Throwable>()
        var released = 0

        owner.close(oldResource) {}
        owner.closeAll(listOf(currentResource), onError = errors::add) { released++ }

        assertEquals(0, released)
        callbacks.getValue(oldResource).invoke()
        assertEquals(1, released)
        assertEquals(1, errors.size)
    }

    @Test
    fun throwingRecoveryCallbackDoesNotBlockStopRelease() {
        val callbacks = mutableListOf<() -> Unit>()
        val owner = PendingCloseOwner<Any> { _, complete -> callbacks += complete }
        val resource = Any()
        var keepAliveReleased = 0

        owner.close(resource) { throw IllegalStateException("recovery callback failed") }
        owner.closeAll(emptyList()) { keepAliveReleased++ }

        val thrown = runCatching { callbacks.single().invoke() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(1, keepAliveReleased)
        assertFalse(owner.hasPending)
    }

    @Test
    fun recoveryStopNewRuntimeAndRepeatedStopReleaseEachRuntimeOnce() {
        val callbacks = linkedMapOf<Any, () -> Unit>()
        val owner = PendingCloseOwner<Any> { resource, complete -> callbacks[resource] = complete }
        val oldResource = Any()
        val newResource = Any()
        var recoveryResumed = 0
        var oldRuntimeReleased = 0
        var newRuntimeReleased = 0
        var repeatedStopCompleted = 0

        owner.close(oldResource) { recoveryResumed++ }
        owner.closeAll(emptyList()) { oldRuntimeReleased++ }
        owner.close(newResource) {}
        owner.closeAll(listOf(newResource)) { newRuntimeReleased++ }
        owner.closeAll(emptyList()) { repeatedStopCompleted++ }

        callbacks.getValue(oldResource).invoke()
        callbacks.getValue(oldResource).invoke()

        assertEquals(1, recoveryResumed)
        assertEquals(1, oldRuntimeReleased)
        assertEquals(0, newRuntimeReleased)
        assertEquals(0, repeatedStopCompleted)

        callbacks.getValue(newResource).invoke()
        callbacks.getValue(newResource).invoke()

        assertEquals(1, newRuntimeReleased)
        assertEquals(1, repeatedStopCompleted)
        assertFalse(owner.hasPending)
    }
}
