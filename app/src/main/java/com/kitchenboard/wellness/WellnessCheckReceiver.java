package com.kitchenboard.wellness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Receives the daily wellness-check alarm and forwards a broadcast so that
 * {@code MainActivity} can show the check dialog when the app is in the foreground.
 * Also sets a SharedPreference flag so the dialog is shown on the next resume even if
 * the app was not running at alarm time.
 */
public class WellnessCheckReceiver extends BroadcastReceiver {

    /** Action sent to notify MainActivity to show the wellness dialog. */
    public static final String ACTION_SHOW_DIALOG = "com.kitchenboard.wellness.SHOW_DIALOG";

    private static final String PREFS_NAME = "shopping_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!WellnessCheckScheduler.ACTION_WELLNESS_CHECK.equals(intent.getAction())) return;

        // Mark the check as pending so MainActivity shows the dialog on next resume
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("wellness_check_pending", true).apply();

        // Notify MainActivity if it is currently in the foreground
        Intent showIntent = new Intent(ACTION_SHOW_DIALOG)
                .setPackage(context.getPackageName());
        context.sendBroadcast(showIntent);
    }
}
