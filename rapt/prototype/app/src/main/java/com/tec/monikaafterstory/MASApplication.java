package com.tec.monikaafterstory;

import android.app.Application;
import android.app.Activity;
import android.app.Application.ActivityLifecycleCallbacks;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.app.PictureInPictureParams;
import android.util.Rational;
import java.io.File;
import java.io.FileInputStream;

/**
 * MASApplication - Application lifecycle and PiP manager.
 */
public class MASApplication extends Application {

    public static MASApplication sInstance;
    private boolean mPipEnabled = false;

    public static void setPipMode(final Activity activity, final boolean enabled) {
        if (sInstance != null && activity != null) {
            sInstance.updatePipState(activity, enabled);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(final Activity activity, Bundle savedInstanceState) {
                try {
                    NotificationHelper.isActivityActive = true;
                } catch (Exception ignored) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    try {
                        final View decorView = activity.getWindow().getDecorView();
                        View.OnApplyWindowInsetsListener insetsListener = new View.OnApplyWindowInsetsListener() {
                            @Override
                            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                                v.setPadding(0, 0, 0, 0);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    return WindowInsets.CONSUMED;
                                } else {
                                    return insets.consumeSystemWindowInsets();
                                }
                            }
                        };
                        decorView.setOnApplyWindowInsetsListener(insetsListener);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                try {
                    NotificationHelper.isActivityActive = true;
                } catch (Exception ignored) {}

                syncPipStateFromFile(activity);
                adjustLayoutForPip(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                try {
                    NotificationHelper.isActivityActive = false;
                } catch (Exception ignored) {}
            }

            @Override
            public void onActivityStopped(Activity activity) {
                try {
                    NotificationHelper.isActivityActive = false;
                } catch (Exception ignored) {}
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                try {
                    NotificationHelper.isActivityActive = false;
                } catch (Exception ignored) {}
            }
        });
    }

    private void syncPipStateFromFile(Activity activity) {
        try {
            File logFile = new File("/storage/emulated/0/Monika After Story/log/.data");
            if (logFile.exists()) {
                FileInputStream fis = new FileInputStream(logFile);
                byte[] data = new byte[(int) logFile.length()];
                fis.read(data);
                fis.close();
                String content = new String(data, "UTF-8").trim();
                boolean enabled = "1".equals(content) || "true".equalsIgnoreCase(content);
                updatePipState(activity, enabled);
            }
        } catch (Exception ignored) {}
    }

    private void refreshPipParams(Activity activity, boolean enabled, View decorView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity.isInPictureInPictureMode()) {
                    return;
                }

                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(16, 9));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(enabled);
                    builder.setSeamlessResizeEnabled(false);

                    if (decorView != null) {
                        int width = decorView.getWidth();
                        int height = decorView.getHeight();
                        if (width > 0 && height > 0) {
                            int targetHeight = (int) (width * 9.0f / 16.0f);
                            if (targetHeight <= height) {
                                int top = (height - targetHeight) / 2;
                                builder.setSourceRectHint(new android.graphics.Rect(0, top, width, top + targetHeight));
                            } else {
                                int targetWidth = (int) (height * 16.0f / 9.0f);
                                int left = (width - targetWidth) / 2;
                                builder.setSourceRectHint(new android.graphics.Rect(left, 0, left + targetWidth, height));
                            }
                        }
                    }
                }

                activity.setPictureInPictureParams(builder.build());
            } catch (Exception ignored) {}
        }
    }

    public void updatePipState(final Activity activity, final boolean enabled) {
        this.mPipEnabled = enabled;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final View decorView = activity.getWindow().getDecorView();
                        if (decorView.getWidth() == 0 || decorView.getHeight() == 0) {
                            decorView.post(new Runnable() {
                                @Override
                                public void run() {
                                    refreshPipParams(activity, enabled, decorView);
                                }
                            });
                        } else {
                            refreshPipParams(activity, enabled, decorView);
                        }
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private android.view.SurfaceView findSurfaceView(View view) {
        if (view instanceof android.view.SurfaceView) {
            return (android.view.SurfaceView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.SurfaceView result = findSurfaceView(vg.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private void adjustLayoutForPip(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (activity instanceof org.renpy.android.PythonSDLActivity) {
                    final org.renpy.android.PythonSDLActivity pyActivity = (org.renpy.android.PythonSDLActivity) activity;
                    if (pyActivity.mVbox != null) {
                        pyActivity.mVbox.setPadding(0, 0, 0, 0);
                        pyActivity.mVbox.setFitsSystemWindows(false);
                        
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    boolean inPipMode = pyActivity.isInPictureInPictureMode();
                                    
                                    ViewGroup.LayoutParams vboxLp = pyActivity.mVbox.getLayoutParams();
                                    if (vboxLp instanceof ViewGroup.MarginLayoutParams) {
                                        ((ViewGroup.MarginLayoutParams) vboxLp).setMargins(0, 0, 0, 0);
                                    }
                                    pyActivity.mVbox.setLayoutParams(vboxLp);

                                    if (pyActivity.mFrameLayout != null) {
                                        pyActivity.mFrameLayout.setPadding(0, 0, 0, 0);
                                        pyActivity.mFrameLayout.setFitsSystemWindows(false);
                                        
                                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pyActivity.mFrameLayout.getLayoutParams();
                                        lp.setMargins(0, 0, 0, 0);
                                        if (inPipMode) {
                                            makeViewFillParent(pyActivity.mFrameLayout);
                                            
                                            android.view.SurfaceView sv = findSurfaceView(pyActivity.mFrameLayout);
                                            if (sv != null) {
                                                sv.getHolder().setFixedSize(1280, 720);
                                                sv.setScaleY(1.0f);
                                                sv.setScaleX(1.0f);
                                            }
                                        } else {
                                            lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                                            lp.height = 0;
                                            lp.weight = 1.0f;
                                            pyActivity.mFrameLayout.setLayoutParams(lp);
                                            
                                            android.view.SurfaceView sv = findSurfaceView(pyActivity.mFrameLayout);
                                            if (sv != null) {
                                                sv.setScaleY(1.0f);
                                                sv.setScaleX(1.0f);
                                                sv.getHolder().setSizeFromLayout();
                                            }
                                        }
                                    }

                                    for (int i = 0; i < pyActivity.mVbox.getChildCount(); i++) {
                                        View child = pyActivity.mVbox.getChildAt(i);
                                        if (child != pyActivity.mFrameLayout) {
                                            child.setVisibility(inPipMode ? View.GONE : View.VISIBLE);
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void makeViewFillParent(View view) {
        if (view == null) return;
        view.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            view.setFitsSystemWindows(false);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
            }
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).weight = 0.0f;
            }
            view.setLayoutParams(lp);
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                makeViewFillParent(vg.getChildAt(i));
            }
        }
    }
}
