package com.kuma.motointercom

import java.util.UUID

@JvmInline
value class RuntimeSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Runtime session ID must not be blank" }
    }

    companion object {
        fun create(): RuntimeSessionId = RuntimeSessionId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ConnectionAttemptId(val value: String) {
    init {
        require(value.isNotBlank()) { "Connection attempt ID must not be blank" }
    }

    companion object {
        fun create(): ConnectionAttemptId = ConnectionAttemptId(UUID.randomUUID().toString())
    }
}
