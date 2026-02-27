package com.kitchenboard.shopping;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class ShoppingDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "shopping.db";
    private static final int DB_VERSION = 2;

    static final String TABLE = "shopping_items";
    static final String COL_ID = "_id";
    static final String COL_NAME = "name";
    static final String COL_CATEGORY = "category";
    static final String COL_CHECKED = "checked";
    static final String COL_CREATED = "created_at";

    static final String TABLE_CATEGORIES = "categories";
    static final String COL_CAT_ID = "_id";
    static final String COL_CAT_NAME = "name";

    public ShoppingDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_NAME + " TEXT NOT NULL, " +
                COL_CATEGORY + " TEXT NOT NULL, " +
                COL_CHECKED + " INTEGER DEFAULT 0, " +
                COL_CREATED + " INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE " + TABLE_CATEGORIES + " (" +
                COL_CAT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_CAT_NAME + " TEXT NOT NULL UNIQUE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CATEGORIES);
        onCreate(db);
    }

    /** Insert a new unchecked item. Returns the new row id. */
    public long addItem(String name, String category) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_CHECKED, 0);
        cv.put(COL_CREATED, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE, null, cv);
    }

    /** Mark an item as checked (bought) — it will be hidden from the active list. */
    public void checkItem(long id) {
        ContentValues cv = new ContentValues();
        cv.put(COL_CHECKED, 1);
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Permanently delete an item. */
    public void deleteItem(long id) {
        getWritableDatabase().delete(TABLE, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Returns all unchecked items, ordered by category then name. */
    public List<ShoppingItem> getActiveItems() {
        List<ShoppingItem> items = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_CATEGORY, COL_CHECKED},
                COL_CHECKED + "=?", new String[]{"0"}, null, null,
                COL_CATEGORY + " ASC, " + COL_NAME + " ASC");
        while (c.moveToNext()) {
            items.add(new ShoppingItem(
                    c.getLong(0), c.getString(1), c.getString(2), false));
        }
        c.close();
        return items;
    }

    /**
     * Returns all user-defined categories ordered alphabetically.
     */
    public List<String> getCategories() {
        List<String> categories = new ArrayList<>();
        Cursor c = getReadableDatabase().query(
                TABLE_CATEGORIES, new String[]{COL_CAT_NAME},
                null, null, null, null, COL_CAT_NAME + " ASC");
        try {
            while (c.moveToNext()) {
                categories.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return categories;
    }

    /**
     * Adds a new category if it does not already exist (case-sensitive).
     */
    public void addCategory(String name) {
        ContentValues cv = new ContentValues();
        cv.put(COL_CAT_NAME, name);
        getWritableDatabase().insertWithOnConflict(
                TABLE_CATEGORIES, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Returns all distinct item names ever added (including checked ones)
     * for autocomplete suggestions, ordered alphabetically.
     */
    public List<String> getAllItemNames() {
        List<String> names = new ArrayList<>();
        // COL_NAME and TABLE are compile-time constants – safe to interpolate
        Cursor c = getReadableDatabase().query(
                TABLE, new String[]{"DISTINCT " + COL_NAME},
                null, null, COL_NAME, null, COL_NAME + " ASC");
        while (c.moveToNext()) {
            names.add(c.getString(0));
        }
        c.close();
        return names;
    }
}
