package com.kitchenboard.calendar;

public class Person {
    private final long id;
    private final String name;
    private final String color;   // hex color string, e.g. "#E53935"

    public Person(long id, String name, String color) {
        this.id    = id;
        this.name  = name;
        this.color = color;
    }

    public long   getId()    { return id; }
    public String getName()  { return name; }
    public String getColor() { return color; }
}
