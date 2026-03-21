package com.kitchenboard.powersaving;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.kitchenboard.update.UpdateLogger;

import java.util.Calendar;

/**
 * Manages device power-saving features for the wall-mounted KitchenBoard.
 *
 * <h3>Features managed</h3>
 * <ul>
 *   <li><b>Low-battery brightness</b>: when the battery level drops below
 *       {@value #LOW_BATTERY_THRESHOLD_PCT}%, the window brightness is dimmed
 *       to {@value #LOW_BATTERY_BRIGHTNESS} (≈ 10%).</li>
 *   <li><b>Dark schedule</b>: two daily AlarmManager alarms dim the screen to
 *       near-zero at the configured "dark start" time and restore it at "dark
 *       end" time.  Defaults are 23:00 (dark) and 05:30 (restore).</li>
 * </ul>
 *
 * <p>Call {@link #init(Window)} once from {@code MainActivity.onCreate()} and
 * {@link #destroy()} from {@code MainActivity.onDestroy()}.
 */
public class PowerSavingManager {

    private static final String TAG = "PowerSavingManager";

    // ── SharedPreferences keys ────────────────────────────────────────────────
    /** SharedPreferences file name – same file used by the account setup dialog. */
    public static final String PREFS_APP_SETTINGS             = "shopping_prefs";
    public static final String PREF_DARK_SCHEDULE_ENABLED = "dark_schedule_enabled";
    public static final String PREF_DARK_START_HOUR       = "dark_start_hour";
    public static final String PREF_DARK_START_MINUTE     = "dark_start_minute";
    public static final String PREF_DARK_END_HOUR         = "dark_end_hour";
    public static final String PREF_DARK_END_MINUTE       = "dark_end_minute";
    public static final String PREF_LOW_BATTERY_DIM       = "low_battery_dim";

    // ── Defaults ──────────────────────────────────────────────────────────────
    public static final int  DEFAULT_DARK_START_HOUR   = 23;
    public static final int  DEFAULT_DARK_START_MINUTE =  0;
    public static final int  DEFAULT_DARK_END_HOUR     =  5;
    public static final int  DEFAULT_DARK_END_MINUTE   = 30;

    /** Battery level (percent) below which the screen is dimmed. */
    private static final int   LOW_BATTERY_THRESHOLD_PCT = 15;
    /** WindowManager brightness for low-battery mode (0–1, -1 = system default). */
    private static final float LOW_BATTERY_BRIGHTNESS    = 0.08f;
    /** WindowManager brightness for dark-schedule mode. */
    private static final float DARK_SCHEDULE_BRIGHTNESS  = 0.01f;
    /** WindowManager brightness for normal operation (system default). */
    private static final float NORMAL_BRIGHTNESS         = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    private final Context context;
    private Window window;
    private boolean isDarkScheduleActive = false;
    private boolean isLowBattery         = false;

    // ── Battery receiver ──────────────────────────────────────────────────────

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) return;
            int pct = level * 100 / scale;
            boolean low = pct <= LOW_BATTERY_THRESHOLD_PCT;
            if (low != isLowBattery) {
                isLowBattery = low;
                applyBrightness();
            }
        }
    };

    // ── Dark-schedule receiver ────────────────────────────────────────────────

    private final BroadcastReceiver darkScheduleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (DarkScheduleReceiver.ACTION_DARK_ON.equals(action)) {
                isDarkScheduleActive = true;
                applyBrightness();
            } else if (DarkScheduleReceiver.ACTION_DARK_OFF.equals(action)) {
                isDarkScheduleActive = false;
                applyBrightness();
            }
        }
    };

    public PowerSavingManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Initialises the manager and starts monitoring.
     * Must be called from the main thread after {@code setContentView()}.
     *
     * @param window The activity window used to control brightness
     */
    public void init(Window window) {
        this.window = window;

        // Register battery receiver
        SharedPreferences prefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_LOW_BATTERY_DIM, true)) {
            try {
                context.registerReceiver(batteryReceiver,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Exception e) {
                UpdateLogger.logError(context, "PowerSavingManager: battery receiver error", e);
            }
        }

        // Register dark schedule action receivers
        IntentFilter darkFilter = new IntentFilter();
        darkFilter.addAction(DarkScheduleReceiver.ACTION_DARK_ON);
        darkFilter.addAction(DarkScheduleReceiver.ACTION_DARK_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(darkScheduleReceiver, darkFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(darkScheduleReceiver, darkFilter);
        }

        // Apply dark schedule immediately if we are inside the dark window
        if (prefs.getBoolean(PREF_DARK_SCHEDULE_ENABLED, true)) {
            isDarkScheduleActive = isInsideDarkWindow(prefs);
            applyBrightness();
            scheduleDarkAlarms(prefs);
        }
    }

    /** Releases resources – call from {@code MainActivity.onDestroy()}. */
    public void destroy() {
        try { context.unregisterReceiver(batteryReceiver);     } catch (Exception ignored) {}
        try { context.unregisterReceiver(darkScheduleReceiver);} catch (Exception ignored) {}
    }

    /**
     * Re-reads the saved settings and reschedules alarms.
     * Call after the user changes settings in the account dialog.
     */
    public void applySettings() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE);
        boolean darkEnabled = prefs.getBoolean(PREF_DARK_SCHEDULE_ENABLED, true);
        if (darkEnabled) {
            isDarkScheduleActive = isInsideDarkWindow(prefs);
            scheduleDarkAlarms(prefs);
        } else {
            isDarkScheduleActive = false;
            cancelDarkAlarms();
        }
        applyBrightness();
    }

    // ── Brightness helpers ────────────────────────────────────────────────────

    /** Applies the correct brightness based on current state to the activity window. */
    private void applyBrightness() {
        if (window == null) return;
        float brightness;
        if (isDarkScheduleActive) {
            brightness = DARK_SCHEDULE_BRIGHTNESS;
        } else if (isLowBattery) {
            brightness = LOW_BATTERY_BRIGHTNESS;
        } else {
            brightness = NORMAL_BRIGHTNESS;
        }
        final float finalBrightness = brightness;
        // Must be called on the main thread
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.screenBrightness = finalBrightness;
                window.setAttributes(lp);
            } catch (Exception e) {
                Log.w(TAG, "Could not set window brightness", e);
            }
        });
    }

    // ── Dark schedule ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the current time is within the configured dark window.
     * Handles overnight ranges (e.g. 23:00–05:30).
     */
    private boolean isInsideDarkWindow(SharedPreferences prefs) {
        int startH = prefs.getInt(PREF_DARK_START_HOUR,   DEFAULT_DARK_START_HOUR);
        int startM = prefs.getInt(PREF_DARK_START_MINUTE, DEFAULT_DARK_START_MINUTE);
        int endH   = prefs.getInt(PREF_DARK_END_HOUR,     DEFAULT_DARK_END_HOUR);
        int endM   = prefs.getInt(PREF_DARK_END_MINUTE,   DEFAULT_DARK_END_MINUTE);

        Calendar now = Calendar.getInstance();
        int nowMinutes   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinutes = startH * 60 + startM;
        int endMinutes   = endH   * 60 + endM;

        if (startMinutes <= endMinutes) {
            // Same-day range (e.g. 01:00–06:00)
            return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        } else {
            // Overnight range (e.g. 23:00–05:30)
            return nowMinutes >= startMinutes || nowMinutes < endMinutes;
        }
    }

    /** Schedules (or replaces) the daily dark-on and dark-off alarms. */
    private void scheduleDarkAlarms(SharedPreferences prefs) {
        int startH = prefs.getInt(PREF_DARK_START_HOUR,   DEFAULT_DARK_START_HOUR);
        int startM = prefs.getInt(PREF_DARK_START_MINUTE, DEFAULT_DARK_START_MINUTE);
        int endH   = prefs.getInt(PREF_DARK_END_HOUR,     DEFAULT_DARK_END_HOUR);
        int endM   = prefs.getInt(PREF_DARK_END_MINUTE,   DEFAULT_DARK_END_MINUTE);

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        scheduleDaily(am, DarkScheduleReceiver.ACTION_DARK_ON,
                DarkScheduleReceiver.RC_DARK_ON, startH, startM);
        scheduleDaily(am, DarkScheduleReceiver.ACTION_DARK_OFF,
                DarkScheduleReceiver.RC_DARK_OFF, endH, endM);
    }

    /** Cancels both dark alarms. */
    private void cancelDarkAlarms() {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        cancelAlarm(am, DarkScheduleReceiver.ACTION_DARK_ON,  DarkScheduleReceiver.RC_DARK_ON);
        cancelAlarm(am, DarkScheduleReceiver.ACTION_DARK_OFF, DarkScheduleReceiver.RC_DARK_OFF);
    }

    private void scheduleDaily(AlarmManager am, String action, int rc, int hour, int minute) {
        PendingIntent pi = buildAlarmPi(action, rc);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE,      minute);
        cal.set(Calendar.SECOND,      0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY, pi);
    }

    private void cancelAlarm(AlarmManager am, String action, int rc) {
        PendingIntent pi = buildAlarmPi(action, rc);
        am.cancel(pi);
        pi.cancel();
    }

    private PendingIntent buildAlarmPi(String action, int rc) {
        Intent intent = new Intent(action).setPackage(context.getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(context, rc, intent, flags);
    }
}
