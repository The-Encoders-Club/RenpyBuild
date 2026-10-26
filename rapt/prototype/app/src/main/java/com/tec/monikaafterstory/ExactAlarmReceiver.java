package com.tec.monikaafterstory;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.WorkManager;

/**
 * ExactAlarmReceiver — BroadcastReceiver for exact alarms from AlarmManager.
 */
public class ExactAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "MAS_EXACT_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !NotificationHelper.ACTION_TRIGGER_NOTIF.equals(intent.getAction())) {
            return;
        }

        // Notifications disabled by the user: intentionally do nothing
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return;
        }

        try {
            String title = intent.getStringExtra("title");
            String message = intent.getStringExtra("message");
            String action1 = intent.getStringExtra("action1_label");
            String action2 = intent.getStringExtra("action2_label");
            String jobTag = intent.getStringExtra("job_tag");
            boolean soundEnabled = intent.getBooleanExtra("sound_enabled", true);
            boolean vibrationEnabled = intent.getBooleanExtra("vibration_enabled", true);
            String reminderTexts = intent.getStringExtra("reminder_texts");
            int reminderDelay = intent.getIntExtra("reminder_delay", 0);
            String imageName = intent.getStringExtra("image_name");

            String uniqueName = (jobTag != null) ? jobTag : "mas_notification_job";
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName);

            if ((message == null || message.trim().isEmpty()) && jobTag == null) {
                return;
            }

            if (title == null || title.trim().isEmpty() || title.equals("Monika After Story")) {
                title = NotificationHelper.getMonikaNickname();
            }

            int type = ((action1 != null && !action1.trim().isEmpty()) || (action2 != null && !action2.trim().isEmpty()))
                    ? NotificationHelper.TYPE_INTERACTIVE
                    : NotificationHelper.TYPE_REMINDER;

            Notification n = NotificationHelper.buildNotification(
                    context, title, message, action1, action2, type, soundEnabled, vibrationEnabled,
                    jobTag, reminderTexts, reminderDelay, imageName);

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationHelper.postNotification(context, NotificationHelper.NOTIFICATION_ID_STICKY, n);
            }

        } catch (Exception e) {
            Log.e(TAG, "ExactAlarmReceiver error: " + e.getMessage());
        }
    }
}
