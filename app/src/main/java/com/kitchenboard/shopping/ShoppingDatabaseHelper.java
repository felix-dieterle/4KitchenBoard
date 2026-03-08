package com.kitchenboard.shopping;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class ShoppingDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "shopping.db";
    private static final int DB_VERSION = 6;

    static final String TABLE = "shopping_items";
    static final String COL_ID = "_id";
    static final String COL_NAME = "name";
    static final String COL_CATEGORY = "category";
    static final String COL_CHECKED = "checked";
    static final String COL_CREATED = "created_at";
    static final String COL_QUANTITY = "quantity";
    static final String COL_SHOP = "shop";
    static final String COL_PRIORITY = "priority";

    static final String TABLE_CATEGORIES = "categories";
    static final String COL_CAT_ID = "_id";
    static final String COL_CAT_NAME = "name";
    /** Optional user-chosen icon name (maps to a drawable via {@link IconProvider}). */
    static final String COL_CAT_ICON = "icon_name";

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
                COL_CREATED + " INTEGER DEFAULT 0, " +
                COL_QUANTITY + " INTEGER DEFAULT 1, " +
                COL_SHOP + " TEXT DEFAULT '', " +
                COL_PRIORITY + " INTEGER DEFAULT 2)");
        db.execSQL("CREATE TABLE " + TABLE_CATEGORIES + " (" +
                COL_CAT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_CAT_NAME + " TEXT NOT NULL UNIQUE, " +
                COL_CAT_ICON + " TEXT DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // categories table was introduced in v2; create it if upgrading from v1
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CATEGORIES + " (" +
                    COL_CAT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_CAT_NAME + " TEXT NOT NULL UNIQUE, " +
                    COL_CAT_ICON + " TEXT DEFAULT '')");
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_QUANTITY + " INTEGER DEFAULT 1");
            } catch (SQLiteException ignored) {
                // Column may already exist if upgrade runs twice; ignore.
            }
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_SHOP + " TEXT DEFAULT ''");
            } catch (SQLiteException ignored) {
                // Column may already exist if upgrade runs twice; ignore.
            }
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_PRIORITY + " INTEGER DEFAULT 2");
            } catch (SQLiteException ignored) {
                // Column may already exist if upgrade runs twice; ignore.
            }
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_CATEGORIES + " ADD COLUMN " + COL_CAT_ICON + " TEXT DEFAULT ''");
            } catch (SQLiteException ignored) {
                // Column may already exist if upgrade runs twice; ignore.
            }
        }
    }

    /** Insert a new unchecked item. Returns the new row id. */
    public long addItem(String name, String category, int quantity, String shop, int priority) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_CHECKED, 0);
        cv.put(COL_CREATED, System.currentTimeMillis());
        cv.put(COL_QUANTITY, quantity < 1 ? 1 : quantity);
        cv.put(COL_SHOP, shop != null ? shop : "");
        cv.put(COL_PRIORITY, priority);
        return getWritableDatabase().insert(TABLE, null, cv);
    }

    /** Insert a new unchecked item. Returns the new row id. */
    public long addItem(String name, String category, int quantity, String shop) {
        return addItem(name, category, quantity, shop, ShoppingItem.PRIORITY_NORMAL);
    }

    /** Insert a new unchecked item with quantity 1 and no shop. */
    public long addItem(String name, String category, int quantity) {
        return addItem(name, category, quantity, "");
    }

    /** Insert a new unchecked item with quantity 1. */
    public long addItem(String name, String category) {
        return addItem(name, category, 1, "");
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

    /** Update the quantity of an item. */
    public void updateItemQuantity(long id, int quantity) {
        ContentValues cv = new ContentValues();
        cv.put(COL_QUANTITY, quantity < 1 ? 1 : quantity);
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Update the assigned shop of an item. */
    public void updateItemShop(long id, String shop) {
        ContentValues cv = new ContentValues();
        cv.put(COL_SHOP, shop != null ? shop : "");
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Update the priority of an item. */
    public void updateItemPriority(long id, int priority) {
        ContentValues cv = new ContentValues();
        cv.put(COL_PRIORITY, priority);
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Returns all unchecked items, ordered by priority then category then name. */
    public List<ShoppingItem> getActiveItems() {
        List<ShoppingItem> items = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_CATEGORY, COL_CHECKED, COL_QUANTITY, COL_SHOP, COL_PRIORITY},
                COL_CHECKED + "=?", new String[]{"0"}, null, null,
                COL_PRIORITY + " ASC, " + COL_CATEGORY + " ASC, " + COL_NAME + " ASC");
        while (c.moveToNext()) {
            items.add(new ShoppingItem(
                    c.getLong(0), c.getString(1), c.getString(2), false,
                    c.getInt(4), c.getString(5), c.getInt(6)));
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
     * Updates the icon name stored for a category. Creates the category row if it
     * does not exist yet.
     */
    public void setCategoryIcon(String categoryName, String iconName) {
        ContentValues cv = new ContentValues();
        cv.put(COL_CAT_NAME, categoryName);
        cv.put(COL_CAT_ICON, iconName != null ? iconName : "");
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.update(TABLE_CATEGORIES, cv,
                COL_CAT_NAME + "=?", new String[]{categoryName});
        if (rows == 0) {
            db.insertWithOnConflict(TABLE_CATEGORIES, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    /**
     * Returns the icon name stored for the given category, or {@code ""} if none.
     */
    public String getCategoryIcon(String categoryName) {
        Cursor c = getReadableDatabase().query(
                TABLE_CATEGORIES, new String[]{COL_CAT_ICON},
                COL_CAT_NAME + "=?", new String[]{categoryName}, null, null, null);
        try {
            if (c.moveToFirst()) {
                String icon = c.getString(0);
                return icon != null ? icon : "";
            }
        } finally {
            c.close();
        }
        return "";
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
