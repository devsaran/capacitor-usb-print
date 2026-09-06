export interface UsbPrinterInfo {
  deviceId: string;
  name: string;
  connected: boolean;
}

export interface UsbPrintPlugin {
  listPrinters(): Promise<{ printers: UsbPrinterInfo[] }>;
  connectPrinter(options: { deviceId: string }): Promise<{ connected: boolean }>;
  disconnectPrinter(): Promise<{ connected: boolean }>;
  printRaw(options: { data: string }): Promise<{ success: boolean }>;
  getStatus(): Promise<{ connected: boolean; printerName: string }>;

  /** Existing kiosk compatibility surface; new app paths use printRaw. */
  printReceipt(options: { receipt: unknown }): Promise<{ success: boolean }>;
}
