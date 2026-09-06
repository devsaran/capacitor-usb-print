var capacitorUsbPrint = (function (exports, core) {
    'use strict';

    const UsbPrint = core.registerPlugin('ReceiptPrinter', {
        web: () => Promise.resolve().then(function () { return web; }).then((module) => new module.UsbPrintWeb()),
    });

    function notSupported() {
        return Object.assign(new Error('USB printing is not supported on web.'), { code: 'NOT_SUPPORTED' });
    }
    class UsbPrintWeb extends core.WebPlugin {
        async listPrinters() {
            throw notSupported();
        }
        async connectPrinter() {
            throw notSupported();
        }
        async disconnectPrinter() {
            throw notSupported();
        }
        async printRaw() {
            throw notSupported();
        }
        async getStatus() {
            throw notSupported();
        }
        async printReceipt() {
            throw notSupported();
        }
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        UsbPrintWeb: UsbPrintWeb
    });

    exports.UsbPrint = UsbPrint;
    exports.default = UsbPrint;

    Object.defineProperty(exports, '__esModule', { value: true });

    return exports;

})({}, capacitorExports);
