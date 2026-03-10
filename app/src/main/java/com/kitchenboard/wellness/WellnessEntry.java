package com.kitchenboard.wellness;

/** Represents a single morning wellness check entry for one person on one day. */
public class WellnessEntry {
    public long   id;
    public long   personId;
    public String date;       // YYYY-MM-DD
    public int    tiredness;  // 1–5
    public int    health;     // 1–5
    public int    mood;       // 1–5

    public WellnessEntry(long id, long personId, String date,
                         int tiredness, int health, int mood) {
        this.id        = id;
        this.personId  = personId;
        this.date      = date;
        this.tiredness = tiredness;
        this.health    = health;
        this.mood      = mood;
    }
}
