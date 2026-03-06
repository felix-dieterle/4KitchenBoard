package com.kitchenboard.update;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Schedules a twice-daily (every 12 hours) background check for auto-update releases.
 * The first check fires approximately one minute after {@link #schedule(Context)} is called so
 * that the check runs shortly after the app starts without blocking the UI.
 */
public class AutoUpdateScheduler {

    /** Broadcast action used to trigger the update check. */
    static final String ACTION_AUTO_UPDATE_CHECK =
            "com.kitchenboard.action.AUTO_UPDATE_CHECK";

    /** PendingIntent request code (must be unique within the app). */
    private static final int REQUEST_CODE = 7200;

    /**
     * Schedules repeating update checks every 12 hours.
     * Safe to call on every app start – AlarmManager replaces any existing alarm registered
     * with the same PendingIntent, so there is no risk of duplicate alarms.
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(context, 0);

        // First check: 1 minute from now; interval: 12 hours (twice daily).
        long triggerMs = System.currentTimeMillis() + 60_000L;
        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerMs,
                AlarmManager.INTERVAL_HALF_DAY,
                pi);
    }

    /** Cancels any previously scheduled update check alarm. */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPendingIntent(context, 0);
        am.cancel(pi);
        pi.cancel();
    }

    private static PendingIntent buildPendingIntent(Context context, int extraFlags) {
        Intent intent = new Intent(ACTION_AUTO_UPDATE_CHECK)
                .setPackage(context.getPackageName());
        int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE | extraFlags;
        } else {
            flags = extraFlags;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
