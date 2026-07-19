package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectCloseSequenceTest {
    @Test
    fun cleanupRunsInRequiredOrderAndWaitsForEveryCallback() {
        val calls = mutableListOf<String>()
        val callbacks = ArrayDeque<() -> Unit>()
        val scheduler = TestScheduler()
        val action: (String) -> ((() -> Unit) -> Unit) = { name ->
            { complete ->
                calls += name
                callbacks += complete
            }
        }

        WifiDirectCloseSequence(
            cancelConnect = action("cancelConnect"),
            removeGroup = action("removeGroup"),
            clearServiceRequests = action("clearServiceRequests"),
            clearLocalServices = action("clearLocalServices"),
            closeChannel = { calls += "close" },
            postDelayed = scheduler::post,
            removeCallbacks = scheduler::remove,
            stepTimeoutMillis = 1L
        ).start()

        assertEquals(listOf("cancelConnect"), calls)
        callbacks.removeFirst().invoke()
        assertEquals(listOf("cancelConnect", "removeGroup"), calls)
        callbacks.removeFirst().invoke()
        assertEquals(
            listOf("cancelConnect", "removeGroup", "clearServiceRequests"),
            calls
        )
        callbacks.removeFirst().invoke()
        assertEquals(
            listOf(
                "cancelConnect",
                "removeGroup",
                "clearServiceRequests",
                "clearLocalServices"
            ),
            calls
        )
        callbacks.removeFirst().invoke()
        assertEquals(
            listOf(
                "cancelConnect",
                "removeGroup",
                "clearServiceRequests",
                "clearLocalServices",
                "close"
            ),
            calls
        )
    }

    @Test
    fun thrownAndDuplicateCallbacksStillCompleteExactlyOnce() {
        val calls = mutableListOf<String>()
        var duplicateCancel: (() -> Unit)? = null
        val scheduler = TestScheduler()

        WifiDirectCloseSequence(
            cancelConnect = { complete ->
                calls += "cancelConnect"
                duplicateCancel = complete
                complete()
            },
            removeGroup = {
                calls += "removeGroup"
                throw IllegalStateException("manager unavailable")
            },
            clearServiceRequests = { complete ->
                calls += "clearServiceRequests"
                complete()
            },
            clearLocalServices = { complete ->
                calls += "clearLocalServices"
                complete()
                complete()
            },
            closeChannel = { calls += "close" },
            postDelayed = scheduler::post,
            removeCallbacks = scheduler::remove,
            stepTimeoutMillis = 1L,
            onError = { calls += "error" }
        ).start()

        duplicateCancel?.invoke()
        assertEquals(
            listOf(
                "cancelConnect",
                "removeGroup",
                "error",
                "clearServiceRequests",
                "clearLocalServices",
                "close"
            ),
            calls
        )
    }

    @Test
    fun everyMissingAndroidCallbackTimesOutAndStillCloses() {
        val labels = listOf(
            "cancelConnect",
            "removeGroup",
            "clearServiceRequests",
            "clearLocalServices"
        )

        labels.indices.forEach { stalledIndex ->
            val calls = mutableListOf<String>()
            val errors = mutableListOf<Throwable>()
            val scheduler = TestScheduler()
            val actions = labels.mapIndexed { index, label ->
                { complete: () -> Unit ->
                    calls += label
                    if (index != stalledIndex) complete()
                }
            }

            WifiDirectCloseSequence(
                cancelConnect = actions[0],
                removeGroup = actions[1],
                clearServiceRequests = actions[2],
                clearLocalServices = actions[3],
                closeChannel = { calls += "close" },
                postDelayed = scheduler::post,
                removeCallbacks = scheduler::remove,
                stepTimeoutMillis = 1L,
                onError = errors::add
            ).start()

            scheduler.runAll()

            assertEquals(labels + "close", calls)
            assertEquals(1, errors.size)
            assertTrue(errors.single().message.orEmpty().contains(labels[stalledIndex]))
        }
    }

    private class TestScheduler {
        private val callbacks = ArrayDeque<Runnable>()

        fun post(callback: Runnable, delayMillis: Long) {
            require(delayMillis > 0L)
            callbacks += callback
        }

        fun remove(callback: Runnable) {
            callbacks.remove(callback)
        }

        fun runAll() {
            while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
        }
    }
}
