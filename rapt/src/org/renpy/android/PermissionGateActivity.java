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
 * Designed for Monika After Story (MAS):
 * - Pure solid black background (#000000) for immersive aesthetic.
 * - Dialogue textbox (game/gui/textbox.png / textbox_d.png / textbox_monika_d.png).
 * - Choice menu buttons styled with DDLC aesthetics and emerald green hover accent (#5DE23C).
 * - Full automatic initialization of public storage directories in /sdcard/Monika After Story/.
 * - Consecutive permission workflow on "Aceptar": notifications first, followed immediately by storage.
 * - Accent-free text to ensure compatibility with custom ASCII DDLC fonts.
 */
public class PermissionGateActivity extends Activity {

    private static final String TAG = "PermissionGate";
    private static final int REQUEST_CODE_MANAGE_STORAGE = 1001;
    private static final int REQUEST_CODE_LEGACY_PERMISSIONS = 1002;
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1003;

    // DDLC / MAS asset paths with x- prefix applied by RAPT packaging
    private static final String ASSET_TEXTBOX_PRIMARY = "x-game/x-gui/x-textbox.png";
    private static final String ASSET_TEXTBOX_DARK = "x-game/x-gui/x-textbox_d.png";
    private static final String ASSET_TEXTBOX_MONIKA = "x-game/x-gui/x-textbox_monika_d.png";
    private static final String ASSET_CHOICE_IDLE = "x-game/x-gui/x-button/x-choice_idle_background.png";
    private static final String ASSET_CHOICE_HOVER = "x-game/x-gui/x-button/x-choice_hover_background.png";
    private static final String ASSET_CHOICE_DARK_IDLE = "x-game/x-gui/x-button/x-choice_dark_idle_background.png";
    private static final String ASSET_CHOICE_DARK_HOVER = "x-game/x-gui/x-button/x-choice_dark_hover_background.png";
    private static final String ASSET_FONT_M1 = "x-game/x-mod_assets/x-font/x-m1_fixed.ttf";
    private static final String ASSET_FONT_M1_ES = "x-game/x-tl/x-spanish/x-m1_fixed.ttf";
    private static final String ASSET_FONT_ROOT = "x-game/x-m1.ttf";
    private static final String ASSET_SFX_HOVER = "x-game/x-mod_assets/x-buttons/x-sounds/x-button_hover_day.ogg";
    private static final String ASSET_SFX_CONFIRM = "x-game/x-mod_assets/x-buttons/x-sounds/x-button_click_day.ogg";

    // Palette matching Monika / DDLC UI
    private static final int COLOR_CHOICE_IDLE = Color.parseColor("#e2d1d1");
    private static final int COLOR_CHOICE_HOVER = Color.parseColor("#5DE23C"); // Emerald green
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
    private Typeface monikaTypeface;

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
            appName = "Monika After Story";
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
        try {
            AssetFileDescriptor afd = getAssets().openFd(assetPath);
            return soundPool.load(afd, 1);
        } catch (Exception e1) {
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
                Log.w(TAG, "Could not load sound asset: " + assetPath);
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
            // Asset not found or alternative path
        }
        return null;
    }

    private void buildPermissionGateUI() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scaleFactor = (float) dm.heightPixels / 720.0f;

        // Attempt to load Monika custom font
        String[] fontCandidates = new String[]{
                ASSET_FONT_M1,
                ASSET_FONT_M1_ES,
                ASSET_FONT_ROOT
        };
        for (String fPath : fontCandidates) {
            try {
                monikaTypeface = Typeface.createFromAsset(getAssets(), fPath);
                if (monikaTypeface != null) break;
            } catch (Exception ignored) {
            }
        }
        if (monikaTypeface == null) {
            monikaTypeface = Typeface.SANS_SERIF;
        }

        // Load DDLC choice button backgrounds (with dark / fallback options)
        drawableChoiceIdle = loadAssetDrawable(ASSET_CHOICE_IDLE);
        if (drawableChoiceIdle == null) {
            drawableChoiceIdle = loadAssetDrawable(ASSET_CHOICE_DARK_IDLE);
        }
        drawableChoiceHover = loadAssetDrawable(ASSET_CHOICE_HOVER);
        if (drawableChoiceHover == null) {
            drawableChoiceHover = loadAssetDrawable(ASSET_CHOICE_DARK_HOVER);
        }

        // Root container: fullscreen canvas with pure solid black background
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. BUTTON CONTAINER (Centered horizontally, positioned ~28% from top)
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
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

        LinearLayout.LayoutParams btnLayoutParam = (LinearLayout.LayoutParams) acceptButton.getLayoutParams();
        btnLayoutParam.bottomMargin = (int) (14 * scaleFactor);
        acceptButton.setLayoutParams(btnLayoutParam);

        buttonContainer.addView(acceptButton);
        buttonContainer.addView(denyButton);
        rootLayout.addView(buttonContainer);

        // 2. BOTTOM TEXTBOX CONTAINER (Strictly fixed width & height: 816x146 scaled)
        int tbWidth = (int) (816 * scaleFactor);
        int tbHeight = (int) (146 * scaleFactor);

        FrameLayout textboxContainer = new FrameLayout(this);
        FrameLayout.LayoutParams tbContainerParams = new FrameLayout.LayoutParams(tbWidth, tbHeight);
        tbContainerParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        tbContainerParams.bottomMargin = (int) (10 * scaleFactor);
        textboxContainer.setLayoutParams(tbContainerParams);

        // Textbox background image
        ImageView textboxBg = new ImageView(this);
        Drawable tbDrawable = loadAssetDrawable(ASSET_TEXTBOX_PRIMARY);
        if (tbDrawable == null) {
            tbDrawable = loadAssetDrawable(ASSET_TEXTBOX_MONIKA);
        }
        if (tbDrawable == null) {
            tbDrawable = loadAssetDrawable(ASSET_TEXTBOX_DARK);
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
        if (monikaTypeface != null) {
            dialogueTextView.setTypeface(monikaTypeface);
        }
        dialogueTextView.setTextColor(COLOR_TEXTBOX_TEXT);
        dialogueTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22.0f * scaleFactor);
        dialogueTextView.setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, dm), 1.0f);
        dialogueTextView.setShadowLayer(3.0f, 1.0f, 1.0f, Color.parseColor("#000000"));
        dialogueTextView.setGravity(Gravity.TOP | Gravity.START);
        dialogueTextView.setMaxLines(4);

        // Inner margins for the dialogue text inside the box
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

        // 3. DIALOGUE TEXT (Strictly accent-free to prevent missing character gaps with ASCII fonts)
        if (isSpanish) {
            fullDialogueText = "Monika After Story requiere permisos de almacenamiento para gestionar tus partidas, regalos y submods en la memoria externa. Sin este permiso, la aplicacion no puede iniciar.";
        } else {
            fullDialogueText = "Monika After Story requires storage permissions to manage your save data, gifts, and submods in external storage. Without this permission, the application cannot start.";
        }

        startTypewriterEffect();
    }

    private View createChoiceButton(String text, Runnable onClick) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scaleFactor = (float) dm.heightPixels / 720.0f;

        TextView button = new TextView(this);
        button.setText(text);
        if (monikaTypeface != null) {
            button.setTypeface(monikaTypeface);
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

            File basePublic = new File(extDir, appDirName);
            if (basePublic.exists() || basePublic.mkdirs()) {
                File nomedia = new File(basePublic, ".nomedia");
                if (!nomedia.exists()) {
                    nomedia.createNewFile();
                }
            }

            // Comprehensive directory structure for Monika After Story
            String[] dirsToCreate = new String[]{
                    "",
                    "characters",
                    "chess_games",
                    "log",
                    "logs",
                    "custom_bgm",
                    "game",
                    "game/saves",
                    "game/Submods",
                    "game/python-packages",
                    "game/mod_assets",
                    "game/mod_assets/location",
                    "game/mod_assets/thumbs",
                    "game/mod_assets/monika",
                    "game/mod_assets/monika/a",
                    "game/mod_assets/monika/c",
                    "game/mod_assets/monika/f",
                    "game/mod_assets/monika/h",
                    "game/mod_assets/monika/j",
                    "game/mod_assets/monika/t"
            };

            for (String sub : dirsToCreate) {
                File dir = sub.isEmpty() ? basePublic : new File(basePublic, sub);
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
