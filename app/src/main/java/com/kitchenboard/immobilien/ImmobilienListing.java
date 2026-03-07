package com.kitchenboard.immobilien;

/** A single real-estate listing URL discovered while checking an {@link ImmobilienAlert}. */
public class ImmobilienListing {

    public long   id;
    public long   alertId;
    /** Normalised listing URL used as the unique identifier for deduplication. */
    public String listingUrl;
    /** Unix millis when this listing was first discovered. */
    public long   firstSeenMs;
    /** Whether a push-notification has already been sent for this listing. */
    public boolean notified;

    public ImmobilienListing() {}

    public ImmobilienListing(long id, long alertId, String listingUrl,
                             long firstSeenMs, boolean notified) {
        this.id          = id;
        this.alertId     = alertId;
        this.listingUrl  = listingUrl;
        this.firstSeenMs = firstSeenMs;
        this.notified    = notified;
    }
}
