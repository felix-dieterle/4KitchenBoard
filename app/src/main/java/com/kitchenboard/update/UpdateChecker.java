package com.kitchenboard.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Checks for a newer version of the app.
 *
 * <p>The check is always performed against the GitHub Releases API directly
 * ({@value #RELEASES_URL}), independent of any backend server that may be configured
 * for other features of the app.
 *
 * <p>Auto-update releases are identified by the presence of the token {@value #AUTO_UPDATE_FLAG}
 * anywhere in the release body. Releases without this flag are never installed automatically.
 */
public class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/felix-dieterle/4KitchenBoard/releases";

    /** Token that must appear in a release body to enable automatic installation. */
    public static final String AUTO_UPDATE_FLAG = "[auto_update]";

    public interface UpdateCallback {
        void onUpdateAvailable(String tagName, String downloadUrl);
        void onNoUpdate();
        void onError(String message);
    }

    /**
     * Result object returned by the background check so callers can inspect whether the release
     * carries the auto-update flag before deciding how to proceed.
     */
    public static class UpdateResult {
        public final String tagName;
        public final String downloadUrl;
        public final boolean isAutoUpdate;

        UpdateResult(String tagName, String downloadUrl, boolean isAutoUpdate) {
            this.tagName      = tagName;
            this.downloadUrl  = downloadUrl;
            this.isAutoUpdate = isAutoUpdate;
        }

        /** Parses and returns the build number from the tag (e.g. {@code "v1.0-42"} → 42). */
        public int getBuildNumber() {
            try {
                int dashIndex = tagName.lastIndexOf('-');
                if (dashIndex >= 0) {
                    return Integer.parseInt(tagName.substring(dashIndex + 1));
                }
            } catch (NumberFormatException ignored) {
            }
            return 0;
        }
    }

    public interface UpdateResultCallback {
        /** Called when a newer release is found. {@code result.isAutoUpdate} reflects the flag. */
        void onUpdateAvailable(UpdateResult result);
        void onNoUpdate();
        void onError(String message);
    }

    /**
     * Asynchronously checks for updates. The callback is always invoked on the main thread.
     *
     * @param currentVersionCode the installed app's versionCode (BuildConfig.VERSION_CODE)
     * @param callback           receives the result
     */
    public static void checkForUpdate(final int currentVersionCode, final UpdateCallback callback) {
        checkForUpdateWithFlag(null, currentVersionCode, new UpdateResultCallback() {
            @Override
            public void onUpdateAvailable(UpdateResult result) {
                callback.onUpdateAvailable(result.tagName, result.downloadUrl);
            }

            @Override
            public void onNoUpdate() {
                callback.onNoUpdate();
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /**
     * Asynchronously checks for updates and reports whether the release carries the
     * {@value #AUTO_UPDATE_FLAG} flag. Always queries the GitHub Releases API directly.
     * The callback is always invoked on the main thread.
     *
     * @param context            used to log errors when the check fails
     * @param currentVersionCode the installed app's versionCode
     * @param callback           receives a full {@link UpdateResult}
     */
    public static void checkForUpdateWithFlag(final Context context,
                                              final int currentVersionCode,
                                              final UpdateResultCallback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateResult result = fetchViaGitHub(currentVersionCode);
                    if (result != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onUpdateAvailable(result);
                            }
                        });
                    } else {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onNoUpdate();
                            }
                        });
                    }
                } catch (final Exception e) {
                    final String errorMsg = e.getClass().getSimpleName()
                            + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    if (context != null) {
                        UpdateLogger.logError(context, "Update check failed", e);
                    } else {
                        Log.e("UpdateChecker", "Update check failed: " + errorMsg, e);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(errorMsg);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Fetches the latest GitHub release directly (including pre-releases).
     * Returns {@code null} when no newer version is available.
     */
    private static UpdateResult fetchViaGitHub(int currentVersionCode) throws Exception {
        String response = httpGet(RELEASES_URL, null);
        JSONArray releases = new JSONArray(response);
        if (releases.length() == 0) return null;

        // The list is newest-first; pick the first entry.
        JSONObject json = releases.getJSONObject(0);

        String tagName = json.getString("tag_name"); // e.g. "v1.0-5"

        int latestBuildNumber = parseBuildNumber(tagName);
        if (latestBuildNumber <= currentVersionCode) return null;

        // Look for an APK asset to get a direct download URL
        String apkUrl = null;
        if (json.has("assets")) {
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url");
                    break;
                }
            }
        }
        if (apkUrl == null) {
            apkUrl = json.getString("html_url");
        }

        String body = json.optString("body", "");
        boolean isAutoUpdate = body.contains(AUTO_UPDATE_FLAG);
        return new UpdateResult(tagName, apkUrl, isAutoUpdate);
    }

    /**
     * Parses the build number from a release tag of the form "v{version}-{buildNumber}".
     * Returns 0 if parsing fails.
     */
    private static int parseBuildNumber(String tagName) {
        try {
            int dashIndex = tagName.lastIndexOf('-');
            if (dashIndex >= 0) {
                return Integer.parseInt(tagName.substring(dashIndex + 1));
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /**
     * Performs a GET request. Sends {@code X-Api-Token} when {@code apiToken} is non-empty.
     */
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
