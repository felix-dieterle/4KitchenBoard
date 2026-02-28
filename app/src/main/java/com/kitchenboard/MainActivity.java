package com.kitchenboard;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentTransaction;

import com.kitchenboard.shopping.ShoppingFragment;
import com.kitchenboard.update.UpdateChecker;
import com.kitchenboard.weather.WeatherFragment;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.container_weather, new WeatherFragment());
            ft.replace(R.id.container_shopping, new ShoppingFragment());
            ft.commit();

            checkForUpdates();
        }
    }

    private void checkForUpdates() {
        UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE, new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(final String tagName, final String downloadUrl) {
                if (isFinishing()) return;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_message, tagName))
                        .setPositiveButton(R.string.update_download, (dialog, which) -> {
                            if (downloadUrl.endsWith(".apk")) {
                                downloadAndInstallApk(downloadUrl, tagName);
                            } else {
                                // Fallback: open releases page in browser
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(downloadUrl)));
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }

            @Override
            public void onNoUpdate() {
                // nothing to do
            }

            @Override
            public void onError(String message) {
                // silently ignore update check errors
            }
        });
    }

    private void downloadAndInstallApk(String url, String tagName) {
        File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            // External storage unavailable; fall back to browser
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return;
        }

        // Clean up any previously downloaded APK
        File apkFile = new File(downloadDir, "4KitchenBoard-update.apk");
        if (apkFile.exists()) apkFile.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(getString(R.string.update_available_title))
                .setDescription(tagName)
                .setDestinationInExternalFilesDir(this,
                        Environment.DIRECTORY_DOWNLOADS, "4KitchenBoard-update.apk")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive");

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        downloadId = dm.enqueue(request);

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    unregisterReceiver(downloadReceiver);
                    downloadReceiver = null;
                    installApk(apkFile);
                }
            }
        };
        registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void installApk(File apkFile) {
        if (!apkFile.exists()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
            downloadReceiver = null;
        }
    }
}
