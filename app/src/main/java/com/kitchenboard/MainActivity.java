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
import android.provider.Settings;
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

import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;
import com.kitchenboard.shopping.ShoppingFragment;
import com.kitchenboard.tasks.TasksCheckScheduler;
import com.kitchenboard.update.AutoUpdateReceiver;
import com.kitchenboard.update.AutoUpdateScheduler;
import com.kitchenboard.update.UpdateChecker;
import com.kitchenboard.wellness.WellnessCheckDialog;
import com.kitchenboard.wellness.WellnessCheckScheduler;
import com.kitchenboard.calendar.CalendarDatabaseHelper;
import com.kitchenboard.calendar.Person;
import com.kitchenboard.calendar.PersonAvatarHelper;
import android.app.TimePickerDialog;

import android.view.LayoutInflater;
import android.widget.ImageView;
import android.graphics.Bitmap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int AUTO_ADVANCE_DELAY_MS = 20_000;

    /** Fraction of screen height used as the max height of the notification list. */
    private static final float NOTIFICATION_PANEL_HEIGHT_RATIO = 0.45f;
    /** Maximum unread count shown in the notification badge before showing "99+". */
    private static final int MAX_BADGE_COUNT = 99;

    /** SharedPreferences file for calendar / active-profile state. */
    public static final String PREFS_CALENDAR = "calendar_prefs";
    /** SharedPreferences key storing the active person's database ID (-1 = none). */
    public static final String PREF_ACTIVE_PERSON_ID = "active_person_id";

    private long downloadId = -1;
    /** Sub-number of the APK being downloaded (0 = GitHub release, >0 = backend release).
     *  Saved to SharedPreferences when the download completes so future checks compare correctly. */
    private int  pendingSubNumber = 0;
    private BroadcastReceiver downloadReceiver;
    private BroadcastReceiver wellnessCheckReceiver;
    private boolean isAutoAdvancePaused = false;

    // ── Active profile avatar ─────────────────────────────────────────────────
    private ImageView ivActiveProfile;
    private SharedPreferences.OnSharedPreferenceChangeListener activeProfileListener;

    private ViewPager2 viewPager;
    private ScreenPagerAdapter pagerAdapter;
    private View[] dots;
    private LinearLayout dotContainer;
    private ViewPager2.OnPageChangeCallback pageChangeCallback;

    // ── In-app notification panel ─────────────────────────────────────────────
    private View notificationPanelOverlay;
    private LinearLayout notificationListContainer;
    private TextView tvNotificationBadge;
    private TextView tvNoNotifications;
    private final NotificationStore.Observer notificationObserver = () -> {
        refreshNotificationBadge();
        if (notificationPanelOverlay != null
                && notificationPanelOverlay.getVisibility() == View.VISIBLE) {
            populateNotificationList();
        }
    };

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
        com.kitchenboard.update.UpdateLogger.logInfo(this, "MainActivity.onCreate: start");
        // Keep the screen on permanently so the wall-mounted tablet never goes idle.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        com.kitchenboard.update.UpdateLogger.logInfo(this,
                "MainActivity.onCreate: calling checkForUpdates");
        checkForUpdates();
        handleDeepLinkIntent(getIntent());
        handleNavigateToPageIntent(getIntent());
        showVersionOverlay();

        // Schedule the twice-daily background auto-update check
        AutoUpdateReceiver.createNotificationChannel(this);
        AutoUpdateScheduler.schedule(this);

        // Schedule daily morning wellness check
        WellnessCheckScheduler.schedule(this);

        // Schedule background check for newly assigned tasks
        TasksCheckScheduler.schedule(this);

        ImageButton btnAccountSetup = findViewById(R.id.btn_account_setup);
        if (btnAccountSetup != null) {
            btnAccountSetup.setOnClickListener(v -> showAccountSetupDialog());
        }

        setupNotificationPanel();
        setupActiveProfile();
        com.kitchenboard.update.UpdateLogger.logInfo(this, "MainActivity.onCreate: complete");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLinkIntent(intent);
        handleNavigateToPageIntent(intent);
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

    /**
     * Navigates to a specific page when the app is opened from a notification.
     * The page index is passed as an integer extra with key {@code "navigate_to_page"}.
     */
    private void handleNavigateToPageIntent(Intent intent) {
        if (intent == null) return;
        int page = intent.getIntExtra("navigate_to_page", -1);
        if (page >= 0 && viewPager != null && pagerAdapter != null
                && page < pagerAdapter.getItemCount()) {
            viewPager.setCurrentItem(page, true);
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

    private static final String PREF_WELLNESS_ENABLED   = "wellness_enabled";
    private static final String PREF_WELLNESS_HOUR      = "wellness_hour";
    private static final String PREF_WELLNESS_MINUTE    = "wellness_minute";
    private static final String PREF_WELLNESS_LAST_DATE = "wellness_last_date";
    private static final String PREF_WELLNESS_PENDING   = "wellness_check_pending";

    /** Request code for the system package-installer activity launched via startActivityForResult. */
    private static final int REQUEST_INSTALL_APK         = 1001;
    /** Request code for the "Install unknown apps" settings page (Android 8+). */
    private static final int REQUEST_UNKNOWN_APP_SOURCES = 1002;
    /** SharedPreferences key: path of the APK file waiting to be installed. */
    private static final String PREF_INSTALL_APK_PATH = "install_apk_path";

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

        final TextView tvUrlExample = new TextView(this);
        tvUrlExample.setText(R.string.sync_url_example);
        tvUrlExample.setTextSize(12f);
        tvUrlExample.setAlpha(0.6f);

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
            R.string.page_name_tasks,
            R.string.page_name_immobilien
        };
        final CheckBox[] cbPages = new CheckBox[pageNameResIds.length];
        for (int i = 0; i < pageNameResIds.length; i++) {
            cbPages[i] = new CheckBox(this);
            cbPages[i].setText(pageNameResIds[i]);
            cbPages[i].setChecked(isPageInRotation(i));
        }

        android.widget.Button btnInfo = new android.widget.Button(this);
        btnInfo.setText(R.string.settings_info_button);
        btnInfo.setOnClickListener(v -> showSettingsInfoDialog());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padPx, padPx, padPx, padPx);
        layout.addView(btnInfo);
        layout.addView(etUrl);
        layout.addView(tvUrlExample);
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

        // ── Update-Protokoll section ──────────────────────────────────────────
        final TextView tvLogsSection = new TextView(this);
        tvLogsSection.setText(R.string.update_logs_section);
        tvLogsSection.setTextSize(12f);
        tvLogsSection.setPadding(0, padPx, 0, padPx / 4);

        android.widget.Button btnViewLogs = new android.widget.Button(this);
        btnViewLogs.setText(R.string.update_logs_view_button);
        btnViewLogs.setOnClickListener(v -> showUpdateLogsDialog());

        layout.addView(tvLogsSection);
        layout.addView(btnViewLogs);

        // ── Wellness-Check Einstellungen ──────────────────────────────────────
        final TextView tvWellnessSection = new TextView(this);
        tvWellnessSection.setText(R.string.wellness_settings_section);
        tvWellnessSection.setTextSize(12f);
        tvWellnessSection.setPadding(0, padPx, 0, padPx / 4);

        final CheckBox cbWellnessEnabled = new CheckBox(this);
        cbWellnessEnabled.setText(R.string.wellness_settings_enabled);
        cbWellnessEnabled.setChecked(prefs.getBoolean(PREF_WELLNESS_ENABLED, true));

        int wHour   = prefs.getInt(PREF_WELLNESS_HOUR,   WellnessCheckScheduler.DEFAULT_HOUR);
        int wMinute = prefs.getInt(PREF_WELLNESS_MINUTE, WellnessCheckScheduler.DEFAULT_MINUTE);
        final int[] wellnessTime = {wHour, wMinute};

        android.widget.Button btnWellnessTime = new android.widget.Button(this);
        btnWellnessTime.setText(getString(R.string.wellness_settings_time, wHour, wMinute));
        btnWellnessTime.setOnClickListener(v -> new TimePickerDialog(this, (view1, h, m) -> {
            wellnessTime[0] = h;
            wellnessTime[1] = m;
            btnWellnessTime.setText(getString(R.string.wellness_settings_time, h, m));
        }, wellnessTime[0], wellnessTime[1], true).show());

        layout.addView(tvWellnessSection);
        layout.addView(cbWellnessEnabled);
        layout.addView(btnWellnessTime);

        // Track whether the user explicitly cancelled (back-press / outside tap should still save)
        final AtomicBoolean cancelClicked = new AtomicBoolean(false);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.account_setup_title)
                .setMessage(R.string.account_setup_message)
                .setView(scrollView)
                .setPositiveButton(R.string.account_setup_save, null)
                .setNeutralButton(R.string.account_setup_copy, null)
                .setNegativeButton(R.string.cancel, (d, which) -> cancelClicked.set(true))
                .create();

        dialog.setOnDismissListener(d -> {
            if (!cancelClicked.get()) {
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
                editor.putBoolean(PREF_WELLNESS_ENABLED, cbWellnessEnabled.isChecked());
                editor.putInt(PREF_WELLNESS_HOUR,   wellnessTime[0]);
                editor.putInt(PREF_WELLNESS_MINUTE, wellnessTime[1]);
                editor.apply();
                WellnessCheckScheduler.schedule(MainActivity.this);
            }
        });

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

    // ── Settings info dialog ──────────────────────────────────────────────────

    private void showSettingsInfoDialog() {
        int padPx = Math.round(12 * getResources().getDisplayMetrics().density);

        final TextView tvInfo = new TextView(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            tvInfo.setText(android.text.Html.fromHtml(getString(R.string.settings_info_message),
                    android.text.Html.FROM_HTML_MODE_COMPACT));
        } else {
            tvInfo.setText(android.text.Html.fromHtml(getString(R.string.settings_info_message)));
        }
        tvInfo.setTextSize(13f);
        tvInfo.setPadding(padPx, padPx, padPx, padPx);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(tvInfo);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_info_title)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ── Update-Protokoll dialog ───────────────────────────────────────────────

    private void showUpdateLogsDialog() {
        String logs = com.kitchenboard.update.UpdateLogger.readLogs(this);
        if (logs.isEmpty()) {
            logs = getString(R.string.update_logs_empty);
        }

        final TextView tvLogs = new TextView(this);
        tvLogs.setText(logs);
        tvLogs.setTextSize(10f);
        tvLogs.setTypeface(android.graphics.Typeface.MONOSPACE);

        int padPx = Math.round(8 * getResources().getDisplayMetrics().density);
        tvLogs.setPadding(padPx, padPx, padPx, padPx);

        final ScrollView svLogs = new ScrollView(this);
        svLogs.addView(tvLogs);
        svLogs.post(() -> svLogs.fullScroll(View.FOCUS_DOWN));

        new AlertDialog.Builder(this)
                .setTitle(R.string.update_logs_title)
                .setView(svLogs)
                .setPositiveButton(R.string.update_logs_share, (d, which) -> {
                    Intent shareIntent =
                            com.kitchenboard.update.UpdateLogger.createShareIntent(this);
                    if (shareIntent != null) {
                        startActivity(Intent.createChooser(shareIntent,
                                getString(R.string.update_logs_share)));
                    } else {
                        Toast.makeText(this, R.string.update_logs_empty,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.update_logs_clear, (d, which) -> {
                    com.kitchenboard.update.UpdateLogger.clearLogs(this);
                    Toast.makeText(this, R.string.update_logs_cleared,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Dot indicator helpers ─────────────────────────────────────────────────

    /** Module accent color resources, ordered by page index (matches ScreenPagerAdapter). */
    private static final int[] MODULE_COLORS = {
        R.color.module_shopping,    // page 0: CombinedFragment – shopping (right) + weather (left)
        R.color.module_calendar,    // page 1: CalendarFragment
        R.color.module_cooking,     // page 2: CookingFragment
        R.color.module_tasks,       // page 3: TaskFragment
        R.color.module_immobilien   // page 4: ImmobilienFragment
    };

    // ── In-app notification panel ─────────────────────────────────────────────

    private void setupNotificationPanel() {
        notificationPanelOverlay   = findViewById(R.id.notification_panel_overlay);
        notificationListContainer  = findViewById(R.id.notification_list_container);
        tvNotificationBadge        = findViewById(R.id.tv_notification_badge);
        tvNoNotifications          = findViewById(R.id.tv_no_notifications);

        // Constrain the notification list scroll area to 45% of screen height
        android.widget.ScrollView scrollView = findViewById(R.id.notification_scroll_view);
        if (scrollView != null) {
            int maxHeightPx = (int) (getResources().getDisplayMetrics().heightPixels
                    * NOTIFICATION_PANEL_HEIGHT_RATIO);
            android.view.ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
            lp.height = maxHeightPx;
            scrollView.setLayoutParams(lp);
        }

        ImageButton btnBell = findViewById(R.id.btn_notification_bell);
        if (btnBell != null) {
            btnBell.setOnClickListener(v -> openNotificationPanel());
        }

        View btnClose = findViewById(R.id.btn_close_notification_panel);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> closeNotificationPanel());
        }

        View tvClearAll = findViewById(R.id.tv_notifications_clear_all);
        if (tvClearAll != null) {
            tvClearAll.setOnClickListener(v -> {
                NotificationStore.getInstance(this).clearAll();
                closeNotificationPanel();
            });
        }

        // Tapping the dark backdrop closes the panel
        if (notificationPanelOverlay != null) {
            notificationPanelOverlay.setOnClickListener(v -> closeNotificationPanel());
            // Prevent touches on the panel itself from propagating to the backdrop
            View panel = notificationPanelOverlay.findViewById(R.id.notification_panel);
            if (panel != null) {
                panel.setOnClickListener(v -> { /* consume */ });
            }
        }

        refreshNotificationBadge();
    }

    private void openNotificationPanel() {
        if (notificationPanelOverlay == null) return;
        NotificationStore.getInstance(this).markAllRead();
        populateNotificationList();
        notificationPanelOverlay.setVisibility(View.VISIBLE);
        notificationPanelOverlay.setAlpha(0f);
        notificationPanelOverlay.animate().alpha(1f).setDuration(180).start();
        pauseAutoAdvance();
    }

    private void closeNotificationPanel() {
        if (notificationPanelOverlay == null) return;
        notificationPanelOverlay.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    notificationPanelOverlay.setVisibility(View.GONE);
                    notificationPanelOverlay.setAlpha(1f);
                })
                .start();
        resumeAutoAdvance();
    }

    private void populateNotificationList() {
        if (notificationListContainer == null) return;
        notificationListContainer.removeAllViews();

        List<AppNotification> notifications = NotificationStore.getInstance(this).getAll();

        if (tvNoNotifications != null) {
            tvNoNotifications.setVisibility(notifications.isEmpty() ? View.VISIBLE : View.GONE);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppNotification n : notifications) {
            View item = inflater.inflate(R.layout.item_notification, notificationListContainer, false);

            TextView tvTitle   = item.findViewById(R.id.tv_notif_title);
            TextView tvMessage = item.findViewById(R.id.tv_notif_message);
            TextView tvTime    = item.findViewById(R.id.tv_notif_time);
            ImageView ivIcon   = item.findViewById(R.id.iv_notif_icon);
            View unreadDot     = item.findViewById(R.id.view_unread_dot);

            if (tvTitle   != null) tvTitle.setText(n.title);
            if (tvMessage != null) tvMessage.setText(n.message);
            if (tvTime    != null) tvTime.setText(formatRelativeTime(n.timestampMs));
            if (unreadDot != null) unreadDot.setVisibility(n.isRead ? View.GONE : View.VISIBLE);

            if (ivIcon != null) {
                if (n.type == AppNotification.TYPE_REMINDER) {
                    ivIcon.setImageResource(R.drawable.ic_reminder_notification);
                    ivIcon.setContentDescription(
                            getString(R.string.calendar_reminder_notification_title));
                    DrawableCompat.setTint(
                            DrawableCompat.wrap(ivIcon.getDrawable()).mutate(),
                            ContextCompat.getColor(this, R.color.module_calendar));
                } else {
                    ivIcon.setImageResource(android.R.drawable.ic_dialog_info);
                    ivIcon.setContentDescription(
                            getString(R.string.immobilien_notif_title));
                    DrawableCompat.setTint(
                            DrawableCompat.wrap(ivIcon.getDrawable()).mutate(),
                            ContextCompat.getColor(this, R.color.module_immobilien));
                }
            }

            final int page = n.navigateTo;
            item.setOnClickListener(v -> {
                closeNotificationPanel();
                if (page >= 0 && viewPager != null && pagerAdapter != null
                        && page < pagerAdapter.getItemCount()) {
                    viewPager.setCurrentItem(page, true);
                }
            });

            notificationListContainer.addView(item);
        }
    }

    private void refreshNotificationBadge() {
        if (tvNotificationBadge == null) return;
        int unread = NotificationStore.getInstance(this).getUnreadCount();
        if (unread > 0) {
            tvNotificationBadge.setVisibility(View.VISIBLE);
            tvNotificationBadge.setText(unread > MAX_BADGE_COUNT ? "99+" : String.valueOf(unread));
        } else {
            tvNotificationBadge.setVisibility(View.GONE);
        }
    }

    // ── Active profile ────────────────────────────────────────────────────────

    /** Binds the active-profile avatar view and registers a prefs change listener. */
    private void setupActiveProfile() {
        ivActiveProfile = findViewById(R.id.iv_active_profile);
        if (ivActiveProfile == null) return;
        ivActiveProfile.setOnClickListener(v -> showActiveProfileSelection());

        // Listen for active-person changes triggered from CalendarFragment
        SharedPreferences calPrefs = getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE);
        activeProfileListener = (prefs, key) -> {
            if (PREF_ACTIVE_PERSON_ID.equals(key)) {
                refreshActiveProfileAvatar();
            }
        };
        calPrefs.registerOnSharedPreferenceChangeListener(activeProfileListener);

        refreshActiveProfileAvatar();
    }

    /** Reloads the active person from SharedPreferences and updates the avatar view. */
    private void refreshActiveProfileAvatar() {
        if (ivActiveProfile == null) return;
        long activeId = getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE)
                .getLong(PREF_ACTIVE_PERSON_ID, -1L);
        if (activeId < 0) {
            // No active profile – show a generic person silhouette
            ivActiveProfile.setImageResource(R.drawable.ic_active_profile_empty);
            ivActiveProfile.setBackgroundResource(0);
            return;
        }
        CalendarDatabaseHelper db = new CalendarDatabaseHelper(this);
        try {
            List<Person> persons = db.getPersons();
            Person active = null;
            for (Person p : persons) {
                if (p.getId() == activeId) {
                    active = p;
                    break;
                }
            }
            if (active == null) {
                // Person was deleted – clear stale pref
                getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE)
                        .edit().remove(PREF_ACTIVE_PERSON_ID).apply();
                ivActiveProfile.setImageResource(R.drawable.ic_active_profile_empty);
                ivActiveProfile.setBackgroundResource(0);
                return;
            }
            int avatarSizeDp = (int) (getResources().getDimension(R.dimen.active_profile_avatar_size)
                    / getResources().getDisplayMetrics().density);
            Bitmap avatar = PersonAvatarHelper.createAvatarBitmap(this, active, avatarSizeDp);
            ivActiveProfile.setImageBitmap(avatar);
            ivActiveProfile.setBackgroundResource(0);
        } finally {
            db.close();
        }
    }

    /** Shows a dialog to pick an active profile from all known persons. */
    private void showActiveProfileSelection() {
        pauseAutoAdvance();
        CalendarDatabaseHelper db = new CalendarDatabaseHelper(this);
        List<Person> persons;
        try {
            persons = db.getPersons();
        } catch (Exception e) {
            persons = new ArrayList<>();
        } finally {
            db.close();
        }
        final List<Person> finalPersons = persons;
        SharedPreferences calPrefs = getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE);
        final long currentActiveId = calPrefs.getLong(PREF_ACTIVE_PERSON_ID, -1L);

        // Build display names (mark currently active one)
        String[] names = new String[finalPersons.size() + 1];
        names[0] = getString(R.string.active_profile_none);
        for (int i = 0; i < finalPersons.size(); i++) {
            Person p = finalPersons.get(i);
            names[i + 1] = p.getId() == currentActiveId
                    ? getString(R.string.active_profile_active) + " " + p.getName()
                    : p.getName();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.active_profile_select_title)
                .setItems(names, (dialog, which) -> {
                    SharedPreferences.Editor editor = calPrefs.edit();
                    if (which == 0) {
                        editor.remove(PREF_ACTIVE_PERSON_ID);
                    } else {
                        editor.putLong(PREF_ACTIVE_PERSON_ID, finalPersons.get(which - 1).getId());
                    }
                    editor.apply();
                    refreshActiveProfileAvatar();
                    resumeAutoAdvance();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> resumeAutoAdvance())
                .setOnCancelListener(d -> resumeAutoAdvance())
                .show();
    }

    /** Returns a human-readable relative time string for the notification panel. */
    private String formatRelativeTime(long timestampMs) {
        long diffMs = System.currentTimeMillis() - timestampMs;
        long minutes = diffMs / 60_000;
        if (minutes < 1)   return getString(R.string.notification_just_now);
        if (minutes < 60)  return getString(R.string.notification_minutes_ago, (int) minutes);
        long hours = minutes / 60;
        if (hours < 24)    return getString(R.string.notification_hours_ago, (int) hours);
        return getString(R.string.notification_days_ago, (int) (hours / 24));
    }

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

    // ── Wellness check ────────────────────────────────────────────────────────

    private void maybeShowWellnessCheck() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_WELLNESS_ENABLED, true)) return;

        java.util.Calendar cal = java.util.Calendar.getInstance();
        String today = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH));
        if (today.equals(prefs.getString(PREF_WELLNESS_LAST_DATE, ""))) return;

        // Check if we have a pending trigger from the alarm or current time >= check time
        boolean pending   = prefs.getBoolean(PREF_WELLNESS_PENDING, false);
        int checkHour     = prefs.getInt(PREF_WELLNESS_HOUR,   WellnessCheckScheduler.DEFAULT_HOUR);
        int checkMinute   = prefs.getInt(PREF_WELLNESS_MINUTE, WellnessCheckScheduler.DEFAULT_MINUTE);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int nowHour   = now.get(java.util.Calendar.HOUR_OF_DAY);
        int nowMinute = now.get(java.util.Calendar.MINUTE);
        boolean timeReached = nowHour > checkHour
                || (nowHour == checkHour && nowMinute >= checkMinute);

        if (!pending && !timeReached) return;

        // Clear pending flag
        prefs.edit().putBoolean(PREF_WELLNESS_PENDING, false).apply();

        CalendarDatabaseHelper db = new CalendarDatabaseHelper(this);
        List<Person> persons = db.getPersons();

        if (persons.isEmpty()) {
            Toast.makeText(this, R.string.wellness_no_persons, Toast.LENGTH_LONG).show();
            // Mark as done so we don't keep showing the toast
            prefs.edit().putString(PREF_WELLNESS_LAST_DATE, today).apply();
            return;
        }

        // Mark as shown for today before displaying so the dialog does not re-appear
        // on the next onResume (e.g. after the user closes it via X or back button).
        prefs.edit().putString(PREF_WELLNESS_LAST_DATE, today).apply();

        WellnessCheckDialog dialog = new WellnessCheckDialog(this, persons, db, today);
        dialog.show();
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
        NotificationStore.getInstance(this).addObserver(notificationObserver);
        refreshNotificationBadge();
        refreshActiveProfileAvatar();

        // Register receiver for direct wellness check trigger (e.g. from alarm)
        wellnessCheckReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                maybeShowWellnessCheck();
            }
        };
        IntentFilter wellnessFilter = new IntentFilter(
                com.kitchenboard.wellness.WellnessCheckReceiver.ACTION_SHOW_DIALOG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wellnessCheckReceiver, wellnessFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wellnessCheckReceiver, wellnessFilter);
        }
        maybeShowWellnessCheck();
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoAdvanceHandler.removeCallbacks(autoAdvanceRunnable);
        NotificationStore.getInstance(this).removeObserver(notificationObserver);
        if (wellnessCheckReceiver != null) {
            try {
                unregisterReceiver(wellnessCheckReceiver);
            } catch (Exception ignored) { }
            wellnessCheckReceiver = null;
        }
    }

    // ── Update checker ────────────────────────────────────────────────────────

    private void checkForUpdates() {
        com.kitchenboard.update.UpdateLogger.logInfo(this,
                "checkForUpdates: starting check (versionCode=" + BuildConfig.VERSION_CODE + ")");
        Toast.makeText(this, R.string.auto_update_checking_text, Toast.LENGTH_SHORT).show();

        // Backend is the primary update source; GitHub is checked as the fallback.
        int currentBuildNr = BuildConfig.VERSION_CODE;
        int currentSubNr   = com.kitchenboard.update.BackendUpdateChecker.getCurrentSubNumber(this);

        com.kitchenboard.update.BackendUpdateChecker.checkForUpdate(this, currentBuildNr,
                currentSubNr, new com.kitchenboard.update.BackendUpdateChecker.BackendUpdateCallback() {
            @Override
            public void onUpdateAvailable(
                    com.kitchenboard.update.BackendUpdateChecker.BackendUpdateResult result) {
                if (isFinishing() || isActivityDestroyed()) return;
                com.kitchenboard.update.UpdateLogger.logInfo(MainActivity.this,
                        "checkForUpdates: backend update available tag=" + result.tagName);
                try {
                    if (result.downloadUrl != null && result.downloadUrl.endsWith(".apk")) {
                        downloadAndInstallApk(result.downloadUrl, result.tagName,
                                result.subNumber);
                    } else if (result.downloadUrl != null && !result.downloadUrl.isEmpty()) {
                        // Non-APK link: show dialog so the user can open it
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.update_available_title)
                                .setMessage(getString(
                                        R.string.auto_update_backend_available_text,
                                        result.tagName))
                                .setPositiveButton(R.string.update_download, (d, w) -> {
                                    try {
                                        startActivity(new Intent(Intent.ACTION_VIEW,
                                                Uri.parse(result.downloadUrl)));
                                    } catch (Exception e) {
                                        com.kitchenboard.update.UpdateLogger.logError(
                                                MainActivity.this,
                                                "Failed to open backend update URL", e);
                                    }
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                } catch (Exception e) {
                    com.kitchenboard.update.UpdateLogger.logError(MainActivity.this,
                            "Error handling backend update result for " + result.tagName, e);
                }
            }

            @Override
            public void onNoUpdate() {
                if (isFinishing() || isActivityDestroyed()) return;
                com.kitchenboard.update.UpdateLogger.logInfo(MainActivity.this,
                        "checkForUpdates: no backend update – checking GitHub");
                // Backend healthy but no update; check GitHub for a newer release.
                checkGitHubForUpdate(true);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isActivityDestroyed()) return;
                Log.e(TAG, "Backend update check failed (falling back to GitHub): "
                        + (message != null ? message : "unknown error"));
                // Backend unavailable – always fall back to GitHub.
                checkGitHubForUpdate(false);
            }
        });
    }

    /**
     * Checks GitHub for updates as a fallback when the backend has no update or is unavailable.
     *
     * @param showUpToDateOnNoUpdate when {@code true} a "up-to-date" toast is shown when GitHub
     *                               also has no newer version (i.e. backend was healthy but empty)
     */
    private void checkGitHubForUpdate(final boolean showUpToDateOnNoUpdate) {
        if (isFinishing() || isActivityDestroyed()) return;
        UpdateChecker.checkForUpdateWithFlag(this, BuildConfig.VERSION_CODE,
                new UpdateChecker.UpdateResultCallback() {
            @Override
            public void onUpdateAvailable(final UpdateChecker.UpdateResult result) {
                if (isFinishing() || isActivityDestroyed()) return;
                com.kitchenboard.update.UpdateLogger.logInfo(MainActivity.this,
                        "checkForUpdates: GitHub update available tag=" + result.tagName
                                + " autoUpdate=" + result.isAutoUpdate
                                + " url=" + result.downloadUrl);
                try {
                    if (result.isAutoUpdate) {
                        // Trigger the download immediately instead of waiting for the background
                        // scheduler, so updates are applied as soon as the app is opened.
                        if (result.downloadUrl != null && result.downloadUrl.endsWith(".apk")) {
                            downloadAndInstallApk(result.downloadUrl, result.tagName, 0);
                        } else if (result.downloadUrl != null && !result.downloadUrl.isEmpty()) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(result.downloadUrl)));
                            } catch (android.content.ActivityNotFoundException e) {
                                com.kitchenboard.update.UpdateLogger.logError(MainActivity.this,
                                        "No browser available to open update URL", e);
                            }
                        }
                        return;
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.update_available_title)
                            .setMessage(getString(R.string.update_available_message, result.tagName))
                            .setPositiveButton(R.string.update_download, (dialog, which) -> {
                                try {
                                    if (result.downloadUrl != null && result.downloadUrl.endsWith(".apk")) {
                                        downloadAndInstallApk(result.downloadUrl, result.tagName, 0);
                                    } else if (result.downloadUrl != null) {
                                        startActivity(new Intent(Intent.ACTION_VIEW,
                                                Uri.parse(result.downloadUrl)));
                                    } else {
                                        com.kitchenboard.update.UpdateLogger.logError(MainActivity.this,
                                                "Download URL is null for " + result.tagName);
                                        Toast.makeText(MainActivity.this,
                                                R.string.update_check_error, Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    com.kitchenboard.update.UpdateLogger.logError(MainActivity.this,
                                            "Failed to open update URL", e);
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                } catch (Exception e) {
                    com.kitchenboard.update.UpdateLogger.logError(MainActivity.this,
                            "Error handling update result for " + result.tagName, e);
                    if (!isFinishing() && !isActivityDestroyed()) {
                        Toast.makeText(MainActivity.this,
                                R.string.update_install_error,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onNoUpdate() {
                if (isFinishing() || isActivityDestroyed()) return;
                if (showUpToDateOnNoUpdate) {
                    com.kitchenboard.update.UpdateLogger.logInfo(MainActivity.this,
                            "checkForUpdates: app is up to date (backend + GitHub)");
                    Toast.makeText(MainActivity.this,
                            R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isActivityDestroyed()) return;
                Log.e(TAG, "GitHub update check error: "
                        + (message != null ? message : "unknown error"));
                Toast.makeText(MainActivity.this,
                        R.string.update_check_error, Toast.LENGTH_SHORT).show();
            }
        });
    }


    private void downloadAndInstallApk(String url, String tagName, int subNumber) {
        com.kitchenboard.update.UpdateLogger.logInfo(this,
                "downloadAndInstallApk: starting download for " + tagName + " from " + url);
        pendingSubNumber = subNumber;
        File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            // External storage unavailable; fall back to browser
            com.kitchenboard.update.UpdateLogger.logError(this,
                    "downloadAndInstallApk: external storage unavailable for " + tagName);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (android.content.ActivityNotFoundException e) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "No browser available to open update URL (no external storage)", e);
            }
            return;
        }

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            com.kitchenboard.update.UpdateLogger.logError(this,
                    "Cannot download APK: DownloadManager service unavailable for " + tagName);
            // Fall back to browser
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (android.content.ActivityNotFoundException e) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "No browser available to open update URL (no DownloadManager)", e);
            }
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

        downloadId = dm.enqueue(request);
        com.kitchenboard.update.UpdateLogger.logInfo(this,
                "downloadAndInstallApk: enqueued download id=" + downloadId + " for " + tagName);

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        try {
                            unregisterReceiver(downloadReceiver);
                        } catch (IllegalArgumentException ignored) {
                            // Receiver was already unregistered (e.g. activity restarted)
                        }
                        downloadReceiver = null;
                        com.kitchenboard.update.UpdateLogger.logInfo(MainActivity.this,
                                "Download complete (id=" + id + "), launching installer");
                        // Commit the sub-number so future version comparisons are correct.
                        com.kitchenboard.update.BackendUpdateChecker.saveCurrentSubNumber(
                                MainActivity.this, pendingSubNumber);
                        installApk(apkFile);
                    }
                } catch (Exception e) {
                    com.kitchenboard.update.UpdateLogger.logError(MainActivity.this,
                            "Error in download completion receiver", e);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void installApk(File apkFile) {
        if (!apkFile.exists()) {
            com.kitchenboard.update.UpdateLogger.logError(this,
                    "installApk: APK file not found at " + apkFile.getAbsolutePath());
            return;
        }

        // On Android 8+ the user must explicitly allow this app to install APKs.
        // If permission is not yet granted, send the user to the settings page and remember
        // the APK path so we can retry after they return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            com.kitchenboard.update.UpdateLogger.logInfo(this,
                    "installApk: REQUEST_INSTALL_PACKAGES not granted – opening settings");
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_INSTALL_APK_PATH, apkFile.getAbsolutePath())
                    .apply();
            Toast.makeText(this, R.string.install_permission_needed, Toast.LENGTH_LONG).show();
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(settingsIntent, REQUEST_UNKNOWN_APP_SOURCES);
            } catch (Exception e) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "Failed to open install-permission settings", e);
            }
            return;
        }

        try {
            com.kitchenboard.update.UpdateLogger.logInfo(this,
                    "Launching APK installer for " + apkFile.getAbsolutePath());
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
            // Do NOT add FLAG_ACTIVITY_NEW_TASK here so that startActivityForResult works
            // and we receive the installation result in onActivityResult.
            startActivityForResult(intent, REQUEST_INSTALL_APK);
        } catch (Exception e) {
            com.kitchenboard.update.UpdateLogger.logError(this,
                    "Failed to launch APK installer for " + apkFile.getAbsolutePath(), e);
            if (!isFinishing() && !isActivityDestroyed()) {
                Toast.makeText(this, R.string.update_install_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_APK) {
            // RESULT_OK means the package was installed successfully (the new version will have
            // already started; this branch is only reached in edge cases).
            if (resultCode != RESULT_OK && !isFinishing() && !isActivityDestroyed()) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "APK install returned non-OK result: " + resultCode);
                showInstallFailedDialog();
            }
        } else if (requestCode == REQUEST_UNKNOWN_APP_SOURCES
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // User returned from the "Install unknown apps" settings page.
            String apkPath = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_INSTALL_APK_PATH, null);
            if (apkPath != null) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    com.kitchenboard.update.UpdateLogger.logInfo(this,
                            "Install permission granted – retrying APK install");
                    installApk(new File(apkPath));
                } else {
                    Toast.makeText(this, R.string.install_permission_needed,
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * Shows a dialog explaining that the APK installation failed, likely due to a signing-key
     * mismatch with a previously installed version.  Offers an "Uninstall" action so the user
     * can remove the old version and then install the downloaded APK fresh.
     */
    private void showInstallFailedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_install_failed_title)
                .setMessage(R.string.update_install_failed_message)
                .setPositiveButton(R.string.update_uninstall_button, (d, w) -> {
                    try {
                        com.kitchenboard.update.UpdateLogger.logInfo(this,
                                "User requested uninstall to resolve signature conflict");
                        Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                        uninstallIntent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(uninstallIntent);
                    } catch (Exception e) {
                        com.kitchenboard.update.UpdateLogger.logError(this,
                                "Failed to launch uninstall", e);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Returns true if the activity has been destroyed (API 17+), false on older devices. */
    private boolean isActivityDestroyed() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed();
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
        if (activeProfileListener != null) {
            getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(activeProfileListener);
            activeProfileListener = null;
        }
    }
}

