package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDirectCloseSequenceTest {
    @Test
    fun cleanupRunsInRequiredOrderAndWaitsForEveryCallback() {
        val calls = mutableListOf<String>()
        val callbacks = ArrayDeque<() -> Unit>()
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
            closeChannel = { calls += "close" }
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
}
