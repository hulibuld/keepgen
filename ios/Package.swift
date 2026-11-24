// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "KeepGen",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(name: "KeepGenCore", targets: ["KeepGenCore"]),
        .library(name: "KeepGenUI", targets: ["KeepGenUI"])
    ],
    targets: [
        .target(
            name: "KeepGenCore",
            path: "Sources/KeepGenCore"
        ),
        .target(
            name: "KeepGenUI",
            dependencies: ["KeepGenCore"],
            path: "Sources/KeepGenUI"
        )
    ]
)