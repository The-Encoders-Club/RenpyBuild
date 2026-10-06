package org.renpy.android;

import android.Manifest;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.Layout;
import android.app.Activity;
import android.app.ActivityManager;
import java.util.List;
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
 * Pre-launch gateway activity (LAUNCHER) that requests mandatory external
 * storage
 * and notification permissions before the Ren'Py engine (PythonSDLActivity)
 * initializes.
 *
 * Designed for Monika After Story (MAS):
 * - Pure solid black background (#000000) for immersive aesthetic.
 * - Dialogue textbox (game/gui/textbox.png / textbox_d.png /
 * textbox_monika_d.png).
 * - Choice menu buttons styled with DDLC aesthetics and emerald green hover
 * accent (#5DE23C).
 * - Full automatic initialization of public storage directories in
 * /sdcard/Monika After Story/.
 * - Consecutive permission workflow on "Aceptar": notifications first, followed
 * immediately by storage.
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
    private static final String ASSET_FONT_ALLER = "x-game/x-gui/x-font/x-Aller_Rg.ttf";
    private static final String ASSET_FONT_ALLER_ALT = "x-game/x-gui/x-font/Aller_Rg.ttf";
    private static final String ASSET_SFX_HOVER = "x-game/x-mod_assets/x-buttons/x-sounds/x-button_hover_day.ogg";
    private static final String ASSET_SFX_CONFIRM = "x-game/x-mod_assets/x-buttons/x-sounds/x-button_click_day.ogg";
    private static final String[] ASSET_SFX_HOVER_CANDIDATES = new String[] {
            "x-game/x-mod_assets/x-buttons/x-sounds/x-button_hover_day.ogg",
            "x-game/x-gui/x-sfx/x-hover.ogg",
            "game/gui/sfx/hover.ogg"
    };
    private static final String[] ASSET_SFX_CONFIRM_CANDIDATES = new String[] {
            "x-game/x-mod_assets/x-buttons/x-sounds/x-button_click_day.ogg",
            "x-game/x-gui/x-sfx/x-select.ogg",
            "game/gui/sfx/select.ogg"
    };

    // Palette matching Monika / DDLC UI
    private static final int COLOR_CHOICE_IDLE = Color.BLACK;
    private static final int COLOR_CHOICE_HOVER = 0xFFFFAA99; // DDLC choice hover color (#FFAA99 / #fa9)
    private static final int COLOR_TEXTBOX_TEXT = Color.WHITE;

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

    private static class ChoiceButtonHolder {
        final View view;
        final Runnable action;

        ChoiceButtonHolder(View view, Runnable action) {
            this.view = view;
            this.action = action;
        }

        void setHovered(boolean hovered, Drawable idleBg, Drawable hoverBg, int idleColor, int hoverColor) {
            if (view instanceof TextView) {
                ((TextView) view).setTextColor(hovered ? hoverColor : idleColor);
            }
            if (hovered) {
                if (hoverBg != null) {
                    view.setBackground(hoverBg.getConstantState().newDrawable());
                }
            } else {
                if (idleBg != null) {
                    view.setBackground(idleBg.getConstantState().newDrawable());
                }
            }
        }
    }

    private final java.util.List<ChoiceButtonHolder> choiceButtonHolders = new java.util.ArrayList<>();
    private ChoiceButtonHolder currentlyHoveredHolder = null;
    private float touchDownX = 0f;
    private float touchDownY = 0f;
    private long touchDownTime = 0L;

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

        // If storage permission is already granted, proceed immediately without
        // displaying the gate
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

            for (String p : ASSET_SFX_HOVER_CANDIDATES) {
                soundHoverId = loadSoundReliably(p, true);
                if (soundHoverId != -1)
                    break;
            }
            for (String p : ASSET_SFX_CONFIRM_CANDIDATES) {
                soundConfirmId = loadSoundReliably(p, false);
                if (soundConfirmId != -1)
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize SoundPool", e);
        }
    }

    /**
     * Loads sound into SoundPool reliably, caching compressed APK streams to disk
     * when needed.
     */
    private int loadSoundReliably(String assetPath, boolean isHover) {
        if (soundPool == null)
            return -1;
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

        // Load official MAS / DDLC font (Aller_Rg.ttf) with system default as fallback
        String[] fontCandidates = new String[] {
                ASSET_FONT_ALLER,
                ASSET_FONT_ALLER_ALT
        };
        for (String fPath : fontCandidates) {
            try {
                monikaTypeface = Typeface.createFromAsset(getAssets(), fPath);
                if (monikaTypeface != null)
                    break;
            } catch (Exception ignored) {
            }
        }
        if (monikaTypeface == null) {
            monikaTypeface = Typeface.DEFAULT;
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
        // Overrides dispatchTouchEvent so finger touch anywhere on screen acts as mouse
        // cursor
        FrameLayout rootLayout = new FrameLayout(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                handleScreenTouch(ev);
                return true;
            }
        };
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. BUTTON CONTAINER (Ren'Py choice_vbox: xalign 0.5, ypos 270, yanchor 0.5,
        // spacing 22)
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setGravity(Gravity.CENTER_HORIZONTAL);

        int btnWidth = (int) (420 * scaleFactor);
        int btnHeight = (int) (38 * scaleFactor);
        int btnSpacing = (int) (22 * scaleFactor); // gui.choice_spacing = 22

        // Total height of 2 buttons + spacing: (38 * 2 + 22) = 98 px.
        // Centered at y=270: top = 270 - (98 / 2) = 221 px.
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        buttonParams.topMargin = (int) (221 * scaleFactor);
        buttonContainer.setLayoutParams(buttonParams);

        boolean isSpanish = Locale.getDefault().getLanguage().equalsIgnoreCase("es");
        String acceptText = isSpanish ? "Estoy de acuerdo." : "I agree.";
        String denyText = isSpanish ? "Salir" : "Exit";

        // Accept button triggers consecutive actions: notifications first if needed,
        // then storage
        View acceptButton = createChoiceButton(acceptText, () -> {
            playSfx(soundConfirmId, false);
            onAcceptClicked();
        });

        // Deny button exits the application natively
        View denyButton = createChoiceButton(denyText, () -> {
            playSfx(soundConfirmId, false);
            exitApp();
        });

        LinearLayout.LayoutParams btnLayoutParam = (LinearLayout.LayoutParams) acceptButton.getLayoutParams();
        btnLayoutParam.bottomMargin = btnSpacing;
        acceptButton.setLayoutParams(btnLayoutParam);

        buttonContainer.addView(acceptButton);
        buttonContainer.addView(denyButton);
        rootLayout.addView(buttonContainer);

        // 2. BOTTOM TEXTBOX CONTAINER (Ren'Py gui.textbox: 816x146 px, yalign 0.99 ->
        // 6px from bottom)
        int tbWidth = (int) (816 * scaleFactor);
        int tbHeight = (int) (146 * scaleFactor);

        FrameLayout textboxContainer = new FrameLayout(this);
        FrameLayout.LayoutParams tbContainerParams = new FrameLayout.LayoutParams(tbWidth, tbHeight);
        tbContainerParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        tbContainerParams.bottomMargin = (int) (6 * scaleFactor);
        textboxContainer.setLayoutParams(tbContainerParams);

        textboxContainer.setClipChildren(false);
        textboxContainer.setClipToPadding(false);

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
                FrameLayout.LayoutParams.MATCH_PARENT));
        textboxContainer.addView(textboxBg);

        // Dialogue text inside textbox (DDLC style: 24px Aller_Rg font, white fill with
        // black outline)
        OutlineTextView otv = new OutlineTextView(this);
        otv.setStrokeColor(Color.BLACK);
        // Ren'Py outlines [(2, "#000000aa", 0, 0)] -> Under FILL, strokeWidth of 3.5f *
        // scaleFactor
        // produces ~1.75f - 2.0f outer border, perfectly matching Ren'Py's crisp
        // outline!
        otv.setStrokeWidth(3.5f * scaleFactor, scaleFactor);
        dialogueTextView = otv;
        if (monikaTypeface != null)
            dialogueTextView.setTypeface(monikaTypeface);
        dialogueTextView.setTextColor(COLOR_TEXTBOX_TEXT); // White
        dialogueTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24.0f * scaleFactor); // gui.text_size = 24
        dialogueTextView.setLineSpacing(1.0f * scaleFactor, 1.0f); // Ren'Py line_spacing 1
        dialogueTextView.setGravity(Gravity.TOP | Gravity.START);
        dialogueTextView.setMaxLines(4);

        // Ren'Py TeX Knuth-Plass paragraph line-breaking algorithm
        // (BREAK_STRATEGY_HIGH_QUALITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dialogueTextView.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
            dialogueTextView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }

        // Exact Ren'Py coordinates: leftMargin 31*s + padH 5*s = 36*scaleFactor (xpos
        // 268-232=36)
        // topMargin 23*s + padV 2*s = 25*scaleFactor (raised 1px from 26px to match
        // overlay)
        // effective width: (744 + 10)*s - 10*s = 744*scaleFactor (exact gui.text_width)
        int textContainerWidth = (int) Math.round((744.0f + 10.0f) * scaleFactor);
        int textContainerHeight = (int) Math.round(115.0f * scaleFactor);
        FrameLayout.LayoutParams tvParams = new FrameLayout.LayoutParams(textContainerWidth, textContainerHeight);
        tvParams.leftMargin = (int) Math.round(33.0f * scaleFactor);
        tvParams.topMargin = (int) Math.round(25.0f * scaleFactor);
        dialogueTextView.setLayoutParams(tvParams);

        textboxContainer.addView(dialogueTextView);
        rootLayout.addView(textboxContainer);

        // Tap anywhere on canvas or textbox to immediately complete typewriter
        // animation
        View.OnClickListener skipTypewriter = v -> completeTypewriterImmediately();
        rootLayout.setOnClickListener(skipTypewriter);
        textboxContainer.setOnClickListener(skipTypewriter);
        dialogueTextView.setOnClickListener(skipTypewriter);

        setContentView(rootLayout);

        // 3. DIALOGUE TEXT (Formatted with DDLC disclaimer/agreement formula from
        // splash.rpy:L196)
        // Strictly accent-free to ensure 100% rendering safety with custom ASCII DDLC
        // fonts
        if (isSpanish) {
            fullDialogueText = "Al jugar Monika After Story, aceptas otorgar los permisos de almacenamiento necesarios para gestionar tus partidas, regalos\ny submods en la memoria externa.";
        } else {
            fullDialogueText = "By playing Monika After Story, you agree to grant storage permissions to manage your save data, gifts, and submods in external storage.";
        }

        startTypewriterEffect();
    }

    private View createChoiceButton(String text, Runnable onClick) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scaleFactor = (float) dm.heightPixels / 720.0f;

        TextView button = new TextView(this);
        button.setText(text);
        if (monikaTypeface != null)
            button.setTypeface(monikaTypeface);
        button.setTextColor(COLOR_CHOICE_IDLE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24.0f * scaleFactor);
        button.setGravity(Gravity.CENTER);

        if (drawableChoiceIdle != null) {
            button.setBackground(drawableChoiceIdle.getConstantState().newDrawable());
        }

        int btnWidth = (int) (420 * scaleFactor);
        int btnHeight = (int) (38 * scaleFactor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnWidth, btnHeight);
        button.setLayoutParams(lp);

        ChoiceButtonHolder holder = new ChoiceButtonHolder(button, onClick);
        choiceButtonHolders.add(holder);

        return button;
    }

    /**
     * Emulates continuous mouse-cursor tracking across the entire screen (Ren'Py /
     * desktop behavior).
     * Having the finger pressed anywhere on the screen corresponds to the cursor.
     * Moving the finger over any choice button activates its hover state and sound.
     * Releasing the finger confirms the button, and lifting the finger leaves no
     * active cursor.
     */
    private boolean handleScreenTouch(MotionEvent event) {
        float rawX = event.getRawX();
        float rawY = event.getRawY();

        ChoiceButtonHolder target = null;
        int[] loc = new int[2];
        for (ChoiceButtonHolder holder : choiceButtonHolders) {
            holder.view.getLocationOnScreen(loc);
            int left = loc[0];
            int top = loc[1];
            int right = left + holder.view.getWidth();
            int bottom = top + holder.view.getHeight();
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                target = holder;
                break;
            }
        }

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = rawX;
                touchDownY = rawY;
                touchDownTime = System.currentTimeMillis();
                if (target != null) {
                    target.setHovered(true, drawableChoiceIdle, drawableChoiceHover, COLOR_CHOICE_IDLE,
                            COLOR_CHOICE_HOVER);
                    playSfx(soundHoverId, true);
                    currentlyHoveredHolder = target;
                } else {
                    currentlyHoveredHolder = null;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (target != currentlyHoveredHolder) {
                    if (currentlyHoveredHolder != null) {
                        currentlyHoveredHolder.setHovered(false, drawableChoiceIdle, drawableChoiceHover,
                                COLOR_CHOICE_IDLE, COLOR_CHOICE_HOVER);
                    }
                    if (target != null) {
                        target.setHovered(true, drawableChoiceIdle, drawableChoiceHover, COLOR_CHOICE_IDLE,
                                COLOR_CHOICE_HOVER);
                        playSfx(soundHoverId, true);
                    }
                    currentlyHoveredHolder = target;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (currentlyHoveredHolder != null) {
                    ChoiceButtonHolder clicked = currentlyHoveredHolder;
                    currentlyHoveredHolder.setHovered(false, drawableChoiceIdle, drawableChoiceHover, COLOR_CHOICE_IDLE,
                            COLOR_CHOICE_HOVER);
                    currentlyHoveredHolder = null;
                    if (clicked.action != null) {
                        clicked.action.run();
                    }
                } else {
                    // Tap on empty space or textbox completes typewriter text immediately
                    long duration = System.currentTimeMillis() - touchDownTime;
                    float dist = (float) Math.hypot(rawX - touchDownX, rawY - touchDownY);
                    if (duration < 400 && dist < 30) {
                        completeTypewriterImmediately();
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (currentlyHoveredHolder != null) {
                    currentlyHoveredHolder.setHovered(false, drawableChoiceIdle, drawableChoiceHover, COLOR_CHOICE_IDLE,
                            COLOR_CHOICE_HOVER);
                    currentlyHoveredHolder = null;
                }
                break;
        }
        return true;
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
            return checkCallingOrSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[] {
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
     * Executes superimposed permission requests when the user taps "Aceptar" / "Estoy de acuerdo".
     * Replicates the exact behavior found in the Ren'Py 7.8.4 port (zz_android.rpy):
     * 1. Storage access request is triggered first (opens system settings on Android 11+).
     * 2. Notification runtime permission (Android 13+) is requested immediately without waiting,
     *    causing the notification permission dialog to pop up superimposed right on top of Settings.
     */
    private void onAcceptClicked() {
        // 1. Storage permission request
        requestStoragePermission();

        // 2. Immediate superimposed Notification permission request (Android 13+)
        if (needsNotificationPermission()) {
            requestNotificationPermission();
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
            requestPermissions(new String[] {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQUEST_CODE_LEGACY_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (hasStoragePermission()) {
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
            String[] dirsToCreate = new String[] {
                    "",
                    "characters",
                    "chess_games",
                    "log",
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

    /**
     * Custom TextView that renders text with a stroke/outline matching DDLC
     * dialogue style:
     * crisp white fill with a solid black outline around each glyph.
     * Overrides invalidate/requestLayout while isDrawing=true to prevent recursive
     * loops
     * when switching text colors between stroke and fill passes.
     */
    public static class OutlineTextView extends TextView {
        private int strokeColor = Color.BLACK;
        private float strokeWidth = 3.5f;

        public OutlineTextView(Context context) {
            super(context);
        }

        public void setStrokeColor(int color) {
            this.strokeColor = color;
            invalidate();
        }

        public void setStrokeWidth(float width, float scaleFactor) {
            this.strokeWidth = width;
            int padH = (int) Math.round(5.0f * scaleFactor);
            int padV = (int) Math.round(2.0f * scaleFactor);
            setPadding(padH, padV, padH, padV);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Layout layout = getLayout();
            if (layout != null && strokeWidth > 0) {
                canvas.save();
                canvas.translate(getPaddingLeft(), getPaddingTop());

                TextPaint paint = getPaint();
                paint.setAntiAlias(true);
                paint.setSubpixelText(true);
                int originalColor = getCurrentTextColor();

                // 1. Draw solid black outline directly on Layout (no clipRect restriction)
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(strokeWidth);
                paint.setColor(strokeColor);
                layout.draw(canvas);

                // 2. Draw crisp white text fill
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(originalColor);
                layout.draw(canvas);

                canvas.restore();
            } else {
                super.onDraw(canvas);
            }
        }
    }

    private void completeTypewriterImmediately() {
        if (typewriterHandler != null && typewriterRunnable != null) {
            typewriterHandler.removeCallbacks(typewriterRunnable);
        }
        if (dialogueTextView != null && fullDialogueText != null) {
            dialogueTextView.setText(fullDialogueText);
        }
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    private void exitApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        List<ActivityManager.AppTask> appTasks = am.getAppTasks();
                        if (appTasks != null) {
                            for (ActivityManager.AppTask appTask : appTasks) {
                                appTask.finishAndRemoveTask();
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                finishAndRemoveTask();
            } else {
                finishAffinity();
            }
        } catch (Exception e) {
            finishAffinity();
        }

        // Allow system_server to cleanly remove the task from Multitasking/Recents before terminating VM
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        }, 150);
    }
}
