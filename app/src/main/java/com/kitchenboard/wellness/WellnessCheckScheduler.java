package com.kitchenboard.wellness;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * Schedules a daily alarm at a configurable time that triggers {@link WellnessCheckReceiver}.
 * Safe to call on every app start – AlarmManager replaces any existing alarm registered
 * with the same PendingIntent.
 */
public class WellnessCheckScheduler {

    /** Action broadcast to the wellness check receiver. */
    public static final String ACTION_WELLNESS_CHECK =
            "com.kitchenboard.action.WELLNESS_CHECK";

    /** Default check time: 07:00. */
    public static final int DEFAULT_HOUR   = 7;
    public static final int DEFAULT_MINUTE = 0;

    /** PendingIntent request code – must be unique within the app. */
    private static final int REQUEST_CODE = 9100;

    private static final String PREFS_NAME = "shopping_prefs";

    /**
     * Schedules (or replaces) the daily repeating alarm.
     * Fires at the configured time today if not yet passed, otherwise tomorrow.
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int hour   = prefs.getInt("wellness_hour",   DEFAULT_HOUR);
        int minute = prefs.getInt("wellness_minute", DEFAULT_MINUTE);

        Calendar trigger = Calendar.getInstance();
        trigger.set(Calendar.HOUR_OF_DAY, hour);
        trigger.set(Calendar.MINUTE, minute);
        trigger.set(Calendar.SECOND, 0);
        trigger.set(Calendar.MILLISECOND, 0);

        // If the time has already passed today, schedule for tomorrow
        if (trigger.getTimeInMillis() <= System.currentTimeMillis()) {
            trigger.add(Calendar.DAY_OF_MONTH, 1);
        }

        PendingIntent pi = buildPendingIntent(context);
        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                trigger.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY,
                pi);
    }

    /** Cancels the daily alarm. */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPendingIntent(context);
        am.cancel(pi);
        pi.cancel();
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(ACTION_WELLNESS_CHECK)
                .setPackage(context.getPackageName());
        int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
