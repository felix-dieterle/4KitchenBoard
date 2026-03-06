package com.kitchenboard.update;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kitchenboard.R;

import java.io.File;

/**
 * Handles both the scheduled auto-update alarm and the DownloadManager completion broadcast.
 *
 * <p>When the twice-daily alarm fires ({@link AutoUpdateScheduler#ACTION_AUTO_UPDATE_CHECK}):
 * <ol>
 *   <li>A brief "Prüfe auf Updates…" notification is shown.
 *   <li>The latest GitHub release is fetched.
 *   <li>If the release is newer <b>and</b> carries the {@link UpdateChecker#AUTO_UPDATE_FLAG},
 *       the APK is downloaded silently via DownloadManager with a progress notification.
 *   <li>If the release is newer but does <b>not</b> carry the flag, no automatic download is
 *       performed (the user will see the normal in-app prompt on next launch).
 *   <li>A brief completion notification is shown in both cases.
 * </ol>
 *
 * When DownloadManager reports {@link DownloadManager#ACTION_DOWNLOAD_COMPLETE}, the downloaded
 * APK is offered for installation via an install-intent notification.
 */
public class AutoUpdateReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "auto_update";

    /** Notification ID for the transient status bar hint. */
    private static final int NOTIF_ID_STATUS   = 2001;
    /** Notification ID for the download-complete / install-ready hint. */
    private static final int NOTIF_ID_INSTALL  = 2002;

    /** SharedPreferences key that stores the active DownloadManager download ID. */
    private static final String PREFS_NAME    = "auto_update_prefs";
    private static final String PREF_DL_ID    = "download_id";
    /** SharedPreferences key for the APK file path of the pending download. */
    private static final String PREF_APK_PATH = "apk_path";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        switch (intent.getAction()) {
            case AutoUpdateScheduler.ACTION_AUTO_UPDATE_CHECK:
                handleUpdateCheck(context);
                break;
            case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
                handleDownloadComplete(context, intent);
                break;
        }
    }

    // ── Update check ──────────────────────────────────────────────────────────

    private void handleUpdateCheck(final Context context) {
        createNotificationChannel(context);

        // Show "checking" hint
        showStatusNotification(context,
                context.getString(R.string.auto_update_checking_title),
                context.getString(R.string.auto_update_checking_text));

        // Use goAsync so the BroadcastReceiver window stays open during the HTTP call
        final PendingResult pendingResult = goAsync();

        UpdateChecker.checkForUpdateWithFlag(
                com.kitchenboard.BuildConfig.VERSION_CODE,
                new UpdateChecker.UpdateResultCallback() {
                    @Override
                    public void onUpdateAvailable(UpdateChecker.UpdateResult result) {
                        try {
                            if (result.isAutoUpdate) {
                                if (result.downloadUrl.endsWith(".apk")) {
                                    startDownload(context, result.downloadUrl, result.tagName);
                                    // Download notification is shown by DownloadManager itself
                                    cancelNotification(context, NOTIF_ID_STATUS);
                                } else {
                                    // No APK asset – inform user; no silent install possible
                                    showStatusNotification(context,
                                            context.getString(R.string.auto_update_available_title),
                                            context.getString(
                                                    R.string.auto_update_available_text,
                                                    result.tagName));
                                }
                            } else {
                                // Release exists but is not flagged for auto-update
                                cancelNotification(context, NOTIF_ID_STATUS);
                            }
                        } finally {
                            pendingResult.finish();
                        }
                    }

                    @Override
                    public void onNoUpdate() {
                        try {
                            cancelNotification(context, NOTIF_ID_STATUS);
                        } finally {
                            pendingResult.finish();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        try {
                            cancelNotification(context, NOTIF_ID_STATUS);
                        } finally {
                            pendingResult.finish();
                        }
                    }
                });
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private void startDownload(Context context, String url, String tagName) {
        File downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            showStatusNotification(context,
                    context.getString(R.string.auto_update_available_title),
                    context.getString(R.string.auto_update_download_failed_text));
            return;
        }

        File apkFile = new File(downloadDir, "4KitchenBoard-update.apk");
        if (apkFile.exists()) apkFile.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(context.getString(R.string.auto_update_download_title))
                .setDescription(tagName)
                .setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, "4KitchenBoard-update.apk")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive");

        DownloadManager dm =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        long downloadId = dm.enqueue(request);

        // Persist download ID and file path so the completion receiver can verify them
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_DL_ID, downloadId)
                .putString(PREF_APK_PATH, apkFile.getAbsolutePath())
                .apply();
    }

    // ── Download complete ─────────────────────────────────────────────────────

    private void handleDownloadComplete(Context context, Intent intent) {
        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long expectedId = prefs.getLong(PREF_DL_ID, -1);

        if (completedId != expectedId) return; // not our download

        String apkPath = prefs.getString(PREF_APK_PATH, null);
        prefs.edit().remove(PREF_DL_ID).remove(PREF_APK_PATH).apply();

        if (apkPath == null) return;
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) return;

        createNotificationChannel(context);
        showInstallNotification(context, apkFile);
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void showStatusNotification(Context context, String title, String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);
        NotificationManagerCompat.from(context).notify(NOTIF_ID_STATUS, builder.build());
    }

    private void showInstallNotification(Context context, File apkFile) {
        // Build an intent that opens the APK installer
        Uri apkUri;
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", apkFile);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, 0, installIntent, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(context.getString(R.string.auto_update_install_title))
                .setContentText(context.getString(R.string.auto_update_install_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManagerCompat.from(context).notify(NOTIF_ID_INSTALL, builder.build());
    }

    private static void cancelNotification(Context context, int id) {
        NotificationManagerCompat.from(context).cancel(id);
    }

    /** Creates the notification channel (no-op on API < 26). */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.auto_update_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.auto_update_channel_desc));
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
