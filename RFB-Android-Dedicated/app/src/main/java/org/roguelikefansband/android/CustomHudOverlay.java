package org.roguelikefansband.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * One coordinate space shared by the reserved control strip and every HUD key.
 * The strip is only a movable/resizable background: keys are never its children,
 * so they can cross freely between the game area and either docked side.
 */
public final class CustomHudOverlay extends FrameLayout {
    public interface Listener {
        void onAction(VirtualKeyStore.KeySpec spec);
        void onEditRequested(int index);
        void onPanelEditRequested();
        void onPositionChanged();
        void onPanelChanged();
    }

    private final Listener listener;
    private List<VirtualKeyStore.KeySpec> keys = new ArrayList<>();
    private VirtualKeyStore.HudPanelSpec panel = VirtualKeyStore.HudPanelSpec.defaults();
    private View panelView;
    private View resizeHandle;
    private boolean editing;
    private boolean gameAreaKeysHidden;

    public CustomHudOverlay(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
    }

    public void setKeys(List<VirtualKeyStore.KeySpec> value) {
        keys = value == null ? new ArrayList<>() : value;
        rebuild();
    }

    public void setPanel(VirtualKeyStore.HudPanelSpec value) {
        panel = value == null ? VirtualKeyStore.HudPanelSpec.defaults() : value;
        rebuild();
    }

    public boolean isEditing() {
        return editing;
    }

    public void setEditing(boolean value) {
        if (editing == value) return;
        editing = value;
        setClickable(editing);
        setFocusable(editing);
        setBackgroundColor(editing ? 0x292196F3 : Color.TRANSPARENT);
        rebuild();
    }

    /**
     * Temporarily suppress keys whose centre is outside the reserved operation strip.
     * The big keyboard occupies only the game area, so strip keys deliberately stay
     * visible and interactive while game-area keys cannot obscure the keyboard.
     */
    public void setGameAreaKeysHidden(boolean value) {
        if (gameAreaKeysHidden == value) return;
        gameAreaKeysHidden = value;
        post(this::updateGameAreaKeyVisibility);
    }

    private void rebuild() {
        removeAllViews();
        buildPanel();
        for (int i = 0; i < keys.size(); i++) {
            VirtualKeyStore.KeySpec spec = keys.get(i);
            TouchFeedbackButton button = new TouchFeedbackButton(getContext());
            boolean repeats = spec.repeat && !VirtualKeyStore.isFrontendAction(spec.action);
            button.setText(spec.label);
            button.setTextColor(Color.WHITE);
            button.setTextSize(textSizeFor(spec));
            button.setGravity(Gravity.CENTER);
            button.setMaxLines(3);
            button.setPadding(dp(2), 0, dp(2), 0);
            button.setContentDescription(spec.label + "，动作 " + spec.action
                    + (repeats ? "，支持长按连发" : ""));
            button.setElevation(dp(editing ? 8 : 3));
            button.applyStyle(spec.color, spec.opacity, spec.shape, editing);
            button.setTag(spec);

            LayoutParams params = new LayoutParams(dp(spec.widthDp), dp(spec.heightDp));
            addView(button, params);
            final int index = i;
            if (editing) {
                bindDrag(button, spec, index);
            } else {
                PressRepeater.bind(button, repeats, () -> listener.onAction(spec));
            }
        }
        post(this::applyPositions);
    }

    private void buildPanel() {
        panelView = new View(getContext());
        panelView.setContentDescription("可停靠操作区背景");
        panelView.setElevation(dp(1));
        panelView.setBackground(panelBackground());
        panelView.setVisibility(panel.visible || editing ? VISIBLE : INVISIBLE);
        addView(panelView, new LayoutParams(dp(panel.widthDp), LayoutParams.MATCH_PARENT));

        resizeHandle = null;
        if (editing) {
            bindPanelDrag(panelView);
            resizeHandle = new View(getContext());
            resizeHandle.setContentDescription("拖动调整操作区宽度");
            GradientDrawable handleBackground = new GradientDrawable();
            handleBackground.setColor(0xD9FFC107);
            handleBackground.setCornerRadius(dp(20));
            resizeHandle.setBackground(handleBackground);
            resizeHandle.setElevation(dp(7));
            addView(resizeHandle, new LayoutParams(dp(10), LayoutParams.MATCH_PARENT));
            bindPanelResize(resizeHandle);
        }
    }

    private GradientDrawable panelBackground() {
        GradientDrawable result = new GradientDrawable();
        if (panel.shape == TouchFeedbackButton.SHAPE_CIRCLE) {
            result.setShape(GradientDrawable.OVAL);
        } else {
            result.setShape(GradientDrawable.RECTANGLE);
            if (panel.shape == TouchFeedbackButton.SHAPE_ROUNDED) result.setCornerRadius(dp(9));
            else if (panel.shape == TouchFeedbackButton.SHAPE_CAPSULE) {
                result.setCornerRadius(dp(100));
            } else result.setCornerRadius(dp(1));
        }
        int opacity = panel.visible ? panel.opacity : 7;
        result.setColor(withPercentAlpha(panel.color, opacity));
        result.setStroke(dp(editing ? 2 : 1), editing ? 0xFFFFC107
                : withPercentAlpha(0xFF87919B, Math.min(100, opacity + 12)));
        return result;
    }

    private void bindPanelDrag(View target) {
        target.setOnClickListener(v -> listener.onPanelEditRequested());
        target.setOnTouchListener(new OnTouchListener() {
            private float downRawX;
            private float startX;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        startX = target.getX();
                        moved = false;
                        target.setPressed(true);
                        target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        if (Math.abs(dx) > dp(3)) moved = true;
                        target.setX(clamp(startX + dx, 0f,
                                Math.max(0f, getWidth() - target.getWidth())));
                        positionResizeHandle(target.getX());
                        return true;
                    case MotionEvent.ACTION_UP:
                        target.setPressed(false);
                        if (moved) {
                            panel.dockRight = target.getX() + target.getWidth() / 2f
                                    >= getWidth() / 2f;
                            applyPositions();
                            listener.onPanelChanged();
                        } else {
                            target.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        target.setPressed(false);
                        applyPositions();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void bindPanelResize(View handle) {
        handle.setOnTouchListener(new OnTouchListener() {
            private float downRawX;
            private int startWidthDp;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        startWidthDp = panel.widthDp;
                        handle.setPressed(true);
                        handle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float density = getResources().getDisplayMetrics().density;
                        int deltaDp = Math.round((event.getRawX() - downRawX) / density);
                        int requested = panel.dockRight
                                ? startWidthDp - deltaDp : startWidthDp + deltaDp;
                        panel.widthDp = clamp(requested,
                                VirtualKeyStore.MIN_PANEL_WIDTH_DP,
                                VirtualKeyStore.MAX_PANEL_WIDTH_DP);
                        applyPositions();
                        return true;
                    case MotionEvent.ACTION_UP:
                        handle.setPressed(false);
                        listener.onPanelChanged();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handle.setPressed(false);
                        listener.onPanelChanged();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void bindDrag(TouchFeedbackButton button, VirtualKeyStore.KeySpec spec, int index) {
        button.setOnClickListener(v -> listener.onEditRequested(index));
        button.setOnTouchListener(new OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private float startX;
            private float startY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = button.getX();
                        startY = button.getY();
                        moved = false;
                        button.bringToFront();
                        button.beginManualPress();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) moved = true;
                        button.setX(clamp(startX + dx, 0f,
                                Math.max(0f, getWidth() - button.getWidth())));
                        button.setY(clamp(startY + dy, 0f,
                                Math.max(0f, getHeight() - button.getHeight())));
                        return true;
                    case MotionEvent.ACTION_UP:
                        finishDrag(button, spec);
                        if (!moved) button.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        finishDrag(button, spec);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void finishDrag(TouchFeedbackButton button, VirtualKeyStore.KeySpec spec) {
        button.endManualPress();
        int travelX = Math.max(0, getWidth() - button.getWidth());
        int travelY = Math.max(0, getHeight() - button.getHeight());
        spec.x = travelX == 0 ? 0f : clamp(button.getX() / travelX, 0f, 1f);
        spec.y = travelY == 0 ? 0f : clamp(button.getY() / travelY, 0f, 1f);
        listener.onPositionChanged();
    }

    private void applyPositions() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        applyPanelPosition();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof VirtualKeyStore.KeySpec)) continue;
            VirtualKeyStore.KeySpec spec = (VirtualKeyStore.KeySpec) tag;
            int width = child.getWidth() > 0 ? child.getWidth() : dp(spec.widthDp);
            int height = child.getHeight() > 0 ? child.getHeight() : dp(spec.heightDp);
            child.setX(clamp(spec.x, 0f, 1f) * Math.max(0, getWidth() - width));
            child.setY(clamp(spec.y, 0f, 1f) * Math.max(0, getHeight() - height));
        }
        updateGameAreaKeyVisibility();
    }

    private void applyPanelPosition() {
        if (panelView == null) return;
        int panelWidth = effectivePanelWidthPx();
        LayoutParams params = (LayoutParams) panelView.getLayoutParams();
        if (params.width != panelWidth || params.height != getHeight()) {
            params.width = panelWidth;
            params.height = getHeight();
            panelView.setLayoutParams(params);
        }
        float x = panel.dockRight ? Math.max(0, getWidth() - panelWidth) : 0f;
        panelView.setX(x);
        panelView.setY(0f);
        panelView.setBackground(panelBackground());
        positionResizeHandle(x);
    }

    private int effectivePanelWidthPx() {
        int minimum = dp(VirtualKeyStore.MIN_PANEL_WIDTH_DP);
        int maximum = Math.max(minimum,
                getWidth() - dp(VirtualKeyStore.MIN_GAME_AREA_DP));
        return Math.max(minimum, Math.min(dp(panel.widthDp), maximum));
    }

    private void updateGameAreaKeyVisibility() {
        if (getWidth() <= 0) return;
        int panelWidth = effectivePanelWidthPx();
        float panelStart = panel.dockRight ? getWidth() - panelWidth : 0f;
        float panelEnd = panel.dockRight ? getWidth() : panelWidth;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child.getTag() instanceof VirtualKeyStore.KeySpec)) continue;
            float centerX = child.getX() + child.getWidth() / 2f;
            boolean insideOperationStrip = centerX >= panelStart && centerX <= panelEnd;
            boolean show = editing || !gameAreaKeysHidden || insideOperationStrip;
            child.setVisibility(show ? VISIBLE : INVISIBLE);
            child.setEnabled(show);
        }
    }

    private void positionResizeHandle(float panelX) {
        if (resizeHandle == null || panelView == null) return;
        int handleWidth = resizeHandle.getWidth() > 0 ? resizeHandle.getWidth() : dp(10);
        int panelWidth = panelView.getLayoutParams().width > 0
                ? panelView.getLayoutParams().width : panelView.getWidth();
        float x = panel.dockRight ? panelX : panelX + panelWidth - handleWidth;
        resizeHandle.setX(clamp(x, 0f, Math.max(0f, getWidth() - handleWidth)));
        resizeHandle.setY(0f);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::applyPositions);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return editing;
    }

    private float textSizeFor(VirtualKeyStore.KeySpec spec) {
        float size = Math.max(8f, Math.min(14f, spec.heightDp * 0.27f));
        if (spec.label != null && spec.label.length() > 8) size *= 0.83f;
        return size;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int withPercentAlpha(int color, int percent) {
        int alpha = Color.alpha(color) * clamp(percent, 0, 100) / 100;
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
