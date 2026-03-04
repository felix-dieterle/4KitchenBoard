package com.kitchenboard.tasks;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.List;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.ViewHolder> {

    public interface OnTaskActionListener {
        void onMoveUp(int position);
        void onMoveDown(int position);
        void onDone(int position);
        void onLongClick(int position);
    }

    private final List<Task>            items    = new ArrayList<>();
    private final OnTaskActionListener  listener;

    public TaskAdapter(List<Task> initialItems, OnTaskActionListener listener) {
        this.listener = listener;
        items.addAll(initialItems);
    }

    public void setItems(List<Task> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public Task getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_task, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final Task task = items.get(position);

        holder.tvTitle.setText(task.title);
        holder.tvPriority.setText(String.valueOf(position + 1));

        holder.btnUp.setVisibility(position > 0 ? View.VISIBLE : View.INVISIBLE);
        holder.btnDown.setVisibility(position < items.size() - 1 ? View.VISIBLE : View.INVISIBLE);

        holder.btnUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = holder.getAdapterPosition();
                if (pos > 0 && listener != null) listener.onMoveUp(pos);
            }
        });

        holder.btnDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && pos < items.size() - 1 && listener != null) listener.onMoveDown(pos);
            }
        });

        holder.btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && listener != null) listener.onDone(pos);
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && listener != null) listener.onLongClick(pos);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvPriority;
        final TextView tvTitle;
        final Button   btnUp;
        final Button   btnDown;
        final Button   btnDone;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPriority = itemView.findViewById(R.id.tv_task_priority);
            tvTitle    = itemView.findViewById(R.id.tv_task_title);
            btnUp      = itemView.findViewById(R.id.btn_task_up);
            btnDown    = itemView.findViewById(R.id.btn_task_down);
            btnDone    = itemView.findViewById(R.id.btn_task_done);
        }
    }
}
