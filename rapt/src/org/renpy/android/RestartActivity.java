package org.renpy.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * Restarts the app in a new process.
 *
 * It lives in its own process (see the manifest), so it can survive while the process of the
 * app is killed, and then open the app again. The same trick as ProcessPhoenix.
 *
 * Used after the files of the original game are installed: the game starts in a clean process,
 * as it would if the user had closed the app and opened it again.
 */
public class RestartActivity extends Activity {

    private static final String EXTRA_NEXT = "org.renpy.android.RESTART_NEXT";
    private static final String EXTRA_PID = "org.renpy.android.RESTART_PID";

    /** Closes the app and opens it again. It doesn't return. */
    public static void restart(Activity activity) {
        Intent next = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (next == null) {
            return;
        }
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Intent helper = new Intent(activity, RestartActivity.class);
        helper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        helper.putExtra(EXTRA_NEXT, next);
        helper.putExtra(EXTRA_PID, Process.myPid());
        activity.startActivity(helper);

        activity.finish();
        Runtime.getRuntime().exit(0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Intent next = intent.getParcelableExtra(EXTRA_NEXT);
        int pid = intent.getIntExtra(EXTRA_PID, -1);

        // Make sure the old process is gone, so the app doesn't open inside it
        if (pid > 0 && pid != Process.myPid()) {
            Process.killProcess(pid);
        }

        if (next != null) {
            startActivity(next);
        }

        finish();
        Runtime.getRuntime().exit(0);
    }
}
