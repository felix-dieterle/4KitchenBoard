package com.kitchenboard.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;

/**
 * Checks for app updates delivered by the configured 4KitchenBoard backend server.
 *
 * <p>The backend uses a three-part version scheme:
 * {@code version + buildNumber + subNumber}.
 * SubNumbers are assigned by the backend on top of a specific GitHub build number,
 * allowing backend-only incremental releases between GitHub builds.
 *
 * <p>Version ordering rules:
 * <ul>
 *   <li>{@code (buildY, subN)} &gt; {@code (buildY, 0)} for any N &gt; 0 –
 *       any backend sub-release is newer than the base GitHub build.</li>
 *   <li>{@code (buildY+1, any)} &gt; {@code (buildY, subN)} for any subN –
 *       a higher build number always supersedes any sub-number.</li>
 * </ul>
 *
 * <p>The backend is expected to expose an endpoint at the configured server URL:
 * <pre>
 * GET {server_url}?action=check_update&amp;board_token={board_token}
 * </pre>
 * with the following JSON response:
 * <pre>
 * {
 *   "build_number":  42,
 *   "sub_number":     3,
 *   "download_url":  "http://example.com/4KitchenBoard.apk",
 *   "tag":           "v1.0-42+3"
 * }
 * </pre>
 * {@code sub_number} and {@code tag} are optional; {@code download_url} must be present
 * for an update to be offered.
 */
public class BackendUpdateChecker {

    /** SharedPreferences file that holds the server configuration (shared with shopping module).
     *  Reading from "shopping_prefs" follows the existing pattern used by UpdateLogger and
     *  other modules that require the server URL – it is the single source of truth for the
     *  configured backend. */
    private static final String PREFS_SHOPPING   = "shopping_prefs";
    private static final String PREF_SERVER_URL  = "server_url";
    private static final String PREF_BOARD_TOKEN = "board_token";
    private static final String PREF_API_TOKEN   = "api_token";

    /** SharedPreferences file used to persist update state. */
    static final String PREFS_UPDATE             = "auto_update_prefs";
    /** Key: sub-number of the currently installed build (0 = GitHub release). */
    public static final String PREF_CURRENT_SUB_NR = "current_sub_number";
    /** Key: sub-number of the APK that is currently being downloaded (pending install). */
    static final String PREF_PENDING_SUB_NR      = "pending_sub_number";

    // ── Result / Callback ─────────────────────────────────────────────────────

    /** Result of a successful backend update check. */
    public static class BackendUpdateResult {
        /** GitHub build number this release is based on. */
        public final int buildNumber;
        /** Backend sub-number (&gt; 0 means a backend-only incremental release). */
        public final int subNumber;
        /** Direct APK download URL provided by the backend. */
        public final String downloadUrl;
        /** Human-readable version tag, e.g. {@code v1.0-42+3}. */
        public final String tagName;

        BackendUpdateResult(int buildNumber, int subNumber,
                            String downloadUrl, String tagName) {
            this.buildNumber = buildNumber;
            this.subNumber   = subNumber;
            this.downloadUrl = downloadUrl;
            this.tagName     = tagName;
        }
    }

    /** Callback for the asynchronous update check. Always invoked on the main thread. */
    public interface BackendUpdateCallback {
        /** Called when the backend reports a version newer than the installed one. */
        void onUpdateAvailable(BackendUpdateResult result);
        /** Called when the backend has no newer version, or no backend is configured. */
        void onNoUpdate();
        /** Called when the check could not be completed due to a network or parse error. */
        void onError(String message);
    }

    // ── Sub-number persistence ────────────────────────────────────────────────

    /**
     * Returns the sub-number of the currently installed build.
     * 0 means the build was installed directly from a GitHub release.
     */
    public static int getCurrentSubNumber(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
                .getInt(PREF_CURRENT_SUB_NR, 0);
    }

    /** Persists the sub-number of a newly installed backend build. */
    public static void saveCurrentSubNumber(Context context, int subNumber) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_CURRENT_SUB_NR, subNumber)
                .apply();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when a backend server URL is configured and a check
     * could be attempted.
     */
    public static boolean isBackendConfigured(Context context) {
        String url = context.getApplicationContext()
                .getSharedPreferences(PREFS_SHOPPING, Context.MODE_PRIVATE)
                .getString(PREF_SERVER_URL, "").trim();
        return !url.isEmpty();
    }

    /**
     * Asynchronously checks the configured backend for a newer version.
     * Calls {@link BackendUpdateCallback#onNoUpdate()} immediately when no backend
     * server is configured. The callback is always delivered on the main thread.
     *
     * @param context        application context
     * @param currentBuildNr installed build number ({@code BuildConfig.VERSION_CODE})
     * @param currentSubNr   installed sub-number (see {@link #getCurrentSubNumber(Context)})
     * @param callback       receives the result
     */
    public static void checkForUpdate(final Context context,
                                      final int currentBuildNr,
                                      final int currentSubNr,
                                      final BackendUpdateCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_SHOPPING, Context.MODE_PRIVATE);
        final String serverUrl  = prefs.getString(PREF_SERVER_URL,  "").trim();
        final String boardToken = prefs.getString(PREF_BOARD_TOKEN, "");
        final String apiToken   = prefs.getString(PREF_API_TOKEN,   "");

        if (serverUrl.isEmpty()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onNoUpdate();
                }
            });
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final BackendUpdateResult result =
                            fetchFromBackend(serverUrl, boardToken, apiToken,
                                    currentBuildNr, currentSubNr);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (result != null) {
                                callback.onUpdateAvailable(result);
                            } else {
                                callback.onNoUpdate();
                            }
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    UpdateLogger.logError(appCtx, "Backend update check failed", e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(msg);
                        }
                    });
                }
            }
        }).start();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Performs the HTTP request to the backend and returns a {@link BackendUpdateResult}
     * when the backend reports a version newer than the currently installed one,
     * or {@code null} otherwise.
     */
    private static BackendUpdateResult fetchFromBackend(String serverUrl,
                                                        String boardToken,
                                                        String apiToken,
                                                        int currentBuildNr,
                                                        int currentSubNr) throws Exception {
        String base = serverUrl.endsWith("/")
                ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;

        String urlStr = base + "?action=check_update"
                + "&board_token=" + URLEncoder.encode(boardToken, "UTF-8");

        String response = httpGet(urlStr, apiToken);
        JSONObject json = new JSONObject(response);

        int backendBuildNr = json.getInt("build_number");
        int backendSubNr   = json.optInt("sub_number", 0);

        if (!isNewer(backendBuildNr, backendSubNr, currentBuildNr, currentSubNr)) {
            return null;
        }

        String downloadUrl = json.optString("download_url", "");
        if (downloadUrl.isEmpty()) {
            return null;
        }

        String defaultTag = "build-" + backendBuildNr
                + (backendSubNr > 0 ? "+" + backendSubNr : "");
        String tag = json.optString("tag", defaultTag);

        return new BackendUpdateResult(backendBuildNr, backendSubNr, downloadUrl, tag);
    }

    /**
     * Version comparison following the three-part ordering rules:
     * build number takes precedence; sub-number breaks ties within the same build.
     *
     * @return {@code true} when {@code (newBuild, newSub)} is strictly newer than
     *         {@code (currentBuild, currentSub)}
     */
    public static boolean isNewer(int newBuild, int newSub, int currentBuild, int currentSub) {
        if (newBuild != currentBuild) {
            return newBuild > currentBuild;
        }
        return newSub > currentSub;
    }

    /** Performs a GET request with optional {@code X-Api-Token} header. */
    private static String httpGet(String urlString, String apiToken) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "4KitchenBoard-Android");
        if (apiToken != null && !apiToken.isEmpty()) {
            conn.setRequestProperty("X-Api-Token", apiToken);
        }
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("HTTP " + responseCode);
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
}
