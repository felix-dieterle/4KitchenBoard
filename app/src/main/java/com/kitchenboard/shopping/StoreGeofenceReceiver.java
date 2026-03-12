package com.kitchenboard.shopping;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.kitchenboard.R;
import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;

import java.util.List;

/**
 * Receives proximity alerts from {@link android.location.LocationManager} when the device
 * enters a store's geofence radius.  Builds an in-app notification (via
 * {@link NotificationStore}) and a system status-bar notification listing the open
 * shopping items for that store.
 *
 * <p>Notification ID range: 4000–4999 (shopping geofence).
 */
public class StoreGeofenceReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID   = "shopping_geofence";
    private static final int    NOTIF_BASE   = 4000;

    @Override
    public void onReceive(Context context, Intent intent) {
        // Only react when entering, not when leaving.
        boolean entering = intent.getBooleanExtra(
                LocationManager.KEY_PROXIMITY_ENTERING, false);
        if (!entering) return;

        String storeName = intent.getStringExtra(StoreGeofenceHelper.EXTRA_STORE_NAME);
        if (storeName == null || storeName.isEmpty()) return;

        // Fetch unchecked items assigned to this store.
        ShoppingDatabaseHelper db = new ShoppingDatabaseHelper(context);
        List<ShoppingItem> items = db.getActiveItemsForShop(storeName);
        if (items.isEmpty()) return;

        // Build a concise summary of items.
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(items.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            ShoppingItem item = items.get(i);
            sb.append(item.getName());
            if (item.getQuantity() > 1) {
                sb.append(" (").append(item.getQuantity()).append("×)");
            }
        }
        if (items.size() > limit) sb.append(" …");

        String title   = context.getString(R.string.geofence_notif_title, storeName);
        String message = context.getString(R.string.geofence_notif_message,
                items.size(), sb.toString());

        // In-app notification (shown in the bell panel when app is in foreground).
        NotificationStore.getInstance(context).addNotification(
                AppNotification.TYPE_SHOPPING, title, message, 0 /* shopping page */);

        // System status-bar notification (visible when app is in background).
        showSystemNotification(context, storeName, title, message);
    }

    private void showSystemNotification(Context context, String storeName,
                                        String title, String message) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.geofence_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.geofence_channel_desc));
            nm.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_store)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        // Use a per-store notification id so each store can show its own notification.
        int notifId = NOTIF_BASE + (storeName.hashCode() & 0x3FF); // 0–1023 range
        nm.notify(notifId, builder.build());
    }
}
