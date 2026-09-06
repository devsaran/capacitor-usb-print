import Foundation
import Capacitor

@objc(ReceiptPrinterPlugin)
public class ReceiptPrinterPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ReceiptPrinterPlugin"
    public let jsName = "ReceiptPrinter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "listPrinters", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connectPrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnectPrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "printRaw", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "printReceipt", returnType: CAPPluginReturnPromise)
    ]

    private func reject(_ call: CAPPluginCall) {
        call.reject("USB printing is not supported on iOS.", "NOT_SUPPORTED")
    }

    @objc func listPrinters(_ call: CAPPluginCall) { reject(call) }
    @objc func connectPrinter(_ call: CAPPluginCall) { reject(call) }
    @objc func disconnectPrinter(_ call: CAPPluginCall) { reject(call) }
    @objc func printRaw(_ call: CAPPluginCall) { reject(call) }
    @objc func getStatus(_ call: CAPPluginCall) { reject(call) }
    @objc func printReceipt(_ call: CAPPluginCall) { reject(call) }
}
