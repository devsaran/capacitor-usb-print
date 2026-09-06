import { WebPlugin } from '@capacitor/core';

import type { UsbPrinterInfo, UsbPrintPlugin } from './definitions';

function notSupported(): Error & { code: string } {
  return Object.assign(new Error('USB printing is not supported on web.'), { code: 'NOT_SUPPORTED' });
}

export class UsbPrintWeb extends WebPlugin implements UsbPrintPlugin {
  async listPrinters(): Promise<{ printers: UsbPrinterInfo[] }> {
    throw notSupported();
  }

  async connectPrinter(): Promise<{ connected: boolean }> {
    throw notSupported();
  }

  async disconnectPrinter(): Promise<{ connected: boolean }> {
    throw notSupported();
  }

  async printRaw(): Promise<{ success: boolean }> {
    throw notSupported();
  }

  async getStatus(): Promise<{ connected: boolean; printerName: string }> {
    throw notSupported();
  }

  async printReceipt(): Promise<{ success: boolean }> {
    throw notSupported();
  }
}
