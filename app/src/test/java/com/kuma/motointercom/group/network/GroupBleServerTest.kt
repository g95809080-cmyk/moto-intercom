package com.kuma.motointercom.group.network

import android.Manifest
import android.app.Application
import android.bluetooth.*
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowBluetoothGattServer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [RespondingGattServer::class])
class GroupBleServerTest {
    private lateinit var adapter: BluetoothAdapter
    private lateinit var server: GroupBleServer
    private lateinit var native: BluetoothGattServer
    private lateinit var callback: BluetoothGattServerCallback
    private lateinit var device: BluetoothDevice
    private val requests = mutableListOf<ByteArray>()
    private val replies = mutableListOf<(ByteArray?) -> Unit>()
    private val closed = mutableListOf<UUID>()
    private var throwOnClose = false
    private val mailbox = BluetoothGattCharacteristic(GroupBleProtocol.MAILBOX,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
    @Before fun setup() {
        RespondingGattServer.accept = true
        RespondingGattServer.sent.clear()
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setIsMultipleAdvertisementSupported(true)
        server = GroupBleServer(context, {}, { _, bytes, reply -> requests += bytes; replies += reply }, {
            closed += it
            if (throwOnClose) error("application callback")
        }, {})
        server.start()
        native = ReflectionHelpers.getField(server, "server")
        callback = shadowOf(native).gattServerCallback
        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, native.getService(GroupBleProtocol.SERVICE))
        idle()
        device = adapter.getRemoteDevice("00:11:22:33:44:55")
        connect(device)
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun connect(peer: BluetoothDevice) {
        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED); idle()
    }
    private fun write(message: ByteArray = byteArrayOf(42)) {
        GroupBleChunks.split(message).forEach {
            callback.onCharacteristicWriteRequest(device, 1, mailbox, false, true, 0, it); idle()
        }
    }
    @Test fun completeMessageDeliveredOnceAndConcurrentRequestClosesPeer() {
        write(ByteArray(80) { it.toByte() })
        assertEquals(1, requests.size)
        assertArrayEquals(ByteArray(80) { it.toByte() }, requests.single())
        write()
        assertEquals(1, requests.size)
        assertEquals(1, closed.size)
        replies.single()(byteArrayOf(9)); idle()
        assertEquals(1, closed.size)
        server.close()
    }
    @Test fun deadlineDropsPendingWorkAndLateReplyCannotReopenIt() {
        write()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertEquals(1, closed.size)
        replies.single()(ByteArray(100)); idle()
        assertTrue(shadowOf(native).isConnectionCancelled(device))
        server.close()
    }
    @Test fun fifthConnectionIsRejectedWithoutEvictingFourExistingConnections() {
        val others = (1..4).map { adapter.getRemoteDevice("00:11:22:33:44:0$it") }
        others.forEach(::connect)
        assertTrue(shadowOf(native).isConnectionCancelled(others.last()))
        assertFalse(shadowOf(native).isConnectionCancelled(device))
        assertTrue(closed.isEmpty())
        server.close(); assertEquals(4, closed.size)
    }
    @Test fun attPreparedOrNonzeroOffsetCannotReachApplication() {
        callback.onCharacteristicWriteRequest(device, 1, mailbox, true, true, 0, GroupBleChunks.split(byteArrayOf(1)).single())
        idle(); assertTrue(requests.isEmpty()); assertEquals(1, closed.size)
        connect(device)
        callback.onCharacteristicWriteRequest(device, 2, mailbox, false, true, 1, GroupBleChunks.split(byteArrayOf(1)).single())
        idle(); assertTrue(requests.isEmpty()); assertEquals(2, closed.size)
        server.close()
    }
    @Test fun stoppedOwnerNeverDeliversQueuedWriteOrLateReply() {
        write()
        callback.onCharacteristicWriteRequest(device, 2, mailbox, false, true, 0, GroupBleChunks.split(byteArrayOf(1)).single())
        server.close()
        replies.single()(byteArrayOf(7)); idle()
        assertTrue(shadowOf(native).isClosed)
        assertEquals(1, requests.size)
        assertEquals(1, closed.size)
    }
    @Test fun responseFragmentsAndRequestCounterKeepLateDuplicateReplyOutOfNextRound() {
        write()
        val first = replies.single()
        first(ByteArray(33) { it.toByte() }); idle()
        val assembler = GroupBleAssembler()
        repeat(3) { index ->
            callback.onCharacteristicReadRequest(device, index, 0, mailbox); idle()
            val decoded = assembler.accept(RespondingGattServer.sent.last())
            if (index == 2) assertArrayEquals(ByteArray(33) { it.toByte() }, decoded)
            else assertNull(decoded)
        }
        write(byteArrayOf(99))
        first(byteArrayOf(100)); idle()
        callback.onCharacteristicReadRequest(device, 8, 0, mailbox); idle()
        assertTrue(RespondingGattServer.sent.last().isEmpty())
        replies.last()(byteArrayOf(101)); idle()
        callback.onCharacteristicReadRequest(device, 9, 0, mailbox); idle()
        assertArrayEquals(byteArrayOf(101), GroupBleAssembler().accept(RespondingGattServer.sent.last()))
        server.close()
    }
    @Test fun failedNativeAcknowledgementNeverDeliversRequest() {
        RespondingGattServer.accept = false
        write()
        assertTrue(requests.isEmpty())
        assertEquals(1, closed.size)
        server.close()
    }
    @Test fun throwingPeerCallbackCannotInterruptAllPeerAndPlatformCleanup() {
        connect(adapter.getRemoteDevice("00:11:22:33:44:01"))
        connect(adapter.getRemoteDevice("00:11:22:33:44:02"))
        throwOnClose = true
        server.close()
        assertEquals(3, closed.size)
        assertTrue(shadowOf(native).isClosed)
        assertEquals(0, shadowOf(adapter.bluetoothLeAdvertiser).advertisementRequestCount)
        server.close(); assertEquals(3, closed.size)
    }
    @Test fun unauthenticatedBurstCannotBuildUnboundedPendingDelivery() {
        repeat(10_000) {
            callback.onCharacteristicWriteRequest(device, it, mailbox, false, false, 0, byteArrayOf(1))
        }
        idle()
        assertTrue(requests.isEmpty())
        assertEquals(1, closed.size)
        assertTrue(shadowOf(native).isClosed)
    }
}

/** Robolectric's default sendResponse delegates to an unregistered binder and returns false. */
@Implements(BluetoothGattServer::class)
class RespondingGattServer : ShadowBluetoothGattServer() {
    @Implementation override fun sendResponse(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?): Boolean {
        if (value != null) sent += value.copyOf()
        return accept
    }
    companion object {
        var accept = true
        val sent = mutableListOf<ByteArray>()
    }
}
