package com.kitchenboard.powersaving;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BroadcastReceiver triggered by the daily active-window schedule alarms.
 *
 * <p>This manifest-declared receiver serves as the concrete target for the
 * AlarmManager {@link android.app.PendingIntent}s so that the alarm can wake
 * the app process even if it was killed.  Because the alarm intent is sent
 * with {@code setPackage()} targeting this app, the dynamically-registered
 * receiver inside {@link PowerSavingManager} also receives every alarm
 * broadcast directly and handles the actual brightness / screen-state change.
 * {@code DarkScheduleReceiver} therefore does not need to do anything itself.
 *
 * <p>Three configurable active windows are supported (defaults: 06:00–09:45,
 * 12:00–13:45, 16:30–21:15). Outside these windows the screen is dimmed and
 * {@code FLAG_KEEP_SCREEN_ON} is cleared so the display can sleep normally.
 */
public class DarkScheduleReceiver extends BroadcastReceiver {

    /** Broadcast action that starts an active (bright) window. */
    public static final String ACTION_DARK_OFF = "com.kitchenboard.action.DARK_OFF";
    /** Broadcast action that ends an active window (dims / sleeps screen). */
    public static final String ACTION_DARK_ON  = "com.kitchenboard.action.DARK_ON";

    /** PendingIntent request codes – one pair per active window. */
    static final int RC_ACTIVE_ON_1  = 8600;
    static final int RC_ACTIVE_OFF_1 = 8601;
    static final int RC_ACTIVE_ON_2  = 8602;
    static final int RC_ACTIVE_OFF_2 = 8603;
    static final int RC_ACTIVE_ON_3  = 8604;
    static final int RC_ACTIVE_OFF_3 = 8605;

    @Override
    public void onReceive(Context context, Intent intent) {
        // The alarm broadcast already targets this package (via setPackage), so
        // PowerSavingManager's dynamically-registered darkScheduleReceiver also
        // receives it directly.  No re-broadcast is needed here, and doing so
        // would cause DarkScheduleReceiver to receive its own re-broadcast and
        // re-broadcast again, creating an infinite broadcast loop that floods the
        // main thread with window.setAttributes() calls and causes continuous
        // screen flickering on older hardware (e.g. Galaxy Tab 10.1, API 15).
    }
}
