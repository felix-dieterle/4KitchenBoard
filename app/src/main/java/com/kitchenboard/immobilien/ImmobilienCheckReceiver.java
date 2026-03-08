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
import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;
import com.kitchenboard.update.UpdateLogger;

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

    /** Maximum number of HTTP fetch attempts before giving up. */
    private static final int  MAX_RETRIES    = 3;
    /** Base delay between retries in milliseconds (multiplied by attempt number). */
    private static final long RETRY_DELAY_MS = 2_000L;

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
                UpdateLogger.logError(context,
                        "Immobilien check failed for alert '" + alert.name
                                + "' [" + alert.searchUrl + "]", e);
            }
        }

        if (totalNew > 0) {
            sendNotification(context, totalNew, alertsWithNew);
            postInAppNotification(context, totalNew, alertsWithNew);
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

    /**
     * Fetches the given URL with up to {@link #MAX_RETRIES} attempts.
     *
     * <p>Transient failures (socket timeouts, connection resets, HTTP 5xx) trigger
     * a retry after a short back-off delay.  Non-transient errors (e.g. HTTP 403,
     * HTTP 404) are re-thrown immediately without retrying.
     *
     * <p>Package-visible so that {@link ImmobilienFragment} can reuse the same
     * logic for its manual-check path.
     */
    String fetchUrl(String urlStr) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return fetchUrlOnce(urlStr);
            } catch (Exception e) {
                lastException = e;
                if (!isTransientError(e) || attempt == MAX_RETRIES) {
                    throw e;
                }
                Log.w(TAG, "Fetch attempt " + attempt + " failed for " + urlStr
                        + ": " + e.getMessage() + " – retrying");
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastException != null ? lastException
                : new Exception("Fetch failed after " + MAX_RETRIES + " attempts for: " + urlStr);
    }

    /** Returns true if the exception represents a transient network or server error. */
    private static boolean isTransientError(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof java.net.SocketException)        return true;
        if (e instanceof java.net.UnknownHostException)   return true;
        // HTTP 5xx responses
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("HTTP 5"))      return true;
        return false;
    }

    private String fetchUrlOnce(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setInstanceFollowRedirects(true);
        // Use a realistic browser User-Agent to avoid HTTP 403 responses from
        // portals that block obvious bot user-agents.
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Connection", "close");

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("HTTP " + responseCode);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(),
                        java.nio.charset.Charset.forName("UTF-8")))) {
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
     * Regex patterns that mark the beginning of the "extended / approximate
     * results" section on German real-estate portals.  Listings that appear
     * after this boundary do NOT fully match the configured search criteria
     * and must be excluded to avoid spurious new-listing notifications.
     *
     * <p>Ordered from most precise (portal-specific data attributes / JSON
     * keys) to least precise (natural-language headings).  The first pattern
     * matched in the HTML wins.
     */
    private static final Pattern[] EXTENDED_RESULTS_PATTERNS = {
        // Immowelt – data-testid on the "Weitere Ergebnisse in der Nähe"
        // enlargement-list container (current portal markup)
        Pattern.compile("data-testid=[\"']serp-enlargementlist", Pattern.CASE_INSENSITIVE),
        // Immowelt – older data-testid variants on the extended-results container;
        // covers both "extended-result" (kebab) and "extendedResult" (camelCase)
        Pattern.compile("data-testid=[\"']extended-?result",  Pattern.CASE_INSENSITIVE),
        // ImmobilienScout24 – sectionType marker embedded in page JSON
        Pattern.compile("\"sectionType\"\\s*:\\s*\"EXTENDED", Pattern.CASE_INSENSITIVE),
        // Generic JSON properties indicating extended / non-exact results
        Pattern.compile("\"isExtended\"\\s*:\\s*true",                   Pattern.CASE_INSENSITIVE),
        Pattern.compile("\"extendedClassifieds\"\\s*:\\s*\\[",           Pattern.CASE_INSENSITIVE),
        Pattern.compile("\"relatedClassifieds\"\\s*:\\s*\\[",            Pattern.CASE_INSENSITIVE),
        // German section headings – catch-all for portals without
        // machine-readable markers (e.g. Immonet, Kleinanzeigen)
        Pattern.compile("Weitere\\s+Ergebnisse\\s+in\\s+der\\s+N\u00E4he", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Ergebnisse\\s+in\\s+der\\s+Umgebung",          Pattern.CASE_INSENSITIVE),
        Pattern.compile("Erweiterte\\s+Ergebnisse",           Pattern.CASE_INSENSITIVE),
        Pattern.compile("Weitere\\s+passende\\s+Angebote",    Pattern.CASE_INSENSITIVE),
        Pattern.compile("Ergebnisse\\s+au\u00DFerhalb",       Pattern.CASE_INSENSITIVE),
        Pattern.compile("au\u00DFerhalb\\s+Ihrer\\s+Suche",   Pattern.CASE_INSENSITIVE),
        Pattern.compile("Nicht\\s+alle\\s+Suchkriterien",     Pattern.CASE_INSENSITIVE),
    };

    /**
     * Returns the portion of {@code html} that precedes the portal's
     * "extended / approximate results" section (Erweiterte Ergebnisse), or
     * the full HTML unchanged if no such section is detected.
     *
     * <p>German real-estate portals (Immowelt, ImmobilienScout24, …)
     * typically show exact-match listings first and then append an
     * "Erweiterte Ergebnisse" section when the exact result set is small.
     * By truncating at that boundary only genuine exact-match listings are
     * extracted and stored, preventing spurious new-listing notifications for
     * properties that do not actually satisfy the configured search criteria.
     */
    static String truncateAtExtendedResults(String html) {
        for (int i = 0; i < EXTENDED_RESULTS_PATTERNS.length; i++) {
            Matcher m = EXTENDED_RESULTS_PATTERNS[i].matcher(html);
            if (m.find()) {
                Log.d(TAG, "Extended-results boundary detected (pattern #" + i
                        + "), ignoring HTML after position " + m.start());
                return html.substring(0, m.start());
            }
        }
        return html;
    }

    /**
     * Extracts unique listing URLs from the HTML of a search-results page,
     * <strong>ignoring</strong> any listings that appear in the portal's
     * "extended / approximate results" section (Erweiterte Ergebnisse).
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
        String exactHtml = truncateAtExtendedResults(html);
        Set<String> urls = new HashSet<>();
        Matcher m = HREF_PATTERN.matcher(exactHtml);
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

    private void postInAppNotification(Context context, int totalNew,
                                       List<String> alertNames) {
        String title = context.getString(R.string.immobilien_notif_title);
        String text;
        if (alertNames.size() == 1) {
            text = context.getString(R.string.immobilien_notif_text, totalNew, alertNames.get(0));
        } else {
            text = context.getString(R.string.immobilien_notif_text_multi);
        }
        NotificationStore.getInstance(context).addNotification(
                AppNotification.TYPE_PROPERTY, title, text, 4 /* ImmobilienFragment */);
    }

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
