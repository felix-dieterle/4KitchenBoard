package com.kitchenboard.shopping;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays shopping items grouped by category.
 * Each list entry is either a category header or a shopping item.
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

    // Each list entry is either a String (category header) or ShoppingItem
    private final List<Object> rows = new ArrayList<>();
    private OnItemCheckedListener checkedListener;
    private OnItemLongClickListener longClickListener;
    private OnQuantityChangedListener quantityChangedListener;
    private OnShowQrListener showQrListener;

    public void setOnItemCheckedListener(OnItemCheckedListener l) { checkedListener = l; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { longClickListener = l; }
    public void setOnQuantityChangedListener(OnQuantityChangedListener l) { quantityChangedListener = l; }
    public void setOnShowQrListener(OnShowQrListener l) { showQrListener = l; }

    /** Replaces the current data with a fresh grouped list. */
    public void setItems(List<ShoppingItem> items) {
        rows.clear();
        String lastCategory = null;
        for (ShoppingItem item : items) {
            if (!item.getCategory().equals(lastCategory)) {
                rows.add(item.getCategory()); // header
                lastCategory = item.getCategory();
            }
            rows.add(item);
        }
        notifyDataSetChanged();
    }

    /** Returns only the ShoppingItem entries (no header strings). */
    public List<ShoppingItem> getItems() {
        List<ShoppingItem> result = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof ShoppingItem) result.add((ShoppingItem) o);
        }
        return result;
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
        final TextView tvCategory;

        HeaderViewHolder(View v) {
            super(v);
            tvCategory = v.findViewById(R.id.tv_category_header);
        }

        void bind(String category) {
            tvCategory.setText(category);
        }
    }

    class ItemViewHolder extends RecyclerView.ViewHolder {
        final CheckBox checkBox;
        final TextView tvName;
        final Button btnMinus;
        final TextView tvQuantity;
        final Button btnPlus;
        final Button btnShowQr;

        ItemViewHolder(View v) {
            super(v);
            checkBox = v.findViewById(R.id.cb_item);
            tvName = v.findViewById(R.id.tv_item_name);
            btnMinus = v.findViewById(R.id.btn_qty_minus);
            tvQuantity = v.findViewById(R.id.tv_quantity);
            btnPlus = v.findViewById(R.id.btn_qty_plus);
            btnShowQr = v.findViewById(R.id.btn_show_qr);
        }

        void bind(final ShoppingItem item) {
            checkBox.setChecked(false);
            tvName.setText(item.getName());
            tvName.setPaintFlags(tvName.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            tvQuantity.setText(String.valueOf(item.getQuantity()));

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
        }
    }
}
