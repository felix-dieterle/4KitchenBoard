package com.kitchenboard.calendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    /**
     * Adds appointments for a recurring pattern from startDate to endDate (both inclusive).
     * recurrence values: "once", "daily", "weekdays" (Mon–Fri), "mon_sat" (Mon–Sat),
     *                    "weekly" (same weekday), "monthly" (same day of month).
     */
    public void addRecurringAppointments(String startDate, String endDate,
                                         String title, String recurrence) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date startD = fmt.parse(startDate);
            Date endD   = fmt.parse(endDate);
            if (startD == null || endD == null) return;

            Calendar startCal = Calendar.getInstance();
            startCal.setTime(startD);
            int startDow = startCal.get(Calendar.DAY_OF_WEEK);
            int startDom = startCal.get(Calendar.DAY_OF_MONTH);

            Calendar cal = Calendar.getInstance();
            cal.setTime(startD);

            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                while (!cal.getTime().after(endD)) {
                    int dow = cal.get(Calendar.DAY_OF_WEEK);
                    int dom = cal.get(Calendar.DAY_OF_MONTH);
                    boolean add = false;
                    switch (recurrence) {
                        case "once":
                        case "daily":
                            add = true;
                            break;
                        case "weekdays":
                            add = (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY);
                            break;
                        case "mon_sat":
                            add = (dow != Calendar.SUNDAY);
                            break;
                        case "weekly":
                            add = (dow == startDow);
                            break;
                        case "monthly":
                            add = (dom == startDom);
                            break;
                    }
                    if (add) {
                        ContentValues cv = new ContentValues();
                        cv.put(COL_DATE, fmt.format(cal.getTime()));
                        cv.put(COL_TITLE, title);
                        db.insert(TABLE_APPOINTMENTS, null, cv);
                    }
                    if ("once".equals(recurrence)) break;
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            // silently ignore parse errors — dates come from our own DATE_FMT so this should
            // never fire, but guard defensively to avoid crashing the calendar UI
        }
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
