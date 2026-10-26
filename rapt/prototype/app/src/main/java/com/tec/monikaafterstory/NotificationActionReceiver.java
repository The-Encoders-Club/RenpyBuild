package com.tec.monikaafterstory;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.work.WorkManager;
import org.json.JSONArray;

/**
 * NotificationActionReceiver — Handles user responses and actions from notifications.
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    private static final String TAG = "MAS_ACTION_RCV";

    public static final String ACTION_DISMISS = "com.tec.monikaafterstory.ACTION_DISMISS";
    public static final String ACTION_REPLY   = "com.tec.monikaafterstory.ACTION_REPLY";

    private static boolean isSpanish(Context context) {
        boolean localeEs = "es".equals(java.util.Locale.getDefault().getLanguage());
        try {
            return context.getSharedPreferences("mas_awareness_prefs", Context.MODE_PRIVATE)
                    .getBoolean("is_spanish", localeEs);
        } catch (Exception e) {
            return localeEs;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        try {
            String action = intent.getAction();

            if (ACTION_REPLY.equals(action)) {
                int notifId = intent.getIntExtra("notification_id", -1);

                if (notifId != -1) {
                    NotificationManager nm =
                            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        int smallIconId = NotificationHelper.getSmallIconId(context);

                        NotificationCompat.Builder builder =
                                new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_INTERACTIVE)
                                        .setSmallIcon(smallIconId)
                                        .setOnlyAlertOnce(true)
                                        .setSilent(true)
                                        .setTimeoutAfter(1500);

                        nm.notify(notifId, builder.build());
                    }
                }

                String reminderTextsJson = intent.getStringExtra("reminder_texts");
                int reminderDelay = intent.getIntExtra("reminder_delay", 0);
                String jobTag = intent.getStringExtra("job_tag");
                if (jobTag == null) jobTag = "mas_notification";

                String imageName = intent.getStringExtra("image_name");

                if (reminderTextsJson != null && !reminderTextsJson.isEmpty() && reminderDelay > 0) {
                    try {
                        JSONArray array = new JSONArray(reminderTextsJson);
                        if (array.length() > 0) {
                            String nextMessage = array.getString(0);
                            JSONArray remaining = new JSONArray();
                            for (int i = 1; i < array.length(); i++) {
                                remaining.put(array.getString(i));
                            }

                            String title = intent.getStringExtra("title");
                            String action2 = intent.getStringExtra("action2");
                            // Chained reminders must keep the Reply action: without it the
                            // notification becomes TYPE_REMINDER and MIUI silences it in background
                            if (action2 == null || action2.trim().isEmpty()) {
                                action2 = isSpanish(context)
                                        ? "Responder|Escribe tu respuesta..."
                                        : "Reply|Write your reply...";
                            }

                            NotificationHelper.scheduleWithWorkManager(
                                    context,
                                    title,
                                    nextMessage,
                                    "",
                                    action2,
                                    reminderDelay,
                                    jobTag,
                                    true,
                                    true,
                                    remaining.toString(),
                                    reminderDelay,
                                    imageName
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in chained reminders queue: " + e.getMessage());
                    }
                }

                WorkManager.getInstance(context).cancelAllWorkByTag("TYPE_SLEEP");

            } else if (ACTION_DISMISS.equals(action)) {
                int notifId = intent.getIntExtra("notification_id", -1);
                if (notifId != -1) {
                    NotificationManager nm =
                            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.cancel(notifId);
                    }
                }
                WorkManager.getInstance(context).cancelAllWorkByTag("mas_notification");
                WorkManager.getInstance(context).cancelAllWorkByTag("TYPE_SLEEP");
            }

        } catch (Exception e) {
            Log.e(TAG, "NotificationActionReceiver error: " + e.getMessage());
        }
    }
}
