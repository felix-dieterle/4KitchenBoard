package com.kitchenboard.immobilien;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite database helper for the Immobilien-Alerts module.
 *
 * <p>Tables:
 * <ul>
 *   <li>{@code immobilien_alerts} – user-configured search alert definitions</li>
 *   <li>{@code immobilien_listings} – listing URLs discovered for each alert</li>
 * </ul>
 */
public class ImmobilienDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "immobilien.db";
    private static final int    DB_VERSION = 2;

    // ── Table: immobilien_alerts ──────────────────────────────────────────────
    static final String TABLE_ALERTS              = "appkitchen_immobilien_alerts";
    static final String COL_ALERT_ID              = "id";
    static final String COL_ALERT_NAME            = "name";
    static final String COL_ALERT_URL             = "search_url";
    static final String COL_ALERT_INTERVAL        = "check_interval_minutes";
    static final String COL_ALERT_ACTIVE          = "active";
    static final String COL_ALERT_LAST_CHECK      = "last_check_ms";

    // ── Table: immobilien_listings ────────────────────────────────────────────
    static final String TABLE_LISTINGS            = "appkitchen_immobilien_listings";
    static final String COL_LISTING_ID            = "id";
    static final String COL_LISTING_ALERT_ID      = "alert_id";
    static final String COL_LISTING_URL           = "listing_url";
    static final String COL_LISTING_FIRST_SEEN    = "first_seen_ms";
    static final String COL_LISTING_NOTIFIED      = "notified";

    public ImmobilienDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_ALERTS + " ("
                + COL_ALERT_ID       + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_ALERT_NAME     + " TEXT NOT NULL, "
                + COL_ALERT_URL      + " TEXT NOT NULL, "
                + COL_ALERT_INTERVAL + " INTEGER NOT NULL DEFAULT 60, "
                + COL_ALERT_ACTIVE   + " INTEGER NOT NULL DEFAULT 1, "
                + COL_ALERT_LAST_CHECK + " INTEGER NOT NULL DEFAULT 0"
                + ")");

        db.execSQL("CREATE TABLE " + TABLE_LISTINGS + " ("
                + COL_LISTING_ID         + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_LISTING_ALERT_ID   + " INTEGER NOT NULL, "
                + COL_LISTING_URL        + " TEXT NOT NULL, "
                + COL_LISTING_FIRST_SEEN + " INTEGER NOT NULL, "
                + COL_LISTING_NOTIFIED   + " INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE(" + COL_LISTING_ALERT_ID + ", " + COL_LISTING_URL + ")"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE immobilien_alerts RENAME TO " + TABLE_ALERTS);
            db.execSQL("ALTER TABLE immobilien_listings RENAME TO " + TABLE_LISTINGS);
        }
    }

    // ── Alert CRUD ────────────────────────────────────────────────────────────

    public long addAlert(ImmobilienAlert alert) {
        ContentValues cv = toAlertValues(alert);
        return getWritableDatabase().insert(TABLE_ALERTS, null, cv);
    }

    public void updateAlert(ImmobilienAlert alert) {
        ContentValues cv = toAlertValues(alert);
        getWritableDatabase().update(TABLE_ALERTS, cv,
                COL_ALERT_ID + "=?", new String[]{String.valueOf(alert.id)});
    }

    public void deleteAlert(long alertId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_LISTINGS, COL_LISTING_ALERT_ID + "=?",
                new String[]{String.valueOf(alertId)});
        db.delete(TABLE_ALERTS, COL_ALERT_ID + "=?",
                new String[]{String.valueOf(alertId)});
    }

    public List<ImmobilienAlert> getAllAlerts() {
        List<ImmobilienAlert> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_ALERTS,
                null, null, null, null, null, COL_ALERT_NAME + " ASC");
        while (c.moveToNext()) {
            list.add(alertFromCursor(c));
        }
        c.close();
        return list;
    }

    /** Returns only alerts that are active and whose next-check time has passed. */
    public List<ImmobilienAlert> getDueAlerts() {
        List<ImmobilienAlert> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        // last_check_ms + interval_minutes*60000 <= now  AND  active = 1
        String where = COL_ALERT_ACTIVE + "=1 AND ("
                + COL_ALERT_LAST_CHECK + " + " + COL_ALERT_INTERVAL + " * 60000) <= " + now;
        Cursor c = getReadableDatabase().query(TABLE_ALERTS,
                null, where, null, null, null, null);
        while (c.moveToNext()) {
            list.add(alertFromCursor(c));
        }
        c.close();
        return list;
    }

    public void updateLastCheck(long alertId, long timestampMs) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ALERT_LAST_CHECK, timestampMs);
        getWritableDatabase().update(TABLE_ALERTS, cv,
                COL_ALERT_ID + "=?", new String[]{String.valueOf(alertId)});
    }

    // ── Listing CRUD ──────────────────────────────────────────────────────────

    /**
     * Inserts a listing URL if it doesn't already exist for the given alert.
     *
     * @return {@code true} if this is a brand-new (previously unseen) listing.
     */
    public boolean addListingIfNew(long alertId, String listingUrl) {
        ContentValues cv = new ContentValues();
        cv.put(COL_LISTING_ALERT_ID,   alertId);
        cv.put(COL_LISTING_URL,        listingUrl);
        cv.put(COL_LISTING_FIRST_SEEN, System.currentTimeMillis());
        cv.put(COL_LISTING_NOTIFIED,   0);
        long rowId = getWritableDatabase().insertWithOnConflict(
                TABLE_LISTINGS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return rowId != -1;
    }

    /** Returns all listings for an alert, newest first. */
    public List<ImmobilienListing> getListingsForAlert(long alertId) {
        List<ImmobilienListing> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_LISTINGS, null,
                COL_LISTING_ALERT_ID + "=?", new String[]{String.valueOf(alertId)},
                null, null, COL_LISTING_FIRST_SEEN + " DESC");
        while (c.moveToNext()) {
            list.add(listingFromCursor(c));
        }
        c.close();
        return list;
    }

    /** Counts listings for an alert that have not yet been notified. */
    public int countNewListings(long alertId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_LISTINGS
                        + " WHERE " + COL_LISTING_ALERT_ID + "=? AND "
                        + COL_LISTING_NOTIFIED + "=0",
                new String[]{String.valueOf(alertId)});
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /** Counts total listings for an alert. */
    public int countAllListings(long alertId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_LISTINGS
                        + " WHERE " + COL_LISTING_ALERT_ID + "=?",
                new String[]{String.valueOf(alertId)});
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /** Marks all listings for an alert as notified. */
    public void markAllNotified(long alertId) {
        ContentValues cv = new ContentValues();
        cv.put(COL_LISTING_NOTIFIED, 1);
        getWritableDatabase().update(TABLE_LISTINGS, cv,
                COL_LISTING_ALERT_ID + "=?", new String[]{String.valueOf(alertId)});
    }

    /** Deletes all listings for an alert (used when resetting). */
    public void clearListings(long alertId) {
        getWritableDatabase().delete(TABLE_LISTINGS,
                COL_LISTING_ALERT_ID + "=?", new String[]{String.valueOf(alertId)});
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ContentValues toAlertValues(ImmobilienAlert a) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ALERT_NAME,     a.name);
        cv.put(COL_ALERT_URL,      a.searchUrl);
        cv.put(COL_ALERT_INTERVAL, a.checkIntervalMinutes);
        cv.put(COL_ALERT_ACTIVE,   a.active ? 1 : 0);
        cv.put(COL_ALERT_LAST_CHECK, a.lastCheckMs);
        return cv;
    }

    private ImmobilienAlert alertFromCursor(Cursor c) {
        return new ImmobilienAlert(
                c.getLong(c.getColumnIndexOrThrow(COL_ALERT_ID)),
                c.getString(c.getColumnIndexOrThrow(COL_ALERT_NAME)),
                c.getString(c.getColumnIndexOrThrow(COL_ALERT_URL)),
                c.getInt(c.getColumnIndexOrThrow(COL_ALERT_INTERVAL)),
                c.getInt(c.getColumnIndexOrThrow(COL_ALERT_ACTIVE)) == 1,
                c.getLong(c.getColumnIndexOrThrow(COL_ALERT_LAST_CHECK))
        );
    }

    private ImmobilienListing listingFromCursor(Cursor c) {
        return new ImmobilienListing(
                c.getLong(c.getColumnIndexOrThrow(COL_LISTING_ID)),
                c.getLong(c.getColumnIndexOrThrow(COL_LISTING_ALERT_ID)),
                c.getString(c.getColumnIndexOrThrow(COL_LISTING_URL)),
                c.getLong(c.getColumnIndexOrThrow(COL_LISTING_FIRST_SEEN)),
                c.getInt(c.getColumnIndexOrThrow(COL_LISTING_NOTIFIED)) == 1
        );
    }
}
