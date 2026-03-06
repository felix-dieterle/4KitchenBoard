package com.kitchenboard.calendar;

/** A single weight measurement for a person on a specific date. */
public class WeightEntry {
    private final long   id;
    private final long   personId;
    private final String date;    // YYYY-MM-DD
    private final float  weightKg;

    public WeightEntry(long id, long personId, String date, float weightKg) {
        this.id       = id;
        this.personId = personId;
        this.date     = date;
        this.weightKg = weightKg;
    }

    public long   getId()       { return id; }
    public long   getPersonId() { return personId; }
    public String getDate()     { return date; }
    public float  getWeightKg() { return weightKg; }
}
