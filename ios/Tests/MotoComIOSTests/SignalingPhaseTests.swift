import XCTest
@testable import MotoComIOS

final class SignalingPhaseTests: XCTestCase {
    private func hello(_ role: RequestRole) -> SignalingMessage {
        .hello(requestRole: role, nickname: "rider", deviceName: "phone", capabilities: ["LAN"])
    }

    func testRequesterAndResponderFollowAndroidWireOrder() throws {
        var requester = SignalingPhaseMachine(initialRequestRole: .requester)
        try requester.onFrame(direction: .outbound, message: hello(.requester))
        try requester.onFrame(direction: .inbound, message: hello(.responder))
        try requester.onFrame(
            direction: .outbound,
            message: .connectRequest(trigger: .user, preferredTransportHint: .lan)
        )
        try requester.onFrame(
            direction: .inbound,
            message: .connectAccept(nickname: "rider", deviceName: "phone")
        )
        try requester.onFrame(direction: .outbound, message: .offer(sdpJSON: "{}"))
        try requester.onFrame(direction: .inbound, message: .answer(sdpJSON: "{}"))
        XCTAssertEqual(requester.phase, .mediaNegotiating)
        try requester.markConnected()
        XCTAssertEqual(requester.phase, .connected)
    }

    func testResponderWaitsForRequesterHello() throws {
        var responder = SignalingPhaseMachine(initialRequestRole: nil)
        XCTAssertThrowsError(try responder.onFrame(direction: .outbound, message: hello(.responder)))
        try responder.onFrame(direction: .inbound, message: hello(.requester))
        try responder.onFrame(direction: .outbound, message: hello(.responder))
        try responder.onFrame(
            direction: .inbound,
            message: .connectRequest(trigger: .user, preferredTransportHint: .lan)
        )
        try responder.onFrame(
            direction: .outbound,
            message: .connectAccept(nickname: "rider", deviceName: "phone")
        )
        try responder.onFrame(direction: .inbound, message: .offer(sdpJSON: "{}"))
        try responder.onFrame(direction: .outbound, message: .answer(sdpJSON: "{}"))
        XCTAssertEqual(responder.phase, .mediaNegotiating)
    }

    func testInvalidFrameCannotAdvancePhase() throws {
        var machine = SignalingPhaseMachine(initialRequestRole: .requester)
        XCTAssertThrowsError(try machine.onFrame(
            direction: .inbound,
            message: .connectAccept(nickname: "rider", deviceName: "phone")
        ))
        XCTAssertEqual(machine.phase, .readyToSendRequesterHello)
    }

    func testGlareCanResolveToResponderUsingAndroidWireOrder() throws {
        var machine = SignalingPhaseMachine(initialRequestRole: .requester)
        try machine.onFrame(direction: .outbound, message: hello(.requester))
        try machine.onFrame(direction: .inbound, message: hello(.requester))
        XCTAssertEqual(machine.phase, .glarePending)

        try machine.resolveGlare(localRequestWins: false)
        XCTAssertEqual(machine.requestRole, .responder)
        XCTAssertEqual(machine.phase, .readyToSendResponderHello)
        try machine.onFrame(direction: .outbound, message: hello(.responder))
        XCTAssertEqual(machine.phase, .awaitingConnectRequest)
    }
}
