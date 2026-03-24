package com.kitchenboard.shopping;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kitchenboard.update.UpdateLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
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

    /** A single item-history entry consisting of a name and its associated category. */
    public static final class HistoryEntry {
        public final String name;
        public final String category;

        public HistoryEntry(String name, String category) {
            this.name = name;
            this.category = category;
        }
    }

    private final Context context;
    private final String baseUrl;
    private final String boardToken;
    private final String apiToken;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * @param context    Application context used for error logging
     * @param baseUrl    Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     * @param boardToken Shared board token that scopes all data (empty = default board)
     * @param apiToken   Server access token sent as X-Api-Token header (empty = no auth)
     */
    public ShoppingApiClient(Context context, String baseUrl, String boardToken, String apiToken) {
        this.context = context.getApplicationContext();
        // Normalise: strip trailing slash
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.boardToken = boardToken != null ? boardToken : "";
        this.apiToken = apiToken != null ? apiToken : "";
    }

    /**
     * @param context    Application context used for error logging
     * @param baseUrl    Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     * @param boardToken Shared board token that scopes all data (empty = default board)
     */
    public ShoppingApiClient(Context context, String baseUrl, String boardToken) {
        this(context, baseUrl, boardToken, "");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Fetch all active (unchecked) items from the server. */
    public void fetchItems(final Callback<List<ShoppingItem>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(baseUrl + "?action=list&board_token=" + encode(boardToken));
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
                                obj.optInt("quantity", 1),
                                obj.optString("shop", ""),
                                obj.optInt("priority", ShoppingItem.PRIORITY_NORMAL)));
                    }
                    postSuccess(callback, items);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync shopping list failed", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Add a new item on the server. Returns the created item (with server-assigned id). */
    public void addItem(final String name, final String category, final int quantity,
                        final String shop, final int priority, final Callback<ShoppingItem> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = "action=add"
                            + "&name=" + encode(name)
                            + "&category=" + encode(category)
                            + "&quantity=" + quantity
                            + "&shop=" + encode(shop != null ? shop : "")
                            + "&priority=" + priority
                            + "&board_token=" + encode(boardToken);
                    String response = httpPost(baseUrl, body);
                    JSONObject json = new JSONObject(response);
                    final ShoppingItem item = new ShoppingItem(
                            json.getLong("id"),
                            json.getString("name"),
                            json.getString("category"),
                            false,
                            json.optInt("quantity", 1),
                            json.optString("shop", ""),
                            json.optInt("priority", ShoppingItem.PRIORITY_NORMAL));
                    postSuccess(callback, item);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync shopping add failed (name=" + name + ")", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Add a new item on the server. Returns the created item (with server-assigned id). */
    public void addItem(final String name, final String category, final int quantity,
                        final String shop, final Callback<ShoppingItem> callback) {
        addItem(name, category, quantity, shop, ShoppingItem.PRIORITY_NORMAL, callback);
    }

    /** Add a new item on the server (no shop). Returns the created item (with server-assigned id). */
    public void addItem(final String name, final String category, final int quantity,
                        final Callback<ShoppingItem> callback) {
        addItem(name, category, quantity, "", callback);
    }

    /** Update the quantity of an item on the server. */
    public void updateItemQuantity(final long id, final int quantity, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=update_quantity&id=" + id + "&quantity=" + quantity
                            + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync shopping update_quantity failed (id=" + id + ")", e);
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
                    httpPost(baseUrl, "action=check&id=" + id + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync shopping check failed (id=" + id + ")", e);
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
                    httpPost(baseUrl, "action=delete&id=" + id + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync shopping delete failed (id=" + id + ")", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Fetch all item-history entries for the configured board from the server. */
    public void fetchHistory(final Callback<List<HistoryEntry>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(baseUrl + "?action=history_list&board_token=" + encode(boardToken));
                    JSONObject json = new JSONObject(response);
                    JSONArray arr = json.getJSONArray("history");
                    final List<HistoryEntry> entries = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        entries.add(new HistoryEntry(
                                obj.getString("name"),
                                obj.optString("category", "")));
                    }
                    postSuccess(callback, entries);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Fetch item history failed", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Push a single item-history entry to the server for the configured board. */
    public void addHistoryItem(final String name, final String category, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=history_add"
                            + "&board_token=" + encode(boardToken)
                            + "&name="        + encode(name)
                            + "&category="    + encode(category));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Push item history failed", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/json");
        if (!apiToken.isEmpty()) {
            conn.setRequestProperty("X-Api-Token", apiToken);
        }
        return readResponse(conn);
    }

    private String httpPost(String urlString, String body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        if (!apiToken.isEmpty()) {
            conn.setRequestProperty("X-Api-Token", apiToken);
        }

        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
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
                new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")))) {
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
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e); // UTF-8 is always supported
        }
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
