package com.kitchenboard.calendar;

public class Appointment {
    private final long id;
    private final String date;
    private final String time;      // nullable, HH:mm
    private final String title;
    private final Long seriesId;    // nullable; shared by all entries of a recurring series
    private final Long personId;    // nullable; person this appointment belongs to
    private final Long groupId;     // nullable; group this appointment belongs to
    private final int reminderMinutes; // minutes before appointment to trigger alarm, 0 = no alarm

    public Appointment(long id, String date, String time, String title, Long seriesId, Long personId, Long groupId) {
        this(id, date, time, title, seriesId, personId, groupId, 0);
    }

    public Appointment(long id, String date, String time, String title, Long seriesId, Long personId, Long groupId, int reminderMinutes) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.title = title;
        this.seriesId = seriesId;
        this.personId = personId;
        this.groupId  = groupId;
        this.reminderMinutes = reminderMinutes;
    }

    public long getId()              { return id; }
    public String getDate()          { return date; }
    public String getTime()          { return time; }
    public String getTitle()         { return title; }
    public Long getSeriesId()        { return seriesId; }
    public Long getPersonId()        { return personId; }
    public Long getGroupId()         { return groupId; }
    public int getReminderMinutes()  { return reminderMinutes; }

    /** Returns a copy of this appointment with a different reminderMinutes value. */
    public Appointment withReminderMinutes(int minutes) {
        return new Appointment(id, date, time, title, seriesId, personId, groupId, minutes);
    }
}
