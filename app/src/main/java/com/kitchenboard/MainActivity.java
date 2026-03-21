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
import com.kitchenboard.chat.ChatCheckScheduler;
import com.kitchenboard.chat.ChatCheckReceiver;
import com.kitchenboard.chat.ChatDatabaseHelper;
import com.kitchenboard.chat.ChatMessage;
import com.kitchenboard.chat.ChatApiClient;
import com.kitchenboard.chat.LanChatClient;
import com.kitchenboard.chat.LanChatServer;
import com.kitchenboard.chat.LanDiscoveryManager;
import com.kitchenboard.chat.LanPeer;
import com.kitchenboard.powersaving.PowerSavingManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        refreshChatBadge();
        if (notificationPanelOverlay != null
                && notificationPanelOverlay.getVisibility() == View.VISIBLE) {
            populateNotificationList();
        }
    };

    // ── Chat panel ────────────────────────────────────────────────────────────
    private View chatPanelOverlay;
    private LinearLayout chatMessageContainer;
    private TextView tvChatBadge;
    private TextView tvChatEmpty;
    private EditText etChatInput;
    private TextView tvChatRecipientSelected;
    private static final ExecutorService CHAT_EXECUTOR = Executors.newSingleThreadExecutor();
    /** Fraction of screen height used as the max height of the chat message list. */
    private static final float CHAT_PANEL_HEIGHT_RATIO = 0.40f;
    /** Currently selected chat recipient, or {@code null} for broadcast. */
    private LanPeer selectedLanRecipient;
    /** Active person chosen as recipient (from persons DB), or {@code null} for broadcast. */
    private Person selectedPersonRecipient;
    /** LAN discovery / messaging helpers (null when LAN mode is off). */
    private LanDiscoveryManager lanDiscovery;

    // ── Power saving ──────────────────────────────────────────────────────────
    private PowerSavingManager powerSavingManager;

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

        // Schedule periodic chat message polling only when not in LAN mode.
        // In LAN mode, messages arrive directly via LanChatServer (TCP) – no backend polling needed.
        SharedPreferences chatPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!chatPrefs.getBoolean(ChatCheckReceiver.PREF_CHAT_LAN_MODE, false)) {
            ChatCheckScheduler.schedule(this);
        }
        setupChatPanel();

        // Initialize power saving manager (dark schedule + low battery dimming)
        powerSavingManager = new PowerSavingManager(this);
        powerSavingManager.init(getWindow());

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

        // ── Chat-Einstellungen ────────────────────────────────────────────────
        final TextView tvChatSection = new TextView(this);
        tvChatSection.setText(R.string.chat_settings_section);
        tvChatSection.setTextSize(12f);
        tvChatSection.setPadding(0, padPx, 0, padPx / 4);

        final CheckBox cbChatEnabled = new CheckBox(this);
        cbChatEnabled.setText(R.string.chat_settings_enabled);
        cbChatEnabled.setChecked(prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_ENABLED, false));

        final CheckBox cbChatTokenFilter = new CheckBox(this);
        cbChatTokenFilter.setText(R.string.chat_settings_token_filter);
        cbChatTokenFilter.setChecked(prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_TOKEN_FILTER, false));

        final CheckBox cbChatLanMode = new CheckBox(this);
        cbChatLanMode.setText(R.string.chat_settings_lan_mode);
        cbChatLanMode.setChecked(prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_LAN_MODE, false));

        layout.addView(tvChatSection);
        layout.addView(cbChatEnabled);
        layout.addView(cbChatTokenFilter);
        layout.addView(cbChatLanMode);

        // ── Stromspar-Einstellungen ───────────────────────────────────────────
        final TextView tvPowerSection = new TextView(this);
        tvPowerSection.setText(R.string.power_saving_section);
        tvPowerSection.setTextSize(12f);
        tvPowerSection.setPadding(0, padPx, 0, padPx / 4);

        final CheckBox cbLowBatteryDim = new CheckBox(this);
        cbLowBatteryDim.setText(R.string.power_saving_low_battery_dim);
        cbLowBatteryDim.setChecked(prefs.getBoolean(PowerSavingManager.PREF_LOW_BATTERY_DIM, true));

        final CheckBox cbDarkSchedule = new CheckBox(this);
        cbDarkSchedule.setText(R.string.power_saving_dark_schedule_enabled);
        cbDarkSchedule.setChecked(prefs.getBoolean(PowerSavingManager.PREF_DARK_SCHEDULE_ENABLED, true));

        // winTimes[i] = { startH, startM, endH, endM } for active window i+1
        final int[][] winTimes = new int[PowerSavingManager.WINDOW_COUNT][4];
        final android.widget.Button[] winButtons = new android.widget.Button[PowerSavingManager.WINDOW_COUNT];

        for (int wi = 0; wi < PowerSavingManager.WINDOW_COUNT; wi++) {
            final int idx = wi;
            int sh = prefs.getInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_START_HOUR,   wi + 1), PowerSavingManager.DEFAULT_WIN_START_HOUR[wi]);
            int sm = prefs.getInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_START_MINUTE, wi + 1), PowerSavingManager.DEFAULT_WIN_START_MINUTE[wi]);
            int eh = prefs.getInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_END_HOUR,     wi + 1), PowerSavingManager.DEFAULT_WIN_END_HOUR[wi]);
            int em = prefs.getInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_END_MINUTE,   wi + 1), PowerSavingManager.DEFAULT_WIN_END_MINUTE[wi]);
            winTimes[wi][0] = sh; winTimes[wi][1] = sm;
            winTimes[wi][2] = eh; winTimes[wi][3] = em;

            android.widget.Button btn = new android.widget.Button(this);
            btn.setText(getString(R.string.power_saving_win_label, wi + 1, sh, sm, eh, em));
            btn.setOnClickListener(v -> {
                // First pick start time, then end time
                new TimePickerDialog(this, (tp, h, m) -> {
                    winTimes[idx][0] = h;
                    winTimes[idx][1] = m;
                    new TimePickerDialog(this, (tp2, h2, m2) -> {
                        winTimes[idx][2] = h2;
                        winTimes[idx][3] = m2;
                        winButtons[idx].setText(getString(R.string.power_saving_win_label,
                                idx + 1, winTimes[idx][0], winTimes[idx][1],
                                winTimes[idx][2], winTimes[idx][3]));
                    }, winTimes[idx][2], winTimes[idx][3], true).show();
                    winButtons[idx].setText(getString(R.string.power_saving_win_label,
                            idx + 1, h, m, winTimes[idx][2], winTimes[idx][3]));
                }, winTimes[idx][0], winTimes[idx][1], true).show();
            });
            winButtons[wi] = btn;
        }

        layout.addView(tvPowerSection);
        layout.addView(cbLowBatteryDim);
        layout.addView(cbDarkSchedule);
        for (android.widget.Button b : winButtons) layout.addView(b);

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
                // Chat settings
                boolean newLanMode = cbChatLanMode.isChecked();
                editor.putBoolean(ChatCheckReceiver.PREF_CHAT_ENABLED,      cbChatEnabled.isChecked());
                editor.putBoolean(ChatCheckReceiver.PREF_CHAT_TOKEN_FILTER,  cbChatTokenFilter.isChecked());
                editor.putBoolean(ChatCheckReceiver.PREF_CHAT_LAN_MODE,                       newLanMode);
                // Power saving settings
                editor.putBoolean(PowerSavingManager.PREF_LOW_BATTERY_DIM,       cbLowBatteryDim.isChecked());
                editor.putBoolean(PowerSavingManager.PREF_DARK_SCHEDULE_ENABLED, cbDarkSchedule.isChecked());
                for (int wi = 0; wi < PowerSavingManager.WINDOW_COUNT; wi++) {
                    editor.putInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_START_HOUR,   wi + 1), winTimes[wi][0]);
                    editor.putInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_START_MINUTE, wi + 1), winTimes[wi][1]);
                    editor.putInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_END_HOUR,     wi + 1), winTimes[wi][2]);
                    editor.putInt(String.format(Locale.US, PowerSavingManager.PREF_WIN_END_MINUTE,   wi + 1), winTimes[wi][3]);
                }
                editor.apply();
                WellnessCheckScheduler.schedule(MainActivity.this);
                if (powerSavingManager != null) powerSavingManager.applySettings();
                // Start or stop LAN mode depending on the new setting
                stopLanMode();
                if (newLanMode) {
                    // LAN mode: cancel backend polling – messages arrive via LanChatServer
                    ChatCheckScheduler.cancel(MainActivity.this);
                    initLanModeIfEnabled();
                } else {
                    // Backend mode: ensure the polling alarm is active
                    ChatCheckScheduler.schedule(MainActivity.this);
                }
                refreshChatBadge();
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
                } else if (n.type == AppNotification.TYPE_CHAT) {
                    ivIcon.setImageResource(R.drawable.ic_chat);
                    ivIcon.setContentDescription(getString(R.string.chat_title));
                    DrawableCompat.setTint(
                            DrawableCompat.wrap(ivIcon.getDrawable()).mutate(),
                            ContextCompat.getColor(this, R.color.accent));
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
            final int notifType = n.type;
            item.setOnClickListener(v -> {
                closeNotificationPanel();
                if (notifType == AppNotification.TYPE_CHAT) {
                    // Open the chat panel instead of navigating to a page
                    openChatPanel();
                } else if (page >= 0 && viewPager != null && pagerAdapter != null
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

    // ── Chat panel ────────────────────────────────────────────────────────────

    /** Binds the chat panel views and wires up button listeners. */
    private void setupChatPanel() {
        chatPanelOverlay         = findViewById(R.id.chat_panel_overlay);
        chatMessageContainer     = findViewById(R.id.chat_message_container);
        tvChatBadge              = findViewById(R.id.tv_chat_badge);
        tvChatEmpty              = findViewById(R.id.tv_chat_empty);
        etChatInput              = findViewById(R.id.et_chat_input);
        tvChatRecipientSelected  = findViewById(R.id.tv_chat_recipient_selected);

        // Constrain the message list scroll area to ~40% screen height
        android.widget.ScrollView chatScroll = findViewById(R.id.chat_scroll_view);
        if (chatScroll != null) {
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels
                    * CHAT_PANEL_HEIGHT_RATIO);
            android.view.ViewGroup.LayoutParams lp = chatScroll.getLayoutParams();
            lp.height = maxH;
            chatScroll.setLayoutParams(lp);
        }

        ImageButton btnChat = findViewById(R.id.btn_chat);
        if (btnChat != null) {
            btnChat.setOnClickListener(v -> openChatPanel());
        }

        View btnCloseChat = findViewById(R.id.btn_close_chat_panel);
        if (btnCloseChat != null) {
            btnCloseChat.setOnClickListener(v -> closeChatPanel());
        }

        View btnSend = findViewById(R.id.btn_chat_send);
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> sendChatMessage());
        }

        // Recipient selector tap opens a picker
        if (tvChatRecipientSelected != null) {
            tvChatRecipientSelected.setOnClickListener(v -> showRecipientPicker());
        }

        // Backdrop tap closes the panel
        if (chatPanelOverlay != null) {
            chatPanelOverlay.setOnClickListener(v -> closeChatPanel());
            View panel = chatPanelOverlay.findViewById(R.id.chat_panel);
            if (panel != null) panel.setOnClickListener(v -> { /* consume */ });
        }

        // Start LAN components if LAN mode is enabled
        initLanModeIfEnabled();

        refreshChatBadge();
    }

    /** Starts LAN chat server and discovery if the LAN mode preference is active. */
    private void initLanModeIfEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_LAN_MODE, false)) return;

        String myDeviceId   = "device_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        String myDeviceName = resolveActiveSenderName();

        LanChatServer server = LanChatServer.getInstance();
        server.setMessageReceivedListener(msg -> runOnUiThread(() -> {
            if (chatPanelOverlay != null
                    && chatPanelOverlay.getVisibility() == View.VISIBLE) {
                populateChatPanel();
            }
            refreshChatBadge();
        }));
        server.start(this, myDeviceId);

        lanDiscovery = new LanDiscoveryManager();
        lanDiscovery.setPeerListListener(peers -> {
            // Peer list changed – no immediate UI action needed; picker reads on demand
        });
        CHAT_EXECUTOR.submit(() -> lanDiscovery.start(myDeviceId, myDeviceName));
    }

    /** Stops LAN chat server and discovery. */
    private void stopLanMode() {
        LanChatServer.getInstance().stop();
        if (lanDiscovery != null) {
            lanDiscovery.stop();
            lanDiscovery = null;
        }
    }

    private void openChatPanel() {
        if (chatPanelOverlay == null) return;
        populateChatPanel();
        chatPanelOverlay.setVisibility(View.VISIBLE);
        chatPanelOverlay.setAlpha(0f);
        chatPanelOverlay.animate().alpha(1f).setDuration(180).start();
        pauseAutoAdvance();

        // Mark all messages as read when panel is opened
        CHAT_EXECUTOR.execute(() -> {
            ChatDatabaseHelper db = new ChatDatabaseHelper(this);
            try { db.markAllRead(); } finally { db.close(); }
            runOnUiThread(this::refreshChatBadge);
        });
    }

    private void closeChatPanel() {
        if (chatPanelOverlay == null) return;
        chatPanelOverlay.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    chatPanelOverlay.setVisibility(View.GONE);
                    chatPanelOverlay.setAlpha(1f);
                })
                .start();
        resumeAutoAdvance();
    }

    /** Loads recent messages from the local DB and renders them in the panel. */
    private void populateChatPanel() {
        if (chatMessageContainer == null) return;
        chatMessageContainer.removeAllViews();

        String myDeviceId = "device_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        ChatDatabaseHelper db = new ChatDatabaseHelper(this);
        List<ChatMessage> messages;
        try {
            messages = db.getMessages(100, myDeviceId);
        } finally {
            db.close();
        }

        if (tvChatEmpty != null) {
            tvChatEmpty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final ChatMessage msg : messages) {
            View item = inflater.inflate(R.layout.item_chat_message, chatMessageContainer, false);
            TextView tvSender    = item.findViewById(R.id.tv_chat_sender);
            TextView tvText      = item.findViewById(R.id.tv_chat_message);
            TextView tvTime      = item.findViewById(R.id.tv_chat_time);
            TextView tvRecipient = item.findViewById(R.id.tv_chat_recipient);
            if (tvSender != null) tvSender.setText(msg.senderName);
            if (tvText   != null) tvText.setText(msg.message);
            if (tvTime   != null) tvTime.setText(formatRelativeTime(msg.timestampMs));
            if (tvRecipient != null) {
                if (!msg.recipientName.isEmpty()) {
                    tvRecipient.setText(getString(R.string.chat_to_label, msg.recipientName));
                    tvRecipient.setVisibility(View.VISIBLE);
                } else {
                    tvRecipient.setVisibility(View.GONE);
                }
            }

            // Long-press to copy message to clipboard
            item.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("ChatMessage", msg.message));
                    Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            chatMessageContainer.addView(item);
        }

        // Scroll to bottom (newest message)
        android.widget.ScrollView sv = findViewById(R.id.chat_scroll_view);
        if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    /** Reads the input field and sends the message via the backend API or LAN. */
    private void sendChatMessage() {
        if (etChatInput == null) return;
        String text = etChatInput.getText().toString().trim();
        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean lanMode = prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_LAN_MODE, false);

        // Derive sender identity from active profile
        String senderId   = "device_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        String senderName = resolveActiveSenderName();

        // Determine recipient
        String recipientId   = "";
        String recipientName = "";
        if (lanMode && selectedLanRecipient != null) {
            recipientId   = selectedLanRecipient.deviceId;
            recipientName = selectedLanRecipient.deviceName;
        } else if (!lanMode && selectedPersonRecipient != null) {
            // For server mode, use person name as recipient identifier
            recipientId   = "person_" + selectedPersonRecipient.getId();
            recipientName = selectedPersonRecipient.getName();
        }

        etChatInput.setText("");

        final String finalText       = text;
        final String fSenderId       = senderId;
        final String fSenderName     = senderName;
        final String fRecipientId    = recipientId;
        final String fRecipientName  = recipientName;

        if (lanMode) {
            sendChatMessageLan(fSenderId, fSenderName, fRecipientId, fRecipientName, finalText);
        } else {
            String serverUrl = prefs.getString(PREF_SERVER_URL, "").trim();
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, R.string.chat_send_error, Toast.LENGTH_SHORT).show();
                return;
            }
            String boardToken    = prefs.getString(PREF_BOARD_TOKEN, "").trim();
            String apiToken      = prefs.getString(PREF_API_TOKEN,   "").trim();
            boolean tokenFilter  = prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_TOKEN_FILTER, false);
            String effectiveToken = tokenFilter ? boardToken : "";
            sendChatMessageApi(fSenderId, fSenderName, fRecipientId, fRecipientName,
                    finalText, serverUrl, effectiveToken, apiToken);
        }
    }

    private void sendChatMessageApi(String senderId, String senderName,
                                    String recipientId, String recipientName,
                                    String text, String serverUrl,
                                    String effectiveToken, String apiToken) {
        CHAT_EXECUTOR.execute(() -> {
            try {
                ChatApiClient client = new ChatApiClient(serverUrl, effectiveToken, apiToken);
                long id = client.sendMessage(senderId, senderName, recipientId, recipientName, text);
                ChatMessage sent = new ChatMessage(id, senderId, senderName,
                        recipientId, recipientName, text, System.currentTimeMillis(), true);
                ChatDatabaseHelper db = new ChatDatabaseHelper(this);
                try { db.upsert(sent); } finally { db.close(); }
                runOnUiThread(() -> {
                    if (chatPanelOverlay != null
                            && chatPanelOverlay.getVisibility() == View.VISIBLE) {
                        populateChatPanel();
                    }
                });
            } catch (Exception e) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "ChatSend: failed to send message via API", e);
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.chat_send_error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void sendChatMessageLan(String senderId, String senderName,
                                    String recipientId, String recipientName,
                                    String text) {
        if (lanDiscovery == null) {
            Toast.makeText(this, R.string.chat_send_error, Toast.LENGTH_SHORT).show();
            return;
        }
        // Use negative IDs for locally generated LAN messages to avoid server ID collision
        long localId = -(System.currentTimeMillis());
        ChatMessage msg = new ChatMessage(localId, senderId, senderName,
                recipientId, recipientName, text, System.currentTimeMillis(), true);

        // Store locally first so the UI updates immediately
        ChatDatabaseHelper db = new ChatDatabaseHelper(this);
        try { db.upsert(msg); } finally { db.close(); }
        runOnUiThread(() -> {
            if (chatPanelOverlay != null
                    && chatPanelOverlay.getVisibility() == View.VISIBLE) {
                populateChatPanel();
            }
        });

        CHAT_EXECUTOR.execute(() -> {
            List<LanPeer> peers = lanDiscovery.getPeers();
            if (peers.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.chat_lan_no_peers, Toast.LENGTH_SHORT).show());
                return;
            }
            if (!recipientId.isEmpty()) {
                // Directed message: send only to matching peer
                for (LanPeer p : peers) {
                    if (recipientId.equals(p.deviceId)) {
                        try {
                            LanChatClient.sendToPeer(p.ip, msg);
                        } catch (Exception e) {
                            com.kitchenboard.update.UpdateLogger.logError(this,
                                    "LanChatSend: failed to reach " + p.deviceName, e);
                            runOnUiThread(() -> Toast.makeText(this,
                                    R.string.chat_send_error, Toast.LENGTH_SHORT).show());
                        }
                        break;
                    }
                }
            } else {
                LanChatClient.broadcast(peers, msg);
            }
        });
    }

    /**
     * Shows a dialog to pick the chat recipient (broadcast or a specific person/LAN peer).
     */
    private void showRecipientPicker() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean lanMode = prefs.getBoolean(ChatCheckReceiver.PREF_CHAT_LAN_MODE, false);

        java.util.List<String>  names = new ArrayList<>();
        java.util.List<Runnable> actions = new ArrayList<>();

        // First entry: broadcast / all
        names.add(getString(R.string.chat_recipient_all));
        actions.add(() -> {
            selectedLanRecipient    = null;
            selectedPersonRecipient = null;
            if (tvChatRecipientSelected != null) {
                tvChatRecipientSelected.setText(R.string.chat_recipient_all);
            }
        });

        if (lanMode && lanDiscovery != null) {
            for (LanPeer peer : lanDiscovery.getPeers()) {
                final LanPeer p = peer;
                names.add(peer.deviceName);
                actions.add(() -> {
                    selectedLanRecipient    = p;
                    selectedPersonRecipient = null;
                    if (tvChatRecipientSelected != null) {
                        tvChatRecipientSelected.setText(p.deviceName);
                    }
                });
            }
        } else {
            CalendarDatabaseHelper calDb = new CalendarDatabaseHelper(this);
            List<Person> persons;
            try { persons = calDb.getPersons(); } finally { calDb.close(); }
            for (Person p : persons) {
                names.add(p.getName());
                actions.add(() -> {
                    selectedPersonRecipient = p;
                    selectedLanRecipient    = null;
                    if (tvChatRecipientSelected != null) {
                        tvChatRecipientSelected.setText(p.getName());
                    }
                });
            }
        }

        String[] nameArr = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_recipient_hint)
                .setItems(nameArr, (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Returns the name of the currently active person, or a device-based fallback. */
    private String resolveActiveSenderName() {
        long activeId = getSharedPreferences(PREFS_CALENDAR, MODE_PRIVATE)
                .getLong(PREF_ACTIVE_PERSON_ID, -1L);
        if (activeId >= 0) {
            com.kitchenboard.calendar.CalendarDatabaseHelper db =
                    new com.kitchenboard.calendar.CalendarDatabaseHelper(this);
            try {
                for (com.kitchenboard.calendar.Person p : db.getPersons()) {
                    if (p.getId() == activeId) return p.getName();
                }
            } finally {
                db.close();
            }
        }
        return getString(R.string.chat_sender_unknown);
    }

    /** Updates the unread-message badge on the chat button. */
    private void refreshChatBadge() {
        if (tvChatBadge == null) return;
        String myDeviceId = "device_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        CHAT_EXECUTOR.execute(() -> {
            ChatDatabaseHelper db = new ChatDatabaseHelper(this);
            int unread;
            try { unread = db.getUnreadCount(myDeviceId); } finally { db.close(); }
            final int u = unread;
            runOnUiThread(() -> {
                if (tvChatBadge == null) return;
                if (u > 0) {
                    tvChatBadge.setVisibility(View.VISIBLE);
                    tvChatBadge.setText(u > MAX_BADGE_COUNT ? "99+" : String.valueOf(u));
                } else {
                    tvChatBadge.setVisibility(View.GONE);
                }
            });
        });
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

        // Both sources are checked in parallel; the one with the highest version wins.
        // All callbacks are delivered on the main thread, so the plain array is race-free.
        final int[] completed      = {0};
        final UpdateChecker.UpdateResult[] githubResult   = {null};
        final com.kitchenboard.update.BackendUpdateChecker.BackendUpdateResult[] backendResult = {null};

        final Runnable onBothDone = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isActivityDestroyed()) return;
                applyBestUpdate(githubResult[0], backendResult[0]);
            }
        };

        // ── Backend check ──
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
                backendResult[0] = result;
                if (++completed[0] == 2) onBothDone.run();
            }

            @Override
            public void onNoUpdate() {
                if (++completed[0] == 2) onBothDone.run();
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Backend update check failed: "
                        + (message != null ? message : "unknown error"));
                if (++completed[0] == 2) onBothDone.run();
            }
        });

        // ── GitHub check ──
        UpdateChecker.checkForUpdateWithFlag(this, BuildConfig.VERSION_CODE,
                new UpdateChecker.UpdateResultCallback() {
            @Override
            public void onUpdateAvailable(final UpdateChecker.UpdateResult result) {
                if (isFinishing() || isActivityDestroyed()) return;
                com.kitchenboard.update.UpdateLogger.logInfo(MainActivity.this,
                        "checkForUpdates: GitHub update available tag=" + result.tagName
                                + " autoUpdate=" + result.isAutoUpdate);
                githubResult[0] = result;
                if (++completed[0] == 2) onBothDone.run();
            }

            @Override
            public void onNoUpdate() {
                if (++completed[0] == 2) onBothDone.run();
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "GitHub update check error: "
                        + (message != null ? message : "unknown error"));
                if (++completed[0] == 2) onBothDone.run();
            }
        });
    }

    /**
     * Called once both update sources have responded. Picks the result with the highest version
     * and offers it to the user. GitHub build number takes precedence; for equal build numbers
     * the backend sub-number breaks the tie.
     */
    private void applyBestUpdate(
            UpdateChecker.UpdateResult github,
            com.kitchenboard.update.BackendUpdateChecker.BackendUpdateResult backend) {
        if (github == null && backend == null) {
            com.kitchenboard.update.UpdateLogger.logInfo(this,
                    "checkForUpdates: app is up to date (backend + GitHub)");
            Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
            return;
        }

        // Determine which source has the higher version.
        boolean preferGitHub;
        if (github == null) {
            preferGitHub = false;
        } else if (backend == null) {
            preferGitHub = true;
        } else {
            // backend is newer than GitHub only if (backendBuild, backendSub) > (githubBuild, 0)
            preferGitHub = !com.kitchenboard.update.BackendUpdateChecker.isNewer(
                    backend.buildNumber, backend.subNumber, github.getBuildNumber(), 0);
        }

        if (preferGitHub) {
            com.kitchenboard.update.UpdateLogger.logInfo(this,
                    "checkForUpdates: applying GitHub update " + github.tagName);
            try {
                if (github.isAutoUpdate) {
                    if (github.downloadUrl != null && github.downloadUrl.endsWith(".apk")) {
                        downloadAndInstallApk(github.downloadUrl, github.tagName, 0);
                    } else if (github.downloadUrl != null && !github.downloadUrl.isEmpty()) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(github.downloadUrl)));
                        } catch (android.content.ActivityNotFoundException e) {
                            com.kitchenboard.update.UpdateLogger.logError(this,
                                    "No browser available to open update URL", e);
                        }
                    }
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.update_available_title)
                            .setMessage(getString(R.string.update_available_message,
                                    github.tagName))
                            .setPositiveButton(R.string.update_download, (dialog, which) -> {
                                if (isFinishing() || isActivityDestroyed()) return;
                                try {
                                    if (github.downloadUrl != null
                                            && github.downloadUrl.endsWith(".apk")) {
                                        downloadAndInstallApk(github.downloadUrl,
                                                github.tagName, 0);
                                    } else if (github.downloadUrl != null) {
                                        startActivity(new Intent(Intent.ACTION_VIEW,
                                                Uri.parse(github.downloadUrl)));
                                    } else {
                                        com.kitchenboard.update.UpdateLogger.logError(this,
                                                "Download URL is null for " + github.tagName);
                                        Toast.makeText(this, R.string.update_check_error,
                                                Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    com.kitchenboard.update.UpdateLogger.logError(this,
                                            "Failed to open update URL", e);
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }
            } catch (Exception e) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "Error handling update result for " + github.tagName, e);
                if (!isFinishing() && !isActivityDestroyed()) {
                    Toast.makeText(this, R.string.update_install_error,
                            Toast.LENGTH_LONG).show();
                }
            }
        } else {
            com.kitchenboard.update.UpdateLogger.logInfo(this,
                    "checkForUpdates: applying backend update " + backend.tagName);
            try {
                if (backend.downloadUrl != null && backend.downloadUrl.endsWith(".apk")) {
                    downloadAndInstallApk(backend.downloadUrl, backend.tagName,
                            backend.subNumber);
                } else if (backend.downloadUrl != null && !backend.downloadUrl.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.update_available_title)
                            .setMessage(getString(
                                    R.string.auto_update_backend_available_text,
                                    backend.tagName))
                            .setPositiveButton(R.string.update_download, (d, w) -> {
                                if (isFinishing() || isActivityDestroyed()) return;
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(backend.downloadUrl)));
                                } catch (Exception e) {
                                    com.kitchenboard.update.UpdateLogger.logError(this,
                                            "Failed to open backend update URL", e);
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }
            } catch (Exception e) {
                com.kitchenboard.update.UpdateLogger.logError(this,
                        "Error handling backend update result for " + backend.tagName, e);
            }
        }
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
        if (powerSavingManager != null) {
            powerSavingManager.destroy();
            powerSavingManager = null;
        }
        stopLanMode();
    }
}

