package com.tec.monikaafterstory;

import android.util.Log;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * NotificationHelper — builds, shows and schedules notifications.
 *
 * Scheduling strategy (hybrid):
 *   1. If SCHEDULE_EXACT_ALARM is granted → AlarmManager fires at the exact
 *      time, then ExactAlarmReceiver enqueues an Expedited WorkManager job.
 *   2. If the permission is revoked → falls back to a standard WorkManager
 *      OneTimeWorkRequest with an initial delay.
 */
public class NotificationHelper {

    public static final String TAG = "MAS_HELPER";

    // Channel identifiers
    public static final String CHANNEL_GROUP_ID = "mas_group";
    
    public static final String CHANNEL_ID_INTERACTIVE = "mas_channel_interactive";
    public static final String CHANNEL_ID_GENERAL = "mas_channel_general";
    public static final String CHANNEL_ID_EVENTS = "mas_channel_events";

    // Backward-compatibility aliases
    public static final String CHANNEL_ID_RANDOM = CHANNEL_ID_GENERAL;
    public static final String CHANNEL_ID_BIRTHDAY = CHANNEL_ID_EVENTS;
    public static final String CHANNEL_ID_SPECIAL = CHANNEL_ID_EVENTS;

    // Notification IDs & types
    public static final int NOTIFICATION_ID_STICKY = 777;
    public static final int NOTIF_ID_IMMEDIATE = 1001;
    public static final int TYPE_REMINDER    = 0;
    public static final int TYPE_INTERACTIVE = 1;
    public static final int TYPE_INGAME_EVENT = 2;

    // Intent action used by ExactAlarmReceiver
    public static final String ACTION_TRIGGER_NOTIF =
        "com.tec.monikaafterstory.ACTION_TRIGGER_NOTIF";

    // Shortcut that makes notifications use the conversation layout
    public static final String CONVERSATION_SHORTCUT_ID = "mas_monika";

    // Job tag conventions shared with zz_windowutils.rpy
    public static final String TAG_HOLIDAY_PREFIX = "mas_holiday_";
    public static final String TAG_ALARM_SUFFIX = "#alarm";

    // ---------------------------------------------------------------
    //  Build & Show
    // ---------------------------------------------------------------

    public static boolean isActivityActive = false;
    private static String monikaNickname = "Monika";

    public static void setMonikaNickname(String name) {
        if (name != null && !name.trim().isEmpty()) {
            monikaNickname = name;
        }
    }

    public static String getMonikaNickname() {
        return monikaNickname;
    }

    /**
     * Gets the small notification icon (status bar and conversation badge).
     * It's a white silhouette, as Android tints it. Some OEMs (Xiaomi) show the app icon instead.
     */
    public static int getSmallIconId(Context context) {
        int iconId = context.getResources()
            .getIdentifier("ic_stat_notification", "drawable", context.getPackageName());
        if (iconId == 0) {
            iconId = context.getResources()
                .getIdentifier("ic_stat_smoll", "drawable", context.getPackageName());
        }
        if (iconId == 0) iconId = android.R.drawable.sym_def_app_icon;
        return iconId;
    }

    public static Notification buildNotification(Context context, String title,
            String message, String action1, String action2, int type,
            boolean sound, boolean vibration, String jobTag, String reminderTexts, int reminderDelay) {
        return buildNotification(context, title, message, action1, action2, type, sound, vibration, jobTag, reminderTexts, reminderDelay, null);
    }

    public static Notification buildNotification(Context context, String title,
            String message, String action1, String action2, int type,
            boolean sound, boolean vibration, String jobTag, String reminderTexts, int reminderDelay,
            String imageName) {


        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        initNotificationSkeleton(context);

        // Alarm notifications (sleep, or tags ending in TAG_ALARM_SUFFIX) get the alarm
        // treatment to break through OEM (Xiaomi/MIUI) notification suppression on locked screens
        boolean isSleep = "TYPE_SLEEP".equals(jobTag)
            || (jobTag != null && jobTag.endsWith(TAG_ALARM_SUFFIX));
        boolean isEvent = jobTag != null && jobTag.startsWith(TAG_HOLIDAY_PREFIX);
        String finalChannelId = CHANNEL_ID_GENERAL;
        if (type == TYPE_INTERACTIVE) {
            finalChannelId = CHANNEL_ID_INTERACTIVE;
        } else if (isEvent) {
            finalChannelId = CHANNEL_ID_EVENTS;
        } else if (isSleep) {
            finalChannelId = CHANNEL_ID_INTERACTIVE;
        }

        NotificationCompat.Builder builder =
            new NotificationCompat.Builder(context, finalChannelId);

        int smallIconId = getSmallIconId(context);
        int actionColor = Color.parseColor("#ffbce0");

        builder.setSmallIcon(smallIconId)
               .setColor(actionColor)
               .setPriority(NotificationCompat.PRIORITY_MAX)
               .setCategory(NotificationCompat.CATEGORY_MESSAGE);

        if (sound) {
            int soundId = context.getResources()
                .getIdentifier("notif", "raw", context.getPackageName());
            if (soundId != 0) {
                Uri soundUri = Uri.parse(
                    "android.resource://" + context.getPackageName() + "/" + soundId);
                builder.setSound(soundUri);
            } else {
                builder.setDefaults(Notification.DEFAULT_SOUND);
            }
        }
        if (vibration) {
            builder.setVibrate(new long[] { 0, 250, 250, 250 });
        }

        Intent launchIntent = context.getPackageManager()
            .getLaunchIntentForPackage(context.getPackageName());

        if (launchIntent != null) {
            launchIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.putExtra("launch_from_notif", true);
            PendingIntent piBody = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(piBody);

            // Sleep alarm: use ALARM category to prevent MIUI/OEM from
            // silencing long-delayed notifications on locked screens.
            // (fullScreenIntent intentionally omitted — requires user grant on targetSdk 35)
            if (isSleep) {
                builder.setCategory(NotificationCompat.CATEGORY_ALARM);
            }
        }

        // Profile picture
        Bitmap circularBitmap = null;
        if (imageName != null && !imageName.trim().isEmpty()) {
            File imgFile = null;
            if (imageName.contains("/")) {
                // Must reside within Monika After Story directory
                if (imageName.contains("Monika After Story")) {
                    File candidate = new File(imageName);
                    if (candidate.exists()) {
                        imgFile = candidate;
                    }
                }
            } else {
                File dir = new File("/storage/emulated/0/Monika After Story/notif");
                File f = new File(dir, imageName);
                if (f.exists()) {
                    imgFile = f;
                } else if (!imageName.toLowerCase().endsWith(".png")) {
                    File fPng = new File(dir, imageName + ".png");
                    if (fPng.exists()) imgFile = fPng;
                }
            }

            if (imgFile != null && imgFile.exists() && imgFile.canRead()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    if (bmp != null) {
                        circularBitmap = getCircleBitmap(bmp);
                    }
                } catch (Exception ignored) {}
            }

            if (circularBitmap == null) {
                int resId = context.getResources().getIdentifier(imageName, "drawable", context.getPackageName());
                if (resId != 0) {
                    try {
                        Bitmap bmp = decodeSampledBitmapFromResource(context.getResources(), resId, 256, 256);
                        if (bmp != null) {
                            circularBitmap = getCircleBitmap(bmp);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Default: if no custom image or failed to load, use the app logo (ic_stat_large), NOT Monika
        if (circularBitmap == null) {
            int logoId = context.getResources()
                .getIdentifier("ic_stat_large", "drawable", context.getPackageName());
            if (logoId == 0) {
                logoId = context.getResources()
                    .getIdentifier("ic_stat_smoll", "drawable", context.getPackageName());
            }
            try {
                Bitmap bmp = decodeSampledBitmapFromResource(context.getResources(), logoId, 256, 256);
                if (bmp != null) {
                    circularBitmap = getCircleBitmap(bmp);
                }
            } catch (Exception ignored) {}
        }

        // Sender (Monika)
        Person.Builder personBuilder = new Person.Builder()
            .setKey(CONVERSATION_SHORTCUT_ID)
            .setName(title)
            .setImportant(true);
            
        if (circularBitmap != null) {
            personBuilder.setIcon(IconCompat.createWithBitmap(circularBitmap));
        }
        
        Person monikaUser = personBuilder.build();
        
        // Receiver (Player)
        Person playerUser = new Person.Builder()
            .setName("You")
            .build();

        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(playerUser)
                .setGroupConversation(false)
                .addMessage(message, System.currentTimeMillis(), monikaUser);

        builder.setStyle(style);

        // Conversation layout: Android only shows MessagingStyle notifications as a
        // conversation (one big avatar with the app badge, no header) when they're
        // linked to a long-lived shortcut. postNotification hides it from the launcher again
        try {
            Intent shortcutIntent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
            if (shortcutIntent != null) {
                if (shortcutIntent.getAction() == null) {
                    shortcutIntent.setAction(Intent.ACTION_MAIN);
                }

                String shortcutLabel = (title != null && !title.trim().isEmpty()) ? title : getMonikaNickname();
                ShortcutInfoCompat.Builder shortcutBuilder =
                    new ShortcutInfoCompat.Builder(context, CONVERSATION_SHORTCUT_ID)
                        .setShortLabel(shortcutLabel)
                        .setLongLived(true)
                        .setPerson(monikaUser)
                        .setIntent(shortcutIntent);
                if (circularBitmap != null) {
                    shortcutBuilder.setIcon(IconCompat.createWithBitmap(circularBitmap));
                }

                ShortcutInfoCompat shortcut = shortcutBuilder.build();
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut);
                builder.setShortcutInfo(shortcut);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up the conversation shortcut: " + e.getMessage());
        }

        if (type == TYPE_INTERACTIVE) {
            // Resolve reply label and hint from action2 (format: "Label|Hint" or just hint)
            String replyLabel = "Reply";
            String hintLabel = "Write your reply...";

            if (action2 != null && !action2.trim().isEmpty()) {
                if (action2.contains("|")) {
                    String[] parts = action2.split("\\|");
                    if (parts.length >= 2) {
                        replyLabel = parts[0];
                        hintLabel = parts[1];
                    }
                } else {
                    hintLabel = action2;
                }
            }

            androidx.core.app.RemoteInput remoteInput = new androidx.core.app.RemoteInput.Builder("KEY_TEXT_REPLY")
                    .setLabel(hintLabel)
                    .build();

            Intent replyIntent = new Intent(context, NotificationActionReceiver.class);
            replyIntent.setAction(NotificationActionReceiver.ACTION_REPLY);
            replyIntent.putExtra("notification_id", NOTIFICATION_ID_STICKY);
            if (title != null) replyIntent.putExtra("title", title);
            if (action2 != null) replyIntent.putExtra("action2", action2);
            if (jobTag != null) replyIntent.putExtra("job_tag", jobTag);
            if (reminderTexts != null) replyIntent.putExtra("reminder_texts", reminderTexts);
            replyIntent.putExtra("reminder_delay", reminderDelay);
            if (imageName != null) replyIntent.putExtra("image_name", imageName);

            PendingIntent piReply = PendingIntent.getBroadcast(
                    context, 103, replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                    0, getStyledText(replyLabel, actionColor), piReply)
                    .addRemoteInput(remoteInput)
                    .build();

            builder.addAction(action);

            builder.setOngoing(false);
            builder.setAutoCancel(true);
            builder.setLocalOnly(true);
            builder.setOnlyAlertOnce(true);
            builder.setCategory(NotificationCompat.CATEGORY_ALARM);
        } else {
            builder.setAutoCancel(true);
        }

        return builder.build();
    }

    /**
     * Shows an immediate in-game notification (Window Reactions, alerts).
     *
     * @param context Application context or Activity
     * @param title Title of the notification (e.g. Monika)
     * @param body Body / message of the notification
     * @param imageName Optional custom avatar name or file path (defaults to app icon if null)
     * @return true if notification was dispatched successfully, false otherwise
     */
    public static boolean showImmediateNotification(Context context, String title, String body, String imageName) {
        if (context == null) return false;
        try {
            Notification n = buildNotification(
                context, title, body, null, null,
                TYPE_INGAME_EVENT, true, false, null, null, 0, imageName);
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                postNotification(context, NOTIF_ID_IMMEDIATE, n);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "showImmediateNotification failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Posts a notification built with buildNotification.
     *
     * The conversation shortcut stays cached by the system while the notification exists,
     * so once it's posted we hide it from the launcher's long-press menu.
     * This is delayed because the system processes the notification asynchronously.
     */
    public static void postNotification(Context context, int notifId, Notification n) {
        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        nm.notify(notifId, n);

        final Context appContext = context.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                ShortcutManagerCompat.removeDynamicShortcuts(
                    appContext, Collections.singletonList(CONVERSATION_SHORTCUT_ID));
            } catch (Exception e) {
                Log.e(TAG, "Failed to hide the conversation shortcut: " + e.getMessage());
            }
        }, 3000);
    }

    /**
     * Shows an immediate interactive notification (Reply button), e.g. for Window Reactions.
     * Uses NOTIFICATION_ID_STICKY so the reply flow can replace it.
     *
     * @param action2 Reply button as "Label|Hint"
     * @param reminderTexts Optional JSON array of follow-up messages sent after the player replies
     * @param reminderDelay Seconds between the reply and each follow-up
     * @param jobTag Tag used to schedule (and cancel) the follow-ups
     */
    public static boolean showImmediateInteractiveNotification(Context context, String title, String body,
            String action2, String reminderTexts, int reminderDelay, String jobTag, String imageName) {
        if (context == null) return false;
        try {
            Notification n = buildNotification(
                context, title, body, "", action2,
                TYPE_INTERACTIVE, true, true, jobTag, reminderTexts, reminderDelay, imageName);
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                postNotification(context, NOTIFICATION_ID_STICKY, n);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "showImmediateInteractiveNotification failed: " + e.getMessage());
        }
        return false;
    }

    public static void showNotification(Context context, int notifId,
            String title, String message, String action1, String action2,
            int type, boolean sound, boolean vibration, long timeoutMs) {

        Notification n = buildNotification(
            context, title, message, action1, action2,
            type, sound, vibration, null, null, 0);

        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            postNotification(context, notifId, n);
            if (timeoutMs > 0) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        try { nm.cancel(notifId); } catch (Exception e) { }
                    }, timeoutMs);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Hybrid Scheduling (AlarmManager + WorkManager)
    // ---------------------------------------------------------------

    /**
     * Schedules a notification to fire after {@code delaySeconds}.
     *
     * Path A — exact alarm:
     *   AlarmManager.setExactAndAllowWhileIdle → ExactAlarmReceiver → Expedited Worker
     *
     * Path B — fallback (EXACT_ALARM denied):
     *   WorkManager OneTimeWorkRequest with initial delay
     */
    public static void scheduleExactNotification(Context context,
            String title, String message, String action1, String action2,
            int delaySeconds, String jobTag, boolean sound, boolean vibration,
            String reminderTexts, int reminderDelay) {
        scheduleExactNotification(context, title, message, action1, action2, delaySeconds, jobTag, sound, vibration, reminderTexts, reminderDelay, null);
    }

    public static void scheduleExactNotification(Context context,
            String title, String message, String action1, String action2,
            int delaySeconds, String jobTag, boolean sound, boolean vibration,
            String reminderTexts, int reminderDelay, String imageName) {

        org.json.JSONObject meta = new org.json.JSONObject();
        try {
            meta.put("delay_seconds", delaySeconds);
            meta.put("job_tag", jobTag);
        } catch (Exception ignored) {}

        

        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);
            boolean canScheduleExact = true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                canScheduleExact = am.canScheduleExactAlarms();
            }

            if (canScheduleExact) {
                // --- Path A: Exact alarm ---
                Intent intent = new Intent(context, ExactAlarmReceiver.class);
                intent.setAction(ACTION_TRIGGER_NOTIF);
                intent.putExtra("title", title);
                intent.putExtra("message", message);
                intent.putExtra("action1_label", action1);
                intent.putExtra("action2_label", action2);
                intent.putExtra("job_tag", jobTag);
                intent.putExtra("sound_enabled", sound);
                intent.putExtra("vibration_enabled", vibration);
                if (reminderTexts != null) intent.putExtra("reminder_texts", reminderTexts);
                intent.putExtra("reminder_delay", reminderDelay);
                if (imageName != null) intent.putExtra("image_name", imageName);

                PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    jobTag != null ? jobTag.hashCode() : 0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                long triggerTime = System.currentTimeMillis() + (delaySeconds * 1000L);

                android.app.PendingIntent piShow = null;
                android.content.Intent showIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (showIntent != null) {
                    showIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    piShow = android.app.PendingIntent.getActivity(
                        context,
                        (jobTag != null ? jobTag.hashCode() : 0) + 1,
                        showIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                }
                android.app.AlarmManager.AlarmClockInfo alarmInfo = new android.app.AlarmManager.AlarmClockInfo(triggerTime, piShow);
                am.setAlarmClock(alarmInfo, pi);

                org.json.JSONObject alarmMeta = new org.json.JSONObject();
                try { alarmMeta.put("trigger_time", triggerTime); } catch (Exception ignored) {}
                

            } else {
                // --- Path B: WorkManager fallback ---
                org.json.JSONObject fallbackMeta = new org.json.JSONObject();
                

                androidx.work.Data.Builder dataBuilder = new androidx.work.Data.Builder();
                if (title != null)   dataBuilder.putString("title", title);
                if (message != null) dataBuilder.putString("message", message);
                if (action1 != null) dataBuilder.putString("action1_label", action1);
                if (action2 != null) dataBuilder.putString("action2_label", action2);
                if (jobTag != null)  dataBuilder.putString("job_tag", jobTag);
                dataBuilder.putBoolean("sound_enabled", sound);
                dataBuilder.putBoolean("vibration_enabled", vibration);
                if (reminderTexts != null) dataBuilder.putString("reminder_texts", reminderTexts);
                dataBuilder.putInt("reminder_delay", reminderDelay);
                if (imageName != null) dataBuilder.putString("image_name", imageName);

                androidx.work.OneTimeWorkRequest workRequest =
                    new androidx.work.OneTimeWorkRequest.Builder(NotificationWorker.class)
                        .setInputData(dataBuilder.build())
                        .setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                        .addTag(jobTag != null ? jobTag : "mas_notification")
                        .build();

                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                    jobTag != null ? jobTag : "mas_notification_job",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest);
            }

        } catch (SecurityException se) {
            
        } catch (Exception e) {
            
        }
    }

    // ---------------------------------------------------------------
    //  Channel creation
    // ---------------------------------------------------------------

    private static String getLocalizedText(String key) {
        boolean isEs = Locale.getDefault().getLanguage().equals("es");
        switch (key) {
            case "interactive_name": return isEs ? "Acciones y Respuestas" : "Interactive Actions";
            case "interactive_desc": return isEs ? "Permite que se muestren acciones rápidas y respuestas de despedida." : "Allows quick actions and farewell responses to be displayed.";
            case "general_name": return isEs ? "Notificaciones Generales" : "General Notifications";
            case "general_desc": return isEs ? "Reacciones de ventana, avisos en juego y alertas." : "Window reactions, in-game notices, and alerts.";
            case "events_name": return isEs ? "Eventos y Festividades" : "Events & Holidays";
            case "events_desc": return isEs ? "Avisos sobre días festivos, cumpleaños y fechas importantes." : "Alerts about holidays, birthdays, and important dates.";
            default: return "";
        }
    }

    public static void initNotificationSkeleton(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            
            // Check if all 3 clean semantic channels are already created
            if (nm.getNotificationChannel(CHANNEL_ID_INTERACTIVE) != null
                    && nm.getNotificationChannel(CHANNEL_ID_GENERAL) != null
                    && nm.getNotificationChannel(CHANNEL_ID_EVENTS) != null) {
                return;
            }
            
            // Dynamic purge: delete any channel starting with mas_ that is not one of our 3 clean semantic channels
            try {
                List<NotificationChannel> existingChannels = nm.getNotificationChannels();
                if (existingChannels != null) {
                    Set<String> cleanSet = new HashSet<>();
                    cleanSet.add(CHANNEL_ID_INTERACTIVE);
                    cleanSet.add(CHANNEL_ID_GENERAL);
                    cleanSet.add(CHANNEL_ID_EVENTS);

                    for (NotificationChannel ch : existingChannels) {
                        String id = ch.getId();
                        if (id != null && (id.startsWith("mas_") || id.startsWith("mas_android_channel")) && !cleanSet.contains(id)) {
                            nm.deleteNotificationChannel(id);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Legacy channel purge warning: " + e.getMessage());
            }

            // Also explicitly delete well-known legacy IDs
            String[] oldChannels = {
                "mas_v1", "mas_v2", "mas_v3", "mas_v4", "mas_v5", "mas_v6", "mas_v7", "mas_v8", "mas_v9", "mas_v10",
                "mas_android_channel", "mas_android_channel_v1", "mas_android_channel_v2",
                "mas_android_channel_v3", "mas_android_channel_v4", "mas_android_channel_v5",
                "mas_android_channel_v6", "mas_android_channel_v6_sticky_foreground"
            };
            for (String oldId : oldChannels) {
                try {
                    nm.deleteNotificationChannel(oldId);
                    nm.deleteNotificationChannel(oldId + "_s0v0");
                    nm.deleteNotificationChannel(oldId + "_s0v1");
                    nm.deleteNotificationChannel(oldId + "_s1v0");
                    nm.deleteNotificationChannel(oldId + "_s1v1");
                } catch (Exception ignored) {}
            }
            
            // Create Group
            nm.createNotificationChannelGroup(new NotificationChannelGroup(CHANNEL_GROUP_ID, "Monika After Story"));
            
            // Create New Clean Semantic Channels
            createBaseChannel(context, nm, CHANNEL_ID_INTERACTIVE, getLocalizedText("interactive_name"), getLocalizedText("interactive_desc"));
            createBaseChannel(context, nm, CHANNEL_ID_GENERAL, getLocalizedText("general_name"), getLocalizedText("general_desc"));
            createBaseChannel(context, nm, CHANNEL_ID_EVENTS, getLocalizedText("events_name"), getLocalizedText("events_desc"));
            
            // Log success
            try {
                org.json.JSONObject meta = new org.json.JSONObject();
                meta.put("status", "initialized");
                
            } catch (Exception ignored) {}
        }
    }
    
    private static void createBaseChannel(Context context, NotificationManager nm, String id, String name, String desc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
            chan.setDescription(desc);
            chan.setGroup(CHANNEL_GROUP_ID);
            chan.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            
            // Set default sound/vib logic
            int soundId = context.getResources().getIdentifier("notif", "raw", context.getPackageName());
            Uri soundUri = (soundId != 0) ? Uri.parse("android.resource://" + context.getPackageName() + "/" + soundId) : null;
            AudioAttributes aa = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
            if (soundUri != null) chan.setSound(soundUri, aa);
            
            chan.enableVibration(true);
            chan.setVibrationPattern(new long[] { 0, 250, 250, 250 });
            chan.enableLights(true);
            
            nm.createNotificationChannel(chan);
        }
    }

    // ---------------------------------------------------------------
    //  Bitmap helpers
    // ---------------------------------------------------------------

    private static Bitmap getCircleBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, width, height);
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(0xff424242);
        float radius = Math.min(width, height) / 2.0f;
        canvas.drawCircle(width / 2.0f, height / 2.0f, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    private static CharSequence getStyledText(String text, int color) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(color), 0, s.length(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    public static Bitmap decodeSampledBitmapFromResource(
            android.content.res.Resources res, int resId,
            int reqWidth, int reqHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }

    public static int calculateInSampleSize(BitmapFactory.Options options,
            int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width  = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth  = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                && (halfWidth  / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // ---------------------------------------------------------------
    //  WorkManager-only scheduling (no AlarmManager)
    // ---------------------------------------------------------------

    /**
     * Schedules a notification using WorkManager exclusively.
     * Called from zz_observer.rpy for all in-game notification triggers.
     *
     * The hybrid AlarmManager path (scheduleExactNotification) is kept intact
     * and reserved for future explicit use.
     */
    public static void scheduleWithWorkManager(Context context,
            String title, String message, String action1, String action2,
            int delaySeconds, String jobTag, boolean sound, boolean vibration) {
        scheduleWithWorkManager(context, title, message, action1, action2, delaySeconds, jobTag, sound, vibration, null, 0, null);
    }

    public static void scheduleWithWorkManager(Context context,
            String title, String message, String action1, String action2,
            int delaySeconds, String jobTag, boolean sound, boolean vibration,
            String reminderTexts, int reminderDelay) {
        scheduleWithWorkManager(context, title, message, action1, action2, delaySeconds, jobTag, sound, vibration, reminderTexts, reminderDelay, null);
    }

    public static void scheduleWithWorkManager(Context context,
            String title, String message, String action1, String action2,
            int delaySeconds, String jobTag, boolean sound, boolean vibration,
            String reminderTexts, int reminderDelay, String imageName) {

        
        org.json.JSONObject meta = new org.json.JSONObject();
        try {
            meta.put("trigger_type", "WorkManager+AlarmManager");
            meta.put("delay_seconds", delaySeconds);
            meta.put("job_tag", jobTag != null ? jobTag : "mas_notification");
        } catch (Exception ignored) {}

        

        try {
            String uniqueName = (jobTag != null) ? jobTag : "mas_notification_job";

            // 1. Schedule AlarmManager for precise timing
            android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            boolean canScheduleExact = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                canScheduleExact = am.canScheduleExactAlarms();
            }

            if (canScheduleExact) {
                Intent intent = new Intent(context, ExactAlarmReceiver.class);
                intent.setAction(ACTION_TRIGGER_NOTIF);
                intent.putExtra("title", title);
                intent.putExtra("message", message);
                intent.putExtra("action1_label", action1);
                intent.putExtra("action2_label", action2);
                intent.putExtra("job_tag", jobTag);
                intent.putExtra("sound_enabled", sound);
                intent.putExtra("vibration_enabled", vibration);
                if (reminderTexts != null) intent.putExtra("reminder_texts", reminderTexts);
                intent.putExtra("reminder_delay", reminderDelay);
                if (imageName != null) intent.putExtra("image_name", imageName);

                PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    uniqueName.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                long triggerTime = System.currentTimeMillis() + (delaySeconds * 1000L);
                android.app.PendingIntent piShow = null;
                android.content.Intent showIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (showIntent != null) {
                    showIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    piShow = android.app.PendingIntent.getActivity(
                        context,
                        uniqueName.hashCode() + 1,
                        showIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                }
                android.app.AlarmManager.AlarmClockInfo alarmInfo = new android.app.AlarmManager.AlarmClockInfo(triggerTime, piShow);
                am.setAlarmClock(alarmInfo, pi);
                
                org.json.JSONObject alarmMeta = new org.json.JSONObject();
                try { alarmMeta.put("trigger_time", triggerTime); } catch (Exception ignored) {}
                
            } else {
                org.json.JSONObject fallbackMeta = new org.json.JSONObject();
                
            }

            // 2. Schedule WorkManager as a persistent backup (for reboots)
            androidx.work.Data.Builder data = new androidx.work.Data.Builder();
            if (title   != null) data.putString("title",          title);
            if (message != null) data.putString("message",        message);
            if (action1 != null) data.putString("action1_label",  action1);
            if (action2 != null) data.putString("action2_label",  action2);
            if (jobTag  != null) data.putString("job_tag",        jobTag);
            data.putBoolean("sound_enabled",     sound);
            data.putBoolean("vibration_enabled", vibration);
            if (reminderTexts != null) data.putString("reminder_texts", reminderTexts);
            data.putInt("reminder_delay", reminderDelay);
            if (imageName != null) data.putString("image_name", imageName);

            androidx.work.OneTimeWorkRequest request =
                new androidx.work.OneTimeWorkRequest.Builder(NotificationWorker.class)
                    .setInputData(data.build())
                    .setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .addTag(uniqueName)
                    .build();

            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request);

            org.json.JSONObject successMeta = new org.json.JSONObject();
            try {
                successMeta.put("work_id", request.getId().toString());
                successMeta.put("job_tag", uniqueName);
                successMeta.put("delay_seconds", delaySeconds);
            } catch (Exception ignored) {}
            

        } catch (SecurityException se) {

        } catch (Exception e) {

        }
    }

    /**
     * Cancels a notification scheduled with scheduleWithWorkManager (both the exact
     * alarm and the WorkManager backup). Does nothing if it isn't pending.
     *
     * @param jobTag Same tag used to schedule it
     */
    public static void cancelScheduled(Context context, String jobTag) {
        if (context == null) return;
        String uniqueName = (jobTag != null) ? jobTag : "mas_notification_job";
        try {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(uniqueName);

            Intent intent = new Intent(context, ExactAlarmReceiver.class);
            intent.setAction(ACTION_TRIGGER_NOTIF);
            PendingIntent pi = PendingIntent.getBroadcast(
                context, uniqueName.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);

            android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (pi != null && am != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "cancelScheduled failed: " + e.getMessage());
        }
    }
}