package com.kitchenboard.immobilien;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kitchenboard.MainActivity;
import com.kitchenboard.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the periodic alarm broadcast from {@link ImmobilienCheckScheduler}.
 *
 * <p>For each active {@link ImmobilienAlert} that is due:
 * <ol>
 *   <li>Fetches the configured search URL via HTTP GET.</li>
 *   <li>Extracts listing URLs from the HTML using site-agnostic heuristics.</li>
 *   <li>Compares against previously stored listings to find new ones.</li>
 *   <li>Persists new listings in the database.</li>
 *   <li>Posts a heads-up notification for every batch of new listings.</li>
 * </ol>
 */
public class ImmobilienCheckReceiver extends BroadcastReceiver {

    private static final String TAG        = "ImmobilienCheck";
    static final         String CHANNEL_ID = "immobilien_alerts";

    /** Unique notification IDs start at this base value. */
    private static final int NOTIF_ID_BASE = 3000;

    // Regex that captures relative or absolute listing-URL patterns common on
    // German real-estate portals (ImmobilienScout24, Immowelt, Kleinanzeigen, …).
    private static final Pattern HREF_PATTERN =
            Pattern.compile("href=[\"']([^\"'#?]+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!ImmobilienCheckScheduler.ACTION_IMMOBILIEN_CHECK.equals(intent.getAction())) return;

        createNotificationChannel(context);

        final PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            try {
                runChecks(context);
            } finally {
                pendingResult.finish();
            }
        });
    }

    // ── Core check logic ──────────────────────────────────────────────────────

    private void runChecks(Context context) {
        ImmobilienDatabaseHelper db = new ImmobilienDatabaseHelper(context);
        List<ImmobilienAlert> dueAlerts = db.getDueAlerts();

        int totalNew = 0;
        List<String> alertsWithNew = new ArrayList<>();

        for (ImmobilienAlert alert : dueAlerts) {
            try {
                int newCount = checkAlert(context, db, alert);
                db.updateLastCheck(alert.id, System.currentTimeMillis());
                if (newCount > 0) {
                    totalNew += newCount;
                    alertsWithNew.add(alert.name);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error checking alert '" + alert.name + "': " + e.getMessage());
            }
        }

        if (totalNew > 0) {
            sendNotification(context, totalNew, alertsWithNew);
        }
    }

    /**
     * Fetches the alert's search URL, extracts listing URLs, persists new ones, and
     * returns how many new listings were found.
     */
    private int checkAlert(Context context, ImmobilienDatabaseHelper db,
                           ImmobilienAlert alert) throws Exception {
        String html = fetchUrl(alert.searchUrl);
        Set<String> found = extractListingUrls(html, alert.searchUrl);

        int newCount = 0;
        for (String listingUrl : found) {
            if (db.addListingIfNew(alert.id, listingUrl)) {
                newCount++;
            }
        }
        return newCount;
    }

    // ── HTML fetching ─────────────────────────────────────────────────────────

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (compatible; 4KitchenBoard/1.0)");
        conn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("HTTP " + responseCode);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    // ── Listing URL extraction ────────────────────────────────────────────────

    /**
     * Extracts unique listing URLs from the HTML of a search-results page.
     *
     * <p>Supports common German real-estate portals:
     * <ul>
     *   <li>ImmobilienScout24 – {@code /expose/\d+}</li>
     *   <li>Immowelt – {@code /expose/[A-Z0-9]+}</li>
     *   <li>Immonet – {@code /angebot/\d+}</li>
     *   <li>Kleinanzeigen – {@code /s-anzeige/…/\d+}</li>
     *   <li>Generic fallback – any href with {@code /expose/}, {@code /angebot/},
     *       {@code /immobilie/}, or {@code /objekt/} in the path</li>
     * </ul>
     */
    Set<String> extractListingUrls(String html, String baseUrl) {
        Set<String> urls = new HashSet<>();
        Matcher m = HREF_PATTERN.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            if (isListingUrl(href)) {
                String abs = makeAbsolute(href, baseUrl);
                if (abs != null) {
                    urls.add(abs);
                }
            }
        }
        return urls;
    }

    private boolean isListingUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        // ImmobilienScout24
        if (lower.contains("/expose/")) return true;
        // Immonet
        if (lower.contains("/angebot/")) return true;
        // Kleinanzeigen
        if (lower.contains("/s-anzeige/")) return true;
        // Generic patterns: /immobilie/, /objekt/, /inserat/
        if (lower.contains("/immobilie/")) return true;
        if (lower.contains("/objekt/"))    return true;
        if (lower.contains("/inserat/"))   return true;
        return false;
    }

    private String makeAbsolute(String href, String baseUrl) {
        if (href == null || href.isEmpty()) return null;
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) {
            try {
                Uri base = Uri.parse(baseUrl);
                return base.getScheme() + "://" + base.getHost() + href;
            } catch (Exception e) {
                return null;
            }
        }
        return null; // relative paths without leading slash – skip
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void sendNotification(Context context, int totalNew,
                                  List<String> alertNames) {
        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("navigate_to_page", 4); // ImmobilienFragment is page 4
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, 0, tapIntent, piFlags);

        String title   = context.getString(R.string.immobilien_notif_title);
        String text;
        if (alertNames.size() == 1) {
            text = context.getString(R.string.immobilien_notif_text, totalNew, alertNames.get(0));
        } else {
            text = context.getString(R.string.immobilien_notif_text_multi);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(context)
                    .notify(NOTIF_ID_BASE, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted", e);
        }
    }

    // ── Notification channel (idempotent) ─────────────────────────────────────

    static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.immobilien_notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.immobilien_notif_channel_desc));
        nm.createNotificationChannel(channel);
    }
}
