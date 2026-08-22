// swift-tools-version: 5.9
import PackageDescription

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
    targets: [
        .target(
            name: "MotoComIOS",
            path: "Sources/MotoComIOS"
        ),
        .testTarget(
            name: "MotoComIOSTests",
            dependencies: ["MotoComIOS"],
            path: "Tests/MotoComIOSTests",
            resources: [
                .copy("Fixtures")
            ]
        )
    ]
)
