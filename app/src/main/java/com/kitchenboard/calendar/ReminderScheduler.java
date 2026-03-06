package com.kitchenboard.calendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helper for scheduling and cancelling per-appointment reminder alarms via AlarmManager.
 * Alarms fire even when the app is in the background.
 */
public class ReminderScheduler {

    private static final SimpleDateFormat DATETIME_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    /** Schedules a reminder alarm for the given appointment (no-op if reminderMinutes == 0). */
    public static void scheduleReminder(Context context, Appointment appointment) {
        if (appointment.getReminderMinutes() <= 0) return;
        String time = appointment.getTime();
        if (time == null || time.isEmpty()) return; // all-day appointments cannot have time-based reminders

        String datetimeStr = appointment.getDate() + " " + time;
        Date appointmentDate;
        try {
            appointmentDate = DATETIME_FMT.parse(datetimeStr);
        } catch (ParseException e) {
            return;
        }
        if (appointmentDate == null) return;

        long triggerMillis = appointmentDate.getTime()
                - (long) appointment.getReminderMinutes() * 60_000L;
        if (triggerMillis <= System.currentTimeMillis()) return; // trigger time already in the past

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(context, appointment);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Exact-alarm permission not granted on Android 12+: fall back to inexact
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerMillis, pi);
        }
    }

    /** Cancels a previously scheduled reminder for the given appointment. */
    public static void cancelReminder(Context context, long appointmentId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ReminderReceiver.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getBroadcast(
                context, (int) (appointmentId & 0x7FFFFFFF), intent, piFlags);
        am.cancel(pi);
    }

    private static PendingIntent buildPendingIntent(Context context, Appointment appointment) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(ReminderReceiver.EXTRA_TITLE, appointment.getTitle());
        intent.putExtra(ReminderReceiver.EXTRA_REMINDER_MINUTES, appointment.getReminderMinutes());
        intent.putExtra(ReminderReceiver.EXTRA_APPOINTMENT_ID, appointment.getId());
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(
                context, (int) (appointment.getId() & 0x7FFFFFFF), intent, piFlags);
    }
}
