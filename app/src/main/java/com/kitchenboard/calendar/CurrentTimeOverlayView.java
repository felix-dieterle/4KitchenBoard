package com.kitchenboard.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kitchenboard.R;

import java.util.Calendar;

/**
 * Transparent overlay drawn on top of today's day-strip column.
 * <ul>
 *   <li>Draws a semi-transparent grey rectangle covering the time range that has
 *       already passed (from the start of the visible range up to the current time).</li>
 *   <li>Draws a solid accent-coloured horizontal line at the exact current time.</li>
 * </ul>
 * The visible time range mirrors the drag-interaction constants in CalendarFragment:
 * 06:00 (top) – 22:00 (bottom).
 */
class CurrentTimeOverlayView extends View {

    /** Earliest hour shown in the day strip (top of the column). */
    private static final int START_HOUR = 6;
    /** Latest hour shown in the day strip (bottom of the column). */
    private static final int END_HOUR = 22;
    /** Total minutes covered by the column height. */
    private static final float TOTAL_MINUTES = (END_HOUR - START_HOUR) * 60f;

    private final Paint pastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    CurrentTimeOverlayView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);

        pastPaint.setColor(ContextCompat.getColor(context, R.color.past_time_overlay));
        pastPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(ContextCompat.getColor(context, R.color.current_time_line));
        linePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2f);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Calendar now = Calendar.getInstance();
        int hour   = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);

        float elapsedMinutes = (hour - START_HOUR) * 60f + minute;
        elapsedMinutes = Math.max(0f, Math.min(TOTAL_MINUTES, elapsedMinutes));

        float fraction = elapsedMinutes / TOTAL_MINUTES;
        float y = fraction * getHeight();

        // Update accessibility description with the current time
        setContentDescription(String.format(java.util.Locale.getDefault(),
                "Aktuelle Uhrzeit: %02d:%02d", hour, minute));

        // Grey overlay for elapsed time
        canvas.drawRect(0, 0, getWidth(), y, pastPaint);

        // Accent line at current time
        canvas.drawLine(0, y, getWidth(), y, linePaint);
    }
}
