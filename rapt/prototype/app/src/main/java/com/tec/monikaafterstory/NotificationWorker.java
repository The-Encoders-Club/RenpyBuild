package com.tec.monikaafterstory;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * NotificationWorker — Background fallback worker executed by WorkManager.
 */
public class NotificationWorker extends Worker {

    private static final String TAG = "MAS_WORKER";
    private static final int MAX_RETRIES = 3;

    public NotificationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // Notifications disabled by the user: intentionally do nothing, no retry
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            return Result.success();
        }

        try {
            String title = getInputData().getString("title");
            String message = getInputData().getString("message");
            String action1 = getInputData().getString("action1_label");
            String action2 = getInputData().getString("action2_label");
            String jobTag = getInputData().getString("job_tag");
            boolean sound = getInputData().getBoolean("sound_enabled", true);
            boolean vib = getInputData().getBoolean("vibration_enabled", true);
            String reminderTexts = getInputData().getString("reminder_texts");
            int reminderDelay = getInputData().getInt("reminder_delay", 0);
            String imageName = getInputData().getString("image_name");

            if ((message == null || message.trim().isEmpty()) && jobTag == null) {
                return Result.success();
            }

            if (title == null || title.trim().isEmpty() || title.equals("Monika After Story")) {
                title = NotificationHelper.getMonikaNickname();
            }

            int type = ((action1 != null && !action1.trim().isEmpty()) || (action2 != null && !action2.trim().isEmpty()))
                    ? NotificationHelper.TYPE_INTERACTIVE
                    : NotificationHelper.TYPE_REMINDER;

            if (jobTag != null) {
                Intent intent = new Intent(ctx, ExactAlarmReceiver.class);
                intent.setAction(NotificationHelper.ACTION_TRIGGER_NOTIF);
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                        ctx, jobTag.hashCode(), intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.cancel(pi);
            }

            Notification n = NotificationHelper.buildNotification(
                    ctx, title, message, action1, action2, type, sound, vib,
                    jobTag, reminderTexts, reminderDelay, imageName);

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationHelper.postNotification(ctx, NotificationHelper.NOTIFICATION_ID_STICKY, n);
            }

            return Result.success();

        } catch (SecurityException se) {
            // Missing permission: retrying won't help
            Log.e(TAG, "NotificationWorker permission denied: " + se.getMessage());
            return Result.failure();

        } catch (Exception e) {
            Log.e(TAG, "NotificationWorker failed (attempt " + (getRunAttemptCount() + 1) + "): " + e.getMessage());
            // Let WorkManager retry with backoff, but don't loop forever
            if (getRunAttemptCount() < MAX_RETRIES) {
                return Result.retry();
            }
            return Result.failure();
        }
    }
}
