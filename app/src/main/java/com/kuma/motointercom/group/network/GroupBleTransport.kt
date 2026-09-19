@file:Suppress("DEPRECATION")

package com.kuma.motointercom.group.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.Closeable
import java.util.UUID

internal object GroupBleProtocol {
    val SERVICE: UUID = UUID.fromString("eb6c164c-c89e-43d1-9577-7bcdb4bab751")
    val MAILBOX: UUID = UUID.fromString("eb6c164c-c89e-43d1-9577-7bcdb4bab752")
    const val TIMEOUT_MS = 20_000L
}

@SuppressLint("MissingPermission")
internal fun groupBluetooth(context: Context, advertise: Boolean): BluetoothAdapter {
    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT,
            if (advertise) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_SCAN)
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    check(permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
        "请授予附近设备权限"
    }
    if (Build.VERSION.SDK_INT <= 30) {
        val location = context.getSystemService(LocationManager::class.java)
        check(location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { "请开启位置信息" }
    }
    val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) { "设备不支持蓝牙" }
    check(adapter.isEnabled) { "请开启蓝牙" }
    if (advertise) check(adapter.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser != null) {
        "设备不支持蓝牙房间广播"
    } else check(adapter.bluetoothLeScanner != null) { "设备不支持蓝牙扫描" }
    return adapter
}

internal class GroupBleCandidate internal constructor(val device: BluetoothDevice, val rssi: Int) {
    override fun toString() = "GroupBleCandidate(rssi=$rssi)"
}

/** Finite discovery only. A candidate has not proved knowledge of the room code. */
@SuppressLint("MissingPermission")
internal class GroupBleDiscovery(
    private val context: Context,
    private val onFinished: (List<GroupBleCandidate>) -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val ingress = GroupBoundedCallbacks({ action -> handler.post { action() }; Unit }, ::fail)
    private val candidates = linkedMapOf<String, GroupBleCandidate>()
    private var scanner: BluetoothLeScanner? = null
    private var started = false
    private var closed = false
    private val deadline = Runnable {
        if (!closed) { val result = candidates.values.toList(); close(); onFinished(result) }
    }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { ingress.post {
            if (!closed) runCatching {
                val address = result.device.address
                if (address in candidates || candidates.size < 16)
                    candidates[address] = GroupBleCandidate(result.device, result.rssi)
            }.onFailure { fail() }
        } }
        override fun onScanFailed(errorCode: Int) { ingress.post { fail() } }
    }
    fun start() {
        check(Looper.myLooper() == handler.looper && !started && !closed)
        started = true
        runCatching {
            scanner = groupBluetooth(context, false).bluetoothLeScanner
            scanner!!.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GroupBleProtocol.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed(deadline, 12_000)
        }.onFailure { fail() }
    }
    private fun fail() { if (!closed) { close(); onError("附近房间扫描失败，请检查蓝牙和权限") } }
    override fun close() {
        check(Looper.myLooper() == handler.looper)
        if (closed) return
        closed = true
        ingress.close()
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(callback) }
        scanner = null; candidates.clear()
    }
}

/** Main-thread callbacks. Application processing may run off-thread and reply asynchronously. */
@SuppressLint("MissingPermission")
internal class GroupBleServer(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onRequest: (UUID, ByteArray, (ByteArray?) -> Unit) -> Unit,
    private val onPeerClosed: (UUID) -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private class Peer(val device: BluetoothDevice) {
        val id: UUID = UUID.randomUUID()
        val expiresAt = SystemClock.elapsedRealtime() + GroupBleProtocol.TIMEOUT_MS
        val assembler = GroupBleAssembler()
        var request = 0L
        var processing = false
        val outgoing = ArrayDeque<ByteArray>()
        lateinit var deadline: Runnable
    }
    private val handler = Handler(Looper.getMainLooper())
    private val ingress = GroupBoundedCallbacks({ action -> handler.post { action() }; Unit }, ::fail)
    private val budget = GroupBleBudget(SystemClock::elapsedRealtime)
    private val peers = mutableMapOf<String, Peer>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var started = false
    private var closed = false
    private val startupTimeout = Runnable { fail() }
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { ingress.post {
            if (!closed) { handler.removeCallbacks(startupTimeout); onReady() }
        } }
        override fun onStartFailure(errorCode: Int) { ingress.post { fail() } }
    }
    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) { ingress.post {
            if (closed) return@post
            if (status != BluetoothGatt.GATT_SUCCESS || service.uuid != GroupBleProtocol.SERVICE) return@post fail()
            runCatching {
                advertiser!!.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                    AdvertiseData.Builder().addServiceUuid(ParcelUuid(GroupBleProtocol.SERVICE))
                        .setIncludeDeviceName(false).build(), advertiseCallback)
            }.onFailure { fail() }
        } }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { ingress.post {
            if (closed) return@post
            runCatching {
                if (newState != BluetoothProfile.STATE_CONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    peers[device.address]?.let { drop(it) }
                } else if (device.address !in peers) {
                    if (!budget.acquire(device.address, peers.size)) { server?.cancelConnection(device); return@runCatching }
                    val peer = Peer(device)
                    peers[device.address] = peer
                    peer.deadline = Runnable { if (peers[device.address] === peer) drop(peer) }
                    handler.postDelayed(peer.deadline, GroupBleProtocol.TIMEOUT_MS)
                }
            }.onFailure { fail() }
        } }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            // Reject before retaining a platform-owned buffer in the handler queue.
            val copy = if (value.size <= GroupBleChunks.CHUNK_BYTES) value.copyOf() else byteArrayOf()
            ingress.post {
                if (closed) return@post
                val peer = peers[device.address]
                try {
                    check(peer != null && SystemClock.elapsedRealtime() < peer.expiresAt && characteristic.uuid == GroupBleProtocol.MAILBOX &&
                        !preparedWrite && responseNeeded && offset == 0 && !peer.processing && peer.outgoing.isEmpty())
                    val packet = peer.assembler.accept(copy)
                    check(server!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null))
                    if (packet != null) {
                        peer.processing = true
                        val counter = ++peer.request
                        onRequest(peer.id, packet) { reply ->
                            val bounded = reply?.takeIf { it.size in 1..GroupBleChunks.MAX_MESSAGE }?.copyOf()
                            ingress.post {
                                if (closed || peers[device.address] !== peer || peer.request != counter || !peer.processing) {
                                    bounded?.fill(0); return@post
                                }
                                if (bounded == null || SystemClock.elapsedRealtime() >= peer.expiresAt) { bounded?.fill(0); drop(peer) } else {
                                    peer.outgoing.addAll(GroupBleChunks.split(bounded))
                                    bounded.fill(0)
                                    peer.processing = false
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null) }
                    if (peer != null) drop(peer) else runCatching { server?.cancelConnection(device) }
                } finally { copy.fill(0) }
            }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic) { ingress.post {
            if (closed) return@post
            val peer = peers[device.address]
            try {
                check(peer != null && SystemClock.elapsedRealtime() < peer.expiresAt && characteristic.uuid == GroupBleProtocol.MAILBOX && offset == 0)
                val chunk = peer.outgoing.removeFirstOrNull() ?: byteArrayOf()
                try { check(server!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, chunk)) }
                finally { chunk.fill(0) }
            } catch (_: Exception) {
                runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null) }
                if (peer != null) drop(peer)
            }
        } }
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) { ingress.post {
            if (!closed) {
                runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) }
                peers[device.address]?.let { drop(it) }
            }
        } }
    }
    fun start() {
        check(Looper.myLooper() == handler.looper && !started && !closed)
        started = true
        runCatching {
            advertiser = groupBluetooth(context, true).bluetoothLeAdvertiser
            handler.postDelayed(startupTimeout, 10_000)
            server = checkNotNull(context.getSystemService(BluetoothManager::class.java).openGattServer(context, callback))
            val service = BluetoothGattService(GroupBleProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            check(service.addCharacteristic(BluetoothGattCharacteristic(GroupBleProtocol.MAILBOX,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)))
            check(server!!.addService(service))
        }.onFailure { fail() }
    }
    private fun drop(peer: Peer) {
        if (peers[peer.device.address] !== peer) return
        peers.remove(peer.device.address)
        handler.removeCallbacks(peer.deadline)
        peer.assembler.close(); peer.outgoing.forEach { it.fill(0) }; peer.outgoing.clear()
        runCatching { server?.cancelConnection(peer.device) }
        runCatching { onPeerClosed(peer.id) }
    }
    private fun fail() { if (!closed) { close(); onError("蓝牙房间通道不可用，请检查蓝牙和权限") } }
    override fun close() {
        check(Looper.myLooper() == handler.looper)
        if (closed) return
        closed = true
        ingress.close()
        peers.values.toList().forEach { drop(it) }
        handler.removeCallbacksAndMessages(null)
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        advertiser = null; server = null
    }
}

/** Single-use connection; all application rounds share one absolute 20-second deadline. */
@SuppressLint("MissingPermission")
internal class GroupBleClient(
    private val context: Context,
    private val candidate: GroupBleCandidate,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val ingress = GroupBoundedCallbacks({ action -> handler.post { action() }; Unit }, ::fail)
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var started = false
    private var closed = false
    private var ready = false
    private var reply: ((ByteArray) -> Unit)? = null
    private var assembler = GroupBleAssembler()
    private val outgoing = ArrayDeque<ByteArray>()
    private var reading = false
    private var expiresAt = 0L
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { post(g) {
            check(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED)
            check(g.discoverServices())
        } }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { post(g) {
            check(status == BluetoothGatt.GATT_SUCCESS && !ready)
            characteristic = checkNotNull(g.getService(GroupBleProtocol.SERVICE)?.getCharacteristic(GroupBleProtocol.MAILBOX))
            ready = true; onReady()
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { post(g) {
            check(status == BluetoothGatt.GATT_SUCCESS && c.uuid == GroupBleProtocol.MAILBOX && !reading && reply != null)
            writeNext()
        } }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) received(g, c, c.value ?: byteArrayOf(), status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            received(g, c, value, status)
        }
    }
    private fun received(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val copy = value.takeIf { it.size <= GroupBleChunks.CHUNK_BYTES }?.copyOf()
        post(g) {
            check(status == BluetoothGatt.GATT_SUCCESS && c.uuid == GroupBleProtocol.MAILBOX && reading && reply != null)
            check(copy != null)
            if (copy.isEmpty()) { handler.postDelayed({ if (!closed && reading) readNext() }, 100); return@post }
            val packet = assembler.accept(copy)
            copy.fill(0)
            if (packet == null) readNext() else {
                val complete = checkNotNull(reply)
                reply = null; reading = false
                complete(packet)
            }
        }
    }
    private fun post(g: BluetoothGatt, action: () -> Unit) { ingress.post {
        if (!closed && gatt === g) {
            if (SystemClock.elapsedRealtime() >= expiresAt) fail()
            else runCatching(action).onFailure { fail() }
        }
    } }
    fun start() {
        check(Looper.myLooper() == handler.looper && !started && !closed)
        started = true
        expiresAt = SystemClock.elapsedRealtime() + GroupBleProtocol.TIMEOUT_MS
        runCatching {
            groupBluetooth(context, false)
            handler.postDelayed({ fail() }, GroupBleProtocol.TIMEOUT_MS)
            gatt = checkNotNull(candidate.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE))
        }.onFailure { fail() }
    }
    fun exchange(message: ByteArray, onReply: (ByteArray) -> Unit) {
        check(Looper.myLooper() == handler.looper && !closed && ready && reply == null)
        if (SystemClock.elapsedRealtime() >= expiresAt) { fail(); return }
        outgoing.addAll(GroupBleChunks.split(message))
        reply = onReply
        writeNext()
    }
    private fun writeNext() {
        try {
            val chunk = outgoing.removeFirstOrNull()
            if (chunk == null) { reading = true; readNext(); return }
            val c = checkNotNull(characteristic)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    check(gatt!!.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS)
                } else {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = chunk
                    check(gatt!!.writeCharacteristic(c))
                }
            } finally { chunk.fill(0) }
        } catch (_: Exception) { fail() }
    }
    private fun readNext() { runCatching { check(gatt!!.readCharacteristic(checkNotNull(characteristic))) }.onFailure { fail() } }
    private fun fail() { if (!closed) { close(); onError("房间认证通道中断或超时") } }
    override fun close() {
        check(Looper.myLooper() == handler.looper)
        if (closed) return
        closed = true; reply = null
        ingress.close()
        handler.removeCallbacksAndMessages(null)
        assembler.close(); outgoing.forEach { it.fill(0) }; outgoing.clear()
        val old = gatt; gatt = null; characteristic = null
        runCatching { old?.disconnect() }; runCatching { old?.close() }
    }
}
