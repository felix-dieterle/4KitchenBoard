package com.kitchenboard.calendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class CalendarDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "calendar.db";
    private static final int    DB_VERSION = 1;

    static final String TABLE_APPOINTMENTS = "appointments";
    static final String TABLE_TEMPLATES    = "standard_templates";
    static final String COL_ID    = "_id";
    static final String COL_DATE  = "date";   // YYYY-MM-DD
    static final String COL_TITLE = "title";

    public CalendarDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_APPOINTMENTS + " (" +
                COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_DATE  + " TEXT NOT NULL, " +
                COL_TITLE + " TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_TEMPLATES + " (" +
                COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE + " TEXT NOT NULL UNIQUE)");

        // Pre-populate with example standard templates
        ContentValues cv = new ContentValues();
        String[] defaults = {"Finni Besuch", "Hebamme", "Arzttermin"};
        for (String t : defaults) {
            cv.put(COL_TITLE, t);
            db.insert(TABLE_TEMPLATES, null, cv);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // reserved for future upgrades
    }

    // ── Appointments ──────────────────────────────────────────────────────────

    /** Inserts a new appointment. Returns the new row id. */
    public long addAppointment(String date, String title) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DATE, date);
        cv.put(COL_TITLE, title);
        return getWritableDatabase().insert(TABLE_APPOINTMENTS, null, cv);
    }

    /** Permanently deletes an appointment. */
    public void deleteAppointment(long id) {
        getWritableDatabase().delete(TABLE_APPOINTMENTS,
                COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Returns all appointments for a given date (YYYY-MM-DD), ordered by title. */
    public List<Appointment> getAppointmentsForDate(String date) {
        List<Appointment> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_APPOINTMENTS,
                new String[]{COL_ID, COL_TITLE},
                COL_DATE + "=?", new String[]{date},
                null, null, COL_TITLE + " ASC");
        while (c.moveToNext()) {
            list.add(new Appointment(c.getLong(0), date, c.getString(1)));
        }
        c.close();
        return list;
    }

    // ── Standard templates ────────────────────────────────────────────────────

    /** Inserts a new standard template. Returns the new row id, or -1 if duplicate. */
    public long addTemplate(String title) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, title);
        return getWritableDatabase().insertWithOnConflict(
                TABLE_TEMPLATES, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Permanently deletes a standard template. */
    public void deleteTemplate(long id) {
        getWritableDatabase().delete(TABLE_TEMPLATES,
                COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Returns all standard templates ordered alphabetically. */
    public List<Template> getTemplates() {
        List<Template> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TEMPLATES,
                new String[]{COL_ID, COL_TITLE},
                null, null, null, null, COL_TITLE + " ASC");
        while (c.moveToNext()) {
            list.add(new Template(c.getLong(0), c.getString(1)));
        }
        c.close();
        return list;
    }
}
