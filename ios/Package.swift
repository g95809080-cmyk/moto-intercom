// swift-tools-version: 5.9
import PackageDescription
import Foundation

// Explicit validation only. Default/product builds do not gain an SDK binary.
let nativeValidation = ProcessInfo.processInfo.environment["MOTOCOM_NATIVE_WEBRTC_CI"] == "1"
let nativeTargets: [Target] = nativeValidation ? [
    .binaryTarget(name: "WebRTC",
        url: "https://github.com/stasel/WebRTC/releases/download/125.0.0/WebRTC-M125.xcframework.zip",
        checksum: "4da2f9c7b7481a82249ea61dfcceae7caac6890e0a0412389684765435113ae8")
] : []

let package = Package(
    name: "MotoComIOS",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "MotoComIOS",
            targets: ["MotoComIOS"]
        )
    ],
    targets: nativeTargets + [
        .target(
            name: "MotoComIOS",
            dependencies: nativeValidation ? ["WebRTC"] : [],
            path: "Sources/MotoComIOS",
            swiftSettings: nativeValidation ? [.define("MOTOCOM_REQUIRE_NATIVE_WEBRTC")] : []
        ),
        .testTarget(
            name: "MotoComIOSTests",
            dependencies: nativeValidation ? ["MotoComIOS", "WebRTC"] : ["MotoComIOS"],
            path: "Tests/MotoComIOSTests",
            resources: [
                .copy("Fixtures")
            ],
            swiftSettings: nativeValidation ? [.define("MOTOCOM_REQUIRE_NATIVE_WEBRTC")] : []
        )
    ]
)
