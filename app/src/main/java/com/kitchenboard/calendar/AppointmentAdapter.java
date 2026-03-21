package com.kitchenboard.calendar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppointmentAdapter extends RecyclerView.Adapter<AppointmentAdapter.ViewHolder> {

    public interface OnDeleteListener {
        void onDelete(Appointment appointment);
    }

    public interface OnTimerListener {
        void onTimer(Appointment appointment);
    }

    /** Called when the user requests a time shift on an appointment (±15 min, ±1 h). */
    public interface OnShiftListener {
        void onShift(Appointment appointment);
    }

    private final List<Appointment> items = new ArrayList<>();
    private final Map<Long, Person> personMap = new HashMap<>();
    private final Map<Long, PersonGroup> groupMap = new HashMap<>();
    private OnDeleteListener deleteListener;
    private OnTimerListener timerListener;
    private OnShiftListener shiftListener;

    public void setOnDeleteListener(OnDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setOnTimerListener(OnTimerListener listener) {
        this.timerListener = listener;
    }

    /** Sets the listener that is invoked when the user taps the time label to shift the appointment. */
    public void setOnShiftListener(OnShiftListener listener) {
        this.shiftListener = listener;
    }

    public void setItems(List<Appointment> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    /** Updates the person lookup map used to show color dots or photos. */
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
            // Tap on the time label opens the quarter-hour shift dialog
            holder.tvTime.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (shiftListener != null) shiftListener.onShift(item);
                }
            });
        } else {
            holder.tvTime.setVisibility(View.GONE);
            holder.tvTime.setOnClickListener(null);
        }
        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deleteListener != null) deleteListener.onDelete(item);
            }
        });

        holder.btnTimer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (timerListener != null) timerListener.onTimer(item);
            }
        });

        // Series indicator
        if (item.getSeriesId() != null) {
            holder.ivSeriesIndicator.setVisibility(View.VISIBLE);
        } else {
            holder.ivSeriesIndicator.setVisibility(View.GONE);
        }

        // Person photo, color dot, or group indicator dot
        if (item.getPersonId() != null && personMap.containsKey(item.getPersonId())) {
            Person p = personMap.get(item.getPersonId());
            holder.ivPersonDot.setVisibility(View.VISIBLE);
            String imagePath = p.getImagePath();
            if (imagePath != null && new File(imagePath).exists()) {
                // Show person photo as circular bitmap
                Bitmap bmp = BitmapFactory.decodeFile(imagePath);
                if (bmp != null) {
                    holder.ivPersonDot.setBackground(null);
                    holder.ivPersonDot.setImageBitmap(toCircularBitmap(bmp));
                } else {
                    showColorDot(holder.ivPersonDot, p.getColor());
                }
            } else {
                showColorDot(holder.ivPersonDot, p.getColor());
            }
        } else if (item.getGroupId() != null && groupMap.containsKey(item.getGroupId())) {
            holder.ivPersonDot.setImageDrawable(null);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Color.GRAY);
            holder.ivPersonDot.setBackground(dot);
            holder.ivPersonDot.setVisibility(View.VISIBLE);
        } else {
            holder.ivPersonDot.setVisibility(View.INVISIBLE);
        }
    }

    private static void showColorDot(@NonNull ImageView iv, String colorHex) {
        iv.setImageDrawable(null);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        try {
            dot.setColor(Color.parseColor(colorHex));
        } catch (IllegalArgumentException e) {
            dot.setColor(Color.GRAY);
        }
        iv.setBackground(dot);
    }

    /** Returns a circular cropped version of the given bitmap. */
    static Bitmap toCircularBitmap(@NonNull Bitmap source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Rect srcRect = new Rect(
                (source.getWidth() - size) / 2, (source.getHeight() - size) / 2,
                (source.getWidth() + size) / 2, (source.getHeight() + size) / 2);
        RectF dstRect = new RectF(0, 0, size, size);
        canvas.drawOval(dstRect, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, srcRect, dstRect, paint);
        return output;
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvTime;
        final ImageButton btnDelete;
        final ImageButton btnTimer;
        final ImageView ivPersonDot;
        final ImageView ivSeriesIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle           = itemView.findViewById(R.id.tv_appointment_title);
            tvTime            = itemView.findViewById(R.id.tv_appointment_time);
            btnDelete         = itemView.findViewById(R.id.btn_delete_appointment);
            btnTimer          = itemView.findViewById(R.id.btn_timer_appointment);
            ivPersonDot       = itemView.findViewById(R.id.view_person_dot);
            ivSeriesIndicator = itemView.findViewById(R.id.iv_series_indicator);
        }
    }
}
