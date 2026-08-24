// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SwiftTestHarness",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(name: "RegexSyntax", path: "../build/SPMPackage/macosArm64/Debug")
    ],
    targets: [
        .executableTarget(
            name: "SwiftTestHarnessTests",
            dependencies: [
                .product(name: "RegexSyntaxLibrary", package: "RegexSyntax")
            ],
            path: "Tests/SwiftTestHarnessTests",
            linkerSettings: [
                .unsafeFlags([
                    "-L", "../build/swift-test",
                    "-lRegexSyntax",
                ]),
            ]
        ),
    ]
)
