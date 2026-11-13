package com.kuma.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.Closeable
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the short-lived BLE discovery/bootstrap layer. BLE is never used as the
 * audio transport; once a TCP control channel exists this coordinator can be
 * closed without affecting WebRTC.
 */
@SuppressLint("MissingPermission")
internal class BleBootstrapCoordinator(
    context: Context,
    private val localAdvertisement: CrossPlatformCapabilityAdvertisement,
    private val onScanDevice: (BluetoothDevice) -> Unit = {},
    private val onPeerDiscovered: (BluetoothDevice, CrossPlatformCapabilityAdvertisement) -> Unit = { _, _ -> },
    private val onMessage: (BluetoothDevice, BleBootstrapMessage) -> Unit = { _, _ -> },
    private val onConnectionChanged: (BluetoothDevice, Boolean) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
    private val handler: Handler = Handler(Looper.getMainLooper())
) : Closeable {
    private val context = context.applicationContext
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val running = AtomicBoolean(false)
    private val assemblers = ConcurrentHashMap<String, BleMessageAssembler>()
    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientCharacteristics = ConcurrentHashMap<String, ClientCharacteristics>()
    private val serverDevices = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val respondedBootstrapRequests = ConcurrentHashMap<String, Boolean>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val assemblerExpiry = object : Runnable {
        override fun run() {
            if (!running.get()) return
            assemblers.values.forEach { it.expire() }
            handler.postDelayed(this, ASSEMBLER_EXPIRY_INTERVAL_MS)
        }
    }

    fun start(): Boolean {
        if (!hasBleRuntimePermissions()) {
            onError(SecurityException("BLE permissions are required for automatic bootstrap"))
            return false
        }
        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            onError(IllegalStateException("Bluetooth is unavailable or disabled"))
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        return runCatching {
            openServer(localAdapter)
            startAdvertising(localAdapter)
            startScanning(localAdapter)
            handler.postDelayed(assemblerExpiry, ASSEMBLER_EXPIRY_INTERVAL_MS)
        }.onFailure {
            close()
            onError(it)
        }.isSuccess
    }

    fun connect(device: BluetoothDevice): Boolean {
        if (!running.get() || !hasBleRuntimePermissions()) return false
        val localAdapter = adapter ?: return false
        val address = device.address ?: return false
        gattClients[address]?.let { return true }
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        if (gatt == null) return false
        gattClients[address] = gatt
        return localAdapter.isEnabled
    }

    fun send(device: BluetoothDevice, message: BleBootstrapMessage): Boolean {
        if (!running.get() || !hasBleRuntimePermissions()) return false
        val address = device.address ?: return false
        val gatt = gattClients[address]
        val client = clientCharacteristics[address]
        val chunks = BleChunkCodec.encode(
            messageId = message.requestId.hashCode(),
            message = BleBootstrapCodec.encode(message)
        )
        if (gatt != null && client != null) {
            chunks.forEachIndexed { index, packet ->
                handler.postDelayed({
                    if (!running.get() || gattClients[address] !== gatt) return@postDelayed
                    client.rx.value = packet
                    client.rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    runCatching { gatt.writeCharacteristic(client.rx) }
                        .onFailure(onError)
                    if (index == chunks.lastIndex) {
                        handler.postDelayed({
                            if (gattClients[address] === gatt) gatt.requestConnectionPriority(
                                BluetoothGatt.CONNECTION_PRIORITY_HIGH
                            )
                        }, 100L)
                    }
                }, index * 30L)
            }
            return true
        }
        if (address !in serverDevices || gattServer == null || txCharacteristic == null) {
            return false
        }
        chunks.forEachIndexed { index, packet ->
            handler.postDelayed({
                if (!running.get() || address !in serverDevices) return@postDelayed
                notifyServerDevice(device, packet)
            }, index * 30L)
        }
        return true
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        handler.removeCallbacks(assemblerExpiry)
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        clientCharacteristics.clear()
        respondedBootstrapRequests.clear()
        gattClients.values.toList().forEach { gatt -> runCatching { gatt.close() } }
        gattClients.clear()
        serverDevices.clear()
        assemblers.clear()
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        txCharacteristic = null
    }

    private fun openServer(localAdapter: BluetoothAdapter) {
        val manager = bluetoothManager ?: error("BluetoothManager is unavailable")
        val server = manager.openGattServer(context, gattServerCallback)
            ?: error("Unable to open BLE GATT server")
        val service = BluetoothGattService(
            MotoComBleUuids.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val rx = BluetoothGattCharacteristic(
            MotoComBleUuids.RX_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            MotoComBleUuids.TX_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CONFIGURATION_DESCRIPTOR,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        txCharacteristic = tx
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        check(server.addService(service)) { "Unable to add MotoCom BLE service" }
        gattServer = server
        localAdapter.name?.takeIf(String::isNotBlank)
    }

    private fun startAdvertising(localAdapter: BluetoothAdapter) {
        val localAdvertiser = localAdapter.bluetoothLeAdvertiser
            ?: error("BLE advertising is unavailable")
        advertiser = localAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MotoComBleUuids.SERVICE))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        localAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScanning(localAdapter: BluetoothAdapter) {
        val localScanner = localAdapter.bluetoothLeScanner
            ?: error("BLE scanning is unavailable")
        scanner = localScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MotoComBleUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        localScanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun receive(device: BluetoothDevice, packet: ByteArray) {
        val address = device.address ?: return
        val assembler = assemblers.getOrPut(address) { BleMessageAssembler() }
        val decoded = runCatching { assembler.add(packet) }.getOrElse {
            assembler.clear()
            onError(it)
            return
        } ?: return
        val message = runCatching { BleBootstrapCodec.decode(decoded) }.getOrElse {
            onError(it)
            return
        }
        if (message.type == BleBootstrapMessageType.BOOTSTRAP_CAPABILITIES) {
            decodeCapabilityAdvertisement(message)?.let {
                onPeerDiscovered(device, it)
            }
        }
        onMessage(device, message)
        val requestBelongsToApp = message.fields["app"]
            ?.takeIf(String::isNotBlank)
            ?.let { it == localAdvertisement.app }
            ?: true
        if (
            message.type == BleBootstrapMessageType.BOOTSTRAP_REQUEST &&
            requestBelongsToApp &&
            respondedBootstrapRequests.putIfAbsent("${address}:${message.requestId}", true) == null
        ) {
            send(device, localCapabilitiesMessage())
        }
    }

    private fun localCapabilitiesMessage(): BleBootstrapMessage =
        BleBootstrapMessage(
            type = BleBootstrapMessageType.BOOTSTRAP_CAPABILITIES,
            requestId = UUID.randomUUID().toString(),
            fields = mapOf(
                "app" to localAdvertisement.app,
                "platform" to localAdvertisement.platform.name,
                "deviceId" to localAdvertisement.deviceId,
                "sessionId" to localAdvertisement.sessionId,
                "networkRole" to localAdvertisement.networkRole.name,
                "tcpPort" to localAdvertisement.tcpPort.toString(),
                "protocolVersion" to localAdvertisement.protocolVersion.toString()
            ),
            capabilities = localAdvertisement.capabilities
        )

    private fun decodeCapabilityAdvertisement(
        message: BleBootstrapMessage
    ): CrossPlatformCapabilityAdvertisement? {
        val required = listOf("app", "platform", "deviceId", "sessionId", "networkRole", "tcpPort")
        if (required.any { it !in message.fields }) return null
        return runCatching {
            CrossPlatformCapabilityAdvertisement(
                app = message.fields.getValue("app"),
                protocolVersion = message.fields["protocolVersion"]?.toIntOrNull()
                    ?: message.protocolVersion,
                platform = PeerPlatform.parse(message.fields.getValue("platform")),
                deviceId = message.fields.getValue("deviceId"),
                sessionId = message.fields.getValue("sessionId"),
                capabilities = message.capabilities,
                networkRole = BootstrapNetworkRole.parse(message.fields.getValue("networkRole")),
                tcpPort = message.fields.getValue("tcpPort").toInt()
            )
        }.onFailure(onError).getOrNull()
    }

    private fun hasBleRuntimePermissions(): Boolean {
        val required = PermissionPolicy.bluetoothPermissions(Build.VERSION.SDK_INT).let { permissions ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                permissions + Manifest.permission.ACCESS_FINE_LOCATION
            } else {
                permissions
            }
        }
        return required.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onError(IllegalStateException("BLE advertising failed: $errorCode"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (running.get()) onScanDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            onError(IllegalStateException("BLE scan failed: $errorCode"))
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (!running.get()) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevices += device.address
                reportConnectionChanged(device, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverDevices -= device.address
                assemblers.remove(device.address)
                clearRespondedBootstrapRequests(device.address)
                reportConnectionChanged(device, false)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != MotoComBleUuids.TX_CHARACTERISTIC) return
            val payload = BleBootstrapCodec.encodeAdvertisement(localAdvertisement)
            val response = payload.copyOfRange(offset.coerceAtMost(payload.size), payload.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MotoComBleUuids.RX_CHARACTERISTIC && offset == 0) {
                receive(device, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                reportConnectionChanged(gatt.device, true)
                if (!gatt.discoverServices()) {
                    onError(IllegalStateException("BLE service discovery could not start"))
                    removeGatt(gatt)
                    reportConnectionChanged(gatt.device, false)
                }
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                onError(IllegalStateException("BLE connection failed: $status"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onError(IllegalStateException("BLE service discovery failed: $status"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
                return
            }
            val service = gatt.getService(MotoComBleUuids.SERVICE)
            val rx = service?.getCharacteristic(MotoComBleUuids.RX_CHARACTERISTIC)
            val tx = service?.getCharacteristic(MotoComBleUuids.TX_CHARACTERISTIC)
            if (rx == null || tx == null) {
                onError(IllegalStateException("MotoCom BLE service is incomplete"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
                return
            }
            val descriptor = tx.getDescriptor(CLIENT_CONFIGURATION_DESCRIPTOR)
            if (descriptor == null) {
                onError(IllegalStateException("MotoCom BLE TX descriptor is missing"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
                return
            }
            if (!gatt.setCharacteristicNotification(tx, true)) {
                onError(IllegalStateException("Unable to enable MotoCom BLE notifications"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                onError(IllegalStateException("Unable to write MotoCom BLE notification descriptor"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
                return
            }
            clientCharacteristics[gatt.device.address] = ClientCharacteristics(rx, tx)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                descriptor.uuid == CLIENT_CONFIGURATION_DESCRIPTOR
            ) {
                send(gatt.device, BleBootstrapMessage(
                    type = BleBootstrapMessageType.BOOTSTRAP_REQUEST,
                    requestId = UUID.randomUUID().toString(),
                    fields = mapOf(
                        "app" to localAdvertisement.app,
                        "platform" to localAdvertisement.platform.name,
                        "deviceId" to localAdvertisement.deviceId,
                        "sessionId" to localAdvertisement.sessionId
                    )
                ))
            } else if (descriptor.uuid == CLIENT_CONFIGURATION_DESCRIPTOR) {
                onError(IllegalStateException("BLE notification descriptor write failed: $status"))
                removeGatt(gatt)
                reportConnectionChanged(gatt.device, false)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val packet = characteristic.getValue() ?: return
            receive(gatt.device, packet)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            receive(gatt.device, value)
        }
    }

    private fun removeGatt(gatt: BluetoothGatt) {
        gattClients.remove(gatt.device.address, gatt)
        clientCharacteristics.remove(gatt.device.address)
        assemblers.remove(gatt.device.address)
        clearRespondedBootstrapRequests(gatt.device.address)
        runCatching { gatt.close() }
    }

    private fun reportConnectionChanged(device: BluetoothDevice, connected: Boolean) {
        if (connected || (!serverDevices.contains(device.address) && !gattClients.containsKey(device.address))) {
            onConnectionChanged(device, connected)
        }
    }

    private fun clearRespondedBootstrapRequests(address: String) {
        respondedBootstrapRequests.keys
            .filter { it.startsWith("$address:") }
            .forEach(respondedBootstrapRequests::remove)
    }

    @Suppress("DEPRECATION")
    private fun notifyServerDevice(device: BluetoothDevice, packet: ByteArray) {
        val server = gattServer ?: return
        val characteristic = txCharacteristic ?: return
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, packet)
        } else {
            characteristic.value = packet
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onError(IllegalStateException("BLE notification failed: $status"))
        }
    }

    private data class ClientCharacteristics(
        val rx: BluetoothGattCharacteristic,
        val tx: BluetoothGattCharacteristic
    )

    private companion object {
        const val ASSEMBLER_EXPIRY_INTERVAL_MS = 1_000L
        val CLIENT_CONFIGURATION_DESCRIPTOR: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
