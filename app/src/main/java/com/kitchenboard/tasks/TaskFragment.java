package com.kitchenboard.tasks;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
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

import java.util.List;

public class TaskFragment extends Fragment {

    private static final String PREFS_NAME      = "shopping_prefs";
    private static final String PREF_SERVER_URL = "server_url";

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
        String url = prefs.getString(PREF_SERVER_URL, "").trim();
        apiClient = url.isEmpty() ? null : new TaskApiClient(url);
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

        if (existing != null) {
            etTitle.setText(existing.title);
            etTitle.setSelection(existing.title.length());
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
                        if (existing == null) {
                            final long id = db.addTask(text);
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
                            db.updateTitle(existing.id, text);
                            if (apiClient != null) {
                                apiClient.upsertTask(
                                        new Task(existing.id, text, existing.sortOrder), null);
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
            apiClient.upsertTask(new Task(current.id, current.title, above.sortOrder),   null);
            apiClient.upsertTask(new Task(above.id,   above.title,   current.sortOrder), null);
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
            apiClient.upsertTask(new Task(current.id, current.title, below.sortOrder),   null);
            apiClient.upsertTask(new Task(below.id,   below.title,   current.sortOrder), null);
        }
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
        String current = prefs.getString(PREF_SERVER_URL, "");

        final EditText et = new EditText(requireContext());
        et.setText(current);
        et.setHint(getString(R.string.sync_url_hint));
        et.setSingleLine(true);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_url_title)
                .setMessage(R.string.sync_url_message)
                .setView(et)
                .setPositiveButton(R.string.sync_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = et.getText().toString().trim();
                        prefs.edit().putString(PREF_SERVER_URL, url).apply();
                        refreshApiClient();
                        if (apiClient != null) {
                            periodicSync();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    private void periodicSync() {
        if (apiClient == null) return;
        apiClient.fetchTasks(new TaskApiClient.Callback<List<Task>>() {
            @Override
            public void onSuccess(List<Task> result) {
                for (Task remote : result) {
                    db.insertTaskWithId(remote.id, remote.title, remote.sortOrder);
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
}
