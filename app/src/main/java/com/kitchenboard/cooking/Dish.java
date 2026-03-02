package com.kitchenboard.cooking;

public class Dish {
    public final long id;
    public final String name;
    public final int durationMinutes;  // 0 = not set
    public final String ingredients;   // nullable
    public final String notes;         // nullable
    public final String lastCooked;    // YYYY-MM-DD, nullable

    public Dish(long id, String name, int durationMinutes, String ingredients, String notes, String lastCooked) {
        this.id = id;
        this.name = name;
        this.durationMinutes = durationMinutes;
        this.ingredients = ingredients;
        this.notes = notes;
        this.lastCooked = lastCooked;
    }
}
