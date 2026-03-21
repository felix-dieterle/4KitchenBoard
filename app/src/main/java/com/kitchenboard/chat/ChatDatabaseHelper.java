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
 * v1 – initial schema: chat_messages table
 */
public class ChatDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "chat.db";
    private static final int    DB_VERSION = 1;

    static final String TABLE  = "chat_messages";
    static final String COL_ID           = "id";
    static final String COL_SENDER_ID    = "sender_id";
    static final String COL_SENDER_NAME  = "sender_name";
    static final String COL_MESSAGE      = "message";
    static final String COL_TIMESTAMP_MS = "timestamp_ms";
    static final String COL_IS_READ      = "is_read";

    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE + " ("
            + COL_ID           + " INTEGER PRIMARY KEY,"
            + COL_SENDER_ID    + " TEXT NOT NULL DEFAULT '',"
            + COL_SENDER_NAME  + " TEXT NOT NULL DEFAULT '',"
            + COL_MESSAGE      + " TEXT NOT NULL DEFAULT '',"
            + COL_TIMESTAMP_MS + " INTEGER NOT NULL DEFAULT 0,"
            + COL_IS_READ      + " INTEGER NOT NULL DEFAULT 0"
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
        // No migrations needed for v1
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Inserts or replaces a message in the local cache.
     *
     * @param msg The message to store
     */
    public void upsert(ChatMessage msg) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ID,           msg.id);
        cv.put(COL_SENDER_ID,    msg.senderId);
        cv.put(COL_SENDER_NAME,  msg.senderName);
        cv.put(COL_MESSAGE,      msg.message);
        cv.put(COL_TIMESTAMP_MS, msg.timestampMs);
        cv.put(COL_IS_READ,      msg.isRead ? 1 : 0);
        getWritableDatabase().insertWithOnConflict(TABLE, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Returns all messages ordered by timestamp ascending (oldest first).
     *
     * @param limit Maximum number of messages to return (most recent)
     */
    public List<ChatMessage> getMessages(int limit) {
        List<ChatMessage> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(
                TABLE, null, null, null, null, null,
                COL_TIMESTAMP_MS + " DESC",
                String.valueOf(limit));
        if (c == null) return list;
        try {
            int iId   = c.getColumnIndexOrThrow(COL_ID);
            int iSid  = c.getColumnIndexOrThrow(COL_SENDER_ID);
            int iSn   = c.getColumnIndexOrThrow(COL_SENDER_NAME);
            int iMsg  = c.getColumnIndexOrThrow(COL_MESSAGE);
            int iTs   = c.getColumnIndexOrThrow(COL_TIMESTAMP_MS);
            int iRead = c.getColumnIndexOrThrow(COL_IS_READ);
            while (c.moveToNext()) {
                list.add(new ChatMessage(
                        c.getLong(iId),
                        c.getString(iSid),
                        c.getString(iSn),
                        c.getString(iMsg),
                        c.getLong(iTs),
                        c.getInt(iRead) != 0));
            }
        } finally {
            c.close();
        }
        // Reverse so oldest message is first (conversation order)
        java.util.Collections.reverse(list);
        return list;
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

    /** Returns the number of messages that have not been read yet. */
    public int getUnreadCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE " + COL_IS_READ + "=0", null);
        if (c == null) return 0;
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Marks all messages as read. */
    public void markAllRead() {
        ContentValues cv = new ContentValues();
        cv.put(COL_IS_READ, 1);
        getWritableDatabase().update(TABLE, cv, COL_IS_READ + "=0", null);
    }

    /** Deletes messages older than {@code keepCount} messages (keeps the newest ones). */
    public void pruneOldMessages(int keepCount) {
        getWritableDatabase().execSQL(
                "DELETE FROM " + TABLE + " WHERE " + COL_ID + " NOT IN "
                + "(SELECT " + COL_ID + " FROM " + TABLE
                + " ORDER BY " + COL_TIMESTAMP_MS + " DESC LIMIT " + keepCount + ")");
    }
}
