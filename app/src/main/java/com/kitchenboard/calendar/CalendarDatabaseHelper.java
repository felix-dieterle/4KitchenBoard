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
    private static final int    DB_VERSION = 5;

    static final String TABLE_APPOINTMENTS  = "appointments";
    static final String TABLE_TEMPLATES     = "standard_templates";
    static final String TABLE_PERSONS       = "persons";
    static final String TABLE_PERSON_GROUPS = "person_groups";
    static final String TABLE_GROUP_MEMBERS = "group_members";

    static final String COL_ID        = "_id";
    static final String COL_DATE      = "date";       // YYYY-MM-DD
    static final String COL_TIME      = "time";       // HH:mm, nullable
    static final String COL_TITLE     = "title";
    static final String COL_SERIES_ID = "series_id";  // INTEGER, nullable – shared by all entries of a series
    static final String COL_PERSON_ID = "person_id";  // INTEGER, nullable – assigned person
    static final String COL_COLOR     = "color";      // hex color string for persons
    static final String COL_GROUP_ID  = "group_id";

    /** Predefined colors cycled through when auto-assigning to new persons. */
    static final String[] PERSON_COLORS = {
            "#E53935", "#D81B60", "#8E24AA", "#1E88E5",
            "#00ACC1", "#43A047", "#FB8C00", "#6D4C41"
    };

    /** Orders timed appointments first (by time ASC), then untimed ones (by title ASC). */
    private static final String ORDER_BY_TIME_THEN_TITLE =
            "CASE WHEN " + COL_TIME + " IS NULL THEN 1 ELSE 0 END ASC, "
                    + COL_TIME + " ASC, " + COL_TITLE + " ASC";

    public CalendarDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_APPOINTMENTS + " (" +
                COL_ID        + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_DATE      + " TEXT NOT NULL, " +
                COL_TIME      + " TEXT, " +
                COL_TITLE     + " TEXT NOT NULL, " +
                COL_SERIES_ID + " INTEGER, " +
                COL_PERSON_ID + " INTEGER, " +
                COL_GROUP_ID  + " INTEGER)");

        db.execSQL("CREATE TABLE " + TABLE_TEMPLATES + " (" +
                COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE + " TEXT NOT NULL UNIQUE)");

        db.execSQL("CREATE TABLE " + TABLE_PERSONS + " (" +
                COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE + " TEXT NOT NULL UNIQUE, " +
                COL_COLOR + " TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_PERSON_GROUPS + " (" +
                COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE + " TEXT NOT NULL UNIQUE)");

        db.execSQL("CREATE TABLE " + TABLE_GROUP_MEMBERS + " (" +
                COL_GROUP_ID  + " INTEGER NOT NULL, " +
                COL_PERSON_ID + " INTEGER NOT NULL, " +
                "PRIMARY KEY(" + COL_GROUP_ID + ", " + COL_PERSON_ID + "))");

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
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_APPOINTMENTS + " ADD COLUMN " + COL_TIME + " TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_APPOINTMENTS + " ADD COLUMN " + COL_SERIES_ID + " INTEGER");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_APPOINTMENTS + " ADD COLUMN " + COL_PERSON_ID + " INTEGER");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PERSONS + " (" +
                    COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_TITLE + " TEXT NOT NULL UNIQUE, " +
                    COL_COLOR + " TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PERSON_GROUPS + " (" +
                    COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_TITLE + " TEXT NOT NULL UNIQUE)");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP_MEMBERS + " (" +
                    COL_GROUP_ID  + " INTEGER NOT NULL, " +
                    COL_PERSON_ID + " INTEGER NOT NULL, " +
                    "PRIMARY KEY(" + COL_GROUP_ID + ", " + COL_PERSON_ID + "))");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + TABLE_APPOINTMENTS + " ADD COLUMN " + COL_GROUP_ID + " INTEGER");
        }
    }

    // ── Appointments ──────────────────────────────────────────────────────────

    /** Inserts a new appointment without a time. Returns the new row id. */
    public long addAppointment(String date, String title) {
        return addAppointment(date, null, title, null);
    }

    /** Inserts a new appointment with an optional time (HH:mm, may be null). Returns new row id. */
    public long addAppointment(String date, String time, String title) {
        return addAppointment(date, time, title, null);
    }

    /** Inserts a new appointment with an optional time and optional personId. Returns new row id. */
    public long addAppointment(String date, String time, String title, Long personId) {
        return addAppointment(date, time, title, personId, null);
    }

    /** Inserts a new appointment with an optional time, optional personId and optional groupId. Returns new row id. */
    public long addAppointment(String date, String time, String title, Long personId, Long groupId) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DATE, date);
        if (time != null && !time.isEmpty()) cv.put(COL_TIME, time);
        cv.put(COL_TITLE, title);
        if (personId != null) cv.put(COL_PERSON_ID, personId);
        if (groupId  != null) cv.put(COL_GROUP_ID,  groupId);
        return getWritableDatabase().insert(TABLE_APPOINTMENTS, null, cv);
    }

    /** Permanently deletes an appointment. */
    public void deleteAppointment(long id) {
        getWritableDatabase().delete(TABLE_APPOINTMENTS,
                COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Deletes all appointments that share the given series id. */
    public void deleteSeriesById(long seriesId) {
        getWritableDatabase().delete(TABLE_APPOINTMENTS,
                COL_SERIES_ID + "=?", new String[]{String.valueOf(seriesId)});
    }

    /** Moves an appointment to a new date and updates its time. */
    public void updateAppointmentDateTime(long id, String newDate, String newTime) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DATE, newDate);
        if (newTime != null && !newTime.isEmpty()) {
            cv.put(COL_TIME, newTime);
        } else {
            cv.putNull(COL_TIME);
        }
        // Moving an appointment breaks it out of its series
        cv.putNull(COL_SERIES_ID);
        getWritableDatabase().update(TABLE_APPOINTMENTS, cv,
                COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Returns all appointments for a given date (YYYY-MM-DD), timed ones first then by title. */
    public List<Appointment> getAppointmentsForDate(String date) {
        return getAppointmentsForDate(date, null);
    }

    /**
     * Returns appointments for a given date, optionally filtered by person.
     * If personId is null, returns all appointments.
     * If personId is a valid person ID, returns only appointments assigned to that person.
     * If personId is a Long with value 0, returns appointments with no person assigned.
     */
    public List<Appointment> getAppointmentsForDate(String date, Long personId) {
        List<Appointment> list = new ArrayList<>();
        String selection;
        String[] selectionArgs;
        if (personId == null) {
            selection     = COL_DATE + "=?";
            selectionArgs = new String[]{date};
        } else {
            selection     = COL_DATE + "=? AND " + COL_PERSON_ID + "=?";
            selectionArgs = new String[]{date, String.valueOf(personId)};
        }
        Cursor c = getReadableDatabase().query(TABLE_APPOINTMENTS,
                new String[]{COL_ID, COL_TIME, COL_TITLE, COL_SERIES_ID, COL_PERSON_ID, COL_GROUP_ID},
                selection, selectionArgs,
                null, null, ORDER_BY_TIME_THEN_TITLE);
        while (c.moveToNext()) {
            Long sid = c.isNull(3) ? null : c.getLong(3);
            Long pid = c.isNull(4) ? null : c.getLong(4);
            Long gid = c.isNull(5) ? null : c.getLong(5);
            list.add(new Appointment(c.getLong(0), date, c.getString(1), c.getString(2), sid, pid, gid));
        }
        c.close();
        return list;
    }

    /**
     * Returns appointments for a given date belonging to any of the given person IDs,
     * or directly assigned to the given group.
     * The early-return guard ensures the parenthesised WHERE clause is never empty:
     * at least one of personIds/groupId must be non-null when building the query.
     * Used for group filtering.
     */
    public List<Appointment> getAppointmentsForDateByGroup(String date, List<Long> personIds, Long groupId) {
        // Early return: if no filter criteria, fall back to "return all"
        if ((personIds == null || personIds.isEmpty()) && groupId == null) return getAppointmentsForDate(date);
        List<Appointment> list = new ArrayList<>();
        StringBuilder selection = new StringBuilder(COL_DATE + "=? AND (");
        List<String> argsList = new ArrayList<>();
        argsList.add(date);
        boolean hasPersonCondition = personIds != null && !personIds.isEmpty();
        if (hasPersonCondition) {
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < personIds.size(); i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
                argsList.add(String.valueOf(personIds.get(i)));
            }
            selection.append(COL_PERSON_ID).append(" IN (").append(placeholders).append(")");
        }
        if (groupId != null) {
            if (hasPersonCondition) selection.append(" OR ");
            selection.append(COL_GROUP_ID).append("=?");
            argsList.add(String.valueOf(groupId));
        }
        selection.append(")");
        String[] args = argsList.toArray(new String[0]);
        Cursor c = getReadableDatabase().query(TABLE_APPOINTMENTS,
                new String[]{COL_ID, COL_TIME, COL_TITLE, COL_SERIES_ID, COL_PERSON_ID, COL_GROUP_ID},
                selection.toString(), args, null, null, ORDER_BY_TIME_THEN_TITLE);
        while (c.moveToNext()) {
            Long sid = c.isNull(3) ? null : c.getLong(3);
            Long pid = c.isNull(4) ? null : c.getLong(4);
            Long gid = c.isNull(5) ? null : c.getLong(5);
            list.add(new Appointment(c.getLong(0), date, c.getString(1), c.getString(2), sid, pid, gid));
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
     * time is optional (HH:mm or null for all-day).
     * All appointments share the same series_id so the entire series can be deleted at once.
     */
    public void addRecurringAppointments(String startDate, String endDate,
                                         String title, String recurrence, String time,
                                         Long personId) {
        addRecurringAppointments(startDate, endDate, title, recurrence, time, personId, null);
    }

    public void addRecurringAppointments(String startDate, String endDate,
                                         String title, String recurrence, String time,
                                         Long personId, Long groupId) {
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
                // Generate a unique series ID inside the transaction (MAX + 1)
                long seriesId = 1;
                Cursor seqC = db.rawQuery(
                        "SELECT COALESCE(MAX(" + COL_SERIES_ID + "), 0) + 1 FROM "
                                + TABLE_APPOINTMENTS, null);
                if (seqC.moveToFirst()) seriesId = seqC.getLong(0);
                seqC.close();
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
                        if (time != null && !time.isEmpty()) cv.put(COL_TIME, time);
                        cv.put(COL_SERIES_ID, seriesId);
                        if (personId != null) cv.put(COL_PERSON_ID, personId);
                        if (groupId  != null) cv.put(COL_GROUP_ID,  groupId);
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

    // ── Persons ───────────────────────────────────────────────────────────────

    /** Returns all persons ordered by name. */
    public List<Person> getPersons() {
        List<Person> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_PERSONS,
                new String[]{COL_ID, COL_TITLE, COL_COLOR},
                null, null, null, null, COL_TITLE + " ASC");
        while (c.moveToNext()) {
            list.add(new Person(c.getLong(0), c.getString(1), c.getString(2)));
        }
        c.close();
        return list;
    }

    /** Adds a person with the given name and color. Returns the new row id, or -1 if duplicate. */
    public long addPerson(String name, String color) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, name);
        cv.put(COL_COLOR, color);
        return getWritableDatabase().insertWithOnConflict(
                TABLE_PERSONS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Permanently deletes a person and removes them from all groups. */
    public void deletePerson(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_GROUP_MEMBERS, COL_PERSON_ID + "=?", new String[]{String.valueOf(id)});
        db.delete(TABLE_PERSONS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    // ── Person groups ─────────────────────────────────────────────────────────

    /** Returns all person groups, each with the IDs of their members. */
    public List<PersonGroup> getPersonGroups() {
        List<PersonGroup> groups = new ArrayList<>();
        Cursor cg = getReadableDatabase().query(TABLE_PERSON_GROUPS,
                new String[]{COL_ID, COL_TITLE},
                null, null, null, null, COL_TITLE + " ASC");
        while (cg.moveToNext()) {
            long gid = cg.getLong(0);
            String gname = cg.getString(1);
            List<Long> members = getGroupMemberIds(gid);
            groups.add(new PersonGroup(gid, gname, members));
        }
        cg.close();
        return groups;
    }

    /** Returns the person IDs that belong to the given group. */
    public List<Long> getGroupMemberIds(long groupId) {
        List<Long> ids = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_GROUP_MEMBERS,
                new String[]{COL_PERSON_ID},
                COL_GROUP_ID + "=?", new String[]{String.valueOf(groupId)},
                null, null, null);
        while (c.moveToNext()) ids.add(c.getLong(0));
        c.close();
        return ids;
    }

    /** Adds a person group. Returns the new row id, or -1 if duplicate. */
    public long addPersonGroup(String name) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, name);
        return getWritableDatabase().insertWithOnConflict(
                TABLE_PERSON_GROUPS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Permanently deletes a group and all its member mappings. */
    public void deletePersonGroup(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_GROUP_MEMBERS, COL_GROUP_ID + "=?", new String[]{String.valueOf(id)});
        db.delete(TABLE_PERSON_GROUPS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Adds a person to a group (ignored if already a member). */
    public void addGroupMember(long groupId, long personId) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_ID, groupId);
        cv.put(COL_PERSON_ID, personId);
        getWritableDatabase().insertWithOnConflict(
                TABLE_GROUP_MEMBERS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Removes a person from a group. */
    public void removeGroupMember(long groupId, long personId) {
        getWritableDatabase().delete(TABLE_GROUP_MEMBERS,
                COL_GROUP_ID + "=? AND " + COL_PERSON_ID + "=?",
                new String[]{String.valueOf(groupId), String.valueOf(personId)});
    }
}

