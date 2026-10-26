package com.tec.monikaafterstory;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import java.util.List;

/**
 * AwarenessService — Static utility for querying UsageStatsManager.
 *
 * This is NOT an Android Service. Ren'Py calls it through JNI to know which
 * app is in the foreground (Window Reactions on Android).
 */
public class AwarenessService {

    private static final String TAG = "MAS_AWARENESS";

    // ---------------------------------------------------------------
    // Permission Checks
    // ---------------------------------------------------------------

    /**
     * Checks whether the app has been granted Usage Access (PACKAGE_USAGE_STATS).
     * This is a "special" permission that must be enabled in system settings.
     */
    public static boolean hasUsagePermission(Context context) {
        try {
            AppOpsManager aom = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName());
            return (mode == AppOpsManager.MODE_ALLOWED);
        } catch (Exception e) {
            Log.w(TAG, "hasUsagePermission check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Opens the system Settings screen for Usage Access permission.
     */
    public static void requestUsagePermission(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "requestUsagePermission failed: " + e.getMessage());
        }
    }

    /**
     * Gets the package name of the currently active foreground application.
     * Used by Monika After Story to react to open apps (Window Reactions).
     *
     * @param context Application context or Activity
     * @return Package name of the active foreground app, or null if not found / no permission
     */
    public static String getForegroundPackage(Context context) {
        if (context == null || !hasUsagePermission(context)) {
            return null;
        }
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }

            long now = System.currentTimeMillis();
            long startTime = now - (15 * 60 * 1000L); // 15 minutes lookback

            UsageEvents events = usm.queryEvents(startTime, now);
            if (events == null) {
                return null;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            String ownPackage = context.getPackageName();
            java.util.Map<String, Long> activeForeground = new java.util.HashMap<>();

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                if (pkg == null || pkg.equals(ownPackage) || pkg.startsWith("com.android.systemui")) {
                    continue;
                }

                int type = event.getEventType();
                long timestamp = event.getTimeStamp();

                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    activeForeground.put(pkg, timestamp);
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    activeForeground.remove(pkg);
                }
            }

            String currentForeground = null;
            long maxForegroundTime = 0;
            for (java.util.Map.Entry<String, Long> entry : activeForeground.entrySet()) {
                if (entry.getValue() >= maxForegroundTime) {
                    maxForegroundTime = entry.getValue();
                    currentForeground = entry.getKey();
                }
            }

            // Fallback: If no open active foreground found in events, check UsageStats with latest lastTimeUsed
            if (currentForeground == null) {
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now);
                if (stats != null) {
                    long recentTime = 0;
                    for (UsageStats u : stats) {
                        String pkg = u.getPackageName();
                        if (pkg == null || pkg.equals(ownPackage) || pkg.startsWith("com.android.systemui")) {
                            continue;
                        }
                        if (u.getLastTimeUsed() > recentTime) {
                            recentTime = u.getLastTimeUsed();
                            currentForeground = pkg;
                        }
                    }
                }
            }

            return currentForeground;
        } catch (Exception e) {
            Log.e(TAG, "getForegroundPackage failed: " + e.getMessage());
            return null;
        }
    }
}
