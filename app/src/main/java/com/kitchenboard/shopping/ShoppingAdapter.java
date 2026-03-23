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
import java.util.List;

/**
 * Displays shopping items grouped by category or shop.
 * Supports drag-to-reorder via a drag handle on each item row.
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

    /** Replaces the current data with a fresh grouped list. */
    public void setItems(List<ShoppingItem> items) {
        rows.clear();
        if (groupByShop) {
            // Sort by shop (empty shop goes last), then by manual sort order
            List<ShoppingItem> sorted = new ArrayList<>(items);
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
                rows.add(item);
            }
        } else {
            // Items already ordered by sort_order from the DB query; group by category preserving that order
            String lastCategory = null;
            for (ShoppingItem item : items) {
                if (!item.getCategory().equals(lastCategory)) {
                    rows.add(item.getCategory()); // header
                    lastCategory = item.getCategory();
                }
                rows.add(item);
            }
        }
        notifyDataSetChanged();
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

        HeaderViewHolder(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.iv_category_icon);
            tvCategory = v.findViewById(R.id.tv_category_header);
        }

        void bind(String category) {
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
