package com.kitchenboard.powersaving;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BroadcastReceiver triggered by the daily dark-schedule alarms.
 *
 * <p>Forwards the dark-on / dark-off action to any registered
 * {@link PowerSavingManager} instance running inside the app process.
 * The actual brightness change is applied by the manager's
 * internal receiver which listens for these local broadcasts.
 */
public class DarkScheduleReceiver extends BroadcastReceiver {

    /** Broadcast action that triggers the dark (dim) state. */
    public static final String ACTION_DARK_ON  = "com.kitchenboard.action.DARK_ON";
    /** Broadcast action that restores normal brightness. */
    public static final String ACTION_DARK_OFF = "com.kitchenboard.action.DARK_OFF";

    /** PendingIntent request code for the dark-on alarm. */
    static final int RC_DARK_ON  = 8600;
    /** PendingIntent request code for the dark-off alarm. */
    static final int RC_DARK_OFF = 8601;

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
