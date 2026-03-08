package com.kitchenboard.calendar;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kitchenboard.MainActivity;
import com.kitchenboard.R;
import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;

/**
 * Receives an alarm broadcast and displays a reminder notification for an upcoming appointment.
 * Works regardless of which page the user is on, or whether the app is in the foreground.
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "appointment_reminders";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_REMINDER_MINUTES = "reminder_minutes";
    public static final String EXTRA_APPOINTMENT_ID = "appointment_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra(EXTRA_TITLE);
        int reminderMinutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 30);
        long appointmentId = intent.getLongExtra(EXTRA_APPOINTMENT_ID, 0);

        createNotificationChannel(context);

        // Tap on notification opens the app
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(
                context, (int) (appointmentId & 0x7FFFFFFF), launchIntent, piFlags);

        String text = context.getString(R.string.calendar_reminder_text, reminderMinutes, title);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder_notification)
                .setContentTitle(context.getString(R.string.calendar_reminder_notification_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.notify((int) (appointmentId & 0x7FFFFFFF), builder.build());

        // Also post to the in-app notification panel so it is globally visible while the app runs
        NotificationStore.getInstance(context).addNotification(
                AppNotification.TYPE_REMINDER,
                context.getString(R.string.calendar_reminder_notification_title),
                text,
                1 /* CalendarFragment is page 1 */);
    }

    /** Creates the notification channel (no-op on API < 26). */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.calendar_reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.calendar_reminder_channel_desc));
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
