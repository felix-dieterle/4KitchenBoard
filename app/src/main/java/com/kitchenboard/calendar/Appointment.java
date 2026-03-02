package com.kitchenboard.calendar;

public class Appointment {
    private final long id;
    private final String date;
    private final String time;  // nullable, HH:mm
    private final String title;

    public Appointment(long id, String date, String time, String title) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.title = title;
    }

    public long getId()      { return id; }
    public String getDate()  { return date; }
    public String getTime()  { return time; }
    public String getTitle() { return title; }
}
