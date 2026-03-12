package com.kitchenboard.shopping;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Registers and unregisters {@link LocationManager} proximity alerts for stores that have
 * GPS coordinates saved.  When the device enters a store's geofence radius,
 * {@link StoreGeofenceReceiver} is triggered and shows a shopping-list notification.
 *
 * <p>Uses {@link LocationManager#addProximityAlert} which is available from API 1 and
 * does not require Google Play Services.
 */
public class StoreGeofenceHelper {

    static final String ACTION_GEOFENCE  = "com.kitchenboard.action.STORE_GEOFENCE";
    static final String EXTRA_STORE_NAME = "store_name";

    private StoreGeofenceHelper() {}

    /**
     * Registers proximity alerts for every {@link StoreLocation} in {@code stores} that
     * has valid coordinates.  Existing registrations for the same store id are silently
     * replaced ({@link PendingIntent#FLAG_UPDATE_CURRENT}).
     *
     * <p>Requires {@link Manifest.permission#ACCESS_FINE_LOCATION} to be granted.
     *
     * @param context Application or Activity context.
     * @param stores  List of stores to register (may include entries without coordinates).
     */
    public static void registerAll(Context context, List<StoreLocation> stores) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager lm = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        for (StoreLocation store : stores) {
            if (!store.hasValidCoordinates()) continue;
            PendingIntent pi = buildPendingIntent(context, store, false);
            try {
                lm.addProximityAlert(
                        store.getLatitude(),
                        store.getLongitude(),
                        store.getRadiusMeters(),
                        -1L,   // no expiration
                        pi);
            } catch (SecurityException ignored) {
                // Permission may have been revoked between the check above and this call.
            }
        }
    }

    /**
     * Removes proximity alerts previously registered for the given stores.
     *
     * @param context Application or Activity context.
     * @param stores  Same list that was passed to {@link #registerAll}.
     */
    public static void unregisterAll(Context context, List<StoreLocation> stores) {
        LocationManager lm = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        for (StoreLocation store : stores) {
            PendingIntent pi = buildPendingIntent(context, store, true);
            if (pi != null) {
                try {
                    lm.removeProximityAlert(pi);
                } catch (Exception ignored) { /* best effort */ }
                pi.cancel();
            }
        }
    }

    private static PendingIntent buildPendingIntent(Context context,
                                                    StoreLocation store,
                                                    boolean noCreate) {
        Intent intent = new Intent(ACTION_GEOFENCE);
        intent.setClass(context, StoreGeofenceReceiver.class);
        intent.putExtra(EXTRA_STORE_NAME, store.getName());

        int flags = noCreate ? PendingIntent.FLAG_NO_CREATE : PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // FLAG_MUTABLE is required so the system can add KEY_PROXIMITY_ENTERING extra.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        // Use store db id (cast to int) as request code so each store gets its own intent.
        return PendingIntent.getBroadcast(context, (int) store.getId(), intent, flags);
    }
}
