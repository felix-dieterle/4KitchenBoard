package com.kitchenboard.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite helper for the local chat message cache.
 *
 * <p>Messages fetched from the backend are stored here so the chat panel can
 * be displayed instantly without a network round-trip.  New messages are
 * detected by comparing the highest known server ID with the IDs returned by
 * the backend.
 *
 * <p>DB version history:<br>
 * v1 – initial schema: chat_messages table<br>
 * v2 – added recipient_id and recipient_name columns for directed messages<br>
 * v3 – added delivery_status column (0=sent, 1=delivered, 2=read)
 */
public class ChatDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "chat.db";
    private static final int    DB_VERSION = 3;

    static final String TABLE  = "chat_messages";
    static final String COL_ID              = "id";
    static final String COL_SENDER_ID       = "sender_id";
    static final String COL_SENDER_NAME     = "sender_name";
    /** Recipient device ID; empty string means broadcast (visible to all). */
    static final String COL_RECIPIENT_ID    = "recipient_id";
    /** Human-readable recipient name; empty string for broadcasts. */
    static final String COL_RECIPIENT_NAME  = "recipient_name";
    static final String COL_MESSAGE         = "message";
    static final String COL_TIMESTAMP_MS    = "timestamp_ms";
    static final String COL_IS_READ         = "is_read";
    /**
     * Delivery status for outgoing messages (see {@link ChatMessage#STATUS_SENT} etc.).
     * Stored as INTEGER: 0 = sent, 1 = delivered, 2 = read.
     */
    static final String COL_DELIVERY_STATUS = "delivery_status";

    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE + " ("
            + COL_ID              + " INTEGER PRIMARY KEY,"
            + COL_SENDER_ID       + " TEXT NOT NULL DEFAULT '',"
            + COL_SENDER_NAME     + " TEXT NOT NULL DEFAULT '',"
            + COL_RECIPIENT_ID    + " TEXT NOT NULL DEFAULT '',"
            + COL_RECIPIENT_NAME  + " TEXT NOT NULL DEFAULT '',"
            + COL_MESSAGE         + " TEXT NOT NULL DEFAULT '',"
            + COL_TIMESTAMP_MS    + " INTEGER NOT NULL DEFAULT 0,"
            + COL_IS_READ         + " INTEGER NOT NULL DEFAULT 0,"
            + COL_DELIVERY_STATUS + " INTEGER NOT NULL DEFAULT 0"
            + ")";

    public ChatDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2: add recipient columns (empty = broadcast)
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN "
                    + COL_RECIPIENT_ID   + " TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN "
                    + COL_RECIPIENT_NAME + " TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 3) {
            // v3: add delivery status column (0=sent, 1=delivered, 2=read)
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN "
                    + COL_DELIVERY_STATUS + " INTEGER NOT NULL DEFAULT 0");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Inserts or replaces a message in the local cache.
     *
     * @param msg The message to store
     */
    public void upsert(ChatMessage msg) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ID,              msg.id);
        cv.put(COL_SENDER_ID,       msg.senderId);
        cv.put(COL_SENDER_NAME,     msg.senderName);
        cv.put(COL_RECIPIENT_ID,    msg.recipientId);
        cv.put(COL_RECIPIENT_NAME,  msg.recipientName);
        cv.put(COL_MESSAGE,         msg.message);
        cv.put(COL_TIMESTAMP_MS,    msg.timestampMs);
        cv.put(COL_IS_READ,         msg.isRead ? 1 : 0);
        cv.put(COL_DELIVERY_STATUS, msg.deliveryStatus);
        getWritableDatabase().insertWithOnConflict(TABLE, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Returns all messages visible to this device ordered by timestamp ascending (oldest first).
     * Includes broadcast messages and messages directed to {@code thisDeviceId}.
     *
     * @param limit        Maximum number of messages to return (most recent)
     * @param thisDeviceId This device's sender ID; used to filter directed messages.
     *                     Pass an empty string to show all messages.
     */
    public List<ChatMessage> getMessages(int limit, String thisDeviceId) {
        List<ChatMessage> list = new ArrayList<>();
        // Show broadcast messages (recipientId='') and messages directed to this device
        String selection = null;
        String[] selectionArgs = null;
        if (thisDeviceId != null && !thisDeviceId.isEmpty()) {
            selection = COL_RECIPIENT_ID + "='' OR " + COL_RECIPIENT_ID + "=?";
            selectionArgs = new String[]{ thisDeviceId };
        }
        Cursor c = getReadableDatabase().query(
                TABLE, null, selection, selectionArgs, null, null,
                COL_TIMESTAMP_MS + " DESC",
                String.valueOf(limit));
        if (c == null) return list;
        try {
            int iId     = c.getColumnIndexOrThrow(COL_ID);
            int iSid    = c.getColumnIndexOrThrow(COL_SENDER_ID);
            int iSn     = c.getColumnIndexOrThrow(COL_SENDER_NAME);
            int iRid    = c.getColumnIndexOrThrow(COL_RECIPIENT_ID);
            int iRn     = c.getColumnIndexOrThrow(COL_RECIPIENT_NAME);
            int iMsg    = c.getColumnIndexOrThrow(COL_MESSAGE);
            int iTs     = c.getColumnIndexOrThrow(COL_TIMESTAMP_MS);
            int iRead   = c.getColumnIndexOrThrow(COL_IS_READ);
            int iStatus = c.getColumnIndex(COL_DELIVERY_STATUS); // may be -1 on old DBs
            while (c.moveToNext()) {
                ChatMessage m = new ChatMessage(
                        c.getLong(iId),
                        c.getString(iSid),
                        c.getString(iSn),
                        c.getString(iRid),
                        c.getString(iRn),
                        c.getString(iMsg),
                        c.getLong(iTs),
                        c.getInt(iRead) != 0,
                        iStatus >= 0 ? c.getInt(iStatus) : ChatMessage.STATUS_SENT);
                list.add(m);
            }
        } finally {
            c.close();
        }
        // Reverse so oldest message is first (conversation order)
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * Returns all messages ordered by timestamp ascending (oldest first).
     *
     * @param limit Maximum number of messages to return (most recent)
     * @deprecated Use {@link #getMessages(int, String)} to respect directed messages.
     */
    @Deprecated
    public List<ChatMessage> getMessages(int limit) {
        return getMessages(limit, "");
    }

    /** Returns the highest message ID stored locally, or 0 if the table is empty. */
    public long getMaxId() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(" + COL_ID + ") FROM " + TABLE, null);
        if (c == null) return 0L;
        try {
            if (c.moveToFirst()) return c.isNull(0) ? 0L : c.getLong(0);
        } finally {
            c.close();
        }
        return 0L;
    }

    /**
     * Returns the number of unread messages visible to {@code thisDeviceId}.
     * Includes unread broadcasts and directed messages to this device.
     */
    public int getUnreadCount(String thisDeviceId) {
        String where = COL_IS_READ + "=0";
        String[] args = null;
        if (thisDeviceId != null && !thisDeviceId.isEmpty()) {
            where += " AND (" + COL_RECIPIENT_ID + "='' OR " + COL_RECIPIENT_ID + "=?)";
            args = new String[]{ thisDeviceId };
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE " + where, args);
        if (c == null) return 0;
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Returns the number of messages that have not been read yet (all messages). */
    public int getUnreadCount() {
        return getUnreadCount("");
    }

    /** Marks all messages as read. */
    public void markAllRead() {
        ContentValues cv = new ContentValues();
        cv.put(COL_IS_READ, 1);
        getWritableDatabase().update(TABLE, cv, COL_IS_READ + "=0", null);
    }

    /**
     * Updates the delivery status of a single outgoing message identified by its ID.
     * Only upgrades status (sent→delivered→read), never downgrades.
     *
     * @param msgId     The message ID to update
     * @param newStatus One of {@link ChatMessage#STATUS_DELIVERED} or {@link ChatMessage#STATUS_READ}
     */
    public void updateDeliveryStatus(long msgId, int newStatus) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DELIVERY_STATUS, newStatus);
        // Only upgrade: do not overwrite a higher status with a lower one
        getWritableDatabase().update(TABLE, cv,
                COL_ID + "=? AND " + COL_DELIVERY_STATUS + "<?",
                new String[]{ String.valueOf(msgId), String.valueOf(newStatus) });
    }

    /** Deletes messages older than {@code keepCount} messages (keeps the newest ones). */
    public void pruneOldMessages(int keepCount) {
        getWritableDatabase().execSQL(
                "DELETE FROM " + TABLE + " WHERE " + COL_ID + " NOT IN "
                + "(SELECT " + COL_ID + " FROM " + TABLE
                + " ORDER BY " + COL_TIMESTAMP_MS + " DESC LIMIT " + keepCount + ")");
    }
}
