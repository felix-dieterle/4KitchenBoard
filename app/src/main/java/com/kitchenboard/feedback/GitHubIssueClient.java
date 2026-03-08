package com.kitchenboard.feedback;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Creates GitHub issues via the REST API.
 * Requires a fine-grained personal access token with "Issues: Write" permission
 * on the felix-dieterle/4KitchenBoard repository.
 */
public class GitHubIssueClient {

    private static final String API_URL =
            "https://api.github.com/repos/felix-dieterle/4KitchenBoard/issues";

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    /**
     * Asynchronously creates a new GitHub issue. The callback is invoked on the main thread.
     *
     * @param token    GitHub personal access token (issues:write scope)
     * @param title    issue title
     * @param body     issue body text (may be null)
     * @param callback receives the result
     */
    public static void createIssue(final String token,
                                   final String title,
                                   final String body,
                                   final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("title", title);
                    if (body != null && !body.isEmpty()) {
                        payload.put("body", body);
                    }
                    JSONArray labels = new JSONArray();
                    labels.put("feature request");
                    payload.put("labels", labels);

                    byte[] bytes = payload.toString().getBytes(Charset.forName("UTF-8"));

                    URL url = new URL(API_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(10_000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                    conn.setDoOutput(true);

                    try (BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(conn.getOutputStream(),
                                    Charset.forName("UTF-8")))) {
                        writer.write(payload.toString());
                    }

                    final int responseCode = conn.getResponseCode();
                    conn.disconnect();

                    if (responseCode == HttpURLConnection.HTTP_CREATED) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess();
                            }
                        });
                    } else {
                        final String msg = "HTTP " + responseCode;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(msg);
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
}
