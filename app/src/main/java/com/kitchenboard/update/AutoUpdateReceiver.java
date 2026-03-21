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
 *   <li>The configured backend server is checked first. If the backend reports a newer
 *       {@code (buildNumber, subNumber)}, the backend APK is downloaded and the pending
 *       sub-number is persisted for later commit.
 *   <li>If the backend has no update <b>or fails</b>, the latest GitHub release is fetched as
 *       the fallback. If it is newer <b>and</b> carries the {@link UpdateChecker#AUTO_UPDATE_FLAG},
 *       the APK is downloaded silently via DownloadManager (sub-number reset to 0).
 *   <li>If neither source has an update the status notification is silently cancelled.
 * </ol>
 *
 * When DownloadManager reports {@link DownloadManager#ACTION_DOWNLOAD_COMPLETE}, the
 * downloaded APK is offered for installation and the pending sub-number is committed as
 * the new current sub-number via {@link BackendUpdateChecker#saveCurrentSubNumber}.
 */
public class AutoUpdateReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "auto_update";

    /** Notification ID for the transient status bar hint. */
    private static final int NOTIF_ID_STATUS   = 2001;
    /** Notification ID for the download-complete / install-ready hint. */
    private static final int NOTIF_ID_INSTALL  = 2002;

    /** SharedPreferences key that stores the active DownloadManager download ID. */
    private static final String PREFS_NAME       = BackendUpdateChecker.PREFS_UPDATE;
    private static final String PREF_DL_ID       = "download_id";
    /** SharedPreferences key for the APK file path of the pending download. */
    private static final String PREF_APK_PATH    = "apk_path";

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

        // Use goAsync so the BroadcastReceiver window stays open during the HTTP calls
        final PendingResult pendingResult = goAsync();

        // Backend is the primary update source; GitHub serves as the fallback.
        checkBackend(context, pendingResult);
    }

    /** First step: check the configured backend. Falls back to GitHub on failure or no update. */
    private void checkBackend(final Context context, final PendingResult pendingResult) {
        int currentBuildNr = com.kitchenboard.BuildConfig.VERSION_CODE;
        int currentSubNr   = BackendUpdateChecker.getCurrentSubNumber(context);

        BackendUpdateChecker.checkForUpdate(context, currentBuildNr, currentSubNr,
                new BackendUpdateChecker.BackendUpdateCallback() {
                    @Override
                    public void onUpdateAvailable(BackendUpdateChecker.BackendUpdateResult result) {
                        try {
                            UpdateLogger.logInfo(context,
                                    "Backend update available: " + result.tagName);
                            if (result.downloadUrl.endsWith(".apk")) {
                                startDownload(context, result.downloadUrl,
                                        result.tagName, result.subNumber);
                                cancelNotification(context, NOTIF_ID_STATUS);
                            } else {
                                showStatusNotification(context,
                                        context.getString(R.string.auto_update_available_title),
                                        context.getString(
                                                R.string.auto_update_backend_available_text,
                                                result.tagName));
                            }
                        } finally {
                            pendingResult.finish();
                        }
                    }

                    @Override
                    public void onNoUpdate() {
                        // Backend healthy but no update – still check GitHub for a newer release.
                        checkGitHub(context, pendingResult);
                    }

                    @Override
                    public void onError(String message) {
                        UpdateLogger.logError(context,
                                "Auto-update check (backend) failed: "
                                        + (message != null ? message : "unknown error"));
                        // Backend unavailable – always fall back to GitHub.
                        checkGitHub(context, pendingResult);
                    }
                });
    }

    /** Fallback: check GitHub when the backend has no update or is unavailable. */
    private void checkGitHub(final Context context, final PendingResult pendingResult) {
        UpdateChecker.checkForUpdateWithFlag(
                context,
                com.kitchenboard.BuildConfig.VERSION_CODE,
                new UpdateChecker.UpdateResultCallback() {
                    @Override
                    public void onUpdateAvailable(UpdateChecker.UpdateResult result) {
                        UpdateLogger.logInfo(context,
                                "GitHub update available: " + result.tagName
                                        + (result.isAutoUpdate ? " [auto_update]" : ""));
                        if (result.isAutoUpdate) {
                            if (result.downloadUrl.endsWith(".apk")) {
                                // GitHub [auto_update] APK: download it and reset sub-number
                                startDownload(context, result.downloadUrl, result.tagName, 0);
                                cancelNotification(context, NOTIF_ID_STATUS);
                                pendingResult.finish();
                                return;
                            } else {
                                // No APK asset – inform user
                                UpdateLogger.logError(context,
                                        "Auto-update: no APK asset found for " + result.tagName);
                                showStatusNotification(context,
                                        context.getString(R.string.auto_update_available_title),
                                        context.getString(
                                                R.string.auto_update_available_text,
                                                result.tagName));
                            }
                        }
                        // Non-[auto_update] release or no APK: nothing to download silently.
                        cancelNotification(context, NOTIF_ID_STATUS);
                        pendingResult.finish();
                    }

                    @Override
                    public void onNoUpdate() {
                        cancelNotification(context, NOTIF_ID_STATUS);
                        pendingResult.finish();
                    }

                    @Override
                    public void onError(String message) {
                        UpdateLogger.logError(context,
                                "Auto-update check (GitHub) failed: "
                                        + (message != null ? message : "unknown error"));
                        cancelNotification(context, NOTIF_ID_STATUS);
                        pendingResult.finish();
                    }
                });
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Enqueues an APK download via DownloadManager and persists the download state.
     *
     * @param subNumber backend sub-number of this release (0 for GitHub releases)
     */
    private void startDownload(Context context, String url, String tagName, int subNumber) {
        UpdateLogger.logInfo(context, "Starting APK download for " + tagName + " from " + url);
        File downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            UpdateLogger.logError(context,
                    "APK download failed: external storage unavailable for " + tagName);
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
        if (dm == null) {
            UpdateLogger.logError(context,
                    "APK download failed: DownloadManager service unavailable for " + tagName);
            return;
        }
        long downloadId = dm.enqueue(request);

        // Persist download ID, file path, and pending sub-number so the completion
        // receiver can verify and commit the sub-number upon successful install.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_DL_ID, downloadId)
                .putString(PREF_APK_PATH, apkFile.getAbsolutePath())
                .putInt(BackendUpdateChecker.PREF_PENDING_SUB_NR, subNumber)
                .apply();
    }

    // ── Download complete ─────────────────────────────────────────────────────

    private void handleDownloadComplete(Context context, Intent intent) {
        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long expectedId = prefs.getLong(PREF_DL_ID, -1);

        if (completedId != expectedId) return; // not our download

        String apkPath       = prefs.getString(PREF_APK_PATH, null);
        int pendingSubNumber = prefs.getInt(BackendUpdateChecker.PREF_PENDING_SUB_NR, 0);
        prefs.edit()
                .remove(PREF_DL_ID)
                .remove(PREF_APK_PATH)
                .remove(BackendUpdateChecker.PREF_PENDING_SUB_NR)
                .apply();

        if (apkPath == null) return;
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) return;

        // Commit the sub-number so future checks compare correctly.
        BackendUpdateChecker.saveCurrentSubNumber(context, pendingSubNumber);

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
