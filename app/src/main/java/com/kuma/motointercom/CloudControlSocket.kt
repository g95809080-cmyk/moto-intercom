package com.kuma.motointercom

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal interface CloudEventSocket : Closeable {
    fun connect(token: CloudSocketToken, onOpen: () -> Unit, onEvent: (String) -> Unit, onDisconnected: () -> Unit)
}

internal class CloudControlSocket(
    endpoint: String,
    client: OkHttpClient,
    allowLoopbackForTests: Boolean = false
) : CloudEventSocket {
    private val url = endpoint.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl().also {
        require(it.isHttps || (allowLoopbackForTests && it.host in setOf("localhost", "127.0.0.1")))
        require(it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty())
    }
    private val client = client.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
    private val generation = AtomicLong()
    @Volatile private var socket: WebSocket? = null

    override fun connect(token: CloudSocketToken, onOpen: () -> Unit, onEvent: (String) -> Unit, onDisconnected: () -> Unit) {
        val epoch = generation.incrementAndGet()
        socket?.cancel()
        val request = Request.Builder().url(url.newBuilder().addQueryParameter("token", token.value).build()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (epoch == generation.get()) onOpen() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (epoch != generation.get()) return
                if (text.length > 32_768) { webSocket.cancel(); onDisconnected(); return }
                onEvent(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (epoch == generation.get()) onDisconnected() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Throwable/Request.toString can contain the single-use query token.
                if (epoch == generation.get()) onDisconnected()
            }
        })
    }

    override fun close() { generation.incrementAndGet(); socket?.cancel(); socket = null }
}
