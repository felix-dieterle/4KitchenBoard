package com.kitchenboard.calendar;

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
 * HTTP client for the 4KitchenBoard calendar sync backend (backend/api.php).
 *
 * All methods execute the network call on a background thread and deliver
 * results back on the main (UI) thread via the supplied callback.
 */
public class CalendarApiClient {

    /** Generic two-outcome callback. */
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
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
    public CalendarApiClient(Context context, String baseUrl, String boardToken, String apiToken) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.boardToken = boardToken != null ? boardToken : "";
        this.apiToken = apiToken != null ? apiToken : "";
    }

    /**
     * @param context    Application context used for error logging
     * @param baseUrl    Full URL of api.php, e.g. {@code http://192.168.1.10/kitchenboard/api.php}
     * @param boardToken Shared board token that scopes all data (empty = default board)
     */
    public CalendarApiClient(Context context, String baseUrl, String boardToken) {
        this(context, baseUrl, boardToken, "");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Fetch all appointments from the server. */
    public void fetchAppointments(final Callback<List<Appointment>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(baseUrl + "?action=calendar_list&board_token=" + encode(boardToken));
                    JSONObject json = new JSONObject(response);
                    JSONArray arr = json.getJSONArray("appointments");
                    final List<Appointment> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        Long seriesId = obj.isNull("series_id") ? null : obj.getLong("series_id");
                        String time   = obj.isNull("time")      ? null : obj.getString("time");
                        list.add(new Appointment(
                                obj.getLong("id"),
                                obj.getString("date"),
                                time,
                                obj.getString("title"),
                                seriesId, null, null));
                    }
                    postSuccess(callback, list);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync calendar_list failed", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /**
     * Insert or replace an appointment on the server using its local SQLite id as the sync key.
     * The server stores id, date, time, title, series_id.
     */
    public void upsertAppointment(final Appointment apt, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder body = new StringBuilder();
                    body.append("action=calendar_upsert");
                    body.append("&id=").append(apt.getId());
                    body.append("&date=").append(encode(apt.getDate()));
                    body.append("&title=").append(encode(apt.getTitle()));
                    if (apt.getTime() != null) {
                        body.append("&time=").append(encode(apt.getTime()));
                    }
                    if (apt.getSeriesId() != null) {
                        body.append("&series_id=").append(apt.getSeriesId());
                    }
                    body.append("&board_token=").append(encode(boardToken));
                    httpPost(baseUrl, body.toString());
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync calendar_upsert failed", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Delete an appointment by its id on the server. */
    public void deleteAppointment(final long id, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=calendar_delete&id=" + id + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync calendar_delete failed (id=" + id + ")", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Delete all appointments belonging to a series on the server. */
    public void deleteSeriesById(final long seriesId, final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    httpPost(baseUrl, "action=calendar_delete_series&series_id=" + seriesId + "&board_token=" + encode(boardToken));
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync calendar_delete_series failed (series_id=" + seriesId + ")", e);
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    /** Update the date and time of an appointment on the server (also clears series_id). */
    public void updateAppointmentDateTime(final long id, final String date, final String time,
                                          final Callback<Void> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder body = new StringBuilder();
                    body.append("action=calendar_update_datetime");
                    body.append("&id=").append(id);
                    body.append("&date=").append(encode(date));
                    if (time != null && !time.isEmpty()) {
                        body.append("&time=").append(encode(time));
                    }
                    body.append("&board_token=").append(encode(boardToken));
                    httpPost(baseUrl, body.toString());
                    postSuccess(callback, null);
                } catch (final Exception e) {
                    UpdateLogger.logError(context, "Sync calendar_update_datetime failed (id=" + id + ")", e);
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
