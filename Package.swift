// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Aybinv7CapacitorUsbPrint",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "Aybinv7CapacitorUsbPrint", targets: ["Aybinv7CapacitorUsbPrint"])
    ],
    dependencies: [
        .package(
            url: "https://github.com/ionic-team/capacitor-swift-pm.git",
            exact: "8.4.2"
        )
    ],
    targets: [
        .target(
            name: "Aybinv7CapacitorUsbPrint",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Plugin"
        )
    ]
)
