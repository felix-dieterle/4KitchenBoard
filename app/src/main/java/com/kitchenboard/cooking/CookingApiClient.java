package com.kitchenboard.cooking;

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
 * HTTP client for the 4KitchenBoard cooking-list sync backend (backend/api.php).
 *
 * All methods execute the network call on a background thread and deliver
 * results back on the main (UI) thread via the supplied callback.
 */
public class CookingApiClient {

    /** Generic two-outcome callback. */
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private final String baseUrl;
    private final String boardToken;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * @param baseUrl    Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     * @param boardToken Shared board token that scopes all data (empty = default board)
     */
    public CookingApiClient(String baseUrl, String boardToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.boardToken = boardToken != null ? boardToken : "";
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Fetch all dishes from the server. */
    public void fetchDishes(final Callback<List<Dish>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(baseUrl + "?action=cooking_list&board_token=" + encode(boardToken));
                    JSONObject json = new JSONObject(response);
                    JSONArray arr = json.getJSONArray("dishes");
                    final List<Dish> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        list.add(new Dish(
                                obj.getLong("id"),
                                obj.getString("name"),
                                obj.optInt("duration_minutes", 0),
                                obj.isNull("ingredients") ? null : obj.getString("ingredients"),
                                obj.isNull("notes") ? null : obj.getString("notes"),
                                obj.isNull("last_cooked") ? null : obj.getString("last_cooked")));
                    }
                    postSuccess(callback, list);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Insert or replace a dish on the server using its local SQLite id as the sync key. */
    public void upsertDish(final Dish dish, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder body = new StringBuilder();
                    body.append("action=cooking_upsert");
                    body.append("&id=").append(dish.id);
                    body.append("&name=").append(encode(dish.name));
                    body.append("&duration_minutes=").append(dish.durationMinutes);
                    if (dish.ingredients != null) {
                        body.append("&ingredients=").append(encode(dish.ingredients));
                    }
                    if (dish.notes != null) {
                        body.append("&notes=").append(encode(dish.notes));
                    }
                    if (dish.lastCooked != null) {
                        body.append("&last_cooked=").append(encode(dish.lastCooked));
                    }
                    body.append("&board_token=").append(encode(boardToken));
                    httpPost(baseUrl, body.toString());
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Delete a dish by its id on the server. */
    public void deleteDish(final long id, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=cooking_delete&id=" + id + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Update the last_cooked date of a dish on the server. */
    public void markAsCooked(final long id, final String lastCooked, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl,
                            "action=cooking_mark_cooked&id=" + id
                            + "&last_cooked=" + encode(lastCooked)
                            + "&board_token=" + encode(boardToken));
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
        if (value == null) return "";
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
