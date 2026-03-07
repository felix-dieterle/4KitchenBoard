package com.kitchenboard.update;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persists update-related log entries to internal storage so they can be
 * reviewed and shared from the settings dialog.
 *
 * <p>Log files are written to {@code <filesDir>/update_logs/update_log.txt}.
 * Entries are capped at {@value #MAX_LINES} lines; older lines are discarded
 * automatically when the limit is exceeded.
 */
public class UpdateLogger {

    private static final String TAG          = "UpdateLogger";
    private static final String LOG_DIR      = "update_logs";
    private static final String LOG_FILE     = "update_log.txt";
    private static final int    MAX_LINES    = 200;

    private static final String PREFS_NAME       = "shopping_prefs";
    private static final String PREF_SERVER_URL  = "server_url";
    private static final String PREF_BOARD_TOKEN = "board_token";
    private static final String PREF_API_TOKEN   = "api_token";

    /** Appends an INFO entry. */
    public static void logInfo(Context context, String message) {
        append(context, "INFO", message);
    }

    /** Appends an ERROR entry, forwards to {@link Log#e}, and attempts to send it to the backend. */
    public static void logError(Context context, String message) {
        Log.e(TAG, message);
        append(context, "ERROR", message);
        sendErrorToBackend(context, message, "ERROR");
    }

    /** Returns the full log content as a single string, newest lines last. */
    public static String readLogs(Context context) {
        File logFile = getLogFile(context);
        if (!logFile.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read update log", e);
        }
        return sb.toString();
    }

    /** Deletes the log file. */
    public static synchronized void clearLogs(Context context) {
        File logFile = getLogFile(context);
        if (logFile.exists() && !logFile.delete()) {
            Log.w(TAG, "Failed to delete update log file");
        }
    }

    /**
     * Creates an {@link Intent#ACTION_SEND} intent that shares the log file as
     * plain text. Returns {@code null} when the log file does not exist or is
     * empty.
     */
    public static Intent createShareIntent(Context context) {
        File logFile = getLogFile(context);
        if (!logFile.exists() || logFile.length() == 0) return null;

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", logFile);
        } else {
            uri = Uri.fromFile(logFile);
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Fire-and-forget: posts the error entry to the configured PHP backend.
     * Silently ignored when no server URL is set or the request fails.
     */
    private static void sendErrorToBackend(final Context context, final String message,
                                           final String level) {
        final Context appCtx = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs =
                            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    String serverUrl  = prefs.getString(PREF_SERVER_URL,  "").trim();
                    String boardToken = prefs.getString(PREF_BOARD_TOKEN, "");
                    String apiToken   = prefs.getString(PREF_API_TOKEN,   "");
                    if (serverUrl.isEmpty()) return;

                    String base = serverUrl.endsWith("/")
                            ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;

                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date());

                    String body = "action=log_error"
                            + "&message=" + java.net.URLEncoder.encode(message, StandardCharsets.UTF_8.name())
                            + "&level="   + java.net.URLEncoder.encode(level, StandardCharsets.UTF_8.name())
                            + "&timestamp=" + java.net.URLEncoder.encode(timestamp, StandardCharsets.UTF_8.name())
                            + "&board_token=" + java.net.URLEncoder.encode(boardToken, StandardCharsets.UTF_8.name());

                    URL url = new URL(base);
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
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(bytes);
                    }
                    int responseCode = conn.getResponseCode();
                    if (responseCode < 200 || responseCode >= 300) {
                        Log.w(TAG, "Backend returned HTTP " + responseCode + " for log_error");
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send error log to backend: " + e.getMessage());
                }
            }
        }).start();
    }

    private static synchronized void append(Context context, String level, String message) {
        try {
            File logFile = getLogFile(context);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            String entry = timestamp + " [" + level + "] " + message + "\n";

            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(entry);
            }

            trimLog(logFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write update log", e);
        }
    }

    private static File getLogFile(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), LOG_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create update_logs directory");
        }
        return new File(dir, LOG_FILE);
    }

    /** Keeps only the last {@value #MAX_LINES} lines in the log file. */
    private static void trimLog(File logFile) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        if (lines.size() <= MAX_LINES) return;

        List<String> trimmed = lines.subList(lines.size() - MAX_LINES, lines.size());
        try (FileWriter fw = new FileWriter(logFile, false)) {
            for (String line : trimmed) {
                fw.write(line);
                fw.write('\n');
            }
        }
    }
}
