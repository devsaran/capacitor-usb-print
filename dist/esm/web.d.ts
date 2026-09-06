import { WebPlugin } from '@capacitor/core';
import type { UsbPrinterInfo, UsbPrintPlugin } from './definitions';
export declare class UsbPrintWeb extends WebPlugin implements UsbPrintPlugin {
    listPrinters(): Promise<{
        printers: UsbPrinterInfo[];
    }>;
    connectPrinter(): Promise<{
        connected: boolean;
    }>;
    disconnectPrinter(): Promise<{
        connected: boolean;
    }>;
    printRaw(): Promise<{
        success: boolean;
    }>;
    getStatus(): Promise<{
        connected: boolean;
        printerName: string;
    }>;
    printReceipt(): Promise<{
        success: boolean;
    }>;
}
