package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RelayLifecycleTest {
    @Test fun configurationFailureCannotLeakRuntimeOwner() {
        assertThrows(IllegalArgumentException::class.java) {
            LegacyRuntimeOwnership.withOwner { throw IllegalArgumentException("missing tone frequency") }
        }
        val next = requireNotNull(LegacyRuntimeOwnership.acquire())
        LegacyRuntimeOwnership.release(next)
    }

    @Test fun partialFactoryFailureRetainsOwnerUntilNativeDisposalEvenWithoutReturnedController() {
        var nativeResource: java.io.Closeable? = null
        try {
            assertThrows(IllegalStateException::class.java) {
                LegacyRuntimeOwnership.withOwner {
                    nativeResource = LegacyRuntimeOwnership.retainAudioResources()
                    error("route creation failed before controller returned")
                }
            }
            assertNull(LegacyRuntimeOwnership.acquire())
            nativeResource!!.close()
            val next = requireNotNull(LegacyRuntimeOwnership.acquire())
            nativeResource!!.close() // A late duplicate must not release the replacement.
            assertNull(LegacyRuntimeOwnership.acquire())
            LegacyRuntimeOwnership.release(next)
        } finally { nativeResource?.close() }
    }

    @Test fun shutdownBetweenCurrentCheckAndSubmitDoesNotEscapeNativeCallback() {
        val executor = Executors.newSingleThreadExecutor()
        val current = AtomicBoolean(true)
        val checked = CountDownLatch(1)
        val submit = CountDownLatch(1)
        val escaped = AtomicReference<Throwable?>()
        val errors = mutableListOf<Throwable>()
        val delayed = Executor { task ->
            checked.countDown()
            check(submit.await(5, TimeUnit.SECONDS))
            executor.execute(task)
        }
        val callback = Thread {
            try { submitCurrentMediaWork(delayed, current::get, errors::add) { error("must never execute") } }
            catch (error: Throwable) { escaped.set(error) }
        }
        try {
            callback.start()
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            current.set(false); executor.shutdownNow(); submit.countDown()
            callback.join(5000)
            assertFalse(callback.isAlive)
            assertNull(escaped.get()); assertTrue(errors.isEmpty())
        } finally { current.set(false); submit.countDown(); executor.shutdownNow(); callback.join(5000) }
    }

    @Test fun unexpectedSubmissionRejectionWhileCurrentIsReported() {
        val errors = mutableListOf<Throwable>()
        submitCurrentMediaWork(Executor { throw RejectedExecutionException() }, { true }, errors::add) {}
        assertTrue(errors.single() is RejectedExecutionException)
    }
}
