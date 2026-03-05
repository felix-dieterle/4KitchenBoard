package com.kitchenboard;

import android.animation.ObjectAnimator;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.kitchenboard.shopping.ShoppingFragment;
import com.kitchenboard.update.UpdateChecker;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int AUTO_ADVANCE_DELAY_MS = 5_000;

    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;
    private boolean isAutoAdvancePaused = false;

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
        handleDeepLinkIntent(getIntent());
        showVersionOverlay();

        ImageButton btnAccountSetup = findViewById(R.id.btn_account_setup);
        if (btnAccountSetup != null) {
            btnAccountSetup.setOnClickListener(v -> showAccountSetupDialog());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLinkIntent(intent);
    }

    /**
     * Handles kitchenboard://add?name=...&amp;category=... deep links.
     * Stores the pending item in SharedPreferences and navigates to the shopping page.
     */
    private void handleDeepLinkIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        if ("kitchenboard".equals(data.getScheme()) && "add".equals(data.getHost())) {
            String name = data.getQueryParameter("name");
            String category = data.getQueryParameter("category");
            if (name != null && !name.isEmpty()) {
                ShoppingFragment.storePendingQrItem(this, name, category);
                if (viewPager != null) {
                    viewPager.setCurrentItem(0, true);
                }
            }
        }
    }

    // ── Version overlay ───────────────────────────────────────────────────────

    private static final int VERSION_OVERLAY_DISPLAY_MS = 3_000;
    private static final int VERSION_OVERLAY_FADE_MS    = 1_000;

    private final Handler versionOverlayHandler = new Handler(Looper.getMainLooper());
    private Runnable versionOverlayRunnable;

    private void showVersionOverlay() {
        TextView overlay = findViewById(R.id.version_overlay);
        if (overlay == null) return;
        overlay.setText(getString(R.string.version_display,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        versionOverlayRunnable = () -> {
            ObjectAnimator fade = ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f);
            fade.setDuration(VERSION_OVERLAY_FADE_MS);
            fade.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    overlay.setVisibility(View.GONE);
                }
            });
            fade.start();
        };
        versionOverlayHandler.postDelayed(versionOverlayRunnable, VERSION_OVERLAY_DISPLAY_MS);
    }

    // ── Centralized account / family-board setup ──────────────────────────────

    private static final String PREFS_NAME      = "shopping_prefs";
    private static final String PREF_SERVER_URL  = "server_url";
    private static final String PREF_BOARD_TOKEN = "board_token";

    private void showAccountSetupDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        int padPx = Math.round(16 * getResources().getDisplayMetrics().density);

        final EditText etUrl = new EditText(this);
        etUrl.setHint(R.string.sync_url_hint);
        etUrl.setSingleLine(true);
        etUrl.setText(prefs.getString(PREF_SERVER_URL, ""));

        final TextView tvTokenDesc = new TextView(this);
        tvTokenDesc.setText(R.string.board_token_description);
        tvTokenDesc.setTextSize(12f);
        tvTokenDesc.setPadding(0, padPx / 2, 0, 0);

        final EditText etToken = new EditText(this);
        etToken.setHint(R.string.board_token_hint);
        etToken.setSingleLine(true);
        etToken.setText(prefs.getString(PREF_BOARD_TOKEN, ""));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padPx, padPx, padPx, padPx);
        layout.addView(etUrl);
        layout.addView(tvTokenDesc);
        layout.addView(etToken);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.account_setup_title)
                .setMessage(R.string.account_setup_message)
                .setView(layout)
                .setPositiveButton(R.string.account_setup_save, (d, which) -> {
                    String url   = etUrl.getText().toString().trim();
                    String token = etToken.getText().toString().trim();
                    prefs.edit()
                            .putString(PREF_SERVER_URL, url)
                            .putString(PREF_BOARD_TOKEN, token)
                            .apply();
                })
                .setNeutralButton(R.string.account_setup_copy, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String url   = etUrl.getText().toString().trim();
            String token = etToken.getText().toString().trim();
            String config = url + (token.isEmpty() ? "" : "\nToken: " + token);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("KitchenBoard Config", config));
            }
            Toast.makeText(this, R.string.account_setup_copied, Toast.LENGTH_SHORT).show();
        });
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

    // ── Auto-advance control (used by fragments showing dialogs) ──────────────

    public void pauseAutoAdvance() {
        isAutoAdvancePaused = true;
        autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
    }

    public void resumeAutoAdvance() {
        isAutoAdvancePaused = false;
        autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
        autoAdvanceHandler.postDelayed(autoAdvanceRunnable, AUTO_ADVANCE_DELAY_MS);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && !isAutoAdvancePaused) {
            autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
            autoAdvanceHandler.postDelayed(autoAdvanceRunnable, AUTO_ADVANCE_DELAY_MS);
        }
        return super.dispatchTouchEvent(ev);
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
        versionOverlayHandler.removeCallbacks(versionOverlayRunnable);
        if (viewPager != null && pageChangeCallback != null) {
            viewPager.unregisterOnPageChangeCallback(pageChangeCallback);
        }
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
            downloadReceiver = null;
        }
    }
}

