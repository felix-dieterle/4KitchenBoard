package com.kitchenboard.shopping;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    // Each list entry is either a String (category header) or ShoppingItem
    private final List<Object> rows = new ArrayList<>();
    private OnItemCheckedListener checkedListener;
    private OnItemLongClickListener longClickListener;

    public void setOnItemCheckedListener(OnItemCheckedListener l) { checkedListener = l; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { longClickListener = l; }

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

        ItemViewHolder(View v) {
            super(v);
            checkBox = v.findViewById(R.id.cb_item);
            tvName = v.findViewById(R.id.tv_item_name);
        }

        void bind(final ShoppingItem item) {
            checkBox.setChecked(false);
            tvName.setText(item.getName());
            tvName.setPaintFlags(tvName.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);

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
