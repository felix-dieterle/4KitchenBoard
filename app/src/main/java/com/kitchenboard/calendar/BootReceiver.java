package com.kitchenboard.calendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * Reschedules appointment reminder alarms after the device has been rebooted,
 * because AlarmManager alarms do not survive a power cycle.
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
    }
}
