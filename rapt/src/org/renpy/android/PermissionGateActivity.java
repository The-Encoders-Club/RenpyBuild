package org.renpy.android;

import android.Manifest;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.app.Activity;
import android.app.ActivityManager;
import java.util.Arrays;
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
import android.widget.Toast;

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
 * - Dialogue textbox drawn by code (MasTextboxDrawable), so no file of the original game is needed.
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
    private static final int REQUEST_CODE_DDLC_ZIP = 1004;

    // Where the user gets the official game, which this app doesn't carry
    private static final String DDLC_URL = "http://ddlc.moe";

    // Asset paths with x- prefix applied by RAPT packaging
    // MAS Sans lives in the SDK (renpy/common), which RAPT packages as x-renpy/x-common
    private static final String ASSET_FONT_MAS = "x-renpy/x-common/x-FiraSans-Regular-MAS.ttf";
    private static final String ASSET_FONT_MAS_ALT = "x-renpy/x-common/FiraSans-Regular-MAS.ttf";
    // Sounds of the launcher, made for it (not from the original game). They live in the SDK, next to the
    // MAS Sans font (renpy/common), which RAPT packages as x-renpy/x-common
    private static final String ASSET_SFX_HOVER = "x-renpy/x-common/x-mas_launcher_hover.wav";
    private static final String ASSET_SFX_CONFIRM = "x-renpy/x-common/x-mas_launcher_select.wav";
    private static final String[] ASSET_SFX_HOVER_CANDIDATES = new String[] {
            ASSET_SFX_HOVER, "x-renpy/x-common/mas_launcher_hover.wav"
    };
    private static final String[] ASSET_SFX_CONFIRM_CANDIDATES = new String[] {
            ASSET_SFX_CONFIRM, "x-renpy/x-common/mas_launcher_select.wav"
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

    /**
     * Background of a choice button: flat colored rectangles, one inside the other, like
     * the 400x35 images it replaces. It is stretched to the size of the view, so the
     * border keeps the same proportion those images had when they were stretched.
     */
    private static class ChoiceBackground extends Drawable {
        private static final float REFERENCE_WIDTH = 400f;
        private static final float REFERENCE_HEIGHT = 35f;

        // Colors from the outermost rectangle to the innermost one
        private final int[] colors;
        // Width, in pixels of the 400x35 reference, of each ring before the next color
        private final int[] borders;
        private final Paint paint = new Paint();

        ChoiceBackground(int[] colors, int[] borders) {
            this.colors = colors;
            this.borders = borders;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float scaleX = bounds.width() / REFERENCE_WIDTH;
            float scaleY = bounds.height() / REFERENCE_HEIGHT;

            float referenceInset = 0f;
            for (int i = 0; i < colors.length; i++) {
                int insetX = Math.round(referenceInset * scaleX);
                int insetY = Math.round(referenceInset * scaleY);
                paint.setColor(colors[i]);
                canvas.drawRect(
                        bounds.left + insetX, bounds.top + insetY,
                        bounds.right - insetX, bounds.bottom - insetY,
                        paint);
                if (i < borders.length) {
                    referenceInset += borders[i];
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public ConstantState getConstantState() {
            final int[] stateColors = colors;
            final int[] stateBorders = borders;
            return new ConstantState() {
                @Override
                public Drawable newDrawable() {
                    return new ChoiceBackground(stateColors, stateBorders);
                }

                @Override
                public int getChangingConfigurations() {
                    return 0;
                }
            };
        }
    }

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

    /** A button of a screen of the gate. */
    private static class Choice {
        final String text;
        final Runnable action;

        Choice(String text, Runnable action) {
            this.text = text;
            this.action = action;
        }
    }

    private boolean isSpanish = false;
    private float gateScale = 1f;
    private LinearLayout buttonContainer;
    private FrameLayout.LayoutParams buttonContainerParams;

    // The screen being shown: pages of dialogue, and the buttons that appear on the last one
    private String[] pages = new String[0];
    private int pageIndex = 0;
    private Choice[] pageChoices = new Choice[0];
    // Text of the link that opens the browser (inside the pages), if any
    private String pageLink = null;
    private int linkStart = -1;
    private int linkEnd = -1;
    // Runs once the current page is completely written
    private Runnable afterTyping = null;

    private boolean installing = false;
    private boolean pickerOpen = false;

    // The message over the file chooser of the system: a toast, shown once for its maximum time
    private Toast pickerHint;

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

        // If the permission is already granted and the files of DDLC are installed, there is
        // nothing to ask: proceed immediately without displaying the gate
        if (hasStoragePermission() && isDdlcReady()) {
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
                proceedAfterPermissions();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPickerHint();
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

        // Load MAS Sans (FiraSans-Regular-MAS.ttf, SIL OFL) with system default as fallback
        String[] fontCandidates = new String[] {
                ASSET_FONT_MAS,
                ASSET_FONT_MAS_ALT
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

        // Choice button backgrounds, drawn in code so no image from the original game is needed
        // idle: 3 px border (#ffbde1) around #ffe6f4
        drawableChoiceIdle = new ChoiceBackground(
                new int[] { 0xFFFFBDE1, 0xFFFFE6F4 },
                new int[] { 3 });
        // hover: 2 px border (#ffbde1) and 1 px (#ffe1f1) around #ffffff
        drawableChoiceHover = new ChoiceBackground(
                new int[] { 0xFFFFBDE1, 0xFFFFE1F1, 0xFFFFFFFF },
                new int[] { 2, 1 });

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

        // The top margin depends on how many buttons the screen has (see showPage())
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        buttonParams.topMargin = (int) (221 * scaleFactor);
        buttonContainer.setLayoutParams(buttonParams);

        // The buttons of each screen are added by showPage()
        isSpanish = Locale.getDefault().getLanguage().equalsIgnoreCase("es");
        gateScale = scaleFactor;
        this.buttonContainer = buttonContainer;
        this.buttonContainerParams = buttonParams;
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

        // Textbox background, drawn by code
        ImageView textboxBg = new ImageView(this);
        textboxBg.setImageDrawable(new MasTextboxDrawable());
        textboxBg.setScaleType(ImageView.ScaleType.FIT_XY);
        textboxBg.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        textboxContainer.addView(textboxBg);

        // Dialogue text inside textbox (DDLC style: 24px MAS Sans font, white fill with
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
        // The lines already break where the Ren'Py engine breaks them; the extra 24 only keeps a line
        // that is almost as wide as the box from wrapping because Android measures it a bit wider
        int textContainerWidth = (int) Math.round((744.0f + 10.0f + 24.0f) * scaleFactor);
        int textContainerHeight = (int) Math.round(115.0f * scaleFactor);
        FrameLayout.LayoutParams tvParams = new FrameLayout.LayoutParams(textContainerWidth, textContainerHeight);
        tvParams.leftMargin = (int) Math.round(33.0f * scaleFactor);
        tvParams.topMargin = (int) Math.round(22.5f * scaleFactor);
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

        // 3. DIALOGUE TEXT: the screens of the gate (see the "Screens" section below)
        showInitialScreen();
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
                    // Tap on empty space or textbox completes typewriter text immediately,
                    // or goes to the next page once the text is complete
                    long duration = System.currentTimeMillis() - touchDownTime;
                    float dist = (float) Math.hypot(rawX - touchDownX, rawY - touchDownY);
                    if (duration < 400 && dist < 30) {
                        if (!tryOpenLink(rawX, rawY)) {
                            completeTypewriterImmediately();
                        }
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
        if (typewriterHandler == null) {
            typewriterHandler = new Handler(Looper.getMainLooper());
        }
        // A previous page may still be writing
        if (typewriterRunnable != null) {
            typewriterHandler.removeCallbacks(typewriterRunnable);
        }
        typewriterIndex = 0;
        setDialogueText("");

        final long delayMillis = 28;

        typewriterRunnable = new Runnable() {
            @Override
            public void run() {
                if (typewriterIndex < fullDialogueText.length()) {
                    typewriterIndex++;
                    setDialogueText(fullDialogueText.substring(0, typewriterIndex));
                    typewriterHandler.postDelayed(this, delayMillis);
                } else {
                    runAfterTyping();
                }
            }
        };

        typewriterHandler.postDelayed(typewriterRunnable, 100);
    }

    /** Shows the text of the dialogue, with the link (if the page has one) underlined. */
    private void setDialogueText(String text) {
        SpannableString spannable = new SpannableString(text);
        int end = Math.min(linkEnd, text.length());
        if (linkStart >= 0 && end > linkStart) {
            spannable.setSpan(new UnderlineSpan(), linkStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        dialogueTextView.setText(spannable);
    }

    private boolean isTypingComplete() {
        return typewriterIndex >= fullDialogueText.length();
    }

    private void runAfterTyping() {
        Runnable action = afterTyping;
        afterTyping = null;
        if (action != null) {
            action.run();
        }
    }

    /**
     * Opens the browser if the tap was on the link of the page.
     * The root view takes all the touches, so the TextView can't handle the link by itself.
     */
    private boolean tryOpenLink(float rawX, float rawY) {
        if (linkStart < 0 || pageLink == null || dialogueTextView == null || !isTypingComplete()) {
            return false;
        }
        Layout layout = dialogueTextView.getLayout();
        if (layout == null) {
            return false;
        }

        int[] loc = new int[2];
        dialogueTextView.getLocationOnScreen(loc);
        float x = rawX - loc[0] - dialogueTextView.getTotalPaddingLeft();
        float y = rawY - loc[1] - dialogueTextView.getTotalPaddingTop();
        if (x < 0 || y < 0) {
            return false;
        }

        int line = layout.getLineForVertical((int) y);
        if (y > layout.getLineBottom(line) + 8 * gateScale || x > layout.getLineRight(line) + 16 * gateScale) {
            return false;
        }
        int offset = layout.getOffsetForHorizontal(line, x);
        if (offset < linkStart - 1 || offset > linkEnd) {
            return false;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pageLink)));
        } catch (Exception e) {
            Log.w(TAG, "Could not open " + pageLink, e);
        }
        return true;
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
            proceedAfterPermissions();
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
                proceedAfterPermissions();
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
                proceedAfterPermissions();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (hasStoragePermission()) {
                proceedAfterPermissions();
            }
        } else if (requestCode == REQUEST_CODE_DDLC_ZIP) {
            pickerOpen = false;
            stopPickerHint();
            Uri zip = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (zip != null) {
                startInstall(zip);
            } else {
                // Cancelled: offer the same choice again
                showPickerScreen(false);
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Screens: disclaimer -> permissions -> choose ddlc-win.zip -> copy -> game
    // ------------------------------------------------------------------------------------

    private String t(String spanish, String english) {
        return isSpanish ? spanish : english;
    }

    /** Private folder of the app that holds the original files (kept when the app is updated). */
    private File internalGameDir() {
        return new File(getFilesDir(), "game");
    }

    private File publicGameDir() {
        return new File(new File(Environment.getExternalStorageDirectory(), getAppName()), "game");
    }

    /**
     * True when the four files of the original game are installed. The ones that someone
     * put by hand in the public game folder count too.
     */
    private boolean isDdlcReady() {
        if (InstallerDDLC.isInstalled(internalGameDir())) {
            return true;
        }
        return hasStoragePermission() && InstallerDDLC.hasFiles(publicGameDir());
    }

    /** Once the permissions are granted: ask for the game files if they are missing, or start. */
    private void proceedAfterPermissions() {
        createExternalStorageDirectories();
        if (isDdlcReady()) {
            launchPythonSDLActivity();
        } else {
            showPickerScreen(true);
        }
    }

    private void showInitialScreen() {
        if (!isDdlcReady()) {
            showDisclaimerScreen();
        } else {
            // The game files are there, only the permission is missing
            showPermissionsScreen();
        }
    }

    /** Message 1: disclaimer and where to get the game. */
    private void showDisclaimerScreen() {
        // The line breaks are the ones of the Ren'Py engine for the same width, font and size,
        // so the text is drawn like the game draws it. 3 lines is the most the box holds
        showDialogue(new String[] {
                t("Monika After Story es una modificaci\u00f3n de fans para Doki Doki\nLiterature Club y no est\u00e1 afiliada con Team Salvato.",
                        "Monika After Story is a fan-made mod for Doki Doki Literature Club\nand is not affiliated with Team Salvato."),
                t("Este port para android est\u00e1 dise\u00f1ado para ser jugado \u00fanicamente\ntras haber completado el juego oficial original, ya que contiene\nspoilers importantes del mismo.",
                        "This Android port is meant to be played only after completing the\noriginal game, since it contains major spoilers for it."),
                t("En estricto apego a las directrices de propiedad intelectual de Team\nSalvato, esta aplicaci\u00f3n no incluye archivos del juego base ni los\ndescarga en tu nombre.",
                        "In strict compliance with Team Salvato's IP guidelines, this app does\nnot include the files of the base game, nor does it download them\nfor you."),
                t("Para poder continuar, es obligatorio que aportes los archivos de tu\npropia copia oficial, la cual se puede obtener gratuitamente en:\nhttp://ddlc.moe",
                        "To continue, you must provide the files of your own official copy,\nwhich you can get for free at:\nhttp://ddlc.moe")
        }, DDLC_URL, new Choice(t("Ya tengo DDLC.", "I already have DDLC."), () -> {
            playSfx(soundConfirmId, false);
            // The storage permission goes first, as the files are copied right after
            if (hasStoragePermission()) {
                proceedAfterPermissions();
            } else {
                showPermissionsScreen();
            }
        }),
                new Choice(t("Salir", "Exit"), () -> {
                    playSfx(soundConfirmId, false);
                    exitApp();
                }));
    }

    /** Message 2: why the storage permission is needed. */
    private void showPermissionsScreen() {
        showDialogue(new String[] {
                t("Para que este port funcione correctamente y mantenga la paridad\ntotal con PC, se requieren permisos de almacenamiento en tu\ndispositivo.",
                        "For this port to work properly and keep full parity with the PC\nversion, storage permissions are required on your device."),
                t("Esto garantiza el guardado seguro de tu progreso, as\u00ed como la\ninstalaci\u00f3n de los archivos base, regalos y submods en la carpeta\ndel juego.",
                        "This guarantees the safe saving of your progress, as well as the\ninstallation of the base files, gifts and submods in the game folder."),
                t("\u00bfEst\u00e1s de acuerdo con otorgar los permisos de almacenamiento\npara continuar?",
                        "Do you agree to grant the storage permissions to continue?")
        }, null,
                // Accept triggers consecutive actions: notifications first if needed, then storage
                new Choice(t("Estoy de acuerdo.", "I agree."), () -> {
                    playSfx(soundConfirmId, false);
                    onAcceptClicked();
                }),
                // Deny exits the application natively
                new Choice(t("Salir", "Exit"), () -> {
                    playSfx(soundConfirmId, false);
                    exitApp();
                }));
    }

    /**
     * Asks for ddlc-win.zip. The message "Seleccione ddlc-win.zip por favor." is the title of the
     * file chooser of the system, so the dialogue stays empty while it is open. If the user
     * cancels it, the buttons let them try again.
     */
    private void showPickerScreen(boolean openAutomatically) {
        if (installing || pickerOpen) {
            return;
        }

        if (openAutomatically) {
            showDialogue(new String[] { "" }, null);
            launchZipPicker();
            return;
        }

        showDialogue(new String[] {
                t("Para continuar necesitas el archivo ddlc-win.zip de tu copia oficial\nde DDLC.",
                        "To continue you need the ddlc-win.zip file\nof your official DDLC copy.")
        }, null,
                new Choice(t("Elegir archivo", "Choose file"), () -> {
                    playSfx(soundConfirmId, false);
                    launchZipPicker();
                }),
                new Choice(t("Salir", "Exit"), () -> {
                    playSfx(soundConfirmId, false);
                    exitApp();
                }));
    }

    private void startPickerHint() {
        stopPickerHint();

        pickerHint = Toast.makeText(this,
                t("Seleccione ddlc-win.zip por favor.", "Please select ddlc-win.zip."),
                Toast.LENGTH_LONG);
        pickerHint.show();
    }

    private void stopPickerHint() {
        if (pickerHint != null) {
            pickerHint.cancel();
            pickerHint = null;
        }
    }

    private void launchZipPicker() {
        if (installing || pickerOpen || isFinishing()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Some file managers don't call a zip application/zip, and would grey it out
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
            });

            // The title of the chooser is the message the user reads
            Intent chooser = Intent.createChooser(intent,
                    t("Seleccione ddlc-win.zip por favor.", "Please select ddlc-win.zip."));
            pickerOpen = true;
            startPickerHint();
            startActivityForResult(chooser, REQUEST_CODE_DDLC_ZIP);
        } catch (Exception e) {
            pickerOpen = false;
            stopPickerHint();
            Log.w(TAG, "Could not open the file chooser", e);
        }
    }

    private void showProgressScreen() {
        showDialogue(new String[] {
                t("Estamos empaquetando todo, espere unos momentos por favor...",
                        "We are packing everything, please wait a moment...")
        }, null);
    }

    private void showRetryScreen(String message) {
        showDialogue(new String[] { message }, DDLC_URL,
                new Choice(t("Elegir otro archivo", "Choose another file"), () -> {
                    playSfx(soundConfirmId, false);
                    launchZipPicker();
                }),
                new Choice(t("Salir", "Exit"), () -> {
                    playSfx(soundConfirmId, false);
                    exitApp();
                }));
    }

    /** Copies the game files out of the zip in a thread, as it takes a few seconds. */
    private void startInstall(final Uri zip) {
        installing = true;
        showProgressScreen();

        final File gameDir = internalGameDir();
        new Thread(new Runnable() {
            @Override
            public void run() {
                InstallerDDLC.Result result;
                try (InputStream in = getContentResolver().openInputStream(zip)) {
                    result = (in == null)
                            ? InstallerDDLC.Result.UNREADABLE
                            : InstallerDDLC.install(in, gameDir, null);
                } catch (Exception e) {
                    Log.w(TAG, "Could not read the zip", e);
                    result = InstallerDDLC.Result.UNREADABLE;
                }
                if (InstallerDDLC.lastError != null) {
                    Log.w(TAG, "DDLC install: " + InstallerDDLC.lastError);
                }

                final InstallerDDLC.Result finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onInstallFinished(finalResult);
                    }
                });
            }
        }, "ddlc-install").start();
    }

    private void onInstallFinished(InstallerDDLC.Result result) {
        installing = false;
        if (isFinishing()) {
            return;
        }

        switch (result) {
            case OK:
                showDialogue(new String[] { t("Instalación completa.", "Installation complete.") }, null);
                // The game doesn't start in this run: the app restarts in a new process, and
                // the next run finds everything installed and starts the game
                afterTyping = () -> typewriterHandler.postDelayed(() -> RestartActivity.restart(this), 2000);
                break;
            case NO_SPACE:
                showRetryScreen(t("No hay espacio suficiente en el almacenamiento.\nLibera unos 250 MB y vuelve a intentarlo.",
                        "There is not enough storage space. Free about 250 MB and try again."));
                break;
            default:
                showRetryScreen(t("Ese archivo no es el ddlc-win.zip oficial, o est\u00e1 da\u00f1ado. Desc\u00e1rgalo\nde nuevo desde http://ddlc.moe e int\u00e9ntalo otra vez.",
                        "That file is not the official ddlc-win.zip, or it is damaged. Download\nit again from http://ddlc.moe and try again."));
                break;
        }
    }

    /**
     * Shows a screen: pages of dialogue (a tap goes to the next one) and, on the last one,
     * the buttons.
     * @param link text inside the pages that opens the browser when tapped, or null
     */
    private void showDialogue(String[] newPages, String link, Choice... choices) {
        pages = newPages;
        pageIndex = 0;
        pageChoices = choices;
        pageLink = link;
        showPage();
    }

    private void showPage() {
        fullDialogueText = pages[pageIndex];
        afterTyping = null;

        linkStart = -1;
        linkEnd = -1;
        if (pageLink != null) {
            linkStart = fullDialogueText.indexOf(pageLink);
            if (linkStart >= 0) {
                linkEnd = linkStart + pageLink.length();
            }
        }

        buttonContainer.removeAllViews();
        choiceButtonHolders.clear();
        currentlyHoveredHolder = null;
        if (pageIndex == pages.length - 1) {
            addChoiceButtons(pageChoices);
        }

        startTypewriterEffect();
    }

    private void addChoiceButtons(Choice[] choices) {
        // Ren'Py choice_vbox is centered at y=270: n buttons of 38 px with 22 px between them
        int count = choices.length;
        float totalHeight = 38f * count + 22f * Math.max(count - 1, 0);
        buttonContainerParams.topMargin = (int) ((270f - totalHeight / 2f) * gateScale);
        buttonContainer.setLayoutParams(buttonContainerParams);

        for (int i = 0; i < count; i++) {
            View button = createChoiceButton(choices[i].text, choices[i].action);
            if (i < count - 1) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
                lp.bottomMargin = (int) (22 * gateScale); // gui.choice_spacing = 22
                button.setLayoutParams(lp);
            }
            buttonContainer.addView(button);
        }
    }

    private void ensureAppDirectoriesAndLaunch() {
        createExternalStorageDirectories();
        launchPythonSDLActivity();
    }

    private void createExternalStorageDirectories() {
        cleanOldPortFiles();
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

    // What the previous port (Ren'Py 7) leaves in the public folder. It always wrote records.txt (and diag_pickle.txt),
    // so one of them, or a copy a file manager made of it (records(1).txt), says the folder comes from that port.
    // The cleaning runs while Ren'Py is still asleep and before the folders are created again, so everything the
    // game writes afterwards is new: the whole log folder, every .txt of the main folder, the caches of the game
    // folder and every compiled .rpyc. The markers go last, so a cut cleaning is tried again next start.
    private static final String[] OLD_PORT_GAME_FILES = {
            "log.txt", "traceback.txt", "diag_pickle.txt", "assets.json", "state.json", "submods.rpa"
    };

    private static boolean isOldPortMarker(String name) {
        return name.matches("(?i)(records|diag_pickle)(\\s?\\(\\d+\\))?\\.txt");
    }

    private void cleanOldPortFiles() {
        try {
            if (!hasStoragePermission()) {
                return;
            }

            File root = new File(Environment.getExternalStorageDirectory(), getAppName());
            File[] rootFiles = root.listFiles();
            if (rootFiles == null) {
                return;
            }

            boolean oldPort = false;
            for (File f : rootFiles) {
                if (f.isFile() && isOldPortMarker(f.getName())) {
                    oldPort = true;
                    break;
                }
            }
            if (!oldPort) {
                return;
            }

            // 1. the log folder, whole
            deleteTree(new File(root, "log"));

            // 2. caches and compiled scripts of the game folder
            File game = new File(root, "game");
            File[] gameFiles = game.listFiles();
            if (gameFiles != null) {
                for (File f : gameFiles) {
                    String name = f.getName();
                    if (f.isFile() && (isOldPortMarker(name) || Arrays.asList(OLD_PORT_GAME_FILES).contains(name))) {
                        f.delete();
                    }
                }
            }
            int rpyc = deleteCompiledScripts(game);

            // 3. the .txt of the main folder (markers last) and the leftovers of the old services
            for (File f : rootFiles) {
                String name = f.getName();
                boolean txt = name.toLowerCase().endsWith(".txt");
                if (f.isFile() && (txt || name.startsWith("masrun")) && !isOldPortMarker(name)) {
                    f.delete();
                }
            }
            for (File f : rootFiles) {
                if (f.isFile() && isOldPortMarker(f.getName())) {
                    f.delete();
                }
            }
            Log.i(TAG, "Old port files cleaned, " + rpyc + " rpyc deleted");
        } catch (Throwable e) {
            Log.e(TAG, "Could not clean the files of the old port", e);
        }
    }

    /** Deletes a file or a folder with everything inside. */
    private void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        f.delete();
    }

    /** Deletes every .rpyc under dir (the engine compiles them again). RETURNS how many were deleted. */
    private int deleteCompiledScripts(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                count += deleteCompiledScripts(child);
            } else if (child.getName().endsWith(".rpyc") && child.delete()) {
                count++;
            }
        }
        return count;
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
     * The MAS dialogue box (816 x 146), drawn by code and fitted to the original art, so no file of the
     * original game is needed. Layers, from the bottom: the pink fill, whose opacity fades from 99% at the top
     * to 68% at the bottom, the polka dots (same fade, cut out of the fill), a soft shine at the bottom that only lightens the
     * colors, and the pale border. It is drawn once into a bitmap of the size of the view.
     */
    private static class MasTextboxDrawable extends Drawable {
        private static final float BOX_W = 816f;
        private static final float BOX_H = 146f;
        private static final float RADIUS = 12f;
        private static final float BORDER = 3f;
        private static final int FILL_RGB = 0x00FFA8D2;
        private static final int DOTS_RGB = 0x00FF96C6;
        private static final int EDGE = 0xFFFFDFEE;
        private static final float DOT_RADIUS = 9.2f;

        // Opacity of the fill from the top to the bottom: {y, alpha}
        private static final int[][] FADE = {
                { 0, 255 }, { 8, 253 }, { 16, 249 }, { 24, 245 }, { 32, 241 }, { 40, 236 }, { 48, 231 },
                { 56, 225 }, { 64, 219 }, { 72, 213 }, { 80, 207 }, { 88, 202 }, { 96, 196 }, { 104, 191 },
                { 112, 186 }, { 120, 181 }, { 128, 177 }, { 136, 174 }, { 145, 173 }
        };

        // How much white the shine adds, in percent, every SHINE_STEP_X units across and SHINE_STEP_Y units
        // down, starting at SHINE_TOP
        private static final float SHINE_STEP_X = 24.0f;
        private static final float SHINE_STEP_Y = 6.0f;
        private static final float SHINE_TOP = 72.0f;
        private static final int[][] SHINE = {
                    { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
                    { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
                    { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
                    { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 7, 21, 26, 35, 38, 44, 45, 42, 41, 32, 28, 16, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
                    { 0, 0, 0, 0, 0, 0, 0, 4, 19, 23, 34, 35, 36, 36, 37, 38, 39, 39, 38, 38, 37, 36, 35, 35, 31, 27, 13, 8, 0, 0, 0, 0, 0, 0, 0 },
                    { 0, 0, 0, 0, 0, 4, 24, 27, 28, 28, 28, 28, 28, 28, 28, 27, 28, 28, 28, 27, 27, 27, 28, 28, 28, 28, 28, 28, 17, 8, 0, 0, 0, 0, 0 },
                    { 0, 0, 0, 2, 13, 22, 23, 24, 22, 22, 20, 19, 18, 17, 16, 16, 16, 16, 16, 16, 16, 17, 18, 19, 20, 21, 23, 23, 24, 22, 10, 2, 0, 0, 0 },
                    { 0, 0, 5, 19, 20, 18, 18, 15, 14, 11, 11, 9, 8, 7, 7, 6, 6, 6, 6, 6, 6, 7, 8, 9, 10, 12, 13, 16, 17, 19, 20, 18, 10, 0, 0 },
                    { 0, 10, 17, 16, 16, 12, 12, 9, 8, 5, 5, 3, 3, 2, 2, 2, 2, 1, 1, 2, 2, 3, 3, 4, 4, 6, 7, 10, 11, 14, 15, 17, 18, 8, 1 },
                    { 16, 18, 17, 15, 13, 10, 8, 6, 6, 4, 3, 3, 3, 2, 1, 1, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 5, 7, 8, 11, 12, 15, 16, 18, 18 },
                    { 18, 18, 16, 14, 12, 11, 9, 8, 7, 6, 5, 4, 4, 3, 2, 2, 1, 0, 1, 1, 2, 3, 4, 4, 6, 6, 7, 8, 9, 10, 12, 14, 16, 18, 19 },
                    { 18, 18, 16, 15, 13, 13, 12, 11, 10, 10, 8, 7, 5, 5, 3, 2, 1, 0, 1, 2, 4, 5, 6, 6, 8, 9, 10, 11, 12, 12, 14, 14, 16, 16, 24 },
                    { 17, 17, 17, 16, 15, 14, 13, 13, 11, 11, 9, 9, 6, 6, 3, 3, 1, 0, 1, 2, 4, 5, 7, 8, 10, 10, 12, 12, 14, 14, 15, 15, 16, 17, 53 },
                    { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }
        };

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outer = new RectF(0f, 0f, BOX_W, BOX_H);
        private final RectF edge = new RectF(BORDER / 2f, BORDER / 2f, BOX_W - BORDER / 2f, BOX_H - BORDER / 2f);
        private final Path clip = new Path();
        private Bitmap cache;

        MasTextboxDrawable() {
            clip.addRoundRect(outer, RADIUS, RADIUS, Path.Direction.CW);
        }

        /** The fade as a vertical gradient of the given color */
        private Shader fade(int rgb) {
            int[] colors = new int[FADE.length];
            float[] positions = new float[FADE.length];
            for (int i = 0; i < FADE.length; i++) {
                colors[i] = (FADE[i][1] << 24) | rgb;
                positions[i] = FADE[i][0] / BOX_H;
            }
            return new LinearGradient(0f, 0f, 0f, BOX_H, colors, positions, Shader.TileMode.CLAMP);
        }

        /** White added by the shine at a point of the box, from 0 to 1 */
        private static float shineAt(float x, float y) {
            if (y < SHINE_TOP) {
                return 0f;
            }

            float fx = x / SHINE_STEP_X;
            float fy = (y - SHINE_TOP) / SHINE_STEP_Y;
            int rows = SHINE.length;
            int cols = SHINE[0].length;
            int i0 = Math.min((int) fx, cols - 1);
            int j0 = Math.min((int) fy, rows - 1);
            int i1 = Math.min(i0 + 1, cols - 1);
            int j1 = Math.min(j0 + 1, rows - 1);
            float tx = Math.min(fx - i0, 1f);
            float ty = Math.min(fy - j0, 1f);
            float top = SHINE[j0][i0] * (1f - tx) + SHINE[j0][i1] * tx;
            float bottom = SHINE[j1][i0] * (1f - tx) + SHINE[j1][i1] * tx;
            return (top * (1f - ty) + bottom * ty) / 100f;
        }

        private Bitmap render(int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(width / BOX_W, height / BOX_H);

            // The dots are cut out of the fill, so each pixel is drawn once: dots drawn on top of the fill
            // would pile up their opacity and look lighter than the fill towards the bottom
            Path dots = new Path();
            for (int row = -1; row < 8; row++) {
                float odd = row & 1;
                for (int col = -3; col < 22; col++) {
                    float x = 23.61f + 46.627f * col + 23.13f * odd + 0.259f * row;
                    float y = 23.06f + 23.635f * row + 0.0377f * col - 0.17f * odd;
                    if (x + DOT_RADIUS < 0f || x - DOT_RADIUS > BOX_W || y + DOT_RADIUS < 0f || y - DOT_RADIUS > BOX_H) {
                        continue;
                    }
                    dots.addCircle(x, y, DOT_RADIUS, Path.Direction.CW);
                }
            }
            Path outsideDots = new Path(dots);
            outsideDots.setFillType(Path.FillType.INVERSE_WINDING);

            // 1. Pink fill that gets more transparent towards the bottom, without the dots
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(fade(FILL_RGB));
            canvas.save();
            canvas.clipPath(clip);
            canvas.clipPath(outsideDots);
            canvas.drawRoundRect(outer, RADIUS, RADIUS, paint);
            canvas.restore();

            // 2. Polka dots, cut by the shape of the box, with the same fade
            canvas.save();
            canvas.clipPath(clip);
            paint.setShader(fade(DOTS_RGB));
            canvas.drawPath(dots, paint);
            canvas.restore();
            paint.setShader(null);

            // 3. Soft shine: it lightens the colors but keeps the opacity of each pixel
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int y = 0; y < height; y++) {
                float boxY = (y + 0.5f) * BOX_H / height;
                if (boxY < SHINE_TOP) {
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    int alpha = pixel >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    float white = shineAt((x + 0.5f) * BOX_W / width, boxY);
                    if (white <= 0f) {
                        continue;
                    }
                    int r = Math.round(((pixel >> 16) & 0xFF) * (1f - white) + 255f * white);
                    int g = Math.round(((pixel >> 8) & 0xFF) * (1f - white) + 255f * white);
                    int b = Math.round((pixel & 0xFF) * (1f - white) + 255f * white);
                    pixels[y * width + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            // 4. Pale border on top of everything
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(BORDER);
            paint.setColor(EDGE);
            canvas.drawRoundRect(edge, RADIUS - BORDER / 2f, RADIUS - BORDER / 2f, paint);
            return bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return;
            }

            if (cache == null || cache.getWidth() != bounds.width() || cache.getHeight() != bounds.height()) {
                cache = render(bounds.width(), bounds.height());
            }
            canvas.drawBitmap(cache, bounds.left, bounds.top, null);
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) BOX_W;
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) BOX_H;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
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

    /** First tap writes the whole page, the next one goes to the following page (if any). */
    private void completeTypewriterImmediately() {
        if (dialogueTextView == null || fullDialogueText == null) {
            return;
        }

        if (!isTypingComplete()) {
            if (typewriterHandler != null && typewriterRunnable != null) {
                typewriterHandler.removeCallbacks(typewriterRunnable);
            }
            typewriterIndex = fullDialogueText.length();
            setDialogueText(fullDialogueText);
            runAfterTyping();
        } else if (pageIndex < pages.length - 1) {
            pageIndex++;
            showPage();
        }
    }

    @Override
    public void onBackPressed() {
        // While the files are being copied there is nothing to go back to
        if (installing) {
            return;
        }
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
