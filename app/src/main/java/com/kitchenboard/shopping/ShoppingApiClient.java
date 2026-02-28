package com.kitchenboard.shopping;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP client for the 4KitchenBoard shopping-list sync backend (backend/api.php).
 *
 * All methods execute the network call on a background thread and deliver
 * results back on the main (UI) thread via the supplied callback.
 */
public class ShoppingApiClient {

    /** Generic two-outcome callback. */
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private final String baseUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * @param baseUrl Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     */
    public ShoppingApiClient(String baseUrl) {
        // Normalise: strip trailing slash
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Fetch all active (unchecked) items from the server. */
    public void fetchItems(final Callback<List<ShoppingItem>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(baseUrl + "?action=list");
                    JSONObject json = new JSONObject(response);
                    JSONArray arr = json.getJSONArray("items");
                    final List<ShoppingItem> items = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        items.add(new ShoppingItem(
                                obj.getLong("id"),
                                obj.getString("name"),
                                obj.getString("category"),
                                false,
                                obj.optInt("quantity", 1)));
                    }
                    postSuccess(callback, items);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Add a new item on the server. Returns the created item (with server-assigned id). */
    public void addItem(final String name, final String category, final int quantity,
                        final Callback<ShoppingItem> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = "action=add"
                            + "&name=" + encode(name)
                            + "&category=" + encode(category)
                            + "&quantity=" + quantity;
                    String response = httpPost(baseUrl, body);
                    JSONObject json = new JSONObject(response);
                    final ShoppingItem item = new ShoppingItem(
                            json.getLong("id"),
                            json.getString("name"),
                            json.getString("category"),
                            false,
                            json.optInt("quantity", 1));
                    postSuccess(callback, item);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Update the quantity of an item on the server. */
    public void updateItemQuantity(final long id, final int quantity, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=update_quantity&id=" + id + "&quantity=" + quantity);
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Mark an item as checked (bought) on the server. */
    public void checkItem(final long id, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=check&id=" + id);
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Permanently delete an item on the server. */
    public void deleteItem(final long id, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=delete&id=" + id);
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/json");
        return readResponse(conn);
    }

    private static String httpPost(String urlString, String body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return readResponse(conn);
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    /** URL-encode a string value for an application/x-www-form-urlencoded body. */
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── Thread helpers ────────────────────────────────────────────────────────

    private void runAsync(Runnable task) {
        executor.execute(task);
    }

    private <T> void postSuccess(final Callback<T> cb, final T result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onSuccess(result);
            }
        });
    }

    private <T> void postError(final Callback<T> cb, final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }
}
