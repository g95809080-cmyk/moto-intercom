package com.kuma.motointercom

internal class SessionGeneration {
    @JvmInline
    value class Token internal constructor(val value: Long)

    private var next = 0L
    private var current: Token? = null

    @Synchronized
    fun start(): Token = Token(++next).also { current = it }

    @Synchronized
    fun invalidate() {
        current = null
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean = current == token

    @Synchronized
    fun claimIfCurrent(token: Token, claim: () -> Boolean): Boolean =
        current == token && claim()
}
