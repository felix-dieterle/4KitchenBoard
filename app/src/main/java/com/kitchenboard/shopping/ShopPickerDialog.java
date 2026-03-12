package com.kitchenboard.shopping;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.kitchenboard.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dialog that lets the user search for a shop using the OpenStreetMap Nominatim API
 * and shows results on a Leaflet.js map. The selected shop name and coordinates are
 * returned via callback.
 */
public class ShopPickerDialog {

    private static final String TAG = "ShopPickerDialog";

    public interface OnShopSelectedListener {
        /** Called when the user confirms a shop selection.
         *
         * @param shopName  Display name of the selected shop.
         * @param latitude  WGS-84 latitude of the selected location.
         * @param longitude WGS-84 longitude of the selected location.
         */
        void onShopSelected(String shopName, double latitude, double longitude);
    }

    private final Context context;
    private final OnShopSelectedListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private WebView webView;
    private TextView tvSelectedShop;
    private String selectedShop = null;
    private double selectedLat  = 0.0;
    private double selectedLon  = 0.0;
    private AlertDialog dialog;

    public ShopPickerDialog(Context context, OnShopSelectedListener listener) {
        this.context = context;
        this.listener = listener;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void show() {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_shop_picker, null);

        final EditText etSearch = view.findViewById(R.id.et_shop_search);
        Button btnSearch = view.findViewById(R.id.btn_shop_search);
        tvSelectedShop = view.findViewById(R.id.tv_selected_shop);
        webView = view.findViewById(R.id.wv_map);

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new MapJsInterface(), "Android");
        webView.loadUrl("file:///android_asset/map.html");

        // Search button
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String query = etSearch.getText().toString().trim();
                if (!query.isEmpty()) {
                    performSearch(query);
                }
            }
        });

        // Allow searching by pressing the search key on the keyboard
        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    String query = etSearch.getText().toString().trim();
                    if (!query.isEmpty()) {
                        performSearch(query);
                    }
                    return true;
                }
                return false;
            }
        });

        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.shop_search_title)
                .setView(view)
                .setPositiveButton(R.string.shop_select, (d, which) -> {
                    if (selectedShop != null && listener != null) {
                        listener.onShopSelected(selectedShop, selectedLat, selectedLon);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.show();

        // Expand dialog to fill most of the screen so the map is usable
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void performSearch(final String query) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String encodedQuery = URLEncoder.encode(query, "UTF-8");
                    String urlStr = "https://nominatim.openstreetmap.org/search"
                            + "?q=" + encodedQuery
                            + "&format=json&limit=10&addressdetails=0";

                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "4KitchenBoard/1.0");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    } finally {
                        conn.disconnect();
                    }

                    JSONArray arr = new JSONArray(sb.toString());
                    JSONArray simplified = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        JSONObject s = new JSONObject();
                        // Prefer the "name" field; fall back to display_name
                        String name = obj.optString("name", "");
                        String displayName = obj.optString("display_name", name);
                        if (name.isEmpty()) name = displayName;
                        s.put("name", name);
                        s.put("display_name", displayName);
                        s.put("lat", obj.getString("lat"));
                        s.put("lon", obj.getString("lon"));
                        simplified.put(s);
                    }

                    final String json = simplified.toString();
                    final String hint = context.getString(R.string.shop_tap_hint);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript(
                                        "showResults(" + json + ", " + jsonString(hint) + ")",
                                        null);
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, R.string.shop_search_error,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    /** Safely encodes a Java string as a JSON string literal for injection into JS. */
    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private class MapJsInterface {
        @JavascriptInterface
        public void onShopSelected(final String name, final String displayName,
                                   final String lat, final String lon) {
            final String shop = (name != null && !name.isEmpty()) ? name : displayName;
            selectedShop = shop;
            try {
                selectedLat = Double.parseDouble(lat);
                selectedLon = Double.parseDouble(lon);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse shop coordinates: lat=" + lat + ", lon=" + lon, e);
                selectedLat = 0.0;
                selectedLon = 0.0;
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (tvSelectedShop != null) {
                        tvSelectedShop.setText(
                                context.getString(R.string.shop_selected_prefix) + shop);
                        tvSelectedShop.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }
}
