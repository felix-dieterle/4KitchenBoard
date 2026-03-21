package com.kitchenboard.chat;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single chat message exchanged between board members.
 *
 * <p>Messages are stored locally in {@link ChatDatabaseHelper} and synchronised
 * with the backend API via {@link ChatApiClient}.  Each message carries a server-
 * assigned {@link #id}, the {@link #senderId} and {@link #senderName} of the
 * author, and the plain-text {@link #message} body.
 */
public class ChatMessage {

    /** Unique message ID assigned by the server (primary key). */
    public final long   id;
    /** Opaque string identifying the sender's device / board account. */
    public final String senderId;
    /** Human-readable name of the sender. */
    public final String senderName;
    /** Plain-text message body. */
    public final String message;
    /** Unix timestamp (ms) when the message was created on the server. */
    public final long   timestampMs;
    /** Whether this device has already read/seen the message. */
    public boolean      isRead;

    public ChatMessage(long id, String senderId, String senderName,
                       String message, long timestampMs, boolean isRead) {
        this.id          = id;
        this.senderId    = senderId;
        this.senderName  = senderName;
        this.message     = message;
        this.timestampMs = timestampMs;
        this.isRead      = isRead;
    }

    // ── JSON (de)serialization ─────────────────────────────────────────────────

    /** Serialises this message to a {@link JSONObject}. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id",          id);
        o.put("senderId",    senderId);
        o.put("senderName",  senderName);
        o.put("message",     message);
        o.put("timestampMs", timestampMs);
        o.put("isRead",      isRead);
        return o;
    }

    /** Deserialises a {@link JSONObject} (as returned by the backend) into a ChatMessage. */
    public static ChatMessage fromJson(JSONObject o) throws JSONException {
        return new ChatMessage(
                o.getLong("id"),
                o.optString("senderId",   ""),
                o.optString("senderName", ""),
                o.optString("message",    ""),
                o.optLong("timestampMs",  o.optLong("timestamp_ms", 0L)),
                o.optBoolean("isRead", false));
    }
}
