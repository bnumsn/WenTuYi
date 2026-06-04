// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WentuyiApple",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(name: "WentuyiCore", targets: ["WentuyiCore"]),
        .library(name: "WentuyiIOSKeyboard", targets: ["WentuyiIOSKeyboard"]),
        .library(name: "WentuyiMacInputMethod", targets: ["WentuyiMacInputMethod"]),
    ],
    targets: [
        .target(name: "WentuyiCore"),
        .target(name: "WentuyiIOSKeyboard", dependencies: ["WentuyiCore"]),
        .target(name: "WentuyiMacInputMethod", dependencies: ["WentuyiCore"]),
    ]
)
