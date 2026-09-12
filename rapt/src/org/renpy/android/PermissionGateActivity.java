package org.renpy.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.NinePatch;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * PermissionGateActivity
 *
 * Pre-launch gateway activity (LAUNCHER) that requests mandatory external storage
 * and notification permissions before the Ren'Py engine (PythonSDLActivity) initializes.
 *
 * Features:
 * - 100% solid pure black background (#000000) on window, root layout, and system bars.
 * - Consecutive permission workflow on "Aceptar": requests notifications immediately followed by storage.
 * - Dialogue textbox (mod_assets/panels/textbox.png) with fixed non-shifting layout and typewriter effect.
 * - Authentic mod buttons (mod_assets/buttons/hkb_idle_background.png and hkb_hover_background.png).
 * - Reliable audio engine for hover (button_hover_day.ogg) and click (button_click_day.ogg).
 * - Accent-free text to ensure compatibility with custom fonts lacking accented glyphs.
 */
public class PermissionGateActivity extends Activity {

    private static final String TAG = "PermissionGate";
    private static final int REQUEST_CODE_MANAGE_STORAGE = 1001;
    private static final int REQUEST_CODE_LEGACY_PERMISSIONS = 1002;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1003;

    // Mod asset paths
    private static final String ASSET_TEXTBOX_MOD = "x-game/x-mod_assets/x-panels/x-textbox.png";
    private static final String ASSET_TEXTBOX_FALLBACK = "x-game/x-gui/x-textbox.png";
    private static final String ASSET_CHOICE_IDLE = "x-game/x-mod_assets/x-buttons/x-hkb_idle_background.png";
    private static final String ASSET_CHOICE_HOVER = "x-game/x-mod_assets/x-buttons/x-hkb_hover_background.png";
    private static final String ASSET_FONT = "x-game/x-mod_assets/x-fonts/x-natsuki.ttf";
    private static final String ASSET_SFX_HOVER = "x-game/x-mod_assets/x-buttons/x-sounds/x-button_hover_day.ogg";
    private static final String ASSET_SFX_CONFIRM = "x-game/x-mod_assets/x-buttons/x-sounds/x-button_click_day.ogg";

    // Text colors matching DDLC / Just Natsuki
    private static final int COLOR_CHOICE_IDLE = Color.parseColor("#e2d1d1");
    private static final int COLOR_CHOICE_HOVER = Color.parseColor("#FF8ED0");
    private static final int COLOR_TEXTBOX_TEXT = Color.parseColor("#ffffff");

    private boolean isRequestingStorage = false;
    private ResourceManager resourceManager;

    private SoundPool soundPool;
    private int soundHoverId = -1;
    private int soundConfirmId = -1;
    private File hoverSoundCacheFile = null;
    private File confirmSoundCacheFile = null;

    private Drawable drawableChoiceIdle;
    private Drawable drawableChoiceHover;
    private Typeface natsukiTypeface;

    private Handler typewriterHandler;
    private Runnable typewriterRunnable;
    private int typewriterIndex = 0;
    private String fullDialogueText = "";
    private TextView dialogueTextView;

    private String getAppName() {
        if (resourceManager == null) {
            resourceManager = new ResourceManager(this);
        }
        String appName = resourceManager.getString("appName");
        if (appName == null || appName.isEmpty()) {
            appName = resourceManager.getString("app_name");
        }
        if (appName == null || appName.isEmpty()) {
            appName = "Just Natsuki";
        }
        return appName;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resourceManager = new ResourceManager(this);

        // Keep screen on during permission gate
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Force 100% solid pure black background on window
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        // Hide navigation and status bars for full immersive feeling
        hideSystemUI();

        // If storage permission is already granted, proceed immediately without displaying the gate
        if (hasStoragePermission()) {
            createExternalStorageDirectories();
            checkNotificationPermissionAndProceed();
            return;
        }

        // Initialize audio engine and UI resources
        initAudio();
        buildPermissionGateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();

        // Returning from Android 11+ system settings (MANAGE_EXTERNAL_STORAGE)
        if (isRequestingStorage) {
            isRequestingStorage = false;
            if (hasStoragePermission()) {
                createExternalStorageDirectories();
                launchPythonSDLActivity();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (typewriterHandler != null && typewriterRunnable != null) {
            typewriterHandler.removeCallbacks(typewriterRunnable);
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    private void hideSystemUI() {
        try {
            View decorView = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decorView.setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
    }

    private void initAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                soundPool = new SoundPool.Builder()
                        .setMaxStreams(4)
                        .setAudioAttributes(attrs)
                        .build();
            } else {
                soundPool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
            }

            soundHoverId = loadSoundReliably(ASSET_SFX_HOVER, true);
            soundConfirmId = loadSoundReliably(ASSET_SFX_CONFIRM, false);
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize SoundPool", e);
        }
    }

    /**
     * Loads sound into SoundPool reliably, caching compressed APK streams to disk when needed.
     */
    private int loadSoundReliably(String assetPath, boolean isHover) {
        if (soundPool == null) return -1;
        // First try standard AssetFileDescriptor
        try {
            AssetFileDescriptor afd = getAssets().openFd(assetPath);
            return soundPool.load(afd, 1);
        } catch (Exception e1) {
            // Fallback for compressed assets in APK: copy to cache directory
            try (InputStream is = getAssets().open(assetPath)) {
                File cacheFile = new File(getCacheDir(), "sfx_" + (isHover ? "hover.ogg" : "click.ogg"));
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.flush();
                }
                if (isHover) {
                    hoverSoundCacheFile = cacheFile;
                } else {
                    confirmSoundCacheFile = cacheFile;
                }
                return soundPool.load(cacheFile.getAbsolutePath(), 1);
            } catch (Exception e2) {
                Log.w(TAG, "Failed to load sound asset: " + assetPath, e2);
            }
        }
        return -1;
    }

    private void playSfx(int soundId, boolean isHover) {
        if (soundPool != null && soundId != -1) {
            int streamId = soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
            if (streamId != 0) {
                return;
            }
        }
        // Fallback MediaPlayer playback if SoundPool stream was not ready
        File cacheFile = isHover ? hoverSoundCacheFile : confirmSoundCacheFile;
        if (cacheFile != null && cacheFile.exists()) {
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(cacheFile.getAbsolutePath());
                mp.prepare();
                mp.setOnCompletionListener(MediaPlayer::release);
                mp.start();
            } catch (Exception ignored) {
            }
        }
    }

    private Drawable loadAssetDrawable(String assetPath) {
        try (InputStream is = getAssets().open(assetPath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                byte[] chunk = bitmap.getNinePatchChunk();
                if (chunk != null && NinePatch.isNinePatchChunk(chunk)) {
                    return new NinePatchDrawable(getResources(), bitmap, chunk, null, null);
                }
                return new BitmapDrawable(getResources(), bitmap);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not load asset bitmap: " + assetPath, e);
        }
        return null;
    }

    private void buildPermissionGateUI() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scaleFactor = (float) dm.heightPixels / 720.0f;

        // Load custom Natsuki typeface if present
        try {
            natsukiTypeface = Typeface.createFromAsset(getAssets(), ASSET_FONT);
        } catch (Exception e) {
            Log.w(TAG, "Could not load natsuki.ttf from assets, using default sans-serif", e);
            natsukiTypeface = Typeface.SANS_SERIF;
        }

        drawableChoiceIdle = loadAssetDrawable(ASSET_CHOICE_IDLE);
        drawableChoiceHover = loadAssetDrawable(ASSET_CHOICE_HOVER);

        // Root container: fullscreen canvas with pure solid black background
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. BUTTON CONTAINER (Lowered vertically for better visual balance)
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        // Position buttons lower down (~28% from top, matching Ren'Py choice layout)
        buttonParams.topMargin = (int) (180 * scaleFactor);
        buttonContainer.setLayoutParams(buttonParams);

        boolean isSpanish = Locale.getDefault().getLanguage().equalsIgnoreCase("es");
        String acceptText = isSpanish ? "Aceptar" : "Accept";
        String denyText = isSpanish ? "Denegar" : "Deny";

        // Accept button triggers consecutive actions: notifications first if needed, then storage
        View acceptButton = createChoiceButton(acceptText, () -> {
            playSfx(soundConfirmId, false);
            onAcceptClicked();
        });

        View denyButton = createChoiceButton(denyText, () -> {
            playSfx(soundConfirmId, false);
            exitApp();
        });

        // Vertical spacing between buttons (14dp scaled)
        LinearLayout.LayoutParams btnLayoutParam = (LinearLayout.LayoutParams) acceptButton.getLayoutParams();
        btnLayoutParam.bottomMargin = (int) (14 * scaleFactor);
        acceptButton.setLayoutParams(btnLayoutParam);

        buttonContainer.addView(acceptButton);
        buttonContainer.addView(denyButton);
        rootLayout.addView(buttonContainer);

        // 2. BOTTOM TEXTBOX CONTAINER (Strictly fixed width & height to prevent shifting)
        int tbWidth = (int) (816 * scaleFactor);
        int tbHeight = (int) (146 * scaleFactor);

        FrameLayout textboxContainer = new FrameLayout(this);
        FrameLayout.LayoutParams tbContainerParams = new FrameLayout.LayoutParams(tbWidth, tbHeight);
        tbContainerParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        tbContainerParams.bottomMargin = (int) (10 * scaleFactor);
        textboxContainer.setLayoutParams(tbContainerParams);

        // Textbox background image (from mod assets)
        ImageView textboxBg = new ImageView(this);
        Drawable tbDrawable = loadAssetDrawable(ASSET_TEXTBOX_MOD);
        if (tbDrawable == null) {
            tbDrawable = loadAssetDrawable(ASSET_TEXTBOX_FALLBACK);
        }
        if (tbDrawable != null) {
            textboxBg.setImageDrawable(tbDrawable);
            textboxBg.setScaleType(ImageView.ScaleType.FIT_XY);
        }
        textboxBg.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        textboxContainer.addView(textboxBg);

        // Dialogue text inside textbox
        dialogueTextView = new TextView(this);
        if (natsukiTypeface != null) {
            dialogueTextView.setTypeface(natsukiTypeface);
        }
        dialogueTextView.setTextColor(COLOR_TEXTBOX_TEXT);
        // Say text size matching native Ren'Py 22-23sp scaled
        dialogueTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22.0f * scaleFactor);
        dialogueTextView.setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, dm), 1.0f);
        dialogueTextView.setShadowLayer(3.0f, 1.0f, 1.0f, Color.parseColor("#000000"));
        dialogueTextView.setGravity(Gravity.TOP | Gravity.START);
        dialogueTextView.setMaxLines(4);

        // Inner margins for the dialogue text inside the 816x146 box
        FrameLayout.LayoutParams tvParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        tvParams.leftMargin = (int) (38 * scaleFactor);
        tvParams.rightMargin = (int) (38 * scaleFactor);
        tvParams.topMargin = (int) (18 * scaleFactor);
        tvParams.bottomMargin = (int) (14 * scaleFactor);
        dialogueTextView.setLayoutParams(tvParams);

        textboxContainer.addView(dialogueTextView);
        rootLayout.addView(textboxContainer);

        setContentView(rootLayout);

        // 3. DIALOGUE TEXT (Strictly accent-free to prevent missing character gaps)
        if (isSpanish) {
            fullDialogueText = "Just Natsuki requiere permisos de almacenamiento para gestionar tus partidas, submods y regalos en la memoria externa. Sin este permiso, la aplicacion no puede iniciar.";
        } else {
            fullDialogueText = "Just Natsuki requires storage permissions to manage your save data, submods, and gifts in external storage. Without this permission, the application cannot start.";
        }

        startTypewriterEffect();
    }

    private View createChoiceButton(String text, Runnable onClick) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scaleFactor = (float) dm.heightPixels / 720.0f;

        TextView button = new TextView(this);
        button.setText(text);
        if (natsukiTypeface != null) {
            button.setTypeface(natsukiTypeface);
        }
        button.setTextColor(COLOR_CHOICE_IDLE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18.0f * scaleFactor);
        button.setGravity(Gravity.CENTER);

        if (drawableChoiceIdle != null) {
            button.setBackground(drawableChoiceIdle.getConstantState().newDrawable());
        }

        int btnWidth = (int) (400 * scaleFactor);
        int btnHeight = (int) (42 * scaleFactor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnWidth, btnHeight);
        button.setLayoutParams(lp);

        button.setOnTouchListener((v, event) -> {
            float x = event.getX();
            float y = event.getY();
            boolean isInside = (x >= 0 && x <= v.getWidth() && y >= 0 && y <= v.getHeight());

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    playSfx(soundHoverId, true);
                    if (drawableChoiceHover != null) {
                        button.setBackground(drawableChoiceHover.getConstantState().newDrawable());
                    }
                    button.setTextColor(COLOR_CHOICE_HOVER);
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (isInside) {
                        if (drawableChoiceHover != null) {
                            button.setBackground(drawableChoiceHover.getConstantState().newDrawable());
                        }
                        button.setTextColor(COLOR_CHOICE_HOVER);
                    } else {
                        if (drawableChoiceIdle != null) {
                            button.setBackground(drawableChoiceIdle.getConstantState().newDrawable());
                        }
                        button.setTextColor(COLOR_CHOICE_IDLE);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (drawableChoiceIdle != null) {
                        button.setBackground(drawableChoiceIdle.getConstantState().newDrawable());
                    }
                    button.setTextColor(COLOR_CHOICE_IDLE);
                    if (isInside) {
                        v.performClick();
                        if (onClick != null) {
                            onClick.run();
                        }
                    }
                    break;

                case MotionEvent.ACTION_CANCEL:
                    if (drawableChoiceIdle != null) {
                        button.setBackground(drawableChoiceIdle.getConstantState().newDrawable());
                    }
                    button.setTextColor(COLOR_CHOICE_IDLE);
                    break;
            }
            return true;
        });

        return button;
    }

    private void startTypewriterEffect() {
        typewriterHandler = new Handler(Looper.getMainLooper());
        typewriterIndex = 0;
        dialogueTextView.setText("");

        // Approximately 35 characters per second (~28ms per character)
        final long delayMillis = 28;

        typewriterRunnable = new Runnable() {
            @Override
            public void run() {
                if (typewriterIndex < fullDialogueText.length()) {
                    typewriterIndex++;
                    dialogueTextView.setText(fullDialogueText.substring(0, typewriterIndex));
                    typewriterHandler.postDelayed(this, delayMillis);
                }
            }
        };

        typewriterHandler.postDelayed(typewriterRunnable, 100);
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            int writePerm = checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            int readPerm = checkCallingOrSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
            return (writePerm == PackageManager.PERMISSION_GRANTED) &&
                   (readPerm == PackageManager.PERMISSION_GRANTED);
        }
    }

    private boolean needsNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkCallingOrSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQUEST_CODE_POST_NOTIFICATIONS);
        }
    }

    private void checkNotificationPermissionAndProceed() {
        if (needsNotificationPermission()) {
            requestNotificationPermission();
        } else {
            ensureAppDirectoriesAndLaunch();
        }
    }

    /**
     * Executes consecutive permission requests when the user taps "Aceptar".
     * Notification permission is requested first (if needed on Android 13+),
     * and as soon as it completes, storage permission is immediately requested.
     */
    private void onAcceptClicked() {
        if (needsNotificationPermission()) {
            requestNotificationPermission();
        } else {
            requestStoragePermission();
        }
    }

    private void requestStoragePermission() {
        if (hasStoragePermission()) {
            createExternalStorageDirectories();
            checkNotificationPermissionAndProceed();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                isRequestingStorage = true;
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
            } catch (Exception e) {
                Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(fallback, REQUEST_CODE_MANAGE_STORAGE);
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQUEST_CODE_LEGACY_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            // Notification response received, immediately proceed to storage permission
            if (!hasStoragePermission()) {
                requestStoragePermission();
            } else {
                createExternalStorageDirectories();
                launchPythonSDLActivity();
            }
        } else if (requestCode == REQUEST_CODE_LEGACY_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                createExternalStorageDirectories();
                launchPythonSDLActivity();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (hasStoragePermission()) {
                createExternalStorageDirectories();
                launchPythonSDLActivity();
            }
        }
    }

    private void ensureAppDirectoriesAndLaunch() {
        createExternalStorageDirectories();
        launchPythonSDLActivity();
    }

    private void createExternalStorageDirectories() {
        try {
            File extDir = Environment.getExternalStorageDirectory();
            String appDirName = getAppName();

            String[] dirsToCreate = new String[]{
                    "",
                    "game",
                    "game/saves",
                    "game/Submods",
                    "characters"
            };

            for (String sub : dirsToCreate) {
                File dir = sub.isEmpty() ? new File(extDir, appDirName) : new File(extDir, appDirName + "/" + sub);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create external directories", e);
        }
    }

    private void launchPythonSDLActivity() {
        try {
            Intent intent = new Intent(this, PythonSDLActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch PythonSDLActivity", e);
        }
    }

    private void exitApp() {
        finishAffinity();
        System.exit(0);
    }
}
