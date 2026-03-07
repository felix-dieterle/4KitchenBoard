package com.kitchenboard;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kitchenboard.update.UpdateLogger;

/**
 * Custom Application class.
 *
 * <p>Registers a global {@link Thread.UncaughtExceptionHandler} that persists the full crash
 * stack-trace to the {@link UpdateLogger} before delegating to the previous (system) handler.
 * This ensures that crash details are available in the update-log viewer even when no debugger
 * is attached.
 *
 * <p>On startup, if a crash sentinel left by a previous session is detected, a status-bar
 * notification is shown that embeds the most recent log entries in a
 * {@link NotificationCompat.BigTextStyle}. This makes crash details readable from the
 * notification shade even if the app crashes again before the user can open settings.
 */
public class KitchenBoardApp extends Application {

    private static final String TAG = "KitchenBoardApp";

    /** Notification channel for crash-report notifications (separate from auto-update channel). */
    static final String CRASH_CHANNEL_ID = "crash_report";

    /** Notification ID for the crash-report hint. */
    private static final int CRASH_NOTIF_ID = 3001;

    /** Number of recent log lines to embed in the crash notification body. */
    private static final int CRASH_LOG_LINES = 25;

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();
        // Log the startup event so the log always contains a session boundary entry.
        UpdateLogger.logInfo(this,
                "App-Start: " + BuildConfig.VERSION_NAME
                        + " (Code " + BuildConfig.VERSION_CODE + ") auf "
                        + Build.MANUFACTURER + " " + Build.MODEL
                        + " (Android " + Build.VERSION.RELEASE
                        + ", API " + Build.VERSION.SDK_INT + ")");
        checkForPreviousCrash();
    }

    // ── Crash handler ──────────────────────────────────────────────────────────

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String message = "CRASH on thread \"" + thread.getName() + "\"";
                UpdateLogger.logError(KitchenBoardApp.this, message, throwable);
                // Write the sentinel so the next startup knows a crash occurred.
                UpdateLogger.markCrashOccurred(KitchenBoardApp.this);
                Log.e(TAG, message, throwable);
            } catch (Exception ignored) {
                // Never let logging prevent the default crash handler from running.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    // ── Crash notification ────────────────────────────────────────────────────

    /**
     * If a crash sentinel was left by a previous session, shows a persistent status-bar
     * notification that embeds the most recent log entries in a
     * {@link NotificationCompat.BigTextStyle} so the user can read the crash details from
     * the notification shade – even if the app crashes again during this startup.
     *
     * <p>The sentinel is cleared immediately after the notification is posted so that a
     * new crash in this session will write a fresh sentinel and trigger a new notification
     * on the following startup.
     */
    private void checkForPreviousCrash() {
        if (!UpdateLogger.hasCrashSentinel(this)) return;

        // Clear the sentinel before posting; a new one will be written if this session crashes.
        UpdateLogger.clearCrashSentinel(this);

        try {
            createCrashNotificationChannel();

            String recentLog = UpdateLogger.readRecentLogs(this, CRASH_LOG_LINES);

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_reminder_notification)
                            .setContentTitle(getString(R.string.crash_notif_title))
                            .setContentText(getString(R.string.crash_notif_text))
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true);

            if (!recentLog.isEmpty()) {
                builder.setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(recentLog)
                        .setSummaryText(getString(R.string.crash_notif_summary)));
            }

            // Add a "Share log" action so the user can forward the full log even if the
            // app crashes before the settings dialog becomes accessible.
            Intent shareIntent = UpdateLogger.createShareIntent(this);
            if (shareIntent != null) {
                Intent chooser = Intent.createChooser(
                        shareIntent, getString(R.string.update_logs_share));
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT;
                PendingIntent sharePi = PendingIntent.getActivity(
                        this, CRASH_NOTIF_ID, chooser, piFlags);
                builder.addAction(android.R.drawable.ic_menu_share,
                        getString(R.string.update_logs_share), sharePi);
            }

            NotificationManagerCompat.from(this).notify(CRASH_NOTIF_ID, builder.build());
            Log.i(TAG, "Crash notification posted (previous session crash detected)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to post crash notification", e);
        }
    }

    private void createCrashNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CRASH_CHANNEL_ID,
                    getString(R.string.crash_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.crash_channel_desc));
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
