package com.kuma.motointercom

import android.net.Network
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Blocking developer signaling client. Call from worker threads, never the RTC or main thread. */
internal class RelaySignalingClient(
    endpoint: String,
    private val network: Network? = null,
    private val socketFactory: SSLSocketFactory? = null
) {
    private val endpoint = URL(endpoint).also {
        require(it.protocol == "https" && it.userInfo == null && it.query == null && it.ref == null &&
            (it.path.isEmpty() || it.path == "/")) { "Relay endpoint must be an HTTPS origin" }
    }.toString().trimEnd('/')

    fun request(method: String, path: String, token: String, body: JSONObject? = null): JSONObject {
        require(path.startsWith("/v1/sessions") && token.matches(Regex("[A-Za-z0-9_-]{32,256}")))
        val url = URL(endpoint + path)
        // Network.openConnection binds resolution AND sockets to this Network; no process binding.
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 25_000
            socketFactory?.let { connection.sslSocketFactory = it }
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 65_536)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            check(status in 200..299) { "Relay request rejected: HTTP $status" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 262_144) { "Relay response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    companion object {
        /** A dedicated CA store; platform hostname validation remains enabled. */
        fun trustedCa(pem: String): SSLSocketFactory {
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null); setCertificateEntry("relay-test-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
        }
    }
}
