package com.kitchenboard.tasks;

public class Task {
    public final long   id;
    public final String title;
    public final int    sortOrder;
    /** Person name this task is assigned to, or empty string if unassigned. */
    public final String assignedTo;

    public Task(long id, String title, int sortOrder, String assignedTo) {
        this.id         = id;
        this.title      = title;
        this.sortOrder  = sortOrder;
        this.assignedTo = assignedTo != null ? assignedTo : "";
    }

    /** Convenience constructor for unassigned tasks. */
    public Task(long id, String title, int sortOrder) {
        this(id, title, sortOrder, "");
    }
}
