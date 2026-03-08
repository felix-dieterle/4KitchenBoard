package com.kitchenboard.tasks;

import android.os.Handler;
import android.os.Looper;

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
 * HTTP client for the 4KitchenBoard tasks sync backend (backend/api.php).
 *
 * All methods execute the network call on a background thread and deliver
 * results back on the main (UI) thread via the supplied callback.
 */
public class TaskApiClient {

    /** Generic two-outcome callback. May be null if the caller does not care about the result. */
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    private final String          baseUrl;
    private final String          boardToken;
    private final String          apiToken;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor    = Executors.newFixedThreadPool(2);

    /**
     * @param baseUrl    Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     * @param boardToken Shared board token that scopes all data (empty = default board)
     * @param apiToken   Server access token sent as X-Api-Token header (empty = no auth)
     */
    public TaskApiClient(String baseUrl, String boardToken, String apiToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.boardToken = boardToken != null ? boardToken : "";
        this.apiToken = apiToken != null ? apiToken : "";
    }

    /**
     * @param baseUrl    Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     * @param boardToken Shared board token that scopes all data (empty = default board)
     */
    public TaskApiClient(String baseUrl, String boardToken) {
        this(baseUrl, boardToken, "");
    }

    /** Fetch all tasks from the server. */
    public void fetchTasks(final Callback<List<Task>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(baseUrl + "?action=tasks_list&board_token=" + encode(boardToken));
                    JSONObject json = new JSONObject(response);
                    JSONArray arr = json.getJSONArray("tasks");
                    final List<Task> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        list.add(new Task(
                                obj.getLong("id"),
                                obj.getString("title"),
                                obj.getInt("sort_order")));
                    }
                    postSuccess(callback, list);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Insert or update a task on the server using its local SQLite id as the sync key. */
    public void upsertTask(final Task task, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = "action=tasks_upsert"
                            + "&id=" + task.id
                            + "&title=" + encode(task.title)
                            + "&sort_order=" + task.sortOrder
                            + "&board_token=" + encode(boardToken);
                    httpPost(baseUrl, body);
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Delete a task by its id on the server. */
    public void deleteTask(final long id, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=tasks_delete&id=" + id + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
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
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value; // UTF-8 is always supported
        }
    }

    // ── Thread helpers ────────────────────────────────────────────────────────

    private void runAsync(Runnable task) {
        executor.execute(task);
    }

    private <T> void postSuccess(final Callback<T> cb, final T result) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onSuccess(result);
            }
        });
    }

    private <T> void postError(final Callback<T> cb, final String msg) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }
}
