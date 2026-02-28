package com.kitchenboard.shopping;

import android.content.Context;
import android.graphics.Bitmap;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Generates an HTML page containing QR codes for the given shopping items
 * and submits it to the Android print system.
 */
public class QrCodePrintHelper {

    /** Paper sizes supported by the print dialog. */
    public enum PaperSize {
        A4, LETTER;

        /** Returns the best default for the current device locale. */
        public static PaperSize forLocale() {
            String country = Locale.getDefault().getCountry().toUpperCase(Locale.US);
            return (country.equals("US") || country.equals("CA")) ? LETTER : A4;
        }

        public PrintAttributes.MediaSize toPrintMediaSize() {
            return this == A4
                    ? PrintAttributes.MediaSize.ISO_A4
                    : PrintAttributes.MediaSize.NA_LETTER;
        }
    }

    private static final int QR_SIZE_PX = 256;

    /** Width in mm of a single QR code cell (image + surrounding padding). */
    private static final int CELL_SIZE_MM = 45;

    private final Context context;
    private WebView printWebView; // must be kept alive until printing starts

    public QrCodePrintHelper(Context context) {
        this.context = context;
    }

    /**
     * Generates a print sheet and opens the system print dialog.
     *
     * @param items      items whose QR codes should be printed
     * @param showLabels whether to print the item name below each QR code
     * @param paperSize  target paper size
     * @param jobName    name shown in the print queue
     */
    public void print(List<ShoppingItem> items, boolean showLabels,
                      PaperSize paperSize, String jobName) {
        String html = buildHtml(items, showLabels, paperSize);

        // WebView must be created on the UI thread; the caller is responsible for that.
        printWebView = new WebView(context);
        printWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                PrintManager printManager =
                        (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
                if (printManager == null) return;

                PrintAttributes.Builder attrsBuilder = new PrintAttributes.Builder()
                        .setMediaSize(paperSize.toPrintMediaSize())
                        .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS);

                printManager.print(jobName,
                        view.createPrintDocumentAdapter(jobName),
                        attrsBuilder.build());
            }
        });
        printWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ── HTML generation ───────────────────────────────────────────────────────

    private String buildHtml(List<ShoppingItem> items, boolean showLabels, PaperSize paperSize) {
        // Page width in mm (content width, margins handled by printer)
        int pageWidthMm = paperSize == PaperSize.A4 ? 210 : 215;
        int cols = Math.max(1, pageWidthMm / CELL_SIZE_MM);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><style>");
        sb.append("body{margin:8mm;font-family:sans-serif;}");
        sb.append("table{border-collapse:collapse;width:100%;}");
        sb.append("td{text-align:center;vertical-align:top;padding:4mm;width:")
          .append(100 / cols).append("%;}");
        sb.append("img{width:35mm;height:35mm;display:block;margin:0 auto;}");
        sb.append(".label{font-size:9pt;margin-top:2mm;word-break:break-word;}");
        sb.append("</style></head><body><table>");

        int col = 0;
        for (ShoppingItem item : items) {
            if (col == 0) sb.append("<tr>");
            sb.append("<td>");
            String dataUri = generateQrDataUri(item);
            if (dataUri != null) {
                sb.append("<img src='").append(dataUri).append("' alt='QR'/>");
            }
            if (showLabels) {
                sb.append("<div class='label'>")
                  .append(escapeHtml(item.getName()))
                  .append("</div>");
            }
            sb.append("</td>");
            col++;
            if (col >= cols) {
                sb.append("</tr>");
                col = 0;
            }
        }
        if (col > 0) {
            // Fill remaining cells
            while (col < cols) {
                sb.append("<td></td>");
                col++;
            }
            sb.append("</tr>");
        }

        sb.append("</table></body></html>");
        return sb.toString();
    }

    /** Generates a QR code bitmap and returns it as a data: URI, or null on error. */
    private String generateQrDataUri(ShoppingItem item) {
        try {
            android.net.Uri uri = new android.net.Uri.Builder()
                    .scheme("kitchenboard")
                    .authority("add")
                    .appendQueryParameter("name", item.getName())
                    .appendQueryParameter("category", item.getCategory())
                    .build();

            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(
                    uri.toString(), BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            return "data:image/png;base64," + b64;
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
