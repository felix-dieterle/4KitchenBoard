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
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.kitchenboard.update.UpdateChecker;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int AUTO_ADVANCE_DELAY_MS = 20_000;

    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;

    private ViewPager2 viewPager;
    private ScreenPagerAdapter pagerAdapter;
    private View[] dots;
    private LinearLayout dotContainer;
    private ViewPager2.OnPageChangeCallback pageChangeCallback;

    private final Handler autoAdvanceHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoAdvanceRunnable = new Runnable() {
        @Override
        public void run() {
            if (viewPager == null || pagerAdapter == null) return;
            int next = (viewPager.getCurrentItem() + 1) % pagerAdapter.getItemCount();
            viewPager.setCurrentItem(next, true);
            autoAdvanceHandler.postDelayed(this, AUTO_ADVANCE_DELAY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.view_pager);
        dotContainer = findViewById(R.id.dot_container);

        pagerAdapter = new ScreenPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        setupDots(pagerAdapter.getItemCount());

        pageChangeCallback = new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // Reset the auto-advance timer whenever the page changes
                autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
                autoAdvanceHandler.postDelayed(autoAdvanceRunnable, AUTO_ADVANCE_DELAY_MS);
                updateDots(position);
            }
        };
        viewPager.registerOnPageChangeCallback(pageChangeCallback);

        checkForUpdates();
    }

    // ── Dot indicator helpers ─────────────────────────────────────────────────

    private void setupDots(int count) {
        dotContainer.removeAllViews();
        dots = new View[count];
        int sizePx = dpToPx(5);
        int marginPx = dpToPx(3);
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(lp);
            dot.setBackground(ContextCompat.getDrawable(this, R.drawable.dot_indicator));
            dots[i] = dot;
            dotContainer.addView(dot);
        }
        updateDots(0);
    }

    private void updateDots(int activeIndex) {
        if (dots == null) return;
        for (int i = 0; i < dots.length; i++) {
            dots[i].setAlpha(i == activeIndex ? 0.7f : 0.2f);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        autoAdvanceHandler.postDelayed(autoAdvanceRunnable, AUTO_ADVANCE_DELAY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
    }

    // ── Update checker ────────────────────────────────────────────────────────

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
        autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
        if (viewPager != null && pageChangeCallback != null) {
            viewPager.unregisterOnPageChangeCallback(pageChangeCallback);
        }
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
            downloadReceiver = null;
        }
    }
}

