package com.kitchenboard.tasks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.kitchenboard.MainActivity;
import com.kitchenboard.R;
import com.kitchenboard.calendar.CalendarDatabaseHelper;
import com.kitchenboard.calendar.Person;
import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles the periodic alarm broadcast from {@link TasksCheckScheduler}.
 *
 * <p>Polls the task backend and posts an in-app notification whenever a new
 * task has been assigned to the currently active person on this device.
 * This allows users on other devices that share the same board token to be
 * notified when a task is created for them.
 *
 * <p>A task is considered "new" when it exists on the remote backend but is
 * not yet present in the local SQLite database.
 */
public class TasksCheckReceiver extends BroadcastReceiver {

    /**
     * Shared preferences file used by the tasks sync configuration.
     * This matches the file used by {@link TaskFragment} so both components
     * read the same server URL, board token, and API token settings.
     */
    private static final String PREFS_NAME       = "shopping_prefs";
    private static final String PREF_SERVER_URL  = "server_url";
    private static final String PREF_BOARD_TOKEN = "board_token";
    private static final String PREF_API_TOKEN   = "api_token";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!TasksCheckScheduler.ACTION_TASKS_CHECK.equals(intent.getAction())) return;

        final PendingResult pendingResult = goAsync();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runCheck(context);
                } finally {
                    pendingResult.finish();
                }
            }
        });
    }

    // ── Core check logic ──────────────────────────────────────────────────────

    private void runCheck(Context context) {
        // Resolve the active person's name from CalendarDatabaseHelper
        String activePersonName = resolveActivePersonName(context);
        if (activePersonName == null || activePersonName.isEmpty()) {
            return; // No active person set – nothing to notify
        }

        // Read sync configuration
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String serverUrl  = prefs.getString(PREF_SERVER_URL,  "").trim();
        String boardToken = prefs.getString(PREF_BOARD_TOKEN, "").trim();
        String apiToken   = prefs.getString(PREF_API_TOKEN,   "").trim();

        if (serverUrl.isEmpty()) {
            return; // Sync not configured
        }

        // Fetch tasks from backend
        List<JSONObject> remoteTasks;
        try {
            remoteTasks = fetchRemoteTasks(serverUrl, boardToken, apiToken);
        } catch (Exception e) {
            return; // Network error; will retry on next alarm
        }

        // Compare with locally known task IDs
        TaskDatabaseHelper localDb = new TaskDatabaseHelper(context);
        try {
            Set<Long> knownIds = localDb.getTaskIds();
            for (JSONObject obj : remoteTasks) {
                long   id         = obj.optLong("id", -1);
                String title      = obj.optString("title", "");
                int    sortOrder  = obj.optInt("sort_order", 0);
                String assignedTo = obj.optString("assigned_to", "");

                if (id <= 0) continue;

                boolean isNew = !knownIds.contains(id);

                // Always insert to keep local DB in sync
                localDb.insertTaskWithId(id, title, sortOrder, assignedTo);

                // Notify only for genuinely new tasks assigned to this person
                if (isNew && activePersonName.equals(assignedTo)) {
                    NotificationStore.getInstance(context).addNotification(
                            AppNotification.TYPE_TASK,
                            context.getString(R.string.tasks_notif_new_title),
                            context.getString(R.string.tasks_notif_new_message, title),
                            TaskFragment.TASK_PAGE_INDEX);
                }
            }
        } catch (Exception e) {
            // Ignore parse errors for individual tasks
        } finally {
            localDb.close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the name of the currently active person, or {@code null} if none
     * is configured or the person cannot be found in the local calendar database.
     */
    private String resolveActivePersonName(Context context) {
        SharedPreferences calPrefs = context.getSharedPreferences(
                MainActivity.PREFS_CALENDAR, Context.MODE_PRIVATE);
        long activePersonId = calPrefs.getLong(MainActivity.PREF_ACTIVE_PERSON_ID, -1L);
        if (activePersonId < 0) return null;

        CalendarDatabaseHelper calDb = new CalendarDatabaseHelper(context);
        try {
            for (Person p : calDb.getPersons()) {
                if (p.getId() == activePersonId) {
                    return p.getName();
                }
            }
        } finally {
            calDb.close();
        }
        return null;
    }

    /**
     * Fetches the tasks list from the backend and returns each task as a
     * {@link JSONObject}.  Throws on any network or parse error.
     */
    private List<JSONObject> fetchRemoteTasks(String baseUrl, String boardToken, String apiToken)
            throws Exception {
        String url = baseUrl
                + (baseUrl.contains("?") ? "&" : "?")
                + "action=tasks_list&board_token="
                + java.net.URLEncoder.encode(boardToken, "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Accept", "application/json");
        if (!apiToken.isEmpty()) {
            conn.setRequestProperty("X-Api-Token", apiToken);
        }

        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject json = new JSONObject(sb.toString());
            JSONArray arr = json.getJSONArray("tasks");
            java.util.List<JSONObject> result = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getJSONObject(i));
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }
}
