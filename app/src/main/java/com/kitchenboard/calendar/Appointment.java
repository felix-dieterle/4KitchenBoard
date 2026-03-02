package com.kitchenboard.calendar;

public class Appointment {
    private final long id;
    private final String date;
    private final String time;      // nullable, HH:mm
    private final String title;
    private final Long seriesId;    // nullable; shared by all entries of a recurring series

    public Appointment(long id, String date, String time, String title, Long seriesId) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.title = title;
        this.seriesId = seriesId;
    }

    public long getId()          { return id; }
    public String getDate()      { return date; }
    public String getTime()      { return time; }
    public String getTitle()     { return title; }
    public Long getSeriesId()    { return seriesId; }
}
