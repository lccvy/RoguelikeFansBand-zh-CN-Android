package org.roguelikefansband.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.util.Arrays;

/** Draws RFB's Unicode/RGB/tile terminal and accepts direct or fallback IME input. */
public final class RfbTermView extends View {
    private static final String TAG = "RFB-Term";

    private static final int FRAME_HEADER = 10;
    private static final int CELL_STRIDE = 11;
    private static final int FRAME_LAYOUT_VERSION = 2;
    private static final int WIDE_TRAIL = 0xFFFFFFFF;
    private static final int CELL_TILE_VALID = 0x01;
    private static final long POLL_MS = 16L;

    private static final int C_CP = 0;
    private static final int C_FG = 1;
    private static final int C_BG = 2;
    private static final int C_BORDER_RGB = 3;
    private static final int C_BORDER_FLAGS = 4;
    private static final int C_LEGACY = 5;
    private static final int C_TILE_FLAGS = 6;
    private static final int C_TILE_ROW = 7;
    private static final int C_TILE_COL = 8;
    private static final int C_TERRAIN_ROW = 9;
    private static final int C_TERRAIN_COL = 10;

    private final Paint backgroundPaint = new Paint();
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint();
    private final Typeface latinTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
    private final Typeface cjkTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private int[] frame = new int[0];
    private int cols = 80;
    private int rows = 27;
    private long lastGeneration = Long.MIN_VALUE;
    private float fontScale = 1.0f;
    private boolean polling;
    private Runnable keyboardRequestListener;

    private Bitmap atlas8;
    private Bitmap atlas16;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            try {
                pollFrame();
            } catch (Throwable error) {
                polling = false;
                Log.e(TAG, "Frame polling stopped after JNI/Java failure", error);
                StartupDiagnostics.recordThrowable("term.poll", error);
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    public RfbTermView(Context context) {
        this(context, null);
    }

    public RfbTermView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF000000);
        backgroundPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setTypeface(latinTypeface);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f);
        tilePaint.setAntiAlias(false);
        tilePaint.setFilterBitmap(false);
        tilePaint.setDither(false);
    }

    /** Load RFB's own 8x8 and 16x16 atlas PNGs from the installed lib tree. */
    public void configureRuntimeRoot(File runtimeRoot) {
        recycleAtlas(atlas8);
        recycleAtlas(atlas16);
        atlas8 = null;
        atlas16 = null;
        if (runtimeRoot == null) return;

        File graf = new File(runtimeRoot, "lib/xtra/graf");
        atlas8 = decodeAtlas(new File(graf, "8x8.png"));
        atlas16 = decodeAtlas(new File(graf, "16x16.png"));
        Log.i(TAG, "Atlas 8x8=" + bitmapInfo(atlas8) +
                " 16x16=" + bitmapInfo(atlas16));
        invalidate();
    }

    private static Bitmap decodeAtlas(File file) {
        if (!file.isFile()) {
            Log.w(TAG, "Missing tile atlas: " + file);
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static String bitmapInfo(Bitmap bitmap) {
        return bitmap == null ? "missing" : bitmap.getWidth() + "x" + bitmap.getHeight();
    }

    private static void recycleAtlas(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    public void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    public void stopPolling() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
    }

    public void setFontScale(float scale) {
        fontScale = Math.max(0.70f, Math.min(1.35f, scale));
        invalidate();
    }

    public float getFontScale() {
        return fontScale;
    }

    public void setKeyboardRequestListener(Runnable listener) {
        keyboardRequestListener = listener;
    }

    public void showKeyboard() {
        if (keyboardRequestListener != null) {
            keyboardRequestListener.run();
            return;
        }
        requestFocus();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    private void pollFrame() {
        long generation = RfbNative.nativeGeneration();
        if (generation == lastGeneration) return;
        int count = RfbNative.nativeFrameIntCount();
        if (count <= FRAME_HEADER) return;
        if (frame.length != count) frame = new int[count];
        int copied = RfbNative.nativeCopyFrame(frame);
        if (copied > 0 && frame.length >= FRAME_HEADER && frame[9] == FRAME_LAYOUT_VERSION) {
            cols = Math.max(1, frame[0]);
            rows = Math.max(1, frame[1]);
            lastGeneration = generation;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame.length < FRAME_HEADER + cols * rows * CELL_STRIDE) {
            drawWaitingMessage(canvas);
            return;
        }

        float cellW = getWidth() / (float) cols;
        float cellH = getHeight() / (float) rows;
        float textSize = Math.max(5f, cellH * 0.82f * fontScale);
        glyphPaint.setTextSize(textSize);
        Paint.FontMetrics fm = glyphPaint.getFontMetrics();
        float baselineOffset = (cellH - (fm.descent - fm.ascent)) * 0.5f - fm.ascent;

        drawBackgrounds(canvas, cellW, cellH);
        drawTiles(canvas, cellW, cellH);
        drawGlyphsAndBorders(canvas, cellW, cellH, baselineOffset);
        drawCursor(canvas, cellW, cellH);
    }

    private void drawWaitingMessage(Canvas canvas) {
        glyphPaint.setTypeface(cjkTypeface);
        glyphPaint.setTextSize(Math.max(16f, getHeight() / 18f));
        glyphPaint.setColor(0xFFDDDDDD);
        canvas.drawText("正在初始化 RoguelikeFansBand……", 24f,
                Math.max(40f, getHeight() * 0.5f), glyphPaint);
    }

    private void drawBackgrounds(Canvas canvas, float cellW, float cellH) {
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int p = cellOffset(x, y);
                int bg = frame[p + C_BG] & 0x00FFFFFF;
                if (bg == 0) continue;
                backgroundPaint.setColor(0xFF000000 | bg);
                canvas.drawRect(x * cellW, y * cellH,
                        (x + 1) * cellW, (y + 1) * cellH, backgroundPaint);
            }
        }
    }

    private void drawTiles(Canvas canvas, float cellW, float cellH) {
        int graphicsMode = frame[7];
        boolean bigtile = frame[8] != 0;
        Bitmap atlas = graphicsMode == RfbNative.GRAPHICS_ADAM_BOLT ? atlas16
                : graphicsMode == RfbNative.GRAPHICS_ORIGINAL ? atlas8 : null;
        int tileSize = graphicsMode == RfbNative.GRAPHICS_ADAM_BOLT ? 16 : 8;
        if (atlas == null || atlas.isRecycled()) return;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int p = cellOffset(x, y);
                if ((frame[p + C_TILE_FLAGS] & CELL_TILE_VALID) == 0) continue;

                float width = cellW * (bigtile ? 2f : 1f);
                dstRect.set(x * cellW, y * cellH,
                        Math.min(getWidth(), x * cellW + width), (y + 1) * cellH);

                if (graphicsMode == RfbNative.GRAPHICS_ADAM_BOLT) {
                    drawAtlasTile(canvas, atlas, tileSize,
                            frame[p + C_TERRAIN_ROW], frame[p + C_TERRAIN_COL], dstRect);
                }
                drawAtlasTile(canvas, atlas, tileSize,
                        frame[p + C_TILE_ROW], frame[p + C_TILE_COL], dstRect);
            }
        }
    }

    private void drawAtlasTile(Canvas canvas, Bitmap atlas, int tileSize,
                               int row, int col, RectF destination) {
        if (!isTileDrawable(atlas, tileSize, row, col)) return;
        int left = col * tileSize;
        int top = row * tileSize;
        srcRect.set(left, top, left + tileSize, top + tileSize);
        canvas.drawBitmap(atlas, srcRect, destination, tilePaint);
    }

    private void drawGlyphsAndBorders(Canvas canvas, float cellW, float cellH,
                                      float baselineOffset) {
        int graphicsMode = frame[7];
        Bitmap activeAtlas = graphicsMode == RfbNative.GRAPHICS_ADAM_BOLT ? atlas16
                : graphicsMode == RfbNative.GRAPHICS_ORIGINAL ? atlas8 : null;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int p = cellOffset(x, y);
                int cp = frame[p + C_CP];
                int fg = frame[p + C_FG] & 0x00FFFFFF;
                int borderRgb = frame[p + C_BORDER_RGB] & 0x00FFFFFF;
                int border = frame[p + C_BORDER_FLAGS] & 0xFF;
                int legacy = frame[p + C_LEGACY] & 0xFF;
                int tileSize = graphicsMode == RfbNative.GRAPHICS_ADAM_BOLT ? 16 : 8;
                boolean tileDrawn = activeAtlas != null && !activeAtlas.isRecycled()
                        && (frame[p + C_TILE_FLAGS] & CELL_TILE_VALID) != 0
                        && isTileDrawable(activeAtlas, tileSize,
                                frame[p + C_TILE_ROW], frame[p + C_TILE_COL]);

                if (!tileDrawn && cp != WIDE_TRAIL && cp > 0
                        && cp <= Character.MAX_CODE_POINT) {
                    if (legacy == 127) {
                        drawWallCell(canvas, x, y, cellW, cellH, fg);
                    } else if (!Character.isISOControl(cp)) {
                        boolean wide = x + 1 < cols
                                && frame[cellOffset(x + 1, y) + C_CP] == WIDE_TRAIL;
                        drawCodePoint(canvas, cp, x, y, cellW, cellH,
                                baselineOffset, fg, wide);
                    }
                }
                if (border != 0) {
                    drawBorder(canvas, x, y, cellW, cellH,
                            border, borderRgb != 0 ? borderRgb : fg);
                }
            }
        }
    }

    private int cellOffset(int x, int y) {
        return FRAME_HEADER + (y * cols + x) * CELL_STRIDE;
    }

    private void drawCodePoint(Canvas canvas, int cp, int x, int y,
                               float cellW, float cellH, float baselineOffset,
                               int rgb, boolean wide) {
        glyphPaint.setTypeface(wide ? cjkTypeface : latinTypeface);
        glyphPaint.setColor(0xFF000000 | rgb);
        String text = new String(Character.toChars(cp));
        float left = x * cellW;
        float top = y * cellH;
        float width = wide ? cellW * 2f : cellW;
        float measured = glyphPaint.measureText(text);
        float drawX = left + Math.max(0f, (width - measured) * 0.5f);
        float drawY = top + baselineOffset;

        int save = canvas.save();
        canvas.clipRect(left, top, Math.min(getWidth(), left + width), top + cellH);
        canvas.drawText(text, drawX, drawY, glyphPaint);
        canvas.restoreToCount(save);
    }

    /** Procedural replacement for the Windows bitmap wall glyph (legacy byte 127). */
    private void drawWallCell(Canvas canvas, int x, int y, float cellW, float cellH, int rgb) {
        float l = x * cellW;
        float t = y * cellH;
        float r = l + cellW;
        float b = t + cellH;
        backgroundPaint.setColor(0xFF000000 | rgb);
        float gapX = Math.max(1f, cellW * 0.12f);
        float gapY = Math.max(1f, cellH * 0.10f);
        canvas.drawRect(l + gapX, t + gapY, r - gapX, t + cellH * 0.45f, backgroundPaint);
        canvas.drawRect(l + gapX, t + cellH * 0.55f, r - gapX, b - gapY, backgroundPaint);
        linePaint.setColor(0xFF000000);
        linePaint.setStrokeWidth(Math.max(1f, cellW * 0.08f));
        canvas.drawLine((l + r) * 0.5f, t + gapY,
                (l + r) * 0.5f, t + cellH * 0.45f, linePaint);
        canvas.drawLine(l + cellW * 0.30f, t + cellH * 0.55f,
                l + cellW * 0.30f, b - gapY, linePaint);
    }

    private void drawBorder(Canvas canvas, int x, int y, float cellW, float cellH,
                            int flags, int rgb) {
        float l = x * cellW;
        float t = y * cellH;
        float r = l + cellW;
        float b = t + cellH;
        linePaint.setColor(0xFF000000 | rgb);
        linePaint.setStrokeWidth(Math.max(1f, Math.min(cellW, cellH) * 0.08f));
        if ((flags & 0x01) != 0) canvas.drawLine(l, t, r, t, linePaint);
        if ((flags & 0x02) != 0) canvas.drawLine(r, t, r, b, linePaint);
        if ((flags & 0x04) != 0) canvas.drawLine(l, b, r, b, linePaint);
        if ((flags & 0x08) != 0) canvas.drawLine(l, t, l, b, linePaint);
    }

    private void drawCursor(Canvas canvas, float cellW, float cellH) {
        if (frame[4] == 0) return;
        int cx = frame[2];
        int cy = frame[3];
        if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) return;
        float width = frame[5] != 0 ? cellW * 2f : cellW;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1.5f, Math.min(cellW, cellH) * 0.12f));
        linePaint.setColor(0xFFFFFFFF);
        RectF rect = new RectF(cx * cellW + 1f, cy * cellH + 1f,
                Math.min(getWidth(), cx * cellW + width - 1f),
                (cy + 1) * cellH - 1f);
        canvas.drawRect(rect, linePaint);
    }

    private static boolean isTileDrawable(Bitmap atlas, int tileSize, int row, int col) {
        if (atlas == null || atlas.isRecycled() || tileSize <= 0 || row < 0 || col < 0) {
            return false;
        }
        long right = ((long) col + 1L) * tileSize;
        long bottom = ((long) row + 1L) * tileSize;
        return right <= atlas.getWidth() && bottom <= atlas.getHeight();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && text.length() > 0) RfbNative.sendText(text.toString());
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Do not inject IME composing/preedit text into the turn-based core.
                return true;
            }

            @Override
            public boolean finishComposingText() {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0) RfbNative.sendByte(8);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return handleKeyEvent(event) || super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKeyEvent(event) || super.onKeyDown(keyCode, event);
    }

    private boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                RfbNative.sendByte(13); return true;
            case KeyEvent.KEYCODE_DEL:
                RfbNative.sendByte(8); return true;
            case KeyEvent.KEYCODE_ESCAPE:
                RfbNative.sendByte(27); return true;
            case KeyEvent.KEYCODE_TAB:
                RfbNative.sendByte(9); return true;
            case KeyEvent.KEYCODE_SPACE:
                RfbNative.sendByte(32); return true;
            default:
                break;
        }

        byte[] trigger = RfbKeyEncoder.encode(event);
        if (trigger != null) {
            if (trigger.length > 0) RfbNative.sendBytes(trigger);
            return true;
        }

        int unicode = event.getUnicodeChar(event.getMetaState());
        if (unicode > 0 && unicode <= Character.MAX_CODE_POINT) {
            RfbNative.sendText(new String(Character.toChars(unicode)));
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            showKeyboard();
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPolling();
        Arrays.fill(frame, 0);
        recycleAtlas(atlas8);
        recycleAtlas(atlas16);
        atlas8 = null;
        atlas16 = null;
        super.onDetachedFromWindow();
    }
}
