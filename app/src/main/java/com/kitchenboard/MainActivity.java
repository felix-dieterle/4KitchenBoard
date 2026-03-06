package com.kitchenboard;

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
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.kitchenboard.shopping.ShoppingFragment;
import com.kitchenboard.update.AutoUpdateReceiver;
import com.kitchenboard.update.AutoUpdateScheduler;
import com.kitchenboard.update.UpdateChecker;

import java.io.File;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int AUTO_ADVANCE_DELAY_MS = 20_000;

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
            int current = viewPager.getCurrentItem();
            int count = pagerAdapter.getItemCount();
            int next = current;
            for (int i = 1; i <= count; i++) {
                int candidate = (current + i) % count;
                if (isPageInRotation(candidate)) {
                    next = candidate;
                    break;
                }
            }
            // If no page is in rotation, next == current and the pager stays put.
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

        // Schedule the twice-daily background auto-update check
        AutoUpdateReceiver.createNotificationChannel(this);
        AutoUpdateScheduler.schedule(this);

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
        try {
            TextView overlay = findViewById(R.id.version_overlay);
            if (overlay == null) return;
            overlay.setText(getString(R.string.version_display,
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
            versionOverlayRunnable = () -> {
                if (overlay.isAttachedToWindow()) {
                    overlay.animate()
                            .alpha(0f)
                            .setDuration(VERSION_OVERLAY_FADE_MS)
                            .withEndAction(() -> overlay.setVisibility(View.GONE))
                            .start();
                } else {
                    overlay.setVisibility(View.GONE);
                }
            };
            versionOverlayHandler.postDelayed(versionOverlayRunnable, VERSION_OVERLAY_DISPLAY_MS);
        } catch (Exception e) {
            // Version overlay is non-critical; log and swallow any unexpected exception
            // so it cannot prevent the app from starting.
            Log.w(TAG, "Failed to show version overlay", e);
        }
    }

    // ── Centralized account / family-board setup ──────────────────────────────

    private static final String PREFS_NAME       = "shopping_prefs";
    private static final String PREF_SERVER_URL  = "server_url";
    private static final String PREF_BOARD_TOKEN = "board_token";
    private static final String PREF_API_TOKEN   = "api_token";
    private static final String PREF_PAGE_IN_ROTATION = "page_%d_in_rotation";

    private boolean isPageInRotation(int pageIndex) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), true);
    }

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

        final TextView tvApiTokenDesc = new TextView(this);
        tvApiTokenDesc.setText(R.string.api_token_description);
        tvApiTokenDesc.setTextSize(12f);
        tvApiTokenDesc.setPadding(0, padPx / 2, 0, 0);

        final EditText etApiToken = new EditText(this);
        etApiToken.setHint(R.string.api_token_hint);
        etApiToken.setSingleLine(true);
        etApiToken.setText(prefs.getString(PREF_API_TOKEN, ""));

        // Page rotation section
        final TextView tvRotationSection = new TextView(this);
        tvRotationSection.setText(R.string.page_rotation_section);
        tvRotationSection.setTextSize(12f);
        tvRotationSection.setPadding(0, padPx, 0, padPx / 4);

        final int[] pageNameResIds = {
            R.string.page_name_shopping,
            R.string.page_name_calendar,
            R.string.page_name_cooking,
            R.string.page_name_tasks
        };
        final CheckBox[] cbPages = new CheckBox[pageNameResIds.length];
        for (int i = 0; i < pageNameResIds.length; i++) {
            cbPages[i] = new CheckBox(this);
            cbPages[i].setText(pageNameResIds[i]);
            cbPages[i].setChecked(isPageInRotation(i));
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padPx, padPx, padPx, padPx);
        layout.addView(etUrl);
        layout.addView(tvTokenDesc);
        layout.addView(etToken);
        layout.addView(tvApiTokenDesc);
        layout.addView(etApiToken);
        layout.addView(tvRotationSection);
        for (CheckBox cb : cbPages) {
            layout.addView(cb);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.account_setup_title)
                .setMessage(R.string.account_setup_message)
                .setView(scrollView)
                .setPositiveButton(R.string.account_setup_save, (d, which) -> {
                    String url      = etUrl.getText().toString().trim();
                    String token    = etToken.getText().toString().trim();
                    String apiToken = etApiToken.getText().toString().trim();
                    SharedPreferences.Editor editor = prefs.edit()
                            .putString(PREF_SERVER_URL, url)
                            .putString(PREF_BOARD_TOKEN, token)
                            .putString(PREF_API_TOKEN, apiToken);
                    for (int i = 0; i < cbPages.length; i++) {
                        editor.putBoolean(String.format(Locale.US, PREF_PAGE_IN_ROTATION, i),
                                cbPages[i].isChecked());
                    }
                    editor.apply();
                })
                .setNeutralButton(R.string.account_setup_copy, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String url      = etUrl.getText().toString().trim();
            String token    = etToken.getText().toString().trim();
            String apiToken = etApiToken.getText().toString().trim();
            String config = url
                    + (token.isEmpty()    ? "" : "\nToken: "     + token)
                    + (apiToken.isEmpty() ? "" : "\nAPI-Token: " + apiToken);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("KitchenBoard Config", config));
            }
            Toast.makeText(this, R.string.account_setup_copied, Toast.LENGTH_SHORT).show();
        });
    }

    // ── Dot indicator helpers ─────────────────────────────────────────────────

    /** Module accent color resources, ordered by page index (matches ScreenPagerAdapter). */
    private static final int[] MODULE_COLORS = {
        R.color.module_shopping,   // page 0: CombinedFragment – shopping (right) + weather (left)
        R.color.module_calendar,   // page 1: CalendarFragment
        R.color.module_cooking,    // page 2: CookingFragment
        R.color.module_tasks       // page 3: TaskFragment
    };

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
            android.graphics.drawable.Drawable dotDrawable =
                    ContextCompat.getDrawable(this, R.drawable.dot_indicator);
            if (dotDrawable != null) {
                dotDrawable = DrawableCompat.wrap(dotDrawable).mutate();
                int colorRes = (i < MODULE_COLORS.length) ? MODULE_COLORS[i] : R.color.text_primary;
                DrawableCompat.setTint(dotDrawable, ContextCompat.getColor(this, colorRes));
                dots[i].setBackground(dotDrawable);
            }
            dots[i].setAlpha(i == activeIndex ? 1.0f : 0.25f);
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
        Toast.makeText(this, R.string.auto_update_checking_text, Toast.LENGTH_SHORT).show();
        UpdateChecker.checkForUpdateWithFlag(this, BuildConfig.VERSION_CODE,
                new UpdateChecker.UpdateResultCallback() {
            @Override
            public void onUpdateAvailable(final UpdateChecker.UpdateResult result) {
                if (isFinishing()) return;
                // For auto-update releases the background scheduler handles the download.
                // Only show the interactive prompt for releases that are NOT flagged.
                if (result.isAutoUpdate) {
                    // Background scheduler will handle the download automatically.
                    // Show a brief hint so the user knows an update was detected.
                    Toast.makeText(MainActivity.this,
                            R.string.auto_update_pending_toast,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_message, result.tagName))
                        .setPositiveButton(R.string.update_download, (dialog, which) -> {
                            if (result.downloadUrl.endsWith(".apk")) {
                                downloadAndInstallApk(result.downloadUrl, result.tagName);
                            } else {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(result.downloadUrl)));
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }

            @Override
            public void onNoUpdate() {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(MainActivity.this,
                        R.string.update_up_to_date,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(MainActivity.this,
                        R.string.update_check_error,
                        Toast.LENGTH_SHORT).show();
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

