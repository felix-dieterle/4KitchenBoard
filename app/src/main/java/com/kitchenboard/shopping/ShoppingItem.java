package com.kitchenboard.shopping;

public class ShoppingItem {
    private long id;
    private String name;
    private String category;
    private boolean checked;

    public ShoppingItem(long id, String name, String category, boolean checked) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.checked = checked;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }
}
