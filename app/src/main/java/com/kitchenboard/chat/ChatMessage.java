package com.kitchenboard.chat;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single chat message exchanged between board members.
 *
 * <p>Messages are stored locally in {@link ChatDatabaseHelper} and synchronised
 * with the backend API via {@link ChatApiClient} or sent directly over LAN via
 * {@link LanChatClient}.  Each message carries a server-assigned {@link #id},
 * the {@link #senderId} and {@link #senderName} of the author, an optional
 * {@link #recipientId} / {@link #recipientName} for directed messages, and the
 * plain-text {@link #message} body.
 *
 * <p>When {@link #recipientId} is empty the message is treated as a broadcast
 * visible to all participants; otherwise only the addressed device should show
 * an unread-badge notification for it.
 */
public class ChatMessage {

    /** Message was sent by this device; delivery not yet confirmed. */
    public static final int STATUS_SENT      = 0;
    /** Message was received by the recipient device (not yet opened). */
    public static final int STATUS_DELIVERED = 1;
    /** Message was opened / read by the recipient. */
    public static final int STATUS_READ      = 2;

    /** Unique message ID assigned by the server or generated locally for LAN messages. */
    public final long   id;
    /** Opaque string identifying the sender's device / board account. */
    public final String senderId;
    /** Human-readable name of the sender. */
    public final String senderName;
    /**
     * Opaque recipient device ID, or empty string for a broadcast message.
     * Matches the {@code senderId} format ("device_&lt;ANDROID_ID&gt;").
     */
    public final String recipientId;
    /** Human-readable name of the recipient, or empty string for a broadcast. */
    public final String recipientName;
    /** Plain-text message body. */
    public final String message;
    /** Unix timestamp (ms) when the message was created. */
    public final long   timestampMs;
    /** Whether this device has already read/seen the message. */
    public boolean      isRead;
    /**
     * Delivery status for outgoing messages: {@link #STATUS_SENT},
     * {@link #STATUS_DELIVERED}, or {@link #STATUS_READ}.
     * For incoming messages this field is not used.
     */
    public int deliveryStatus;

    /** Full constructor including optional recipient fields and delivery status. */
    public ChatMessage(long id, String senderId, String senderName,
                       String recipientId, String recipientName,
                       String message, long timestampMs, boolean isRead,
                       int deliveryStatus) {
        this.id             = id;
        this.senderId       = senderId;
        this.senderName     = senderName;
        this.recipientId    = recipientId   != null ? recipientId   : "";
        this.recipientName  = recipientName != null ? recipientName : "";
        this.message        = message;
        this.timestampMs    = timestampMs;
        this.isRead         = isRead;
        this.deliveryStatus = deliveryStatus;
    }

    /** Full constructor without delivery status (defaults to {@link #STATUS_SENT}). */
    public ChatMessage(long id, String senderId, String senderName,
                       String recipientId, String recipientName,
                       String message, long timestampMs, boolean isRead) {
        this(id, senderId, senderName, recipientId, recipientName,
                message, timestampMs, isRead, STATUS_SENT);
    }

    /** Convenience constructor for broadcast messages (no specific recipient). */
    public ChatMessage(long id, String senderId, String senderName,
                       String message, long timestampMs, boolean isRead) {
        this(id, senderId, senderName, "", "", message, timestampMs, isRead, STATUS_SENT);
    }

    // ── JSON (de)serialization ─────────────────────────────────────────────────

    /** Serialises this message to a {@link JSONObject}. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id",             id);
        o.put("senderId",       senderId);
        o.put("senderName",     senderName);
        o.put("recipientId",    recipientId);
        o.put("recipientName",  recipientName);
        o.put("message",        message);
        o.put("timestampMs",    timestampMs);
        o.put("isRead",         isRead);
        o.put("deliveryStatus", deliveryStatus);
        return o;
    }

    /** Deserialises a {@link JSONObject} (as returned by the backend or sent over LAN) into a ChatMessage. */
    public static ChatMessage fromJson(JSONObject o) throws JSONException {
        return new ChatMessage(
                o.getLong("id"),
                o.optString("senderId",      ""),
                o.optString("senderName",    ""),
                o.optString("recipientId",   ""),
                o.optString("recipientName", ""),
                o.optString("message",       ""),
                o.optLong("timestampMs",     o.optLong("timestamp_ms", 0L)),
                o.optBoolean("isRead", false),
                o.optInt("deliveryStatus",   STATUS_SENT));
    }
}
