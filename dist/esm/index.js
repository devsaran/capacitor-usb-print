import { registerPlugin } from '@capacitor/core';
const UsbPrint = registerPlugin('ReceiptPrinter', {
    web: () => import('./web').then((module) => new module.UsbPrintWeb()),
});
export * from './definitions';
export { UsbPrint };
export default UsbPrint;
