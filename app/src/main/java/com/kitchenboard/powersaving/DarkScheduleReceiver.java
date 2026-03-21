package com.kitchenboard.powersaving;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BroadcastReceiver triggered by the daily active-window schedule alarms.
 *
 * <p>Forwards the active-on / active-off action to any registered
 * {@link PowerSavingManager} instance running inside the app process.
 * The actual brightness and screen-state change is applied by the manager's
 * internal receiver which listens for these local broadcasts.
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
        // Re-broadcast locally so PowerSavingManager's registered receiver picks it up.
        String action = intent.getAction();
        if (ACTION_DARK_ON.equals(action) || ACTION_DARK_OFF.equals(action)) {
            Intent local = new Intent(action).setPackage(context.getPackageName());
            context.sendBroadcast(local);
        }
    }
}
