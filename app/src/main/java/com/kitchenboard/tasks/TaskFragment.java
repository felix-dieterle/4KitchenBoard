package com.kitchenboard.tasks;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
import com.kitchenboard.MainActivity;
import com.kitchenboard.R;
import com.kitchenboard.calendar.CalendarDatabaseHelper;
import com.kitchenboard.calendar.Person;
import com.kitchenboard.feedback.FeatureRequestHelper;
import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TaskFragment extends Fragment {

    private static final String PREFS_NAME        = "shopping_prefs";
    private static final String PREF_SERVER_URL   = "server_url";
    private static final String PREF_SERVER_HOST  = "server_host";
    private static final String PREF_SERVER_BASEPATH = "server_basepath";
    private static final String PREF_BOARD_TOKEN  = "board_token";
    private static final String PREF_API_TOKEN    = "api_token";

    /** Default API base path used when no custom path has been configured. */
    private static final String DEFAULT_BASEPATH  = "/apps/kitchenboard/api.php";

    /** ViewPager2 page index of this fragment (used for notification navigation). */
    static final int TASK_PAGE_INDEX = 3;

    /** Periodic sync interval: 5 minutes. */
    private static final long SYNC_INTERVAL_MS = 5 * 60 * 1000L;

    private TaskDatabaseHelper db;
    private TaskAdapter        adapter;
    private RecyclerView       rvTasks;
    private TextView           tvEmpty;
    private TextView           tvSyncStatus;

    /** Non-null when a valid server URL is configured. */
    private TaskApiClient apiClient;

    private final FeatureRequestHelper featureRequestHelper =
            new FeatureRequestHelper(this, "Aufgaben");

    private final Handler  syncHandler  = new Handler(Looper.getMainLooper());
    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            periodicSync();
            syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tasks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = new TaskDatabaseHelper(requireContext());

        tvEmpty      = view.findViewById(R.id.tv_tasks_empty);
        tvSyncStatus = view.findViewById(R.id.tv_tasks_sync_status);
        rvTasks      = view.findViewById(R.id.rv_tasks);
        rvTasks.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new TaskAdapter(db.getAllTasks(), new TaskAdapter.OnTaskActionListener() {
            @Override
            public void onMoveUp(int position) {
                moveTaskUp(position);
            }

            @Override
            public void onMoveDown(int position) {
                moveTaskDown(position);
            }

            @Override
            public void onMoveToTop(int position) {
                moveTaskToTop(position);
            }

            @Override
            public void onMoveToBottom(int position) {
                moveTaskToBottom(position);
            }

            @Override
            public void onDone(int position) {
                markDone(position);
            }

            @Override
            public void onLongClick(int position) {
                showTaskMenu(position);
            }
        });
        rvTasks.setAdapter(adapter);

        FloatingActionButton fab = view.findViewById(R.id.fab_add_task);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddEditDialog(null);
            }
        });

        ImageButton btnSyncConfigure = view.findViewById(R.id.btn_tasks_sync_configure);
        btnSyncConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSyncConfigDialog();
            }
        });

        ImageButton btnFeatureRequest = view.findViewById(R.id.btn_feature_request);
        btnFeatureRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                featureRequestHelper.show();
            }
        });

        setupRotationToggle(view, 3);

        refreshApiClient();
        loadTasks();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL_MS);
        if (apiClient != null) {
            periodicSync();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        syncHandler.removeCallbacks(syncRunnable);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        syncHandler.removeCallbacks(syncRunnable);
        if (db != null) {
            db.close();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void refreshApiClient() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, 0);
        String url      = prefs.getString(PREF_SERVER_URL, "").trim();
        String token    = prefs.getString(PREF_BOARD_TOKEN, "").trim();
        String apiToken = prefs.getString(PREF_API_TOKEN, "").trim();
        apiClient = url.isEmpty() ? null : new TaskApiClient(requireContext(), url, token, apiToken);
    }

    /**
     * Constructs a full API URL from a host (hostname or IP, optionally with scheme)
     * and a base path. Prepends {@code http://} when no scheme is present.
     */
    private static String buildServerUrl(String host, String basepath) {
        if (host == null || host.trim().isEmpty()) return "";
        host = host.trim();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (basepath == null || basepath.trim().isEmpty()) {
            basepath = DEFAULT_BASEPATH;
        } else {
            basepath = basepath.trim();
            if (!basepath.startsWith("/")) {
                basepath = "/" + basepath;
            }
        }
        return host + basepath;
    }

    /** Extracts the host (with scheme and optional port) from a full URL for migration. */
    private static String extractHost(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            String port = u.getPort() != -1 ? ":" + u.getPort() : "";
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Exception e) {
            return url;
        }
    }

    /** Extracts the base path from a full URL for migration. */
    private static String extractBasePath(String url) {
        if (url == null || url.isEmpty()) return DEFAULT_BASEPATH;
        try {
            String path = new java.net.URL(url).getPath();
            return path.isEmpty() ? DEFAULT_BASEPATH : path;
        } catch (Exception e) {
            return DEFAULT_BASEPATH;
        }
    }

    private void loadTasks() {
        List<Task> tasks = db.getAllTasks();
        adapter.setItems(tasks);
        tvEmpty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
        rvTasks.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showAddEditDialog(@Nullable final Task existing) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_task, null);
        final EditText etTitle = dialogView.findViewById(R.id.et_task_title);
        final Spinner spinnerAssignee = dialogView.findViewById(R.id.spinner_task_assignee);

        // Build person list for the spinner
        List<Person> persons = new ArrayList<>();
        CalendarDatabaseHelper calDb = new CalendarDatabaseHelper(requireContext());
        try {
            persons = calDb.getPersons();
        } finally {
            calDb.close();
        }

        // First entry is "Niemand" (nobody / unassigned)
        List<String> personNames = new ArrayList<>();
        personNames.add(getString(R.string.tasks_assign_nobody));
        for (Person p : persons) {
            personNames.add(p.getName());
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                personNames);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAssignee.setAdapter(spinnerAdapter);

        if (existing != null) {
            etTitle.setText(existing.title);
            etTitle.setSelection(existing.title.length());
            // Select the current assignee in the spinner
            if (!existing.assignedTo.isEmpty()) {
                for (int i = 1; i < personNames.size(); i++) {
                    if (personNames.get(i).equals(existing.assignedTo)) {
                        spinnerAssignee.setSelection(i);
                        break;
                    }
                }
            }
        }

        String title = existing == null
                ? getString(R.string.tasks_add_title)
                : getString(R.string.tasks_edit_title);

        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(dialogView)
                .setPositiveButton(R.string.tasks_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = etTitle.getText().toString().trim();
                        if (text.isEmpty()) return;
                        int sel = spinnerAssignee.getSelectedItemPosition();
                        String assignedTo = sel > 0 ? personNames.get(sel) : "";
                        if (existing == null) {
                            final long id = db.addTask(text, assignedTo);
                            if (apiClient != null) {
                                List<Task> all = db.getAllTasks();
                                for (Task t : all) {
                                    if (t.id == id) {
                                        apiClient.upsertTask(t, null);
                                        break;
                                    }
                                }
                            }
                        } else {
                            db.updateTitleAndAssignee(existing.id, text, assignedTo);
                            if (apiClient != null) {
                                apiClient.upsertTask(
                                        new Task(existing.id, text, existing.sortOrder, assignedTo), null);
                            }
                        }
                        loadTasks();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void moveTaskUp(int position) {
        List<Task> tasks = db.getAllTasks();
        if (position <= 0 || position >= tasks.size()) return;
        Task current = tasks.get(position);
        Task above   = tasks.get(position - 1);
        db.swapOrder(current.id, current.sortOrder, above.id, above.sortOrder);
        if (apiClient != null) {
            apiClient.upsertTask(new Task(current.id, current.title, above.sortOrder, current.assignedTo), null);
            apiClient.upsertTask(new Task(above.id,   above.title,   current.sortOrder, above.assignedTo), null);
        }
        loadTasks();
    }

    private void moveTaskDown(int position) {
        List<Task> tasks = db.getAllTasks();
        if (position < 0 || position >= tasks.size() - 1) return;
        Task current = tasks.get(position);
        Task below   = tasks.get(position + 1);
        db.swapOrder(current.id, current.sortOrder, below.id, below.sortOrder);
        if (apiClient != null) {
            apiClient.upsertTask(new Task(current.id, current.title, below.sortOrder, current.assignedTo), null);
            apiClient.upsertTask(new Task(below.id,   below.title,   current.sortOrder, below.assignedTo), null);
        }
        loadTasks();
    }

    /** Moves a task directly to the first position (highest priority). */
    private void moveTaskToTop(int position) {
        List<Task> tasks = db.getAllTasks();
        if (position <= 0 || position >= tasks.size()) return;
        Task task  = tasks.get(position);
        Task first = tasks.get(0);
        int newOrder = first.sortOrder - 1;
        db.setSortOrder(task.id, newOrder);
        if (apiClient != null) {
            apiClient.upsertTask(new Task(task.id, task.title, newOrder, task.assignedTo), null);
        }
        Toast.makeText(requireContext(),
                getString(R.string.tasks_moved_to_top, task.title),
                Toast.LENGTH_SHORT).show();
        loadTasks();
    }

    /** Moves a task directly to the last position (lowest priority). */
    private void moveTaskToBottom(int position) {
        List<Task> tasks = db.getAllTasks();
        if (position < 0 || position >= tasks.size() - 1) return;
        Task task = tasks.get(position);
        Task last = tasks.get(tasks.size() - 1);
        int newOrder = last.sortOrder + 1;
        db.setSortOrder(task.id, newOrder);
        if (apiClient != null) {
            apiClient.upsertTask(new Task(task.id, task.title, newOrder, task.assignedTo), null);
        }
        Toast.makeText(requireContext(),
                getString(R.string.tasks_moved_to_bottom, task.title),
                Toast.LENGTH_SHORT).show();
        loadTasks();
    }

    private void markDone(int position) {
        List<Task> tasks = db.getAllTasks();
        if (position < 0 || position >= tasks.size()) return;
        final Task task = tasks.get(position);
        db.deleteTask(task.id);
        if (apiClient != null) {
            apiClient.deleteTask(task.id, null);
        }
        Toast.makeText(requireContext(),
                getString(R.string.tasks_done_toast, task.title),
                Toast.LENGTH_SHORT).show();
        loadTasks();
    }

    private void showTaskMenu(final int position) {
        List<Task> tasks = db.getAllTasks();
        if (position < 0 || position >= tasks.size()) return;
        final Task task = tasks.get(position);

        String[] options = {
                getString(R.string.tasks_edit_option),
                getString(R.string.tasks_delete_option)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(task.title)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showAddEditDialog(task);
                        } else {
                            confirmDelete(task);
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final Task task) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tasks_delete_title)
                .setMessage(getString(R.string.tasks_delete_confirm, task.title))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        db.deleteTask(task.id);
                        if (apiClient != null) {
                            apiClient.deleteTask(task.id, null);
                        }
                        loadTasks();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showSyncConfigDialog() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, 0);
        int pad = requireContext().getResources().getDimensionPixelSize(R.dimen.panel_padding);

        // Populate host/basepath from saved values; fall back to parsing legacy full URL.
        String savedHost     = prefs.getString(PREF_SERVER_HOST, "");
        String savedBasepath = prefs.getString(PREF_SERVER_BASEPATH, "");
        if (savedHost.isEmpty()) {
            String legacyUrl = prefs.getString(PREF_SERVER_URL, "");
            if (!legacyUrl.isEmpty()) {
                savedHost     = extractHost(legacyUrl);
                savedBasepath = extractBasePath(legacyUrl);
            }
        }
        if (savedBasepath.isEmpty()) {
            savedBasepath = DEFAULT_BASEPATH;
        }

        final EditText etHost = new EditText(requireContext());
        etHost.setText(savedHost);
        etHost.setHint(getString(R.string.sync_host_hint));
        etHost.setSingleLine(true);

        final EditText etBasepath = new EditText(requireContext());
        etBasepath.setHint(getString(R.string.sync_basepath_hint));
        etBasepath.setSingleLine(true);
        etBasepath.setText(savedBasepath);

        final TextView tvTokenDesc = new TextView(requireContext());
        tvTokenDesc.setText(R.string.board_token_description);
        tvTokenDesc.setTextSize(12f);

        final EditText etToken = new EditText(requireContext());
        etToken.setHint(getString(R.string.board_token_hint));
        etToken.setSingleLine(true);
        etToken.setText(prefs.getString(PREF_BOARD_TOKEN, ""));

        final TextView tvApiTokenDesc = new TextView(requireContext());
        tvApiTokenDesc.setText(R.string.api_token_description);
        tvApiTokenDesc.setTextSize(12f);

        final EditText etApiToken = new EditText(requireContext());
        etApiToken.setHint(getString(R.string.api_token_hint));
        etApiToken.setSingleLine(true);
        etApiToken.setText(prefs.getString(PREF_API_TOKEN, ""));

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(etHost);
        layout.addView(etBasepath);
        layout.addView(tvTokenDesc);
        layout.addView(etToken);
        layout.addView(tvApiTokenDesc);
        layout.addView(etApiToken);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_url_title)
                .setMessage(R.string.sync_url_message)
                .setView(layout)
                .setPositiveButton(R.string.sync_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String host     = etHost.getText().toString().trim();
                        String basepath = etBasepath.getText().toString().trim();
                        String token    = etToken.getText().toString().trim();
                        String apiToken = etApiToken.getText().toString().trim();
                        String url      = buildServerUrl(host, basepath);
                        prefs.edit()
                                .putString(PREF_SERVER_HOST,     host)
                                .putString(PREF_SERVER_BASEPATH, basepath)
                                .putString(PREF_SERVER_URL,      url)
                                .putString(PREF_BOARD_TOKEN,     token)
                                .putString(PREF_API_TOKEN,       apiToken)
                                .apply();
                        refreshApiClient();
                        if (apiClient != null) {
                            periodicSync();
                        }
                    }
                })
                .setNeutralButton(R.string.board_token_copy_config, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String host     = etHost.getText().toString().trim();
            String basepath = etBasepath.getText().toString().trim();
            String token    = etToken.getText().toString().trim();
            String apiToken = etApiToken.getText().toString().trim();
            String url      = buildServerUrl(host, basepath);
            String config = url
                    + (token.isEmpty()    ? "" : "\nToken: "     + token)
                    + (apiToken.isEmpty() ? "" : "\nAPI-Token: " + apiToken);
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("KitchenBoard Sync Config", config));
            Toast.makeText(requireContext(), R.string.board_token_copied, Toast.LENGTH_SHORT).show();
        });
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    private void periodicSync() {
        if (apiClient == null) return;
        apiClient.fetchTasks(new TaskApiClient.Callback<List<Task>>() {
            @Override
            public void onSuccess(List<Task> result) {
                String activePersonName = getActivePersonName();
                for (Task remote : result) {
                    boolean inserted = db.insertTaskWithId(
                            remote.id, remote.title, remote.sortOrder, remote.assignedTo);
                    if (inserted
                            && !remote.assignedTo.isEmpty()
                            && remote.assignedTo.equals(activePersonName)) {
                        NotificationStore.getInstance(requireContext()).addNotification(
                                AppNotification.TYPE_TASK,
                                getString(R.string.tasks_notif_new_title),
                                getString(R.string.tasks_notif_new_message, remote.title),
                                TASK_PAGE_INDEX);
                    }
                }
                loadTasks();
                showSyncStatus(true);
            }

            @Override
            public void onError(String message) {
                showSyncStatus(false);
            }
        });
    }

    /** Returns the name of the active person, or an empty string if none is set. */
    private String getActivePersonName() {
        SharedPreferences calPrefs = requireContext()
                .getSharedPreferences(MainActivity.PREFS_CALENDAR, Context.MODE_PRIVATE);
        long activePersonId = calPrefs.getLong(MainActivity.PREF_ACTIVE_PERSON_ID, -1L);
        if (activePersonId < 0) return "";
        CalendarDatabaseHelper calDb = new CalendarDatabaseHelper(requireContext());
        try {
            for (Person p : calDb.getPersons()) {
                if (p.getId() == activePersonId) return p.getName();
            }
        } finally {
            calDb.close();
        }
        return "";
    }

    private void showSyncStatus(final boolean success) {
        if (tvSyncStatus == null) return;
        tvSyncStatus.setVisibility(View.VISIBLE);
        tvSyncStatus.setText(success
                ? getString(R.string.tasks_sync_status_ok)
                : getString(R.string.tasks_sync_status_error));
        tvSyncStatus.setTextColor(ContextCompat.getColor(requireContext(),
                success ? android.R.color.holo_green_dark : R.color.error));
        syncHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tvSyncStatus != null) {
                    tvSyncStatus.setVisibility(View.GONE);
                }
            }
        }, 3000);
    }

    // ── Rotation-toggle helper ────────────────────────────────────────────────

    private static final String PREF_PAGE_IN_ROTATION = "page_%d_in_rotation";

    private void setupRotationToggle(View view, int pageIndex) {
        ImageButton btn = view.findViewById(R.id.btn_rotation_toggle);
        if (btn == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
