package com.kitchenboard.calendar;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppointmentAdapter extends RecyclerView.Adapter<AppointmentAdapter.ViewHolder> {

    public interface OnDeleteListener {
        void onDelete(Appointment appointment);
    }

    private final List<Appointment> items = new ArrayList<>();
    private final Map<Long, Person> personMap = new HashMap<>();
    private final Map<Long, PersonGroup> groupMap = new HashMap<>();
    private OnDeleteListener deleteListener;

    public void setOnDeleteListener(OnDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setItems(List<Appointment> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    /** Updates the person lookup map used to show color dots. */
    public void setPersons(List<Person> persons) {
        personMap.clear();
        for (Person p : persons) personMap.put(p.getId(), p);
    }

    /** Updates the group lookup map used to show group dot. */
    public void setGroups(List<PersonGroup> groups) {
        groupMap.clear();
        for (PersonGroup g : groups) groupMap.put(g.getId(), g);
    }

    public Appointment getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_appointment, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Appointment item = items.get(position);
        holder.tvTitle.setText(item.getTitle());
        String time = item.getTime();
        if (time != null && !time.isEmpty()) {
            holder.tvTime.setText(time);
            holder.tvTime.setVisibility(View.VISIBLE);
        } else {
            holder.tvTime.setVisibility(View.GONE);
        }
        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deleteListener != null) deleteListener.onDelete(item);
            }
        });

        // Series indicator
        if (item.getSeriesId() != null) {
            holder.ivSeriesIndicator.setVisibility(View.VISIBLE);
        } else {
            holder.ivSeriesIndicator.setVisibility(View.GONE);
        }

        // Person color dot
        // Person color dot or group indicator dot
        if (item.getPersonId() != null && personMap.containsKey(item.getPersonId())) {
            Person p = personMap.get(item.getPersonId());
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            try {
                dot.setColor(Color.parseColor(p.getColor()));
            } catch (IllegalArgumentException e) {
                dot.setColor(Color.GRAY);
            }
            holder.viewPersonDot.setBackground(dot);
            holder.viewPersonDot.setVisibility(View.VISIBLE);
        } else if (item.getGroupId() != null && groupMap.containsKey(item.getGroupId())) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Color.GRAY);
            holder.viewPersonDot.setBackground(dot);
            holder.viewPersonDot.setVisibility(View.VISIBLE);
        } else {
            holder.viewPersonDot.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvTime;
        final ImageButton btnDelete;
        final View viewPersonDot;
        final ImageView ivSeriesIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle           = itemView.findViewById(R.id.tv_appointment_title);
            tvTime            = itemView.findViewById(R.id.tv_appointment_time);
            btnDelete         = itemView.findViewById(R.id.btn_delete_appointment);
            viewPersonDot     = itemView.findViewById(R.id.view_person_dot);
            ivSeriesIndicator = itemView.findViewById(R.id.iv_series_indicator);
        }
    }
}
