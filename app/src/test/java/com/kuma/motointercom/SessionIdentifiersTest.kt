package com.kuma.motointercom

import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionIdentifiersTest {
    @Test
    fun eachRuntimeSessionGetsANewId() {
        assertNotEquals(RuntimeSessionId.create(), RuntimeSessionId.create())
    }

    @Test
    fun eachConnectionAttemptGetsANewId() {
        assertNotEquals(ConnectionAttemptId.create(), ConnectionAttemptId.create())
    }
}
