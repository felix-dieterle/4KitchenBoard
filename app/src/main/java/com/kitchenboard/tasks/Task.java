package com.kitchenboard.tasks;

public class Task {
    public final long   id;
    public final String title;
    public final int    sortOrder;

    public Task(long id, String title, int sortOrder) {
        this.id        = id;
        this.title     = title;
        this.sortOrder = sortOrder;
    }
}
