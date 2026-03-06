package com.kitchenboard.calendar;

public class Person {
    private final long id;
    private final String name;
    private final String color;      // hex color string, e.g. "#E53935"
    private final String imagePath;  // absolute path to person photo, or null
    private final int    heightCm;   // body height in cm, 0 = not set

    public Person(long id, String name, String color) {
        this(id, name, color, null, 0);
    }

    public Person(long id, String name, String color, String imagePath) {
        this(id, name, color, imagePath, 0);
    }

    public Person(long id, String name, String color, String imagePath, int heightCm) {
        this.id        = id;
        this.name      = name;
        this.color     = color;
        this.imagePath = imagePath;
        this.heightCm  = heightCm;
    }

    public long   getId()        { return id; }
    public String getName()      { return name; }
    public String getColor()     { return color; }
    public String getImagePath() { return imagePath; }
    public int    getHeightCm()  { return heightCm; }
}
