// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "GhostCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "GhostCore", targets: ["GhostCore"])],
    targets: [
        .target(name: "GhostCore", path: "Sources/GhostCore"),
        .testTarget(name: "GhostCoreTests", dependencies: ["GhostCore"], path: "Tests/GhostCoreTests"),
    ]
)
