import Foundation

public actor PairingStore {
    private let storage: UserDefaults
    private let storageKey: String
    private var records: [String: PairingRecord]

    public init(
        storage: UserDefaults = .standard,
        storageKey: String = "com.motocom.paired-peers"
    ) {
        self.storage = storage
        self.storageKey = storageKey
        if let data = storage.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([PairingRecord].self, from: data) {
            self.records = decoded.reduce(into: [String: PairingRecord]()) { result, record in
                result[record.remoteDeviceID] = record
            }
        } else {
            self.records = [:]
        }
    }

    public func all() -> [PairingRecord] {
        records.values.sorted { $0.lastConnectedAt > $1.lastConnectedAt }
    }

    public func record(for deviceID: String) -> PairingRecord? {
        records[deviceID]
    }

    public func saveConnectedPeer(
        _ record: PairingRecord,
        audioReady: Bool,
        transport: String
    ) throws {
        guard audioReady else { throw MotoComError.notAudioReady }
        let updated: PairingRecord
        if let existing = records[record.remoteDeviceID] {
            updated = try PairingRecord(
                remoteDeviceID: record.remoteDeviceID,
                remoteNickname: record.remoteNickname,
                deviceName: record.deviceName,
                localAlias: existing.localAlias,
                shortCode: existing.shortCode,
                pairedAt: existing.pairedAt,
                lastConnectedAt: Date(),
                isPreferred: existing.isPreferred,
                lastTransport: transport,
                failureCount: existing.failureCount
            )
        } else {
            var firstRecord = record
            firstRecord.lastTransport = transport
            firstRecord.lastConnectedAt = Date()
            updated = firstRecord
        }
        records[record.remoteDeviceID] = updated
        try persist()
    }

    public func setPreferred(deviceID: String, preferred: Bool) {
        guard var record = records[deviceID] else { return }
        if preferred {
            for key in records.keys {
                records[key]?.isPreferred = false
            }
        }
        record.isPreferred = preferred
        records[deviceID] = record
        try? persist()
    }

    public func forget(deviceID: String) {
        records.removeValue(forKey: deviceID)
        try? persist()
    }

    private func persist() throws {
        guard let data = try? JSONEncoder().encode(Array(records.values)) else {
            throw MotoComError.storageFailure("could not encode pairing records")
        }
        storage.set(data, forKey: storageKey)
    }
}
