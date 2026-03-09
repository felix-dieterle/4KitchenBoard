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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
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

    /**
     * Appends an ERROR entry that includes the full stack trace of {@code t}.
     * Also forwards to {@link Log#e} and attempts to send the entry to the backend.
     */
    public static void logError(Context context, String message, Throwable t) {
        String full = buildErrorMessage(message, t);
        Log.e(TAG, full, t);
        append(context, "ERROR", full);
        sendErrorToBackend(context, full, "ERROR");
    }

    /**
     * Builds a descriptive error string that includes the exception type, message,
     * full stack trace, and basic device/build information.
     */
    private static String buildErrorMessage(String message, Throwable t) {
        StringBuilder sb = new StringBuilder(message);
        if (t != null) {
            sb.append('\n')
              .append("Exception: ").append(t.getClass().getName()).append(": ")
              .append(t.getMessage()).append('\n');
            // Cause chain
            Throwable cause = t.getCause();
            while (cause != null) {
                sb.append("Caused by: ").append(cause.getClass().getName())
                  .append(": ").append(cause.getMessage()).append('\n');
                cause = cause.getCause();
            }
            // Stack trace
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }
        sb.append('\n')
          .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" (Android ").append(Build.VERSION.RELEASE)
          .append(", API ").append(Build.VERSION.SDK_INT).append(')');
        return sb.toString();
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

    // ── Crash sentinel ────────────────────────────────────────────────────────

    private static final String CRASH_SENTINEL_FILE = "crash_sentinel.txt";

    /**
     * Writes a sentinel file marking that an uncaught exception was caught in this session.
     * The sentinel persists across restarts so that the next startup can alert the user.
     * Call this from the global {@link Thread.UncaughtExceptionHandler}.
     */
    public static synchronized void markCrashOccurred(Context context) {
        try {
            File dir = new File(context.getApplicationContext().getFilesDir(), LOG_DIR);
            if (!dir.exists()) dir.mkdirs();
            File sentinel = new File(dir, CRASH_SENTINEL_FILE);
            try (FileWriter fw = new FileWriter(sentinel, false)) {
                fw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                fw.write('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write crash sentinel", e);
        }
    }

    /**
     * Returns {@code true} when a crash sentinel file was left by a previous session,
     * i.e. the app crashed before being able to clean up the sentinel.
     */
    public static boolean hasCrashSentinel(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), LOG_DIR);
        return new File(dir, CRASH_SENTINEL_FILE).exists();
    }

    /**
     * Removes the crash sentinel file.
     * Call this after the user has been notified (e.g. after showing the crash notification).
     */
    public static synchronized void clearCrashSentinel(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), LOG_DIR);
        File sentinel = new File(dir, CRASH_SENTINEL_FILE);
        if (sentinel.exists() && !sentinel.delete()) {
            Log.w(TAG, "Failed to delete crash sentinel");
        }
    }

    /**
     * Returns the last {@code maxLines} lines of the log as a single string,
     * or an empty string when the log file does not exist or is empty.
     *
     * <p>Uses a fixed-size circular buffer so only {@code maxLines} entries are
     * kept in memory at any time, regardless of the total file size.
     */
    public static String readRecentLogs(Context context, int maxLines) {
        File logFile = getLogFile(context);
        if (!logFile.exists()) return "";
        // Circular buffer: keep only the last maxLines entries in memory.
        java.util.Deque<String> buffer = new java.util.ArrayDeque<>(maxLines + 1);
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.addLast(line);
                if (buffer.size() > maxLines) buffer.removeFirst();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read update log", e);
            return "";
        }
        if (buffer.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : buffer) {
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
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
                            + "&message=" + java.net.URLEncoder.encode(message, "UTF-8")
                            + "&level="   + java.net.URLEncoder.encode(level, "UTF-8")
                            + "&timestamp=" + java.net.URLEncoder.encode(timestamp, "UTF-8")
                            + "&board_token=" + java.net.URLEncoder.encode(boardToken, "UTF-8");

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
                    byte[] bytes = body.getBytes("UTF-8");
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
