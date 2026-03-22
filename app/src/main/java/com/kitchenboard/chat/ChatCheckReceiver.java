package com.kitchenboard.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.kitchenboard.R;
import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;
import com.kitchenboard.update.UpdateLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles the periodic alarm from {@link ChatCheckScheduler}.
 *
 * <p>Polls the backend for chat messages that have arrived since the last
 * check.  Any genuinely new messages are stored in the local
 * {@link ChatDatabaseHelper} and a single summary notification is posted to
 * {@link NotificationStore} so users see new messages in the notification
 * bell without being overwhelmed by individual per-message alerts.
 *
 * <p>When the board-token filter is enabled in settings, only messages whose
 * {@code board_token} matches the locally configured token are fetched (the
 * backend filters server-side).  When the filter is disabled the token
 * parameter is sent empty, allowing all messages to come through.
 */
public class ChatCheckReceiver extends BroadcastReceiver {

    /** SharedPreferences file shared with the shopping / account setup dialog. */
    static final String PREFS_NAME        = "shopping_prefs";
    static final String PREF_SERVER_URL   = "server_url";
    static final String PREF_BOARD_TOKEN  = "board_token";
    static final String PREF_API_TOKEN    = "api_token";
    /** When {@code true}, the board token is sent with every chat request so only
     *  messages from the same board are delivered. */
    public static final String PREF_CHAT_TOKEN_FILTER = "chat_token_filter";
    /** Whether the chat polling is enabled at all. */
    public static final String PREF_CHAT_ENABLED      = "chat_enabled";
    /** When {@code true}, messages are exchanged directly over the local network
     *  using {@link LanChatServer}; no backend polling is performed. */
    public static final String PREF_CHAT_LAN_MODE     = "chat_lan_mode";
    /**
     * When {@code true} the device announces itself as active (ready to receive messages)
     * in LAN discovery broadcasts.  Other peers show this device as online.
     */
    public static final String PREF_CHAT_ACTIVE        = "chat_active";

    /** Maximum number of messages retained in the local DB. */
    private static final int MAX_MESSAGES = 200;

    /** Page index shown in the chat notification ({@code -1} = no navigation). */
    static final int CHAT_NAVIGATE_TO = -1;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!ChatCheckScheduler.ACTION_CHAT_CHECK.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_CHAT_ENABLED, false)) return; // not configured yet

        final PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            try {
                runCheck(context);
            } finally {
                pendingResult.finish();
            }
        });
    }

    // ── Core check logic ──────────────────────────────────────────────────────

    private void runCheck(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // In LAN mode messages arrive via TCP (LanChatServer) – backend polling is not needed
        // and must not be attempted when the backend may be unreachable.
        if (prefs.getBoolean(PREF_CHAT_LAN_MODE, false)) return;

        String serverUrl        = prefs.getString(PREF_SERVER_URL,  "").trim();
        String boardToken       = prefs.getString(PREF_BOARD_TOKEN, "").trim();
        String apiToken         = prefs.getString(PREF_API_TOKEN,   "").trim();
        boolean tokenFilter     = prefs.getBoolean(PREF_CHAT_TOKEN_FILTER, false);

        if (serverUrl.isEmpty()) return;

        // When the token filter is off, send an empty token so the backend returns all messages.
        String effectiveToken = tokenFilter ? boardToken : "";

        // This device's ID – used to filter directed messages
        String thisDeviceId = "device_" + android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        ChatDatabaseHelper db = new ChatDatabaseHelper(context);
        try {
            long sinceId = db.getMaxId();
            ChatApiClient client = new ChatApiClient(serverUrl, effectiveToken, apiToken);
            List<ChatMessage> newMessages = client.fetchMessages(sinceId);

            if (newMessages.isEmpty()) return;

            // Keep only broadcast messages and messages directed to this device
            List<ChatMessage> relevant = new ArrayList<>();
            for (ChatMessage msg : newMessages) {
                boolean forUs = msg.recipientId.isEmpty()
                        || msg.recipientId.equals(thisDeviceId);
                db.upsert(msg);
                if (forUs) relevant.add(msg);
            }
            db.pruneOldMessages(MAX_MESSAGES);

            if (relevant.isEmpty()) return;

            // Post a single summary notification for all relevant new messages
            int count = relevant.size();
            String title = count == 1
                    ? context.getString(R.string.chat_notif_new_singular, count)
                    : context.getString(R.string.chat_notif_new_plural, count);
            // Build a short preview from the last relevant message
            ChatMessage last = relevant.get(relevant.size() - 1);
            String preview = last.senderName + ": " + last.message;
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";

            NotificationStore.getInstance(context).addNotification(
                    AppNotification.TYPE_CHAT,
                    title,
                    preview,
                    CHAT_NAVIGATE_TO);

        } catch (Exception e) {
            UpdateLogger.logError(context, "ChatCheck: poll failed", e);
        } finally {
            db.close();
        }
    }
}
