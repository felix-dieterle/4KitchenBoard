package com.kitchenboard.cooking;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.List;

public class DishAdapter extends RecyclerView.Adapter<DishAdapter.ViewHolder> {

    public interface OnDishClickListener {
        void onDishClick(Dish dish);
        void onDishLongClick(Dish dish);
    }

    private final List<Dish> items = new ArrayList<>();
    private final OnDishClickListener listener;

    public DishAdapter(List<Dish> initialItems, OnDishClickListener listener) {
        this.listener = listener;
        items.addAll(initialItems);
    }

    public void setItems(List<Dish> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dish, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Dish dish = items.get(position);

        holder.tvName.setText(dish.name);

        boolean hasDuration    = dish.durationMinutes > 0;
        boolean hasIngredients = dish.ingredients != null && !dish.ingredients.isEmpty();

        if (hasDuration) {
            holder.tvDuration.setVisibility(View.VISIBLE);
            holder.tvDuration.setText("\u23F1 " + dish.durationMinutes + " min");
        } else {
            holder.tvDuration.setVisibility(View.GONE);
        }

        if (hasIngredients) {
            holder.tvIngredients.setVisibility(View.VISIBLE);
            holder.tvIngredients.setText(dish.ingredients);
        } else {
            holder.tvIngredients.setVisibility(View.GONE);
        }

        holder.llSecondary.setVisibility((hasDuration || hasIngredients) ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onDishClick(dish);
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onDishLongClick(dish);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView    tvName;
        final LinearLayout llSecondary;
        final TextView    tvDuration;
        final TextView    tvIngredients;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName        = itemView.findViewById(R.id.tv_dish_name);
            llSecondary   = itemView.findViewById(R.id.ll_dish_secondary);
            tvDuration    = itemView.findViewById(R.id.tv_dish_duration);
            tvIngredients = itemView.findViewById(R.id.tv_dish_ingredients);
        }
    }
}
