package com.kitchenboard.immobilien;

/** Configuration model for a single real-estate search alert. */
public class ImmobilienAlert {

    public long   id;
    public String name;
    public String searchUrl;
    /** How often (in minutes) the search URL should be re-checked. */
    public int    checkIntervalMinutes;
    public boolean active;
    /** System-time millis of the last successful check (0 = never checked). */
    public long   lastCheckMs;

    public ImmobilienAlert() {}

    public ImmobilienAlert(long id, String name, String searchUrl,
                           int checkIntervalMinutes, boolean active, long lastCheckMs) {
        this.id                   = id;
        this.name                 = name;
        this.searchUrl            = searchUrl;
        this.checkIntervalMinutes = checkIntervalMinutes;
        this.active               = active;
        this.lastCheckMs          = lastCheckMs;
    }

    /** Returns true when the alert is active and its next check time has passed. */
    public boolean isDue() {
        if (!active) return false;
        long intervalMs = (long) checkIntervalMinutes * 60_000L;
        return System.currentTimeMillis() >= lastCheckMs + intervalMs;
    }
}
