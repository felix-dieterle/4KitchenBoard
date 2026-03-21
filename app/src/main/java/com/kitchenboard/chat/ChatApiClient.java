package com.kitchenboard.chat;

import android.util.Log;

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

/**
 * Handles HTTP communication for the chat feature.
 *
 * <p>All methods are synchronous and must be called from a background thread.
 * They throw {@link Exception} on any network or server error.
 */
public class ChatApiClient {

    private static final String TAG     = "ChatApiClient";
    private static final int    TIMEOUT = 10_000;

    private final String baseUrl;
    private final String boardToken;
    private final String apiToken;

    /**
     * Creates a client for the given server.
     *
     * @param baseUrl    Full URL to the backend API (e.g. {@code http://192.168.1.10/api.php})
     * @param boardToken Shared board token (may be empty)
     * @param apiToken   Server API authentication token (may be empty)
     */
    public ChatApiClient(String baseUrl, String boardToken, String apiToken) {
        this.baseUrl    = baseUrl;
        this.boardToken = boardToken;
        this.apiToken   = apiToken;
    }

    /**
     * Fetches all messages with an ID greater than {@code sinceId}.
     *
     * @param sinceId Only return messages newer than this server ID (use 0 for all)
     * @return List of messages ordered by ID ascending
     * @throws Exception on network or parse error
     */
    public List<ChatMessage> fetchMessages(long sinceId) throws Exception {
        String url = baseUrl
                + (baseUrl.contains("?") ? "&" : "?")
                + "action=chat_list"
                + "&board_token=" + java.net.URLEncoder.encode(boardToken, "UTF-8")
                + "&since_id=" + sinceId;

        HttpURLConnection conn = openGet(url);
        try {
            String body = readBody(conn);
            JSONObject json = new JSONObject(body);
            JSONArray arr = json.getJSONArray("messages");
            List<ChatMessage> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    result.add(ChatMessage.fromJson(arr.getJSONObject(i)));
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed message at index " + i, e);
                }
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Sends a new chat message to the backend.
     *
     * @param senderId   Unique ID of the sending device / user
     * @param senderName Display name of the sender
     * @param message    Plain-text message body
     * @return The server-assigned message ID
     * @throws Exception on network or server error
     */
    public long sendMessage(String senderId, String senderName, String message) throws Exception {
        String body = "action=chat_send"
                + "&board_token=" + java.net.URLEncoder.encode(boardToken, "UTF-8")
                + "&sender_id="   + java.net.URLEncoder.encode(senderId,   "UTF-8")
                + "&sender_name=" + java.net.URLEncoder.encode(senderName, "UTF-8")
                + "&message="     + java.net.URLEncoder.encode(message,    "UTF-8");

        HttpURLConnection conn = openPost(baseUrl, body);
        try {
            String response = readBody(conn);
            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("success", false)) {
                throw new Exception("Server error: " + json.optString("error", "unknown"));
            }
            return json.optLong("id", -1L);
        } finally {
            conn.disconnect();
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private HttpURLConnection openGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        if (!apiToken.isEmpty()) conn.setRequestProperty("X-Api-Token", apiToken);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return conn;
    }

    private HttpURLConnection openPost(String urlStr, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        if (!apiToken.isEmpty()) conn.setRequestProperty("X-Api-Token", apiToken);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(Charset.forName("UTF-8")));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
