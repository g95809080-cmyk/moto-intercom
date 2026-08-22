import Foundation

public enum LengthPrefixedFraming {
    public static let maxFrameBytes = SignalingV2Codec.maxFrameBytes

    public static func encode(_ payload: Data) throws -> Data {
        guard !payload.isEmpty, payload.count <= maxFrameBytes else {
            throw MotoComError.invalidFrame("payload bytes=\(payload.count) max=\(maxFrameBytes)")
        }
        var result = Data(capacity: payload.count + 4)
        let length = UInt32(payload.count)
        result.append(UInt8((length >> 24) & 0xFF))
        result.append(UInt8((length >> 16) & 0xFF))
        result.append(UInt8((length >> 8) & 0xFF))
        result.append(UInt8(length & 0xFF))
        result.append(payload)
        return result
    }
}

public struct LengthPrefixedFrameDecoder: Sendable {
    private var buffer = Data()

    public init() {}

    public mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(data)
        var frames = [Data]()
        while true {
            guard buffer.count >= 4 else { break }
            let length = (UInt32(buffer[0]) << 24)
                | (UInt32(buffer[1]) << 16)
                | (UInt32(buffer[2]) << 8)
                | UInt32(buffer[3])
            guard length > 0, length <= UInt32(LengthPrefixedFraming.maxFrameBytes) else {
                throw MotoComError.invalidFrame("invalid length prefix: \(length)")
            }
            let total = 4 + Int(length)
            guard buffer.count >= total else { break }
            frames.append(Data(buffer[4..<total]))
            buffer = Data(buffer.dropFirst(total))
        }
        return frames
    }

    public var bufferedByteCount: Int { buffer.count }
}
