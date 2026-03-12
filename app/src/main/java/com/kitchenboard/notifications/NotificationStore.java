package com.kitchenboard.notifications;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Singleton store for in-app notifications.
 *
 * <p>Notifications are persisted to SharedPreferences as a JSON array so they
 * survive app restarts. Registered {@link Observer} callbacks are invoked on the
 * main thread whenever the list changes, so UI components can react immediately
 * without polling.
 *
 * <p>A maximum of {@link #MAX_NOTIFICATIONS} recent entries is kept; oldest
 * entries are pruned automatically when the cap is exceeded.
 *
 * <p>This class is thread-safe: all public methods synchronize on the instance.
 */
public class NotificationStore {

    /** Callback interface for observing notification list changes. */
    public interface Observer {
        void onNotificationsChanged();
    }

    private static final String TAG       = "NotificationStore";
    private static final String PREFS     = "notification_store";
    private static final String KEY_LIST  = "notifications";
    /** Maximum number of stored notifications before oldest are pruned. */
    static final int MAX_NOTIFICATIONS    = 50;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile NotificationStore sInstance;

    public static NotificationStore getInstance(Context context) {
        if (sInstance == null) {
            synchronized (NotificationStore.class) {
                if (sInstance == null) {
                    sInstance = new NotificationStore(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final SharedPreferences   prefs;
    private final Handler             mainHandler = new Handler(Looper.getMainLooper());
    /** In-memory cache, sorted newest-first. */
    private final List<AppNotification> cache = new ArrayList<>();
    /** Weak references to observers (avoids leaking Activity instances). */
    private final List<WeakReference<Observer>> observers = new ArrayList<>();

    private NotificationStore(Context appContext) {
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadFromPrefs();
    }

    // ── Observer registration ─────────────────────────────────────────────────

    /** Registers an observer to be notified (on the main thread) when notifications change. */
    public synchronized void addObserver(Observer observer) {
        // Remove stale references first
        pruneObservers();
        observers.add(new WeakReference<>(observer));
    }

    /** Unregisters a previously registered observer. */
    public synchronized void removeObserver(Observer observer) {
        Iterator<WeakReference<Observer>> it = observers.iterator();
        while (it.hasNext()) {
            Observer o = it.next().get();
            if (o == null || o == observer) it.remove();
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Adds a new notification and notifies observers.
     *
     * @param type       {@link AppNotification#TYPE_REMINDER}, {@link AppNotification#TYPE_PROPERTY},
     *                   {@link AppNotification#TYPE_TASK}, or {@link AppNotification#TYPE_SHOPPING}
     * @param title      Short title string
     * @param message    Full detail message
     * @param navigateTo Page index to open when the user taps the notification, or {@code -1}
     */
    public synchronized void addNotification(int type, String title, String message,
                                             int navigateTo) {
        long timestampMs = System.currentTimeMillis();
        long id = timestampMs;
        // Ensure uniqueness if two notifications arrive within the same millisecond
        while (findById(id) != null) {
            id++;
        }
        AppNotification n = new AppNotification(id, type, title, message,
                timestampMs, navigateTo, false);
        cache.add(0, n); // newest-first
        pruneIfNeeded();
        persist();
        notifyObservers();
    }

    /** Returns an unmodifiable snapshot of all notifications, newest-first. */
    public synchronized List<AppNotification> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(cache));
    }

    /** Returns the number of notifications that have not been read yet. */
    public synchronized int getUnreadCount() {
        int count = 0;
        for (AppNotification n : cache) {
            if (!n.isRead) count++;
        }
        return count;
    }

    /** Marks all notifications as read and notifies observers. */
    public synchronized void markAllRead() {
        boolean changed = false;
        for (AppNotification n : cache) {
            if (!n.isRead) {
                n.isRead = true;
                changed  = true;
            }
        }
        if (changed) {
            persist();
            notifyObservers();
        }
    }

    /** Removes all notifications and notifies observers. */
    public synchronized void clearAll() {
        if (!cache.isEmpty()) {
            cache.clear();
            persist();
            notifyObservers();
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private AppNotification findById(long id) {
        for (AppNotification n : cache) {
            if (n.id == id) return n;
        }
        return null;
    }

    private void pruneIfNeeded() {
        while (cache.size() > MAX_NOTIFICATIONS) {
            cache.remove(cache.size() - 1); // remove oldest (last)
        }
    }

    private void pruneObservers() {
        Iterator<WeakReference<Observer>> it = observers.iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) it.remove();
        }
    }

    private void loadFromPrefs() {
        String json = prefs.getString(KEY_LIST, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    cache.add(AppNotification.fromJson(arr.getJSONObject(i)));
                } catch (JSONException e) {
                    Log.w(TAG, "Skipping malformed notification entry", e);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse stored notifications", e);
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (AppNotification n : cache) {
                arr.put(n.toJson());
            }
            prefs.edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to persist notifications", e);
        }
    }

    private void notifyObservers() {
        // Capture live observer list under lock, then dispatch on main thread
        final List<Observer> live = new ArrayList<>();
        Iterator<WeakReference<Observer>> it = observers.iterator();
        while (it.hasNext()) {
            Observer o = it.next().get();
            if (o == null) { it.remove(); continue; }
            live.add(o);
        }
        mainHandler.post(() -> {
            for (Observer o : live) {
                o.onNotificationsChanged();
            }
        });
    }
}

