package com.kitchenboard.shopping;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * {@link ItemTouchHelper.Callback} that enables vertical drag-to-reorder for shopping items.
 * Header rows (category/shop separators) cannot be dragged and items cannot be dropped onto them.
 */
class ShoppingItemTouchCallback extends ItemTouchHelper.Callback {

    /** Notified once when the user releases an item after dragging it to a new position. */
    interface OnDropListener {
        void onDrop();
    }

    private final ShoppingAdapter adapter;
    private final OnDropListener dropListener;

    ShoppingItemTouchCallback(ShoppingAdapter adapter, OnDropListener dropListener) {
        this.adapter = adapter;
        this.dropListener = dropListener;
    }

    @Override
    public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder) {
        if (viewHolder instanceof ShoppingAdapter.ItemViewHolder) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }
        // Headers are not draggable
        return makeMovementFlags(0, 0);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder source,
                          @NonNull RecyclerView.ViewHolder target) {
        // Do not allow dropping onto a header row
        if (!(target instanceof ShoppingAdapter.ItemViewHolder)) return false;
        adapter.moveItem(source.getAdapterPosition(), target.getAdapterPosition());
        return true;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        // Swipe is disabled; nothing to do
    }

    @Override
    public boolean isLongPressDragEnabled() {
        // Drag is initiated exclusively via the drag handle touch listener
        return false;
    }

    @Override
    public void clearView(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder) {
        super.clearView(recyclerView, viewHolder);
        // Drag finished – persist the new order
        if (dropListener != null) {
            dropListener.onDrop();
        }
    }
}
