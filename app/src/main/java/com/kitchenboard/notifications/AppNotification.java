package com.kitchenboard.notifications;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a single in-app notification stored in {@link NotificationStore}.
 *
 * <p>Notifications persist across app restarts via JSON serialization to
 * SharedPreferences. Each instance is identified by its {@link #id} (creation
 * timestamp in milliseconds, unique for practical purposes).
 */
public class AppNotification {

    /** Notification originates from an appointment reminder. */
    public static final int TYPE_REMINDER = 1;
    /** Notification originates from the Immobilien (property) alert checker. */
    public static final int TYPE_PROPERTY = 2;
    /** Notification originates from a new task being assigned to the active person. */
    public static final int TYPE_TASK = 3;
    /** Notification originates from a store geofence (nearby shopping items). */
    public static final int TYPE_SHOPPING = 4;

    /** Unique identifier – creation timestamp in milliseconds. */
    public final long id;
    /** {@link #TYPE_REMINDER} or {@link #TYPE_PROPERTY}. */
    public final int  type;
    /** Short title shown in the notification panel header row. */
    public final String title;
    /** Full detail message shown below the title. */
    public final String message;
    /** Unix timestamp (ms) when the notification was created. */
    public final long timestampMs;
    /**
     * Page index to navigate to when the user taps the notification
     * ({@code -1} means no automatic navigation).
     */
    public final int  navigateTo;
    /** Whether the user has already opened/read this notification. */
    public boolean isRead;

    public AppNotification(long id, int type, String title, String message,
                           long timestampMs, int navigateTo, boolean isRead) {
        this.id          = id;
        this.type        = type;
        this.title       = title;
        this.message     = message;
        this.timestampMs = timestampMs;
        this.navigateTo  = navigateTo;
        this.isRead      = isRead;
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id",          id);
        obj.put("type",        type);
        obj.put("title",       title);
        obj.put("message",     message);
        obj.put("timestampMs", timestampMs);
        obj.put("navigateTo",  navigateTo);
        obj.put("isRead",      isRead);
        return obj;
    }

    public static AppNotification fromJson(JSONObject obj) throws JSONException {
        return new AppNotification(
                obj.getLong("id"),
                obj.getInt("type"),
                obj.getString("title"),
                obj.getString("message"),
                obj.getLong("timestampMs"),
                obj.getInt("navigateTo"),
                obj.optBoolean("isRead", false));
    }
}
