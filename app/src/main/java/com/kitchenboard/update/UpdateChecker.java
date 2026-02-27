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
 */
public class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/felix-dieterle/4KitchenBoard/releases/latest";

    public interface UpdateCallback {
        void onUpdateAvailable(String tagName, String downloadUrl);
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
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGet(RELEASES_URL);
                    JSONObject json = new JSONObject(response);

                    String tagName = json.getString("tag_name"); // e.g. "v1.0-5"
                    String htmlUrl = json.getString("html_url");

                    final int latestBuildNumber = parseBuildNumber(tagName);
                    if (latestBuildNumber > currentVersionCode) {
                        final String tag = tagName;
                        final String url = htmlUrl;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onUpdateAvailable(tag, url);
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
