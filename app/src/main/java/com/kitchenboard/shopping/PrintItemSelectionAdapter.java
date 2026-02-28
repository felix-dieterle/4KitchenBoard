package com.kitchenboard.shopping;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple adapter used in the print-QR dialog that shows each shopping item
 * as a labelled checkbox so the user can choose which items to print.
 */
public class PrintItemSelectionAdapter
        extends RecyclerView.Adapter<PrintItemSelectionAdapter.ViewHolder> {

    private final List<ShoppingItem> items;
    private final boolean[] checked;

    public PrintItemSelectionAdapter(List<ShoppingItem> items) {
        this.items = items;
        this.checked = new boolean[items.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = true; // all selected by default
    }

    /** Checks or unchecks all items at once. */
    public void setAllChecked(boolean value) {
        for (int i = 0; i < checked.length; i++) checked[i] = value;
        notifyDataSetChanged();
    }

    /** Returns only the items whose checkbox is ticked. */
    public List<ShoppingItem> getSelectedItems() {
        List<ShoppingItem> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (checked[i]) result.add(items.get(i));
        }
        return result;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_print_selection, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ShoppingItem item = items.get(position);
        holder.checkBox.setText(item.getName());
        holder.checkBox.setChecked(checked[position]);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) checked[pos] = isChecked;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final CheckBox checkBox;

        ViewHolder(View v) {
            super(v);
            checkBox = v.findViewById(R.id.cb_print_item);
        }
    }
}
