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
    private static final int DB_VERSION = 8;

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

    /** Stores table: one row per shop name, optionally storing GPS coordinates. */
    static final String TABLE_STORES       = "stores";
    static final String COL_STORE_ID       = "_id";
    static final String COL_STORE_NAME     = "name";
    static final String COL_STORE_LAT      = "latitude";
    static final String COL_STORE_LON      = "longitude";
    static final String COL_STORE_RADIUS   = "radius_meters";

    /** History table: one row per distinct item name the user has ever submitted. */
    static final String TABLE_ITEM_HISTORY = "item_history";
    static final String COL_HIST_NAME      = "name";

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
        db.execSQL("CREATE TABLE " + TABLE_STORES + " (" +
                COL_STORE_ID     + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_STORE_NAME   + " TEXT NOT NULL UNIQUE, " +
                COL_STORE_LAT    + " REAL DEFAULT 0, " +
                COL_STORE_LON    + " REAL DEFAULT 0, " +
                COL_STORE_RADIUS + " INTEGER DEFAULT 200)");
        db.execSQL("CREATE TABLE " + TABLE_ITEM_HISTORY + " (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_HIST_NAME + " TEXT NOT NULL UNIQUE)");
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
        if (oldVersion < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STORES + " (" +
                    COL_STORE_ID     + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_STORE_NAME   + " TEXT NOT NULL UNIQUE, " +
                    COL_STORE_LAT    + " REAL DEFAULT 0, " +
                    COL_STORE_LON    + " REAL DEFAULT 0, " +
                    COL_STORE_RADIUS + " INTEGER DEFAULT 200)");
        }
        if (oldVersion < 8) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_ITEM_HISTORY + " (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_HIST_NAME + " TEXT NOT NULL UNIQUE)");
            // Seed history with names already stored in shopping_items
            db.execSQL("INSERT OR IGNORE INTO " + TABLE_ITEM_HISTORY + " (" + COL_HIST_NAME + ") " +
                    "SELECT DISTINCT " + COL_NAME + " FROM " + TABLE);
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

    /** Returns all unchecked items, ordered by category then priority then name. */
    public List<ShoppingItem> getActiveItems() {
        List<ShoppingItem> items = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_CATEGORY, COL_CHECKED, COL_QUANTITY, COL_SHOP, COL_PRIORITY},
                COL_CHECKED + "=?", new String[]{"0"}, null, null,
                COL_CATEGORY + " ASC, " + COL_PRIORITY + " ASC, " + COL_NAME + " ASC");
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
     * Names are stored in the dedicated {@link #TABLE_ITEM_HISTORY} table so they
     * persist even when items are deleted or managed via the remote API.
     */
    public List<String> getAllItemNames() {
        List<String> names = new ArrayList<>();
        Cursor c = getReadableDatabase().query(
                TABLE_ITEM_HISTORY, new String[]{COL_HIST_NAME},
                null, null, null, null, COL_HIST_NAME + " ASC");
        while (c.moveToNext()) {
            names.add(c.getString(0));
        }
        c.close();
        return names;
    }

    /**
     * Records {@code name} in the persistent item history used for autocomplete.
     * Silently ignored if {@code name} is empty or already recorded.
     */
    public void addItemNameToHistory(String name) {
        if (name == null || name.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_HIST_NAME, name);
        getWritableDatabase().insertWithOnConflict(
                TABLE_ITEM_HISTORY, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Returns all distinct shop names: the union of non-empty {@code shop} values on items
     * and names from the stores table, deduplicated case-insensitively and sorted A–Z.
     * Names already in the stores table take precedence over the casing used in items.
     */
    public List<String> getAllShopNames() {
        // Use a case-insensitive TreeSet so "REWE" and "Rewe" count as one entry.
        java.util.TreeSet<String> nameSet =
                new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Collect from the stores table FIRST so its casing takes precedence.
        Cursor c2 = getReadableDatabase().query(TABLE_STORES,
                new String[]{COL_STORE_NAME}, null, null, null, null,
                COL_STORE_NAME + " ASC");
        try {
            while (c2.moveToNext()) {
                String name = c2.getString(0);
                if (name != null && !name.isEmpty()) nameSet.add(name);
            }
        } finally {
            c2.close();
        }
        // Then add any shop names used on items that are not yet in the stores table.
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{"DISTINCT " + COL_SHOP},
                COL_SHOP + " != ''", null, null, null, COL_SHOP + " ASC");
        try {
            while (c.moveToNext()) {
                String shop = c.getString(0);
                if (shop != null && !shop.isEmpty()) nameSet.add(shop);
            }
        } finally {
            c.close();
        }
        return new java.util.ArrayList<>(nameSet);
    }

    /**
     * Returns the {@link StoreLocation} for the given store name (case-insensitive match),
     * or {@code null} if no matching row exists in the stores table.
     */
    public StoreLocation getStoreByName(String name) {
        if (name == null || name.isEmpty()) return null;
        Cursor c = getReadableDatabase().query(TABLE_STORES,
                new String[]{COL_STORE_ID, COL_STORE_NAME, COL_STORE_LAT, COL_STORE_LON, COL_STORE_RADIUS},
                "LOWER(" + COL_STORE_NAME + ")=LOWER(?)", new String[]{name},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                return new StoreLocation(
                        c.getLong(0), c.getString(1),
                        c.getDouble(2), c.getDouble(3), c.getInt(4));
            }
        } finally {
            c.close();
        }
        return null;
    }

    /** Removes GPS coordinates for a store (sets lat/lon back to 0, case-insensitive match). */
    public void clearStoreLocation(String name) {
        if (name == null || name.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_STORE_LAT, 0.0);
        cv.put(COL_STORE_LON, 0.0);
        getWritableDatabase().update(TABLE_STORES, cv,
                "LOWER(" + COL_STORE_NAME + ")=LOWER(?)", new String[]{name});
    }

    // ── Store / Geofence helpers ──────────────────────────────────────────────

    /**
     * Adds a store entry by name only (no GPS coordinates) if none exists for this name.
     * No-op if the name is empty or the store already exists.
     */
    public void addStoreIfAbsent(String name) {
        if (name == null || name.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_STORE_NAME,   name);
        cv.put(COL_STORE_LAT,    0.0);
        cv.put(COL_STORE_LON,    0.0);
        cv.put(COL_STORE_RADIUS, 200);
        getWritableDatabase().insertWithOnConflict(
                TABLE_STORES, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Removes the store row with the given name (case-insensitive match).
     * No-op if the name is empty or does not exist in the stores table.
     */
    public void deleteStore(String name) {
        if (name == null || name.isEmpty()) return;
        getWritableDatabase().delete(TABLE_STORES,
                "LOWER(" + COL_STORE_NAME + ")=LOWER(?)", new String[]{name});
    }

    /**
     * Inserts or updates a store entry with GPS coordinates.
     * If a store with this name already exists its coordinates are updated.
     *
     * @param name         Store display name (must match the {@code shop} field on items).
     * @param latitude     WGS-84 latitude.
     * @param longitude    WGS-84 longitude.
     * @param radiusMeters Geofence radius in metres (use ≤ 0 to keep the default 200 m).
     */
    public void upsertStore(String name, double latitude, double longitude, int radiusMeters) {
        if (name == null || name.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_STORE_NAME,   name);
        cv.put(COL_STORE_LAT,    latitude);
        cv.put(COL_STORE_LON,    longitude);
        cv.put(COL_STORE_RADIUS, radiusMeters > 0 ? radiusMeters : 200);
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.update(TABLE_STORES, cv,
                COL_STORE_NAME + "=?", new String[]{name});
        if (rows == 0) {
            db.insertWithOnConflict(TABLE_STORES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /**
     * Returns all stores that have valid GPS coordinates ({@link StoreLocation#hasValidCoordinates()}).
     */
    public List<StoreLocation> getStoresWithLocation() {
        List<StoreLocation> result = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_STORES,
                new String[]{COL_STORE_ID, COL_STORE_NAME, COL_STORE_LAT, COL_STORE_LON, COL_STORE_RADIUS},
                COL_STORE_LAT + " != 0 OR " + COL_STORE_LON + " != 0",
                null, null, null, COL_STORE_NAME + " ASC");
        try {
            while (c.moveToNext()) {
                result.add(new StoreLocation(
                        c.getLong(0), c.getString(1),
                        c.getDouble(2), c.getDouble(3), c.getInt(4)));
            }
        } finally {
            c.close();
        }
        return result;
    }

    /**
     * Returns all unchecked items whose {@code shop} matches the given store name,
     * ordered by priority then name.
     */
    public List<ShoppingItem> getActiveItemsForShop(String shopName) {
        List<ShoppingItem> items = new ArrayList<>();
        if (shopName == null || shopName.isEmpty()) return items;
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_NAME, COL_CATEGORY, COL_CHECKED, COL_QUANTITY, COL_SHOP, COL_PRIORITY},
                COL_CHECKED + "=? AND " + COL_SHOP + "=?",
                new String[]{"0", shopName}, null, null,
                COL_PRIORITY + " ASC, " + COL_NAME + " ASC");
        try {
            while (c.moveToNext()) {
                items.add(new ShoppingItem(
                        c.getLong(0), c.getString(1), c.getString(2), false,
                        c.getInt(4), c.getString(5), c.getInt(6)));
            }
        } finally {
            c.close();
        }
        return items;
    }
}
