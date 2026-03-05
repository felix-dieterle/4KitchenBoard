package com.kitchenboard.shopping;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.List;

/**
 * A dialog that shows the 20 most common German supermarkets / everyday stores as a
 * grid of tappable icon + label tiles, allowing the user to quickly assign a shop to
 * a shopping item without opening the full map picker.
 */
public class KnownStoresDialog extends Dialog {

    public interface OnStoreSelectedListener {
        void onStoreSelected(String storeName);
    }

    private final OnStoreSelectedListener listener;

    public KnownStoresDialog(@NonNull Context context, OnStoreSelectedListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_known_stores);

        TextView tvTitle = findViewById(R.id.tv_stores_title);
        if (tvTitle != null) {
            tvTitle.setText(R.string.known_stores_title);
        }

        RecyclerView recyclerView = findViewById(R.id.rv_known_stores);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 3));
            List<IconProvider.KnownStore> stores = IconProvider.knownStores();
            recyclerView.setAdapter(new StoreGridAdapter(stores));
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class StoreGridAdapter extends RecyclerView.Adapter<StoreGridAdapter.StoreVH> {

        private final List<IconProvider.KnownStore> stores;

        StoreGridAdapter(List<IconProvider.KnownStore> stores) {
            this.stores = stores;
        }

        @NonNull
        @Override
        public StoreVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_known_store, parent, false);
            return new StoreVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull StoreVH holder, int position) {
            IconProvider.KnownStore store = stores.get(position);
            holder.bind(store);
        }

        @Override
        public int getItemCount() { return stores.size(); }

        class StoreVH extends RecyclerView.ViewHolder {
            final ImageView ivIcon;
            final TextView tvName;

            StoreVH(View v) {
                super(v);
                ivIcon = v.findViewById(R.id.iv_store_icon);
                tvName = v.findViewById(R.id.tv_store_name);
            }

            void bind(final IconProvider.KnownStore store) {
                tvName.setText(store.name);
                ivIcon.setImageResource(store.iconRes);
                try {
                    int color = Color.parseColor(store.colorHex);
                    ivIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                } catch (IllegalArgumentException ignored) {
                    // keep default tint if color string is invalid
                }
                itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (listener != null) {
                            listener.onStoreSelected(store.name);
                        }
                        dismiss();
                    }
                });
            }
        }
    }
}
