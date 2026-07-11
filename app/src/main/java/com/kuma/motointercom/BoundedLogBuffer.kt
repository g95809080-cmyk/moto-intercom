package com.kuma.motointercom

internal class BoundedLogBuffer(private val limit: Int = 300) {
    private val lines = ArrayDeque<String>()

    init {
        require(limit > 0)
    }

    fun append(value: String) {
        if (lines.size == limit) lines.removeFirst()
        lines.addLast(value)
    }

    fun snapshot(): List<String> = lines.toList()
}
