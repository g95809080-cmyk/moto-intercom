import Foundation

public enum BLEPermissionState: String, Equatable, Sendable {
    case unknown
    case poweredOff
    case denied
    case restricted
    case available
}

public enum BLEBootstrapState: Equatable, Sendable {
    case idle
    case permissionBlocked(BLEPermissionState)
    case scanning
    case connecting
    case ready
    case failed(String)
    case stopped
}

#if canImport(CoreBluetooth)
import CoreBluetooth

public final class BLEBootstrapCoordinator: NSObject, @unchecked Sendable {
    // These UUIDs are shared with Android's MotoComBleUuids.
    public static let serviceUUIDString = "0000C001-0000-1000-8000-00805F9B34FB"
    public static let rxCharacteristicUUIDString = "0000C002-0000-1000-8000-00805F9B34FB"
    public static let txCharacteristicUUIDString = "0000C003-0000-1000-8000-00805F9B34FB"

    public private(set) var state: BLEBootstrapState = .idle {
        didSet { onStateChanged?(state) }
    }
    public var onStateChanged: ((BLEBootstrapState) -> Void)?
    public var onAnnouncement: ((BootstrapAnnouncement) -> Void)?
    public var onMessage: ((BootstrapMessage) -> Void)?
    public var onError: ((Error) -> Void)?

    private let callbackQueue = DispatchQueue(label: "com.motocom.ble-bootstrap")
    private let serviceUUID = CBUUID(string: BLEBootstrapCoordinator.serviceUUIDString)
    private let rxCharacteristicUUID = CBUUID(string: BLEBootstrapCoordinator.rxCharacteristicUUIDString)
    private let txCharacteristicUUID = CBUUID(string: BLEBootstrapCoordinator.txCharacteristicUUIDString)
    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var localRXCharacteristic: CBMutableCharacteristic?
    private var localTXCharacteristic: CBMutableCharacteristic?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?
    private var connectedPeripheral: CBPeripheral?
    private var subscribedCentrals = [UUID: CBCentral]()
    private var announcement: BootstrapAnnouncement?
    private var reassembler = BLEReassembler()
    private var pendingCentralWrites = [Data]()
    private var pendingPeripheralNotifications = [Data]()
    private var reassemblyExpiryTimer: DispatchSourceTimer?

    public override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: callbackQueue)
        peripheralManager = CBPeripheralManager(delegate: self, queue: callbackQueue)
    }

    public func start(announcement: BootstrapAnnouncement) {
        self.announcement = announcement
        guard central.state == .poweredOn, peripheralManager.state == .poweredOn else {
            state = .permissionBlocked(permissionState())
            return
        }
        state = .scanning
        startReassemblyExpiryTimer()
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "MotoCom-\(announcement.deviceID.prefix(8))"
        ])
        publishServiceIfNeeded()
    }

    public func stop() {
        reassemblyExpiryTimer?.cancel()
        reassemblyExpiryTimer = nil
        central.stopScan()
        if let connectedPeripheral { central.cancelPeripheralConnection(connectedPeripheral) }
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        connectedPeripheral = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
        subscribedCentrals.removeAll()
        pendingCentralWrites.removeAll()
        pendingPeripheralNotifications.removeAll()
        localRXCharacteristic = nil
        localTXCharacteristic = nil
        state = .stopped
    }

    public func send(_ message: BootstrapMessage) throws {
        let data = try BootstrapCodec.encode(message)
        if let peripheral = connectedPeripheral, let characteristic = writeCharacteristic {
            let maximum = peripheral.maximumWriteValueLength(for: .withoutResponse)
            guard maximum > BLEFragmenter.headerBytes else {
                throw MotoComError.invalidFrame("BLE write MTU is too small")
            }
            let chunks = try BLEFragmenter.fragment(
                data,
                messageID: message.requestID,
                maxPacketBytes: min(BLEFragmenter.defaultMaximumPacketBytes, maximum)
            )
            pendingCentralWrites.append(contentsOf: try chunks.map(BLEChunkWireCodec.encode))
            flushCentralWrites()
            return
        }

        guard localTXCharacteristic != nil, !subscribedCentrals.isEmpty else {
            throw MotoComError.unavailable("BLE GATT characteristic is not ready")
        }
        let chunks = try BLEFragmenter.fragment(data, messageID: message.requestID)
        pendingPeripheralNotifications.append(contentsOf: try chunks.map(BLEChunkWireCodec.encode))
        flushPeripheralNotifications()
    }

    private func publishServiceIfNeeded() {
        guard localTXCharacteristic == nil else { return }
        let rx = CBMutableCharacteristic(
            type: rxCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let tx = CBMutableCharacteristic(
            type: txCharacteristicUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [rx, tx]
        localRXCharacteristic = rx
        localTXCharacteristic = tx
        peripheralManager.add(service)
    }

    private func consume(_ data: Data) {
        var assembledData: Data?
        do {
            let chunk = try BLEChunkWireCodec.decode(data)
            guard let messageData = reassembler.append(chunk) else { return }
            assembledData = messageData
            let message = try BootstrapCodec.decode(messageData)
            if let announcement = message.announcement { onAnnouncement?(announcement) }
            onMessage?(message)
            if message.type == .request, announcement != nil {
                try send(BootstrapMessage(type: .capabilities, announcement: announcement))
            }
        } catch {
            // A peer may use the Android read-only compact advertisement path.
            if let announcement = try? BootstrapCodec.decodeAdvertisement(assembledData ?? data) {
                onAnnouncement?(announcement)
                return
            }
            onError?(error)
            state = .failed(error.localizedDescription)
        }
    }

    private func sendBootstrapRequestIfPossible() {
        guard writeCharacteristic != nil else { return }
        do {
            try send(BootstrapMessage(type: .request))
        } catch {
            onError?(error)
        }
    }

    private func startReassemblyExpiryTimer() {
        reassemblyExpiryTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: callbackQueue)
        timer.schedule(deadline: .now() + .seconds(1), repeating: .seconds(1))
        timer.setEventHandler { [weak self] in
            self?.reassembler.expire()
        }
        timer.resume()
        reassemblyExpiryTimer = timer
    }

    private func permissionState() -> BLEPermissionState {
        switch central.state {
        case .poweredOn: return .available
        case .poweredOff: return .poweredOff
        case .unauthorized: return .denied
        case .unsupported: return .restricted
        default: return .unknown
        }
    }
}

extension BLEBootstrapCoordinator: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            state = .permissionBlocked(permissionState())
            return
        }
        if announcement != nil { state = .scanning }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard connectedPeripheral == nil else { return }
        connectedPeripheral = peripheral
        peripheral.delegate = self
        state = .connecting
        central.connect(peripheral)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        state = .connecting
        peripheral.discoverServices([serviceUUID])
    }

    public func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        state = .failed(error?.localizedDescription ?? "BLE GATT connection failed")
        onError?(error ?? MotoComError.unavailable("BLE GATT connection failed"))
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        connectedPeripheral = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
        pendingCentralWrites.removeAll()
        if let error { onError?(error) }
        guard announcement != nil else { state = .stopped; return }
        state = .scanning
        central.scanForPeripherals(withServices: [serviceUUID])
    }
}

extension BLEBootstrapCoordinator: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { onError?(error); state = .failed(error.localizedDescription); return }
        peripheral.services?.filter { $0.uuid == serviceUUID }.forEach {
            peripheral.discoverCharacteristics([rxCharacteristicUUID, txCharacteristicUUID], for: $0)
        }
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        if let error { onError?(error); state = .failed(error.localizedDescription); return }
        guard let rx = service.characteristics?.first(where: { $0.uuid == rxCharacteristicUUID }),
              let tx = service.characteristics?.first(where: { $0.uuid == txCharacteristicUUID }) else {
            state = .failed("MotoCom BLE RX/TX characteristics missing")
            return
        }
        writeCharacteristic = rx
        notifyCharacteristic = tx
        peripheral.setNotifyValue(true, for: tx)
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error { onError?(error); state = .failed(error.localizedDescription); return }
        guard characteristic.uuid == txCharacteristicUUID, characteristic.isNotifying else {
            state = .failed("MotoCom BLE TX notification is unavailable")
            return
        }
        state = .ready
        sendBootstrapRequestIfPossible()
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error { onError?(error); return }
        if let data = characteristic.value { consume(data) }
    }

    public func peripheralIsReady(toWriteWithoutResponse peripheral: CBPeripheral) {
        flushCentralWrites()
    }
}

extension BLEBootstrapCoordinator: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else {
            state = .permissionBlocked(permissionState())
            return
        }
        if announcement != nil { publishServiceIfNeeded() }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveRead request: CBATTRequest
    ) {
        guard request.characteristic.uuid == txCharacteristicUUID, let announcement else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        do {
            let data = try BootstrapCodec.encodeAdvertisement(announcement)
            guard request.offset <= data.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = Data(data.dropFirst(request.offset))
            peripheral.respond(to: request, withResult: .success)
        } catch {
            onError?(error)
            peripheral.respond(to: request, withResult: .unlikelyError)
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests where request.characteristic.uuid == rxCharacteristicUUID {
            if let value = request.value { consume(value) }
        }
        requests.forEach { peripheral.respond(to: $0, withResult: .success) }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        guard characteristic.uuid == txCharacteristicUUID else { return }
        subscribedCentrals[central.identifier] = central
        state = .ready
        flushPeripheralNotifications()
        if announcement != nil {
            do {
                try send(BootstrapMessage(type: .capabilities, announcement: announcement))
            } catch { onError?(error) }
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        subscribedCentrals.removeValue(forKey: central.identifier)
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPeripheralNotifications()
    }
}

private extension BLEBootstrapCoordinator {
    func flushCentralWrites() {
        guard let peripheral = connectedPeripheral,
              let characteristic = writeCharacteristic else { return }
        while peripheral.canSendWriteWithoutResponse, !pendingCentralWrites.isEmpty {
            peripheral.writeValue(
                pendingCentralWrites.removeFirst(),
                for: characteristic,
                type: .withoutResponse
            )
        }
    }

    func flushPeripheralNotifications() {
        guard let characteristic = localTXCharacteristic,
              !subscribedCentrals.isEmpty else { return }
        let centrals = Array(subscribedCentrals.values)
        while !pendingPeripheralNotifications.isEmpty {
            let packet = pendingPeripheralNotifications[0]
            guard peripheralManager.updateValue(
                packet,
                for: characteristic,
                onSubscribedCentrals: centrals
            ) else { return }
            pendingPeripheralNotifications.removeFirst()
        }
    }
}

#else

public final class BLEBootstrapCoordinator {
    public init() {}
    public func start(announcement: BootstrapAnnouncement) {}
    public func stop() {}
    public func send(_ message: BootstrapMessage) throws {
        throw MotoComError.unavailable("CoreBluetooth is only available on Apple platforms")
    }
}

#endif
