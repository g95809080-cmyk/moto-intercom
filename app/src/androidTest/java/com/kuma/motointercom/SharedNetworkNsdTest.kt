package com.kuma.motointercom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedNetworkNsdTest {
    @Test
    fun exchange() {
        val role = InstrumentationRegistry.getArguments().getString("role")
            ?: error("Missing role")
        when (role) {
            "server" -> runServer()
            "client" -> runClient()
            else -> error("Unsupported role: $role")
        }
    }

    private fun runServer() {
        val nsdManager = nsdManager()
        val registered = CountDownLatch(1)
        val server = ServerSocket(0).apply { soTimeout = TIMEOUT_MILLIS }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered.countDown()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                error("NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = server.localPort
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            assertTrue("NSD service was not registered", registered.await(10, TimeUnit.SECONDS))
            server.accept().use { socket ->
                socket.soTimeout = TIMEOUT_MILLIS
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                assertEquals(HANDSHAKE, input.readUTF())
                output.writeUTF(ACK)
                output.flush()
            }
        } finally {
            runCatching { nsdManager.unregisterService(listener) }
            server.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun runClient() {
        val nsdManager = nsdManager()
        val resolved = CountDownLatch(1)
        val resolvedInfo = AtomicReference<NsdServiceInfo>()
        val discoveryFailure = AtomicReference<String>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName != SERVICE_NAME) return
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            resolvedInfo.set(info)
                            resolved.countDown()
                        }

                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            discoveryFailure.set("NSD resolve failed: $errorCode")
                            resolved.countDown()
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryFailure.set("NSD discovery start failed: $errorCode")
                resolved.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            assertTrue("NSD service was not resolved", resolved.await(30, TimeUnit.SECONDS))
            discoveryFailure.get()?.let(::error)
            val info = resolvedInfo.get() ?: error("NSD resolved without service info")
            Socket().use { socket ->
                socket.soTimeout = TIMEOUT_MILLIS
                socket.connect(InetSocketAddress(info.host, info.port), TIMEOUT_MILLIS)
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                output.writeUTF(HANDSHAKE)
                output.flush()
                assertEquals(ACK, input.readUTF())
            }
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(discovery) }
        }
    }

    private fun nsdManager(): NsdManager =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.NSD_SERVICE) as NsdManager

    companion object {
        private const val SERVICE_NAME = "MotoIntercom-B6"
        private const val SERVICE_TYPE = "_motocom-b6._tcp."
        private const val HANDSHAKE = "MOTOCOM_B6"
        private const val ACK = "MOTOCOM_B6_OK"
        private const val TIMEOUT_MILLIS = 30_000
    }
}
