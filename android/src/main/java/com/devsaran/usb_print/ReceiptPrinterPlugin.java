package com.devsaran.usb_print;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Locale;

/**
 * Native receipt-printer plugin (Android) — USB ESC/POS for an 80mm printer.
 *
 * The target printer enumerates as a standard USB printer-class device
 * (interface class 7) with a bulk-OUT endpoint, so we open it, claim the
 * interface, and write raw ESC/POS bytes. Receipts are laid out for 80mm paper
 * (48 columns, Font A). USB access permission is requested on demand.
 */
@CapacitorPlugin(name = "ReceiptPrinter")
public class ReceiptPrinterPlugin extends Plugin {

    private static final String ACTION_USB_PERMISSION = "com.devsaran.usb_print.USB_PERMISSION";

    /** Star Micronics USB vendor id — see the fallback note in findDevice(). */
    private static final int STAR_MICRONICS_VID = 0x0519;
    private static final Charset RECEIPT_CHARSET = Charset.forName("US-ASCII");
    private static final int LINE_WIDTH = 48; // Font A on 80mm paper

    private UsbDeviceConnection connection;
    private UsbInterface claimedInterface;
    private UsbEndpoint bulkOut;
    private int connectedVid = -1;
    private int connectedPid = -1;
    private String connectedName = "";

    private BroadcastReceiver permissionReceiver;
    private PluginCall pendingPermissionCall;

    private UsbManager usbManager() {
        return (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
    }

    // ----------------------------------------------------------------- listing

    @PluginMethod
    public void listPrinters(PluginCall call) {
        JSArray printers = new JSArray();
        UsbManager manager = usbManager();
        if (manager != null) {
            for (UsbDevice device : manager.getDeviceList().values()) {
                printers.put(describeDevice(device));
            }
        }
        JSObject ret = new JSObject();
        ret.put("printers", printers);
        call.resolve(ret);
    }

    private JSObject describeDevice(UsbDevice device) {
        String vid = hex4(device.getVendorId());
        String pid = hex4(device.getProductId());

        StringBuilder name = new StringBuilder();
        String product = safeProductName(device);
        if (product != null && !product.isEmpty()) {
            name.append(product).append(" ");
        }
        name.append("VID:").append(vid).append(" PID:").append(pid).append(" devCls:").append(device.getDeviceClass());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            name.append(" | if").append(i).append(" cls:").append(usbInterface.getInterfaceClass());
            for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(e);
                String dir = endpoint.getDirection() == UsbConstants.USB_DIR_OUT ? "OUT" : "IN";
                String type = endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk" : "type" + endpoint.getType();
                name.append(" ep").append(endpoint.getEndpointNumber()).append("/").append(dir).append("/").append(type);
            }
        }

        JSObject printer = new JSObject();
        printer.put("deviceId", vid + ":" + pid); // stable id (survives re-plug)
        printer.put("name", name.toString());
        printer.put("connected", isConnectedTo(device));
        return printer;
    }

    // -------------------------------------------------------------- connecting

    @PluginMethod
    public void connectPrinter(PluginCall call) {
        UsbDevice device = findDevice(call.getString("deviceId", ""));
        UsbManager manager = usbManager();
        if (device == null || manager == null) {
            resolveConnected(call, false);
            return;
        }
        if (manager.hasPermission(device)) {
            resolveConnected(call, openDevice(device));
        } else {
            requestPermission(device, call);
        }
    }

    /**
     * Release the USB device.
     *
     * openDevice() force-claims the interface and, without this, nothing ever
     * released it before the process died — so a StarXpand USB session
     * (star-usb transport) could never open the same bus. The TS transport
     * layer calls this before every Star USB connect. (Byte-parity with the
     * pos_app plugin.)
     */
    @PluginMethod
    public void disconnectPrinter(PluginCall call) {
        closeConnection();
        resolveConnected(call, false);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        boolean connected = connection != null && bulkOut != null;
        JSObject ret = new JSObject();
        ret.put("connected", connected);
        ret.put("printerName", connected ? connectedName : "");
        call.resolve(ret);
    }

    // ---------------------------------------------------------------- printing

    /**
     * Writes caller-supplied raw printer bytes (base64). The kiosk renders
     * ESC/POS documents in TypeScript (PrintDoc → drivers/escpos) and pushes
     * the bytes through here; this plugin stays a dumb byte sink. The legacy
     * printReceipt below (Java-composed layout) is kept ONE release as the
     * rollback path, then deleted. (Byte-parity with the pos_app plugin.)
     */
    @PluginMethod
    public void printRaw(PluginCall call) {
        String data = call.getString("data");
        if (data == null || data.isEmpty()) {
            call.reject("Missing raw print data.");
            return;
        }
        if (!ensureOpen()) {
            call.reject("Printer is not connected. Connect it in printer settings first.");
            return;
        }
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            boolean ok = writeBytes(bytes);
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception ex) {
            call.reject("Raw print failed: " + ex.getMessage());
        }
    }

    @PluginMethod
    public void printReceipt(PluginCall call) {
        JSObject receipt = call.getObject("receipt");
        if (receipt == null) {
            call.reject("Missing receipt data.");
            return;
        }
        if (!ensureOpen()) {
            call.reject("Receipt printer is not connected. Connect it in printer settings first.");
            return;
        }
        try {
            boolean ok = writeBytes(buildEscPos(receipt));
            JSObject ret = new JSObject();
            ret.put("success", ok);
            call.resolve(ret);
        } catch (Exception ex) {
            call.reject("Print failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ usb plumbing

    /**
     * Resolve a device from a "VID:PID" id (preferred), a /dev path, or — when
     * nothing matches — the first attached USB printer-class device.
     */
    private UsbDevice findDevice(String deviceId) {
        UsbManager manager = usbManager();
        if (manager == null) return null;

        Integer wantVid = null;
        Integer wantPid = null;
        if (deviceId != null && deviceId.contains(":") && !deviceId.startsWith("/")) {
            String[] parts = deviceId.split(":");
            try {
                wantVid = Integer.parseInt(parts[0].trim(), 16);
                wantPid = Integer.parseInt(parts[1].trim(), 16);
            } catch (Exception ignored) {
            }
        }

        UsbDevice printerClassFallback = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (wantVid != null && device.getVendorId() == wantVid && device.getProductId() == wantPid) {
                return device;
            }
            if (deviceId != null && deviceId.equals(device.getDeviceName())) {
                return device;
            }
            // Never FALL BACK onto a Star printer. It belongs to the StarXpand
            // path (star-usb transport), and force-claiming it here would both
            // break Star printing and print nothing — the TSP100III series has
            // no text-mode interpreter, so the raw bytes are discarded and the
            // write still "succeeds". An explicit 0519:PID assignment still
            // resolves through the exact-match branch above.
            if (printerClassFallback == null
                    && device.getVendorId() != STAR_MICRONICS_VID
                    && pickInterface(device) != null) {
                printerClassFallback = device;
            }
        }
        return printerClassFallback;
    }

    private boolean openDevice(UsbDevice device) {
        closeConnection();
        UsbManager manager = usbManager();
        if (manager == null) return false;

        UsbInterface usbInterface = pickInterface(device);
        if (usbInterface == null) return false;
        UsbEndpoint out = pickBulkOut(usbInterface);
        if (out == null) return false;

        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) return false;
        if (!conn.claimInterface(usbInterface, true)) {
            conn.close();
            return false;
        }

        connection = conn;
        claimedInterface = usbInterface;
        bulkOut = out;
        connectedVid = device.getVendorId();
        connectedPid = device.getProductId();
        String product = safeProductName(device);
        connectedName = product != null && !product.isEmpty()
                ? product
                : hex4(device.getVendorId()) + ":" + hex4(device.getProductId());
        return true;
    }

    private boolean ensureOpen() {
        if (connection != null && bulkOut != null) return true;
        UsbDevice device = findDevice(null);
        UsbManager manager = usbManager();
        if (device == null || manager == null || !manager.hasPermission(device)) return false;
        return openDevice(device);
    }

    /** Prefer a printer-class interface with a bulk-OUT; else any interface with a bulk-OUT. */
    private UsbInterface pickInterface(UsbDevice device) {
        UsbInterface fallback = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (pickBulkOut(usbInterface) != null) {
                if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                    return usbInterface;
                }
                if (fallback == null) fallback = usbInterface;
            }
        }
        return fallback;
    }

    private UsbEndpoint pickBulkOut(UsbInterface usbInterface) {
        for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(e);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                return endpoint;
            }
        }
        return null;
    }

    private void requestPermission(UsbDevice device, PluginCall call) {
        Context context = getContext();
        if (permissionReceiver == null) {
            permissionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                    UsbDevice granted = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    PluginCall saved = pendingPermissionCall;
                    pendingPermissionCall = null;
                    if (saved == null) return;
                    resolveConnected(saved, ok && granted != null && openDevice(granted));
                }
            };
            ContextCompat.registerReceiver(
                    context,
                    permissionReceiver,
                    new IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        pendingPermissionCall = call;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager().requestPermission(device, pendingIntent);
    }

    private boolean writeBytes(byte[] data) {
        if (connection == null || bulkOut == null) return false;
        int offset = 0;
        final int chunk = 16384;
        while (offset < data.length) {
            int len = Math.min(chunk, data.length - offset);
            byte[] part = Arrays.copyOfRange(data, offset, offset + len);
            int sent = connection.bulkTransfer(bulkOut, part, len, 5000);
            if (sent < 0) return false;
            offset += len;
        }
        return true;
    }

    private void closeConnection() {
        try {
            if (connection != null && claimedInterface != null) {
                connection.releaseInterface(claimedInterface);
            }
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
        connection = null;
        claimedInterface = null;
        bulkOut = null;
        connectedVid = -1;
        connectedPid = -1;
        connectedName = "";
    }

    @Override
    protected void handleOnDestroy() {
        closeConnection();
        if (permissionReceiver != null) {
            try {
                getContext().unregisterReceiver(permissionReceiver);
            } catch (Exception ignored) {
            }
            permissionReceiver = null;
        }
    }

    // ----------------------------------------------------------- esc/pos layout

    /**
     * CROSS-VERSION SAFETY: every field below is read through an {@code opt*}
     * accessor and every label through {@link #lbl} (English default), so the
     * TS bundle and this plugin can disagree in either direction:
     *   - old plugin + new ReceiptData: the extra fields are simply never read.
     *   - new plugin + old ReceiptData: fiscalLines / adjustments / tip are all
     *     absent, every optional block is skipped, and the emitted bytes are
     *     IDENTICAL to the pre-2026-07-23 layout.
     * Keep it that way — never make an optional field load-bearing.
     */
    private byte[] buildEscPos(JSObject receipt) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Brand currency symbol for the money rows (already ASCII-safe on the
        // TS side — the layer below is US-ASCII). Absent → "$".
        currencySymbol = receipt.optString("currencySymbol", "$");
        if (currencySymbol == null || currencySymbol.isEmpty()) currencySymbol = "$";

        // Localized fixed labels (TS folds them to ASCII). Absent → English.
        JSONObject labels = receipt.optJSONObject("labels");

        out.write(new byte[]{0x1B, 0x40}); // ESC @ — initialize

        // Logo — best-effort raster at the very top.
        String logo = receipt.optString("logoBase64", "");
        if (!logo.isEmpty()) {
            writeLogo(out, logo);
        }

        JSONObject header = receipt.optJSONObject("header");
        String storeName = header != null ? header.optString("storeName", "Restaurant") : "Restaurant";
        align(out, 1); // center

        // Order number — large + bold.
        bold(out, true);
        tall(out, true);
        text(out, lbl(labels, "orderPrefix", "Order #") + receipt.optString("orderNumber", ""));
        tall(out, false);
        bold(out, false);

        text(out, storeName);
        if (header != null) {
            String addr = header.optString("storeAddress", "");
            if (!addr.isEmpty()) text(out, addr);
        }

        // Optional fiscal identifiers ("Tax ID: RO12345678") — composed,
        // localized and ASCII-folded on the TS side, printed centered here.
        JSONArray fiscalLines = receipt.optJSONArray("fiscalLines");
        if (fiscalLines != null) {
            for (int i = 0; i < fiscalLines.length(); i++) {
                String fiscalLine = fiscalLines.optString(i, "");
                if (!fiscalLine.isEmpty()) text(out, fiscalLine);
            }
        }

        text(out, receipt.optString("dateTime", ""));
        divider(out);
        align(out, 0); // left

        JSONArray items = receipt.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                bold(out, true);
                tall(out, true);
                text(out, item.optInt("quantity", 1) + "x " + item.optString("name", "Item"));
                tall(out, false);
                bold(out, false);
                JSONArray mods = item.optJSONArray("modifiers");
                if (mods != null) {
                    fontB(out, true);
                    for (int m = 0; m < mods.length(); m++) {
                        text(out, "  " + mods.optString(m, ""));
                    }
                    fontB(out, false);
                }
                text(out, leftRight("", money(item.optDouble("totalPrice", 0))));
            }
        }

        divider(out);
        text(out, leftRight(lbl(labels, "subtotal", "Subtotal"), money(receipt.optDouble("subtotal", 0))));

        // Packaging fee / named charges / discounts the server already
        // computed. `amount` is a signed, pre-formatted, ASCII-folded string
        // (e.g. "-EUR2.50") — print it VERBATIM; currency logic stays in TS.
        JSONArray adjustments = receipt.optJSONArray("adjustments");
        if (adjustments != null) {
            for (int i = 0; i < adjustments.length(); i++) {
                JSONObject adjustment = adjustments.optJSONObject(i);
                if (adjustment == null) continue;
                String adjustmentLabel = adjustment.optString("label", "");
                String adjustmentAmount = adjustment.optString("amount", "");
                if (adjustmentLabel.isEmpty() && adjustmentAmount.isEmpty()) continue;
                text(out, leftRight(adjustmentLabel, adjustmentAmount));
            }
        }

        text(out, leftRight(lbl(labels, "tax", "Tax"), money(receipt.optDouble("tax", 0))));

        // VAT contained in the item prices (ReceiptData.taxInclusive) —
        // informational row after Tax, never added to the payable. Absent or
        // zero prints NOTHING, so an old bundle's output stays byte-identical
        // (tip/fiscalLines cross-version precedent).
        double taxInclusive = receipt.optDouble("taxInclusive", 0);
        if (taxInclusive > 0) {
            text(out, leftRight(lbl(labels, "inclTax", "Incl. tax"), money(taxInclusive)));
        }

        // Gratuity — a real aligned row now (it used to be prose in the footer).
        double tip = receipt.optDouble("tip", 0);
        if (tip > 0) {
            text(out, leftRight(lbl(labels, "tip", "Tip"), money(tip)));
        }

        bold(out, true);
        text(out, leftRight(lbl(labels, "total", "Total"), money(receipt.optDouble("total", 0))));
        bold(out, false);
        text(out, lbl(labels, "payment", "Payment") + ": " + receipt.optString("paymentMethod", ""));

        JSONObject loyalty = receipt.optJSONObject("loyalty");
        divider(out);
        if (loyalty != null) {
            // Member — show the points balance after this order, prominently.
            align(out, 1);
            text(out, loyalty.optString("customerName", ""));
            int earned = (int) Math.round(loyalty.optDouble("pointsEarned", 0));
            text(out, lbl(labels, "pointsEarned", "Points earned this visit") + ": +" + String.format(Locale.US, "%,d", earned));
            bold(out, true);
            tall(out, true);
            int balance = (int) Math.round(loyalty.optDouble("pointsBalance", 0));
            text(out, lbl(labels, "pointsBalance", "Points balance") + ": " + String.format(Locale.US, "%,d", balance));
            tall(out, false);
            bold(out, false);
            align(out, 0);
        } else {
            // Guest — nudge them to sign up for loyalty next time.
            align(out, 1);
            bold(out, true);
            text(out, lbl(labels, "guestEarnTitle", "Earn points on your next order!"));
            bold(out, false);
            text(out, lbl(labels, "guestSignupLine1", "Sign up for " + storeName + " Rewards"));
            text(out, lbl(labels, "guestSignupLine2", "to earn points on every visit."));
            align(out, 0);
        }

        JSONObject footer = receipt.optJSONObject("footer");
        if (footer != null && !footer.optString("message", "").isEmpty()) {
            divider(out);
            align(out, 1);
            text(out, footer.optString("message"));
            align(out, 0);
        }

        out.write(new byte[]{0x1B, 0x64, 0x04});       // ESC d 4 — feed 4 lines
        out.write(new byte[]{0x1D, 0x56, 0x42, 0x00}); // GS V 66 0 — feed + partial cut
        return out.toByteArray();
    }

    private void text(ByteArrayOutputStream out, String value) throws Exception {
        out.write(value.getBytes(RECEIPT_CHARSET));
        out.write(0x0A);
    }

    private void align(ByteArrayOutputStream out, int mode) throws Exception {
        out.write(new byte[]{0x1B, 0x61, (byte) mode}); // ESC a n (0 left, 1 center, 2 right)
    }

    private void bold(ByteArrayOutputStream out, boolean on) throws Exception {
        out.write(new byte[]{0x1B, 0x45, (byte) (on ? 1 : 0)}); // ESC E n
    }

    private void tall(ByteArrayOutputStream out, boolean on) throws Exception {
        out.write(new byte[]{0x1D, 0x21, (byte) (on ? 0x01 : 0x00)}); // GS ! n — double height
    }

    private void fontB(ByteArrayOutputStream out, boolean on) throws Exception {
        out.write(new byte[]{0x1B, 0x4D, (byte) (on ? 1 : 0)}); // ESC M — Font A (0) / Font B (1)
    }

    /**
     * Decode a base64 image, scale it to the print head, threshold it to 1-bit,
     * and emit it as an ESC/POS raster (GS v 0). Best-effort: any failure leaves
     * the text header to stand on its own.
     */
    private void writeLogo(ByteArrayOutputStream out, String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (src == null) return;

            final int maxWidth = 384;  // dots
            final int maxHeight = 240; // dots
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) return;

            int targetW = Math.min(maxWidth, w);
            int targetH = Math.max(1, Math.round(h * (targetW / (float) w)));
            if (targetH > maxHeight) {
                targetH = maxHeight;
                targetW = Math.max(1, Math.round(w * (targetH / (float) h)));
            }
            Bitmap scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true);

            int bytesPerRow = (targetW + 7) / 8;
            byte[] raster = new byte[bytesPerRow * targetH];
            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    int px = scaled.getPixel(x, y);
                    int a = (px >>> 24) & 0xFF;
                    int r = (px >>> 16) & 0xFF;
                    int g = (px >>> 8) & 0xFF;
                    int b = px & 0xFF;
                    float alpha = a / 255f;
                    // Composite over white, then luminance threshold to black/white.
                    int lum = Math.round((r * 0.299f + g * 0.587f + b * 0.114f) * alpha + 255f * (1f - alpha));
                    if (lum < 128) {
                        raster[y * bytesPerRow + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                    }
                }
            }

            align(out, 1); // center (honored by most ESC/POS printers for rasters)
            out.write(0x1D);
            out.write(0x76);
            out.write(0x30);
            out.write(0x00); // GS v 0, m = 0 (normal)
            out.write(bytesPerRow & 0xFF);
            out.write((bytesPerRow >> 8) & 0xFF);
            out.write(targetH & 0xFF);
            out.write((targetH >> 8) & 0xFF);
            out.write(raster);
            out.write(0x0A); // advance past the image
            align(out, 0);
        } catch (Exception ignored) {
            // Logo is best-effort; the text header still prints.
        }
    }

    private void divider(ByteArrayOutputStream out) throws Exception {
        char[] dashes = new char[LINE_WIDTH];
        Arrays.fill(dashes, '-');
        text(out, new String(dashes));
    }

    private String leftRight(String left, String right) {
        int pad = LINE_WIDTH - left.length() - right.length();
        if (pad < 1) pad = 1;
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.append(right).toString();
    }

    /** Set per print from ReceiptData.currencySymbol; defaults to "$". */
    private String currencySymbol = "$";

    private String money(double value) {
        return currencySymbol + String.format(Locale.US, "%.2f", value);
    }

    /** Localized label from ReceiptData.labels with an English default. */
    private String lbl(JSONObject labels, String key, String fallback) {
        if (labels == null) return fallback;
        String value = labels.optString(key, "");
        return value == null || value.isEmpty() ? fallback : value;
    }

    // ----------------------------------------------------------------- helpers

    private boolean isConnectedTo(UsbDevice device) {
        return connection != null && bulkOut != null
                && device.getVendorId() == connectedVid
                && device.getProductId() == connectedPid;
    }

    private void resolveConnected(PluginCall call, boolean connected) {
        JSObject ret = new JSObject();
        ret.put("connected", connected);
        call.resolve(ret);
    }

    private String hex4(int value) {
        return String.format(Locale.US, "%04X", value);
    }

    private String safeProductName(UsbDevice device) {
        try {
            return device.getProductName();
        } catch (Exception ignored) {
            return null;
        }
    }
}
