package com.kitchenboard.immobilien;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kitchenboard.R;
import com.kitchenboard.feedback.FeatureRequestHelper;
import com.kitchenboard.update.UpdateLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 5th page of the ViewPager.
 *
 * <p>Displays a list of configured real-estate search alerts with their last-check
 * time and new/total listing counts.  The user can:
 * <ul>
 *   <li>Add a new alert via the FAB.</li>
 *   <li>View all discovered listings by tapping an alert row.</li>
 *   <li>Edit or delete an alert by tapping the edit button on the row.</li>
 * </ul>
 *
 * <p>Background checks are performed by {@link ImmobilienCheckReceiver} via an
 * {@link android.app.AlarmManager} alarm scheduled in
 * {@link ImmobilienCheckScheduler}.
 */
public class ImmobilienFragment extends Fragment {

    private static final String PREFS_NAME           = "shopping_prefs";
    private static final String PREF_PAGE_IN_ROTATION = "page_%d_in_rotation";
    /** This fragment's page index in the ViewPager. */
    private static final int    PAGE_INDEX            = 4;

    private ImmobilienDatabaseHelper db;
    private ImmobilienAdapter        adapter;
    private List<ImmobilienAlert>    alertList;
    private List<int[]>              countList; // [new, total] per alert

    private RecyclerView rvAlerts;
    private TextView     tvEmpty;

    private final Handler          uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService  executor  = Executors.newSingleThreadExecutor();

    private final FeatureRequestHelper featureRequestHelper =
            new FeatureRequestHelper(this, "Immobilien Alerts");

    // Available check intervals (label index matches value index)
    private static final int[] INTERVAL_VALUES = {30, 60, 120, 360, 720, 1440};

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_immobilien, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db         = new ImmobilienDatabaseHelper(requireContext());
        alertList  = new ArrayList<>();
        countList  = new ArrayList<>();

        tvEmpty  = view.findViewById(R.id.tv_immobilien_empty);
        rvAlerts = view.findViewById(R.id.rv_immobilien_alerts);
        rvAlerts.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new ImmobilienAdapter(alertList, countList, new ImmobilienAdapter.AlertListener() {
            @Override
            public void onEditClick(ImmobilienAlert alert) {
                showAddEditDialog(alert);
            }

            @Override
            public void onAlertClick(ImmobilienAlert alert) {
                showListingsDialog(alert);
            }
        });
        rvAlerts.setAdapter(adapter);

        FloatingActionButton fab = view.findViewById(R.id.fab_add_immobilien);
        fab.setOnClickListener(v -> showAddEditDialog(null));

        ImageButton btnFeatureRequest = view.findViewById(R.id.btn_feature_request);
        if (btnFeatureRequest != null) {
            btnFeatureRequest.setOnClickListener(v -> featureRequestHelper.show());
        }

        setupRotationToggle(view, PAGE_INDEX);

        // Schedule background checks (idempotent – replaces any existing alarm)
        ImmobilienCheckReceiver.createNotificationChannel(requireContext());
        ImmobilienCheckScheduler.schedule(requireContext());

        loadAlerts();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAlerts(); // refresh counts after returning from background
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (db != null) db.close();
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadAlerts() {
        executor.execute(() -> {
            List<ImmobilienAlert> list   = db.getAllAlerts();
            List<int[]>           counts = new ArrayList<>();
            for (ImmobilienAlert a : list) {
                counts.add(new int[]{db.countNewListings(a.id), db.countAllListings(a.id)});
            }
            uiHandler.post(() -> {
                alertList.clear();
                alertList.addAll(list);
                countList.clear();
                countList.addAll(counts);
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(alertList.isEmpty() ? View.VISIBLE : View.GONE);
                rvAlerts.setVisibility(alertList.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ── Add / Edit dialog ─────────────────────────────────────────────────────

    private void showAddEditDialog(@Nullable final ImmobilienAlert existing) {
        Context ctx   = requireContext();
        int     padPx = Math.round(16 * ctx.getResources().getDisplayMetrics().density);

        final EditText etName = new EditText(ctx);
        etName.setHint(R.string.immobilien_alert_name_hint);
        etName.setSingleLine(true);

        final TextView tvUrlDesc = new TextView(ctx);
        tvUrlDesc.setText(R.string.immobilien_url_description);
        tvUrlDesc.setTextSize(12f);
        tvUrlDesc.setPadding(0, padPx / 2, 0, 0);

        final EditText etUrl = new EditText(ctx);
        etUrl.setHint(R.string.immobilien_url_hint);
        etUrl.setMaxLines(3);

        // Interval spinner
        final TextView tvIntervalLabel = new TextView(ctx);
        tvIntervalLabel.setText(R.string.immobilien_interval_label);
        tvIntervalLabel.setTextSize(12f);
        tvIntervalLabel.setPadding(0, padPx / 2, 0, 0);

        final Spinner spinnerInterval = new Spinner(ctx);
        String[] intervalLabels = ctx.getResources().getStringArray(R.array.immobilien_intervals);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, intervalLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerInterval.setAdapter(spinnerAdapter);

        final CheckBox cbActive = new CheckBox(ctx);
        cbActive.setText(R.string.immobilien_active_label);
        cbActive.setPadding(0, padPx / 2, 0, 0);

        // Pre-fill if editing
        int selectedIntervalPos = 1; // default 60 min
        if (existing != null) {
            etName.setText(existing.name);
            etName.setSelection(existing.name.length());
            etUrl.setText(existing.searchUrl);
            cbActive.setChecked(existing.active);
            for (int i = 0; i < INTERVAL_VALUES.length; i++) {
                if (INTERVAL_VALUES[i] == existing.checkIntervalMinutes) {
                    selectedIntervalPos = i;
                    break;
                }
            }
        } else {
            cbActive.setChecked(true);
        }
        spinnerInterval.setSelection(selectedIntervalPos);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padPx, padPx, padPx, padPx);
        layout.addView(etName);
        layout.addView(tvUrlDesc);
        layout.addView(etUrl);
        layout.addView(tvIntervalLabel);
        layout.addView(spinnerInterval);
        layout.addView(cbActive);

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.addView(layout);

        String title = existing == null
                ? getString(R.string.immobilien_add_alert)
                : getString(R.string.immobilien_edit_alert);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(scrollView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null);

        if (existing != null) {
            builder.setNeutralButton(R.string.delete, null);
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String url  = etUrl.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(ctx, R.string.immobilien_name_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                Toast.makeText(ctx, R.string.immobilien_url_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            int intervalMinutes = INTERVAL_VALUES[spinnerInterval.getSelectedItemPosition()];
            if (existing == null) {
                ImmobilienAlert alert = new ImmobilienAlert(
                        0, name, url, intervalMinutes, cbActive.isChecked(), 0L);
                db.addAlert(alert);
            } else {
                existing.name                 = name;
                existing.searchUrl            = url;
                existing.checkIntervalMinutes = intervalMinutes;
                existing.active               = cbActive.isChecked();
                db.updateAlert(existing);
            }
            dialog.dismiss();
            loadAlerts();
        });

        if (existing != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                confirmDelete(existing, dialog);
            });
        }
    }

    private void confirmDelete(ImmobilienAlert alert, AlertDialog parentDialog) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.immobilien_delete_alert)
                .setMessage(getString(R.string.immobilien_delete_confirm, alert.name))
                .setPositiveButton(R.string.delete, (d, which) -> {
                    db.deleteAlert(alert.id);
                    parentDialog.dismiss();
                    loadAlerts();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Listings dialog ───────────────────────────────────────────────────────

    private void showListingsDialog(ImmobilienAlert alert) {
        executor.execute(() -> {
            List<ImmobilienListing> listings = db.getListingsForAlert(alert.id);
            db.markAllNotified(alert.id);
            uiHandler.post(() -> {
                loadAlerts(); // refresh badge counts
                displayListingsDialog(alert, listings);
            });
        });
    }

    private void displayListingsDialog(ImmobilienAlert alert,
                                       List<ImmobilienListing> listings) {
        Context ctx   = requireContext();
        int     padPx = Math.round(12 * ctx.getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padPx, padPx, padPx, padPx);

        if (listings.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(R.string.immobilien_listings_empty);
            tv.setTextSize(14f);
            layout.addView(tv);
        } else {
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
            for (ImmobilienListing listing : listings) {
                TextView tv = new TextView(ctx);
                String ts = df.format(new java.util.Date(listing.firstSeenMs));
                tv.setText(listing.listingUrl + "\n" + ts);
                tv.setTextSize(12f);
                tv.setPadding(0, padPx / 2, 0, padPx / 2);
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.accent));
                final String url = listing.listingUrl;
                tv.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                });
                layout.addView(tv);

                View divider = new View(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                divider.setLayoutParams(lp);
                divider.setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider));
                layout.addView(divider);
            }
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(layout);

        new AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.immobilien_listings_title, alert.name))
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.immobilien_check_now, (d, w) -> triggerManualCheck(alert))
                .show();
    }

    // ── Manual check ──────────────────────────────────────────────────────────

    private void triggerManualCheck(ImmobilienAlert alert) {
        Toast.makeText(requireContext(), R.string.immobilien_checking, Toast.LENGTH_SHORT).show();
        final Context appCtx = requireContext().getApplicationContext();
        executor.execute(() -> {
            ImmobilienCheckReceiver receiver = new ImmobilienCheckReceiver();
            try {
                String html = receiver.fetchUrl(alert.searchUrl);
                java.util.Set<String> found = receiver.extractListingUrls(html, alert.searchUrl);
                int newCount = 0;
                for (String u : found) {
                    if (db.addListingIfNew(alert.id, u)) newCount++;
                }
                db.updateLastCheck(alert.id, System.currentTimeMillis());
                final int finalNew = newCount;
                uiHandler.post(() -> {
                    loadAlerts();
                    String msg = finalNew > 0
                            ? getString(R.string.immobilien_notif_text, finalNew, alert.name)
                            : getString(R.string.immobilien_check_success);
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                UpdateLogger.logError(appCtx,
                        "Immobilien manual check failed for alert '" + alert.name
                                + "' [" + alert.searchUrl + "]", e);
                uiHandler.post(() -> {
                    Context ctx = getContext();
                    if (ctx == null) return;
                    new android.app.AlertDialog.Builder(ctx)
                            .setTitle(R.string.immobilien_check_error_title)
                            .setMessage(ctx.getString(R.string.immobilien_check_error_detail, errorDetail))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    // ── Rotation toggle (standard pattern) ────────────────────────────────────

    private void setupRotationToggle(View view, int pageIndex) {
        ImageButton btn = view.findViewById(R.id.btn_rotation_toggle);
        if (btn == null) return;
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        btn.setAlpha(prefs.getBoolean(
                String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), true) ? 1.0f : 0.25f);
        btn.setOnClickListener(v -> {
            boolean current = prefs.getBoolean(
                    String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), true);
            boolean newValue = !current;
            prefs.edit()
                    .putBoolean(String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), newValue)
                    .apply();
            btn.setAlpha(newValue ? 1.0f : 0.25f);
        });
    }
}
