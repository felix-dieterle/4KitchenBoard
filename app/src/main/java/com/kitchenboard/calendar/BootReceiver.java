package com.kitchenboard.calendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.kitchenboard.immobilien.ImmobilienCheckScheduler;
import com.kitchenboard.update.AutoUpdateScheduler;

import java.util.List;

/**
 * Reschedules appointment reminder alarms and the auto-update check alarm after the device
 * has been rebooted, because AlarmManager alarms do not survive a power cycle.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        CalendarDatabaseHelper db = new CalendarDatabaseHelper(context);
        List<Appointment> reminders = db.getAppointmentsWithReminder();
        for (Appointment apt : reminders) {
            ReminderScheduler.scheduleReminder(context, apt);
        }

        // Reschedule the twice-daily auto-update check
        AutoUpdateScheduler.schedule(context);

        // Reschedule the periodic real-estate alert check
        ImmobilienCheckScheduler.schedule(context);
    }
}
