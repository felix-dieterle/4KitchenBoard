package com.kitchenboard.shopping;

public class ShoppingItem {
    private long id;
    private String name;
    private String category;
    private boolean checked;
    private int quantity;

    public ShoppingItem(long id, String name, String category, boolean checked) {
        this(id, name, category, checked, 1);
    }

    public ShoppingItem(long id, String name, String category, boolean checked, int quantity) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.checked = checked;
        this.quantity = quantity < 1 ? 1 : quantity;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity < 1 ? 1 : quantity; }
}
