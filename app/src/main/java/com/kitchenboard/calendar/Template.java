package com.kitchenboard.calendar;

public class Template {
    private final long id;
    private final String title;

    public Template(long id, String title) {
        this.id = id;
        this.title = title;
    }

    public long getId()      { return id; }
    public String getTitle() { return title; }
}
