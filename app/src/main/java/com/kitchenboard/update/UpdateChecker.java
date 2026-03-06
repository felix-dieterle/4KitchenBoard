package com.kitchenboard.update;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub Releases for a newer version of the app.
 * Compares the current versionCode (= build number) against the latest release tag.
 *
 * <p>Auto-update releases are identified by the presence of the token {@value #AUTO_UPDATE_FLAG}
 * anywhere in the release body. Releases without this flag are never installed automatically.
 */
public class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/felix-dieterle/4KitchenBoard/releases/latest";

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
            this.tagName     = tagName;
            this.downloadUrl = downloadUrl;
            this.isAutoUpdate = isAutoUpdate;
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
        checkForUpdateInternal(currentVersionCode, new UpdateResultCallback() {
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
     * {@value #AUTO_UPDATE_FLAG} flag. The callback is always invoked on the main thread.
     *
     * @param currentVersionCode the installed app's versionCode
     * @param callback           receives a full {@link UpdateResult}
     */
    public static void checkForUpdateWithFlag(final int currentVersionCode,
                                              final UpdateResultCallback callback) {
        checkForUpdateInternal(currentVersionCode, callback);
    }

    // ── Internal implementation ───────────────────────────────────────────────

    private static void checkForUpdateInternal(final int currentVersionCode,
                                               final UpdateResultCallback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(RELEASES_URL);
                    JSONObject json = new JSONObject(response);

                    String tagName = json.getString("tag_name"); // e.g. "v1.0-5"

                    final int latestBuildNumber = parseBuildNumber(tagName);
                    if (latestBuildNumber > currentVersionCode) {
                        // Look for an APK asset to get a direct download URL
                        String apkUrl = null;
                        if (json.has("assets")) {
                            org.json.JSONArray assets = json.getJSONArray("assets");
                            for (int i = 0; i < assets.length(); i++) {
                                org.json.JSONObject asset = assets.getJSONObject(i);
                                String name = asset.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    apkUrl = asset.getString("browser_download_url");
                                    break;
                                }
                            }
                        }
                        // Fall back to the release HTML page if no APK asset found
                        if (apkUrl == null) {
                            apkUrl = json.getString("html_url");
                        }

                        // Determine whether this release carries the auto-update flag
                        String body = json.optString("body", "");
                        boolean isAutoUpdate = body.contains(AUTO_UPDATE_FLAG);

                        final UpdateResult result =
                                new UpdateResult(tagName, apkUrl, isAutoUpdate);
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
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                }
            }
        }).start();
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

    private static String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "4KitchenBoard-Android");

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("HTTP " + responseCode);
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
}
