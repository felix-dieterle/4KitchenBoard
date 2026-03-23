package com.kitchenboard.shopping;

public class ShoppingItem {
    /** Priority constants: lower value = higher urgency. */
    public static final int PRIORITY_HIGH   = 1;
    public static final int PRIORITY_NORMAL = 2;
    public static final int PRIORITY_LOW    = 3;

    private long id;
    private String name;
    private String category;
    private boolean checked;
    private int quantity;
    private String shop;
    private int priority;
    /** Manual sort position within the active list – lower value appears first. */
    private int sortOrder;

    public ShoppingItem(long id, String name, String category, boolean checked) {
        this(id, name, category, checked, 1, "", PRIORITY_NORMAL, 0);
    }

    public ShoppingItem(long id, String name, String category, boolean checked, int quantity) {
        this(id, name, category, checked, quantity, "", PRIORITY_NORMAL, 0);
    }

    public ShoppingItem(long id, String name, String category, boolean checked, int quantity, String shop) {
        this(id, name, category, checked, quantity, shop, PRIORITY_NORMAL, 0);
    }

    public ShoppingItem(long id, String name, String category, boolean checked, int quantity, String shop, int priority) {
        this(id, name, category, checked, quantity, shop, priority, 0);
    }

    public ShoppingItem(long id, String name, String category, boolean checked, int quantity, String shop, int priority, int sortOrder) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.checked = checked;
        this.quantity = quantity < 1 ? 1 : quantity;
        this.shop = shop != null ? shop : "";
        this.priority = (priority < PRIORITY_HIGH || priority > PRIORITY_LOW) ? PRIORITY_NORMAL : priority;
        this.sortOrder = sortOrder;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity < 1 ? 1 : quantity; }
    public String getShop() { return shop; }
    public void setShop(String shop) { this.shop = shop != null ? shop : ""; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) {
        this.priority = (priority < PRIORITY_HIGH || priority > PRIORITY_LOW) ? PRIORITY_NORMAL : priority;
    }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
