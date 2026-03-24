package com.kitchenboard.shopping;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Displays shopping items grouped by category or shop.
 * Supports drag-to-reorder via a drag handle on each item row.
 * Group headers can be tapped to collapse/expand the items beneath them.
 */
public class ShoppingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    public interface OnItemCheckedListener {
        void onItemChecked(ShoppingItem item);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(ShoppingItem item);
    }

    public interface OnQuantityChangedListener {
        void onQuantityChanged(ShoppingItem item, int newQuantity);
    }

    public interface OnShowQrListener {
        void onShowQr(ShoppingItem item);
    }

    /** Called when a drag gesture starts from the drag handle of an item row. */
    public interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder viewHolder);
    }

    // Each list entry is either a String (category/shop header) or ShoppingItem
    private final List<Object> rows = new ArrayList<>();
    /** Full unfiltered item list, kept for rebuilding rows when collapse state changes. */
    private List<ShoppingItem> lastItems = new ArrayList<>();
    /** Group keys (category or shop name) that are currently collapsed. */
    private final Set<String> collapsedGroups = new HashSet<>();
    private OnItemCheckedListener checkedListener;
    private OnItemLongClickListener longClickListener;
    private OnQuantityChangedListener quantityChangedListener;
    private OnShowQrListener showQrListener;
    private OnStartDragListener startDragListener;
    private boolean groupByShop = false;
    private String noShopLabel = "Kein Shop";
    /** Optional DB helper – used to look up saved category icons. May be null. */
    private ShoppingDatabaseHelper db;

    public void setOnItemCheckedListener(OnItemCheckedListener l) { checkedListener = l; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { longClickListener = l; }
    public void setOnQuantityChangedListener(OnQuantityChangedListener l) { quantityChangedListener = l; }
    public void setOnShowQrListener(OnShowQrListener l) { showQrListener = l; }
    /** Provide the listener that starts a drag operation (usually the ItemTouchHelper). */
    public void setOnStartDragListener(OnStartDragListener l) { startDragListener = l; }

    /** Provide the database helper so category icons can be resolved. */
    public void setDatabase(ShoppingDatabaseHelper database) { this.db = database; }

    /** Switch between category and shop grouping. */
    public void setGroupByShop(boolean groupByShop) { this.groupByShop = groupByShop; }

    /** Label for items that have no shop assigned (used in shop-grouping mode). */
    public void setNoShopLabel(String label) { this.noShopLabel = label != null ? label : "Kein Shop"; }

    /**
     * Restores the set of collapsed group keys (e.g. from SharedPreferences) so that
     * collapsed state survives configuration changes and fragment resumes.
     */
    public void setCollapsedGroups(Set<String> collapsed) {
        collapsedGroups.clear();
        if (collapsed != null) collapsedGroups.addAll(collapsed);
    }

    /** Returns a copy of the currently collapsed group keys for persistence. */
    public Set<String> getCollapsedGroups() {
        return new HashSet<>(collapsedGroups);
    }

    /** Replaces the current data with a fresh grouped list. */
    public void setItems(List<ShoppingItem> items) {
        lastItems = new ArrayList<>(items);
        rebuildRows();
    }

    /** Rebuilds the visible rows list from lastItems, respecting current collapse state. */
    private void rebuildRows() {
        rows.clear();
        if (groupByShop) {
            // Sort by shop (empty shop goes last), then by manual sort order
            List<ShoppingItem> sorted = new ArrayList<>(lastItems);
            Collections.sort(sorted, new java.util.Comparator<ShoppingItem>() {
                @Override
                public int compare(ShoppingItem a, ShoppingItem b) {
                    String shopA = a.getShop().isEmpty() ? "\uffff" : a.getShop();
                    String shopB = b.getShop().isEmpty() ? "\uffff" : b.getShop();
                    int c = shopA.compareToIgnoreCase(shopB);
                    if (c != 0) return c;
                    return Integer.compare(a.getSortOrder(), b.getSortOrder());
                }
            });
            String lastShop = null;
            for (ShoppingItem item : sorted) {
                String shopKey = item.getShop().isEmpty() ? noShopLabel : item.getShop();
                if (!shopKey.equalsIgnoreCase(lastShop)) {
                    rows.add(shopKey);
                    lastShop = shopKey;
                }
                if (!isCollapsed(shopKey)) {
                    rows.add(item);
                }
            }
        } else {
            // Items already ordered by sort_order from the DB query; group by category preserving that order
            String lastCategory = null;
            for (ShoppingItem item : lastItems) {
                if (!item.getCategory().equals(lastCategory)) {
                    rows.add(item.getCategory()); // header
                    lastCategory = item.getCategory();
                }
                if (!isCollapsed(item.getCategory())) {
                    rows.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** Returns true if the given group key is currently collapsed. */
    private boolean isCollapsed(String groupKey) {
        return collapsedGroups.contains(groupKey);
    }

    /** Toggles the collapsed state of a group and refreshes the list. */
    private void toggleCollapsed(String groupKey) {
        if (collapsedGroups.contains(groupKey)) {
            collapsedGroups.remove(groupKey);
        } else {
            collapsedGroups.add(groupKey);
        }
        rebuildRows();
    }

    /** Returns only the ShoppingItem entries (no header strings) in current display order. */
    public List<ShoppingItem> getItems() {
        List<ShoppingItem> result = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof ShoppingItem) result.add((ShoppingItem) o);
        }
        return result;
    }

    /**
     * Returns ALL items (visible and from collapsed groups) in a stable global order suitable
     * for persisting sort positions after a drag-to-reorder.  Collapsed-group items are inserted
     * at the position of their group header so the relative order between groups is preserved.
     */
    public List<ShoppingItem> getAllItemsInDisplayOrder() {
        // Collect collapsed items per group key in their current lastItems order
        java.util.Map<String, List<ShoppingItem>> collapsedMap =
                new java.util.LinkedHashMap<>();
        for (ShoppingItem item : lastItems) {
            String key = groupByShop
                    ? (item.getShop().isEmpty() ? noShopLabel : item.getShop())
                    : item.getCategory();
            if (isCollapsed(key)) {
                List<ShoppingItem> bucket = collapsedMap.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    collapsedMap.put(key, bucket);
                }
                bucket.add(item);
            }
        }
        // Walk rows in display order, injecting collapsed items at their header position
        List<ShoppingItem> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof String) {
                String key = (String) row;
                List<ShoppingItem> hidden = collapsedMap.get(key);
                if (hidden != null) result.addAll(hidden);
            } else if (row instanceof ShoppingItem) {
                result.add((ShoppingItem) row);
            }
        }
        return result;
    }

    /**
     * Moves an item row from {@code fromPos} to {@code toPos} within the {@code rows} list.
     * Only item-to-item moves are allowed; header rows must not be passed as either position.
     */
    public void moveItem(int fromPos, int toPos) {
        if (fromPos == toPos) return;
        if (!(rows.get(fromPos) instanceof ShoppingItem)) return;
        if (!(rows.get(toPos) instanceof ShoppingItem)) return;
        if (fromPos < toPos) {
            for (int i = fromPos; i < toPos; i++) {
                Collections.swap(rows, i, i + 1);
            }
        } else {
            for (int i = fromPos; i > toPos; i--) {
                Collections.swap(rows, i, i - 1);
            }
        }
        notifyItemMoved(fromPos, toPos);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View v = inflater.inflate(R.layout.item_category_header, parent, false);
            return new HeaderViewHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_shopping, parent, false);
            return new ItemViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((String) rows.get(position));
        } else {
            ((ItemViewHolder) holder).bind((ShoppingItem) rows.get(position));
        }
    }

    @Override
    public int getItemCount() { return rows.size(); }

    // ── ViewHolders ──────────────────────────────────────────────────────────

    class HeaderViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvCategory;
        final ImageView ivCollapseIndicator;

        HeaderViewHolder(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.iv_category_icon);
            tvCategory = v.findViewById(R.id.tv_category_header);
            ivCollapseIndicator = v.findViewById(R.id.iv_collapse_indicator);
        }

        void bind(final String category) {
            tvCategory.setText(category);
            // Resolve icon: prefer user-saved icon, auto-detect from category name as fallback
            int iconRes = R.drawable.ic_cat_other;
            if (db != null) {
                String savedIcon = db.getCategoryIcon(category);
                if (savedIcon != null && !savedIcon.isEmpty()) {
                    iconRes = resolveDrawableByName(savedIcon, itemView.getContext());
                } else {
                    iconRes = IconProvider.iconForCategory(category);
                }
            } else {
                iconRes = IconProvider.iconForCategory(category);
            }
            ivIcon.setImageResource(iconRes);
            ivIcon.setVisibility(View.VISIBLE);

            // Update collapse indicator – icon shows the *action*, not the current state:
            // collapsed → ic_expand_more (chevron ▼ = "tap to expand")
            // expanded  → ic_expand_less (chevron ▲ = "tap to collapse")
            if (ivCollapseIndicator != null) {
                ivCollapseIndicator.setImageResource(
                        isCollapsed(category) ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
            }

            // Toggle collapse on header click
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleCollapsed(category);
                }
            });
        }

        private int resolveDrawableByName(String name, android.content.Context ctx) {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            return id != 0 ? id : IconProvider.iconForCategory(tvCategory.getText().toString());
        }
    }

    class ItemViewHolder extends RecyclerView.ViewHolder {
        final View priorityIndicator;
        final CheckBox checkBox;
        final TextView tvName;
        final Button btnMinus;
        final TextView tvQuantity;
        final Button btnPlus;
        final Button btnShowQr;
        final ImageView ivDragHandle;

        ItemViewHolder(View v) {
            super(v);
            priorityIndicator = v.findViewById(R.id.view_priority_indicator);
            checkBox = v.findViewById(R.id.cb_item);
            tvName = v.findViewById(R.id.tv_item_name);
            btnMinus = v.findViewById(R.id.btn_qty_minus);
            tvQuantity = v.findViewById(R.id.tv_quantity);
            btnPlus = v.findViewById(R.id.btn_qty_plus);
            btnShowQr = v.findViewById(R.id.btn_show_qr);
            ivDragHandle = v.findViewById(R.id.iv_drag_handle);
        }

        void bind(final ShoppingItem item) {
            checkBox.setChecked(false);
            tvName.setText(item.getName());
            tvName.setPaintFlags(tvName.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            tvQuantity.setText(String.valueOf(item.getQuantity()));

            // Show priority indicator
            int priorityColor;
            if (item.getPriority() == ShoppingItem.PRIORITY_HIGH) {
                priorityColor = ContextCompat.getColor(itemView.getContext(), R.color.priority_high);
            } else if (item.getPriority() == ShoppingItem.PRIORITY_LOW) {
                priorityColor = ContextCompat.getColor(itemView.getContext(), R.color.priority_low);
            } else {
                priorityColor = android.graphics.Color.TRANSPARENT;
            }
            priorityIndicator.setBackgroundColor(priorityColor);

            checkBox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Brief visual feedback before removing
                    tvName.setPaintFlags(tvName.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                    if (checkedListener != null) {
                        checkedListener.onItemChecked(item);
                    }
                }
            });

            btnMinus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int newQty = Math.max(1, item.getQuantity() - 1);
                    item.setQuantity(newQty);
                    tvQuantity.setText(String.valueOf(newQty));
                    if (quantityChangedListener != null) {
                        quantityChangedListener.onQuantityChanged(item, newQty);
                    }
                }
            });

            btnPlus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int newQty = item.getQuantity() + 1;
                    item.setQuantity(newQty);
                    tvQuantity.setText(String.valueOf(newQty));
                    if (quantityChangedListener != null) {
                        quantityChangedListener.onQuantityChanged(item, newQty);
                    }
                }
            });

            btnShowQr.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (showQrListener != null) {
                        showQrListener.onShowQr(item);
                    }
                }
            });

            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (longClickListener != null) {
                        longClickListener.onItemLongClick(item);
                        return true;
                    }
                    return false;
                }
            });

            // Drag handle: touching it starts a drag-to-reorder gesture
            ivDragHandle.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (startDragListener != null) {
                            startDragListener.onStartDrag(ItemViewHolder.this);
                            return true;
                        }
                    }
                    return false;
                }
            });
        }
    }
}

