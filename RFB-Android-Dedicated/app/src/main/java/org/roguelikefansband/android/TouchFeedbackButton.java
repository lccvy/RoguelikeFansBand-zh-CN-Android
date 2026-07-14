package org.roguelikefansband.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.Button;

/** Button with explicit visual, scale and haptic feedback for touch screens. */
public final class TouchFeedbackButton extends Button {
    public static final int SHAPE_RECTANGLE = 0;
    public static final int SHAPE_ROUNDED = 1;
    public static final int SHAPE_CIRCLE = 2;
    public static final int SHAPE_CAPSULE = 3;

    private boolean manualPress;

    public TouchFeedbackButton(Context context) {
        super(context);
        initialize();
    }

    public TouchFeedbackButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        setAllCaps(false);
        setHapticFeedbackEnabled(true);
        setSoundEffectsEnabled(true);
        setMinWidth(0);
        setMinHeight(0);
        setIncludeFontPadding(false);
    }

    public void applyStyle(int color, int opacityPercent, int shape, boolean editing) {
        int opacity = clamp(opacityPercent, 20, 100);
        int normal = withAlpha(color, opacity);
        int pressed = withAlpha(blend(color, Color.WHITE, 0.45f),
                Math.min(100, opacity + 8));
        int stroke = editing ? 0xFFFFC107 : withAlpha(0xFF8A949E,
                Math.min(100, opacity + 12));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed},
                drawable(pressed, stroke, shape, editing ? 3 : 2));
        states.addState(new int[] {android.R.attr.state_focused},
                drawable(pressed, 0xFFFFFFFF, shape, 2));
        states.addState(new int[0], drawable(normal, stroke, shape, editing ? 3 : 1));
        setBackground(states);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !manualPress) {
            beginFeedback();
        } else if ((event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && !manualPress) {
            endFeedback();
        }
        return super.onTouchEvent(event);
    }

    public void beginManualPress() {
        manualPress = true;
        setPressed(true);
        beginFeedback();
    }

    public void endManualPress() {
        setPressed(false);
        endFeedback();
        manualPress = false;
    }

    private void beginFeedback() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        animate().scaleX(0.91f).scaleY(0.91f).setDuration(45L).start();
    }

    private void endFeedback() {
        animate().scaleX(1f).scaleY(1f).setDuration(70L).start();
    }

    private GradientDrawable drawable(int fill, int stroke, int shape, int strokeWidthDp) {
        GradientDrawable result = new GradientDrawable();
        if (shape == SHAPE_CIRCLE) {
            result.setShape(GradientDrawable.OVAL);
        } else {
            result.setShape(GradientDrawable.RECTANGLE);
            if (shape == SHAPE_ROUNDED) result.setCornerRadius(dp(8));
            else if (shape == SHAPE_CAPSULE) result.setCornerRadius(dp(100));
            else result.setCornerRadius(dp(1));
        }
        result.setColor(fill);
        result.setStroke(dp(strokeWidthDp), stroke);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, int percent) {
        int alpha = Color.alpha(color) * clamp(percent, 0, 100) / 100;
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int red = Math.round(Color.red(from) * (1f - t) + Color.red(to) * t);
        int green = Math.round(Color.green(from) * (1f - t) + Color.green(to) * t);
        int blue = Math.round(Color.blue(from) * (1f - t) + Color.blue(to) * t);
        return Color.rgb(red, green, blue);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
