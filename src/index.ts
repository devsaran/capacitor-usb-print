import { registerPlugin } from '@capacitor/core';

import type { UsbPrintPlugin } from './definitions';

const UsbPrint = registerPlugin<UsbPrintPlugin>('ReceiptPrinter', {
  web: () => import('./web').then((module) => new module.UsbPrintWeb()),
});

export * from './definitions';
export { UsbPrint };
export default UsbPrint;
