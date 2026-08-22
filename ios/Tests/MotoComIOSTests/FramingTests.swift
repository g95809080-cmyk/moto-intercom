import XCTest
@testable import MotoComIOS

final class FramingTests: XCTestCase {
    func testLengthPrefixIsBigEndianAndSupportsPartialReads() throws {
        let first = Data("hello".utf8)
        let second = Data("world".utf8)
        let firstFrame = try LengthPrefixedFraming.encode(first)
        let secondFrame = try LengthPrefixedFraming.encode(second)
        let encoded = firstFrame + secondFrame
        var decoder = LengthPrefixedFrameDecoder()
        XCTAssertEqual(try decoder.append(encoded.prefix(2)), [])
        XCTAssertEqual(try decoder.append(encoded[2..<8]), [])
        XCTAssertEqual(try decoder.append(encoded[8...]), [first, second])
        XCTAssertEqual(decoder.bufferedByteCount, 0)
    }

    func testInvalidLengthIsRejected() throws {
        let bytes = Data([0x00, 0x02, 0x00, 0x01])
        var decoder = LengthPrefixedFrameDecoder()
        XCTAssertThrowsError(try decoder.append(bytes))
    }
}
