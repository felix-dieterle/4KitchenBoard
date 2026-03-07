package com.kitchenboard.immobilien;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Schedules a repeating background alarm that triggers {@link ImmobilienCheckReceiver}
 * every {@value #INTERVAL_MINUTES} minutes.
 *
 * <p>The receiver then inspects each alert's individual interval to decide whether
 * it is actually due for a check.  Safe to call on every app start – AlarmManager
 * replaces any existing alarm registered with the same PendingIntent.
 */
public class ImmobilienCheckScheduler {

    /** Action broadcast to the check receiver. */
    static final String ACTION_IMMOBILIEN_CHECK =
            "com.kitchenboard.action.IMMOBILIEN_CHECK";

    /** How often the receiver wakes up to evaluate due alerts (30 minutes). */
    private static final int INTERVAL_MINUTES = 30;

    /** PendingIntent request code – must be unique within the app. */
    private static final int REQUEST_CODE = 8300;

    /**
     * Schedules (or replaces) the repeating alarm.
     * The first trigger fires {@value #INTERVAL_MINUTES} minutes from now.
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(context);

        long triggerMs = System.currentTimeMillis() + INTERVAL_MINUTES * 60_000L;
        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerMs,
                INTERVAL_MINUTES * 60_000L,
                pi);
    }

    /** Cancels the repeating alarm (e.g. when all alerts are inactive). */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPendingIntent(context);
        am.cancel(pi);
        pi.cancel();
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(ACTION_IMMOBILIEN_CHECK)
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
