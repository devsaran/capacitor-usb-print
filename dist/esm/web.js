import { WebPlugin } from '@capacitor/core';
function notSupported() {
    return Object.assign(new Error('USB printing is not supported on web.'), { code: 'NOT_SUPPORTED' });
}
export class UsbPrintWeb extends WebPlugin {
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
