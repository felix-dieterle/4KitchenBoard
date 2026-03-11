package com.kitchenboard.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TaskDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "tasks.db";
    private static final int    DB_VERSION = 3;

    private static final String TABLE          = "appkitchen_tasks";
    private static final String COL_ID         = "_id";
    private static final String COL_TITLE      = "title";
    private static final String COL_ORDER      = "sort_order";
    private static final String COL_ASSIGNED   = "assigned_to";

    public TaskDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID       + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE    + " TEXT NOT NULL, " +
                COL_ORDER    + " INTEGER NOT NULL DEFAULT 0, " +
                COL_ASSIGNED + " TEXT NOT NULL DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN " +
                    COL_ASSIGNED + " TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE tasks RENAME TO " + TABLE);
        }
    }

    private Task fromCursor(Cursor c) {
        return new Task(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3));
    }

    /** Returns all tasks ordered by sort_order ASC. */
    public List<Task> getAllTasks() {
        List<Task> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_ID, COL_TITLE, COL_ORDER, COL_ASSIGNED},
                null, null, null, null, COL_ORDER + " ASC");
        try {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** Inserts a new task at the end of the list with an optional assignee. Returns the new row id. */
    public long addTask(String title, String assignedTo) {
        SQLiteDatabase db = getWritableDatabase();
        int maxOrder = 0;
        Cursor c = db.rawQuery("SELECT MAX(" + COL_ORDER + ") FROM " + TABLE, null);
        try {
            if (c.moveToFirst() && !c.isNull(0)) {
                maxOrder = c.getInt(0);
            }
        } finally {
            c.close();
        }
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE,    title);
        cv.put(COL_ORDER,    maxOrder + 1);
        cv.put(COL_ASSIGNED, assignedTo != null ? assignedTo : "");
        return db.insert(TABLE, null, cv);
    }

    /** Inserts a new unassigned task at the end of the list. Returns the new row id. */
    public long addTask(String title) {
        return addTask(title, "");
    }

    /** Deletes a task permanently. */
    public void deleteTask(long id) {
        getWritableDatabase().delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Updates only the title of a task, leaving the assignee unchanged. */
    public void updateTitle(long id, String title) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, title);
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Updates the title and assignee of a task. */
    public void updateTitleAndAssignee(long id, String title, String assignedTo) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE,    title);
        cv.put(COL_ASSIGNED, assignedTo != null ? assignedTo : "");
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Swaps the sort_order values of two tasks (used for move-up / move-down). */
    public void swapOrder(long id1, int order1, long id2, int order2) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv1 = new ContentValues();
            cv1.put(COL_ORDER, order2);
            db.update(TABLE, cv1, COL_ID + "=?", new String[]{String.valueOf(id1)});

            ContentValues cv2 = new ContentValues();
            cv2.put(COL_ORDER, order1);
            db.update(TABLE, cv2, COL_ID + "=?", new String[]{String.valueOf(id2)});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Updates the sort_order of a single task. */
    public void setSortOrder(long id, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ORDER, sortOrder);
        getWritableDatabase().update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Returns the set of all task IDs stored locally (used for sync merge). */
    public Set<Long> getTaskIds() {
        Set<Long> ids = new HashSet<>();
        Cursor c = getReadableDatabase().query(TABLE, new String[]{COL_ID},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
            }
        } finally {
            c.close();
        }
        return ids;
    }

    /**
     * Inserts a task with a known id (for restoring from the remote backend).
     * Uses CONFLICT_IGNORE so existing rows are not overwritten.
     *
     * @return {@code true} if the row was newly inserted, {@code false} if it already existed.
     */
    public boolean insertTaskWithId(long id, String title, int sortOrder, String assignedTo) {
        ContentValues cv = new ContentValues();
        cv.put(COL_ID,       id);
        cv.put(COL_TITLE,    title);
        cv.put(COL_ORDER,    sortOrder);
        cv.put(COL_ASSIGNED, assignedTo != null ? assignedTo : "");
        long result = getWritableDatabase().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }

    /**
     * Inserts an unassigned task with a known id (for restoring from the remote backend).
     * Uses CONFLICT_IGNORE so existing rows are not overwritten.
     *
     * @return {@code true} if the row was newly inserted, {@code false} if it already existed.
     */
    public boolean insertTaskWithId(long id, String title, int sortOrder) {
        return insertTaskWithId(id, title, sortOrder, "");
    }
}
