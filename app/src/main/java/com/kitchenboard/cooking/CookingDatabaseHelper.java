package com.kitchenboard.cooking;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CookingDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "cooking.db";
    private static final int    DB_VERSION = 1;

    private static final String TABLE           = "dishes";
    private static final String COL_ID          = "_id";
    private static final String COL_NAME        = "name";
    private static final String COL_DURATION    = "duration_minutes";
    private static final String COL_INGREDIENTS = "ingredients";
    private static final String COL_NOTES       = "notes";
    private static final String COL_LAST_COOKED = "last_cooked";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public CookingDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID          + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_NAME        + " TEXT NOT NULL, " +
                COL_DURATION    + " INTEGER DEFAULT 0, " +
                COL_INGREDIENTS + " TEXT, " +
                COL_NOTES       + " TEXT, " +
                COL_LAST_COOKED + " TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    private Dish fromCursor(Cursor c) {
        return new Dish(
                c.getLong(0),
                c.getString(1),
                c.getInt(2),
                c.isNull(3) ? null : c.getString(3),
                c.isNull(4) ? null : c.getString(4),
                c.isNull(5) ? null : c.getString(5));
    }

    /** Returns all dishes ordered by name ASC. */
    public List<Dish> getAllDishes() {
        List<Dish> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_DURATION, COL_INGREDIENTS, COL_NOTES, COL_LAST_COOKED},
                null, null, null, null, COL_NAME + " ASC");
        try {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** Returns dishes where last_cooked IS NOT NULL, ordered by last_cooked DESC, limited to limit rows. */
    public List<Dish> getRecentlyCooked(int limit) {
        List<Dish> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_DURATION, COL_INGREDIENTS, COL_NOTES, COL_LAST_COOKED},
                COL_LAST_COOKED + " IS NOT NULL",
                null, null, null,
                COL_LAST_COOKED + " DESC",
                String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * Returns dishes where last_cooked IS NULL or last_cooked is older than daysThreshold days.
     * When daysThreshold >= 9999, returns all dishes. Ordered by last_cooked ASC (NULLs first).
     */
    public List<Dish> getLongNotCooked(int daysThreshold) {
        List<Dish> list = new ArrayList<>();
        String selection = daysThreshold >= 9999 ? null
                : COL_LAST_COOKED + " IS NULL OR date(" + COL_LAST_COOKED
                  + ") <= date('now', '-" + daysThreshold + " days')";
        // NULLs first: CASE WHEN last_cooked IS NULL THEN 0 ELSE 1 END, then date ASC
        String orderBy = "CASE WHEN " + COL_LAST_COOKED + " IS NULL THEN 0 ELSE 1 END ASC, "
                + COL_LAST_COOKED + " ASC";
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_DURATION, COL_INGREDIENTS, COL_NOTES, COL_LAST_COOKED},
                selection, null, null, null, orderBy);
        try {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * Same filter as getLongNotCooked but ordered by duration_minutes ASC,
     * treating 0 (unset) as 9999 so those sort last.
     */
    public List<Dish> getLongNotCookedByDuration(int daysThreshold) {
        List<Dish> list = new ArrayList<>();
        String selection = daysThreshold >= 9999 ? null
                : COL_LAST_COOKED + " IS NULL OR date(" + COL_LAST_COOKED
                  + ") <= date('now', '-" + daysThreshold + " days')";
        String orderBy = "CASE WHEN " + COL_DURATION + " = 0 THEN 9999 ELSE "
                + COL_DURATION + " END ASC";
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_DURATION, COL_INGREDIENTS, COL_NOTES, COL_LAST_COOKED},
                selection, null, null, null, orderBy);
        try {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** Inserts a new dish. Returns the new row id. */
    public long addDish(String name, int durationMinutes, String ingredients, String notes) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_DURATION, durationMinutes);
        if (ingredients != null) cv.put(COL_INGREDIENTS, ingredients);
        if (notes != null)       cv.put(COL_NOTES, notes);
        return getWritableDatabase().insert(TABLE, null, cv);
    }

    /** Updates an existing dish. */
    public void updateDish(long id, String name, int durationMinutes, String ingredients, String notes) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_DURATION, durationMinutes);
        if (ingredients != null) cv.put(COL_INGREDIENTS, ingredients); else cv.putNull(COL_INGREDIENTS);
        if (notes != null)       cv.put(COL_NOTES, notes);             else cv.putNull(COL_NOTES);
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Permanently deletes a dish. */
    public void deleteDish(long id) {
        getWritableDatabase().delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Sets last_cooked to today's date (YYYY-MM-DD). */
    public void markAsCooked(long id) {
        ContentValues cv = new ContentValues();
        cv.put(COL_LAST_COOKED, DATE_FMT.format(new Date()));
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }
}
