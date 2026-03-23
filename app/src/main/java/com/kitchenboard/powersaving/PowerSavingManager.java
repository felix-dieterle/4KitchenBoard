package com.kitchenboard.powersaving;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.kitchenboard.update.UpdateLogger;

import java.util.Calendar;
import java.util.Locale;

/**
 * Manages device power-saving features for the wall-mounted KitchenBoard.
 *
 * <h3>Features managed</h3>
 * <ul>
 *   <li><b>Low-battery brightness</b>: when the battery level drops below
 *       {@value #LOW_BATTERY_THRESHOLD_PCT}%, the window brightness is dimmed
 *       to {@value #LOW_BATTERY_BRIGHTNESS} (≈ 8%).</li>
 *   <li><b>Active-window schedule</b>: three configurable daily time windows
 *       during which the screen is bright and {@code FLAG_KEEP_SCREEN_ON} is
 *       active.  Outside these windows the display is dimmed to near-zero and
 *       the keep-screen-on flag is cleared so the OS screen timeout can switch
 *       the panel off.  A brief wake lock is acquired when the first active
 *       window of the day starts so the screen turns back on automatically.
 *       Defaults: 06:00–09:45, 12:00–13:45, 16:30–21:15.</li>
 * </ul>
 *
 * <p>Call {@link #init(Window)} once from {@code MainActivity.onCreate()} and
 * {@link #destroy()} from {@code MainActivity.onDestroy()}.
 */
public class PowerSavingManager {

    private static final String TAG = "PowerSavingManager";

    // ── SharedPreferences file ────────────────────────────────────────────────
    /** SharedPreferences file name – shared with other modules. */
    public static final String PREFS_APP_SETTINGS = "shopping_prefs";

    // ── SharedPreferences keys ────────────────────────────────────────────────
    public static final String PREF_DARK_SCHEDULE_ENABLED = "dark_schedule_enabled";
    public static final String PREF_LOW_BATTERY_DIM       = "low_battery_dim";
    /** Whether WiFi should be turned off during the dark phase and back on during active windows. */
    public static final String PREF_WIFI_CONTROL          = "wifi_off_during_dark";

    /**
     * Format strings for active-window start/end times stored in SharedPreferences.
     * Use {@code String.format(Locale.US, PREF_WIN_START_HOUR, windowIndex)} where
     * {@code windowIndex} is 1, 2, or 3 to obtain the concrete preference key.
     */
    public static final String PREF_WIN_START_HOUR   = "active_win%d_start_hour";
    public static final String PREF_WIN_START_MINUTE = "active_win%d_start_minute";
    public static final String PREF_WIN_END_HOUR     = "active_win%d_end_hour";
    public static final String PREF_WIN_END_MINUTE   = "active_win%d_end_minute";

    /** Number of configurable active windows. */
    public static final int WINDOW_COUNT = 3;

    // ── Default active-window times ───────────────────────────────────────────
    /** Default start hours for active windows 1–3. */
    public static final int[] DEFAULT_WIN_START_HOUR   = {  6, 12, 16 };
    /** Default start minutes for active windows 1–3. */
    public static final int[] DEFAULT_WIN_START_MINUTE = {  0,  0, 30 };
    /** Default end hours for active windows 1–3. */
    public static final int[] DEFAULT_WIN_END_HOUR     = {  9, 13, 21 };
    /** Default end minutes for active windows 1–3. */
    public static final int[] DEFAULT_WIN_END_MINUTE   = { 45, 45, 15 };

    // ── Brightness constants ──────────────────────────────────────────────────
    /** Battery level (percent) below which the screen is dimmed. */
    private static final int   LOW_BATTERY_THRESHOLD_PCT = 15;
    /** Window brightness for low-battery mode (0–1). */
    private static final float LOW_BATTERY_BRIGHTNESS    = 0.08f;
    /** Window brightness for inactive (dark) mode. */
    private static final float DARK_SCHEDULE_BRIGHTNESS  = 0.005f;
    /** Window brightness for normal operation (system default). */
    private static final float NORMAL_BRIGHTNESS =
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

    // ── Wake lock tag ─────────────────────────────────────────────────────────
    private static final String WAKE_LOCK_TAG = "com.kitchenboard:screenWakeup";
    /** Duration (ms) for the screen-wake wake lock. */
    private static final long WAKE_LOCK_DURATION_MS = 3_000L;

    /**
     * Grace period (ms) applied once at app startup when the device is already in a dark phase.
     * Screen blackout and WiFi-off are deferred by this amount so that the update check
     * (and any pending downloads) can complete over the network before connectivity is cut.
     */
    private static final long STARTUP_DARK_GRACE_PERIOD_MS = 60_000L;

    private final Context context;
    private Window  window;
    private boolean isDarkScheduleActive = false;
    private boolean isLowBattery         = false;
    /** True if we disabled WiFi ourselves so we know to re-enable it on active phase. */
    private boolean wifiDisabledByUs     = false;
    /** Optional callback invoked on the main thread whenever the dark-phase state changes. */
    private Runnable onDarkPhaseChangedCallback;

    /** Handler used to post the deferred dark-phase application at startup. */
    private final android.os.Handler startupHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** Deferred runnable that applies the dark phase after the startup grace period. */
    private Runnable pendingDarkPhaseRunnable;

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

    // ── Active-window receiver ────────────────────────────────────────────────

    private final BroadcastReceiver darkScheduleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (DarkScheduleReceiver.ACTION_DARK_ON.equals(action)) {
                // Active window ended → go dark
                isDarkScheduleActive = true;
                applyScreenState(false);
            } else if (DarkScheduleReceiver.ACTION_DARK_OFF.equals(action)) {
                // Active window started → wake up.
                // Cancel any deferred startup screen-off so it does not fire
                // after the active phase has already begun.
                if (pendingDarkPhaseRunnable != null) {
                    startupHandler.removeCallbacks(pendingDarkPhaseRunnable);
                    pendingDarkPhaseRunnable = null;
                }
                isDarkScheduleActive = false;
                acquireWakeLock();
                applyScreenState(true);
            }
            notifyDarkPhaseChanged();
        }
    };

    /** Creates a new instance bound to the application context. */
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

        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE);

        // Register battery receiver
        if (prefs.getBoolean(PREF_LOW_BATTERY_DIM, true)) {
            try {
                context.registerReceiver(batteryReceiver,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Exception e) {
                UpdateLogger.logError(context, "PowerSavingManager: battery receiver error", e);
            }
        }

        // Register dark-schedule action receivers
        IntentFilter darkFilter = new IntentFilter();
        darkFilter.addAction(DarkScheduleReceiver.ACTION_DARK_ON);
        darkFilter.addAction(DarkScheduleReceiver.ACTION_DARK_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(darkScheduleReceiver, darkFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(darkScheduleReceiver, darkFilter);
        }

        // Apply correct state based on current time.
        // When the app starts inside a dark phase, defer screen-blackout and WiFi-off by
        // STARTUP_DARK_GRACE_PERIOD_MS so that the update check (launched earlier in
        // MainActivity.onCreate) has enough time to complete over the network before
        // connectivity is cut.
        if (prefs.getBoolean(PREF_DARK_SCHEDULE_ENABLED, true)) {
            isDarkScheduleActive = !isInsideActiveWindow(prefs);
            if (isDarkScheduleActive) {
                // Dark phase: honour the grace period so updates are not blocked.
                UpdateLogger.logInfo(context,
                        "PowerSavingManager: dark phase at startup – deferring screen-off "
                        + "and WiFi-off by "
                        + (STARTUP_DARK_GRACE_PERIOD_MS / 1000) + " s");
                pendingDarkPhaseRunnable = () -> applyScreenState(false);
                startupHandler.postDelayed(pendingDarkPhaseRunnable,
                        STARTUP_DARK_GRACE_PERIOD_MS);
            } else {
                // Active phase: apply immediately (screen on, WiFi on).
                applyScreenState(true);
            }
            scheduleActiveAlarms(prefs);
            notifyDarkPhaseChanged();
        }
    }

    /** Releases resources – call from {@code MainActivity.onDestroy()}. */
    public void destroy() {
        if (pendingDarkPhaseRunnable != null) {
            startupHandler.removeCallbacks(pendingDarkPhaseRunnable);
            pendingDarkPhaseRunnable = null;
        }
        try { context.unregisterReceiver(batteryReceiver);      } catch (Exception ignored) {}
        try { context.unregisterReceiver(darkScheduleReceiver); } catch (Exception ignored) {}
    }

    /**
     * Re-reads the saved settings and reschedules alarms.
     * Call after the user changes settings in the account dialog.
     */
    public void applySettings() {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE);
        boolean darkEnabled = prefs.getBoolean(PREF_DARK_SCHEDULE_ENABLED, true);
        if (darkEnabled) {
            isDarkScheduleActive = !isInsideActiveWindow(prefs);
            applyScreenState(!isDarkScheduleActive);
            scheduleActiveAlarms(prefs);
        } else {
            isDarkScheduleActive = false;
            cancelActiveAlarms();
            applyScreenState(true);
        }
        notifyDarkPhaseChanged();
    }

    /**
     * Returns {@code true} if the display is currently in the dark (dimmed) phase.
     * This reflects both scheduled and manually overridden states.
     */
    public boolean isDarkPhase() {
        return isDarkScheduleActive;
    }

    /**
     * Manually forces the dark-phase state until the next scheduled alarm fires.
     * Useful for a quick on-screen toggle.
     *
     * @param dark {@code true} to enter dark phase immediately; {@code false} to enter active phase
     */
    public void setManualDark(boolean dark) {
        isDarkScheduleActive = dark;
        applyScreenState(!dark);
        notifyDarkPhaseChanged();
    }

    /**
     * Registers a callback that is invoked on the main thread whenever the dark-phase
     * state changes (scheduled alarm or manual override).
     *
     * @param callback the {@link Runnable} to invoke, or {@code null} to clear it
     */
    public void setOnDarkPhaseChangedCallback(Runnable callback) {
        this.onDarkPhaseChangedCallback = callback;
    }

    // ── Screen-state helpers ──────────────────────────────────────────────────

    /**
     * Applies the correct brightness and {@code FLAG_KEEP_SCREEN_ON} state.
     *
     * @param keepOn {@code true} during active windows; {@code false} during dark periods
     */
    private void applyScreenState(boolean keepOn) {
        applyBrightness();
        applyWifiState(keepOn);
        if (window == null) return;
        android.os.Handler mainHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not update FLAG_KEEP_SCREEN_ON", e);
            }
        });
    }

    /**
     * Turns WiFi off when entering dark phase and back on when entering active phase,
     * but only if the {@link #PREF_WIFI_CONTROL} preference is enabled.
     * On Android 10+ (API 29+) {@code WifiManager.setWifiEnabled()} is restricted to
     * system apps and will silently return {@code false}; the call is skipped on those
     * versions to avoid unnecessary log noise.
     */
    @SuppressWarnings("deprecation")
    private void applyWifiState(boolean activePhase) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_WIFI_CONTROL, false)) return;
        try {
            WifiManager wm = (WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            if (!activePhase) {
                // Entering dark phase – disable WiFi if it is currently on
                if (wm.isWifiEnabled()) {
                    boolean disabled = wm.setWifiEnabled(false);
                    if (disabled) wifiDisabledByUs = true;
                }
            } else {
                // Entering active phase – re-enable WiFi only if we turned it off
                if (wifiDisabledByUs) {
                    wm.setWifiEnabled(true);
                    wifiDisabledByUs = false;
                }
            }
        } catch (Exception e) {
            UpdateLogger.logError(context, "PowerSavingManager: WiFi control error", e);
        }
    }

    /** Dispatches the dark-phase-changed callback on the main thread, if one is set. */
    private void notifyDarkPhaseChanged() {
        if (onDarkPhaseChangedCallback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(onDarkPhaseChangedCallback);
    }

    /** Applies the correct brightness level to the activity window. */
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
        android.os.Handler mainHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
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

    /**
     * Acquires a brief {@code FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP} to turn
     * the screen back on when an active window starts.
     */
    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    WAKE_LOCK_TAG);
            wl.acquire(WAKE_LOCK_DURATION_MS);
        } catch (Exception e) {
            UpdateLogger.logError(context, "PowerSavingManager: wake lock error", e);
        }
    }

    // ── Active-window schedule ────────────────────────────────────────────────

    /**
     * Returns {@code true} if the current time falls inside any of the
     * configured active windows.
     */
    private boolean isInsideActiveWindow(SharedPreferences prefs) {
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        for (int i = 0; i < WINDOW_COUNT; i++) {
            int startH = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_START_HOUR, i + 1),
                    DEFAULT_WIN_START_HOUR[i]);
            int startM = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_START_MINUTE, i + 1),
                    DEFAULT_WIN_START_MINUTE[i]);
            int endH = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_END_HOUR, i + 1),
                    DEFAULT_WIN_END_HOUR[i]);
            int endM = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_END_MINUTE, i + 1),
                    DEFAULT_WIN_END_MINUTE[i]);

            int startMinutes = startH * 60 + startM;
            int endMinutes   = endH   * 60 + endM;

            // Support overnight windows (e.g. 22:00–02:00)
            boolean inside;
            if (endMinutes < startMinutes) {
                inside = nowMinutes >= startMinutes || nowMinutes < endMinutes;
            } else {
                inside = nowMinutes >= startMinutes && nowMinutes < endMinutes;
            }
            if (inside) {
                return true;
            }
        }
        return false;
    }

    /** Schedules (or replaces) the six daily active-window alarms. */
    private void scheduleActiveAlarms(SharedPreferences prefs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        int[] rcOn  = { DarkScheduleReceiver.RC_ACTIVE_ON_1,
                        DarkScheduleReceiver.RC_ACTIVE_ON_2,
                        DarkScheduleReceiver.RC_ACTIVE_ON_3  };
        int[] rcOff = { DarkScheduleReceiver.RC_ACTIVE_OFF_1,
                        DarkScheduleReceiver.RC_ACTIVE_OFF_2,
                        DarkScheduleReceiver.RC_ACTIVE_OFF_3 };

        for (int i = 0; i < WINDOW_COUNT; i++) {
            int startH = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_START_HOUR, i + 1),
                    DEFAULT_WIN_START_HOUR[i]);
            int startM = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_START_MINUTE, i + 1),
                    DEFAULT_WIN_START_MINUTE[i]);
            int endH = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_END_HOUR, i + 1),
                    DEFAULT_WIN_END_HOUR[i]);
            int endM = prefs.getInt(
                    String.format(Locale.US, PREF_WIN_END_MINUTE, i + 1),
                    DEFAULT_WIN_END_MINUTE[i]);

            // DARK_OFF = start of active window (screen on)
            scheduleDaily(am, DarkScheduleReceiver.ACTION_DARK_OFF, rcOn[i],  startH, startM);
            // DARK_ON  = end of active window (screen dims)
            scheduleDaily(am, DarkScheduleReceiver.ACTION_DARK_ON,  rcOff[i], endH,   endM);
        }
    }

    /** Cancels all six active-window alarms. */
    private void cancelActiveAlarms() {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        int[] rcOn  = { DarkScheduleReceiver.RC_ACTIVE_ON_1,
                        DarkScheduleReceiver.RC_ACTIVE_ON_2,
                        DarkScheduleReceiver.RC_ACTIVE_ON_3  };
        int[] rcOff = { DarkScheduleReceiver.RC_ACTIVE_OFF_1,
                        DarkScheduleReceiver.RC_ACTIVE_OFF_2,
                        DarkScheduleReceiver.RC_ACTIVE_OFF_3 };

        for (int i = 0; i < WINDOW_COUNT; i++) {
            cancelAlarm(am, DarkScheduleReceiver.ACTION_DARK_OFF, rcOn[i]);
            cancelAlarm(am, DarkScheduleReceiver.ACTION_DARK_ON,  rcOff[i]);
        }
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
