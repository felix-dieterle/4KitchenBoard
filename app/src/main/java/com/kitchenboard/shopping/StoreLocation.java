package com.kitchenboard.shopping;

/**
 * Represents a store/shop with an optional GPS location used for geofencing notifications.
 */
public class StoreLocation {

    private final long   id;
    private final String name;
    private final double latitude;
    private final double longitude;
    private final int    radiusMeters;

    public StoreLocation(long id, String name, double latitude, double longitude, int radiusMeters) {
        this.id           = id;
        this.name         = name;
        this.latitude     = latitude;
        this.longitude    = longitude;
        this.radiusMeters = radiusMeters;
    }

    /** Database row id. */
    public long getId() { return id; }

    /** Store display name (matches the {@code shop} field in shopping items). */
    public String getName() { return name; }

    /** WGS-84 latitude in degrees. {@code 0.0} means unset. */
    public double getLatitude() { return latitude; }

    /** WGS-84 longitude in degrees. {@code 0.0} means unset. */
    public double getLongitude() { return longitude; }

    /** Geofence radius in metres. Defaults to 200 m. */
    public int getRadiusMeters() { return radiusMeters; }

    /**
     * Returns {@code true} when this store has a valid GPS location saved.
     * Uses OR semantics: only (0.0, 0.0) is treated as the "unset" sentinel value,
     * so stores on the prime meridian (lon=0) or on the equator (lat=0) are
     * still considered valid.
     */
    public boolean hasValidCoordinates() {
        return latitude != 0.0 || longitude != 0.0;
    }
}
