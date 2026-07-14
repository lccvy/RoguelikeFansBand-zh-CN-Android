package org.roguelikefansband.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/**
 * Compact in-activity keyboard overlay.  It is deliberately not a Dialog:
 * touches outside its own rectangle can still reach the game and reserved-strip HUD.
 */
public final class GameKeyboardDialog {
    public interface InputSink {
        void send(byte[] bytes);
    }

    private static final int MOD_OFF = 0;
    private static final int MOD_ONCE = 1;
    private static final int MOD_LOCKED = 2;

    private static final String PREFS = "rfb_frontend";
    private static final String PREF_SIZE = "game_keyboard_size_percent";
    private static final String PREF_WIDTH = "game_keyboard_width_percent";
    private static final String PREF_OPACITY = "game_keyboard_opacity_percent";
    private static final String PREF_TOP = "game_keyboard_at_top";
    private static final String PREF_PURPOSE = "game_keyboard_show_purpose";

    private static final int DEFAULT_SIZE = 68;
    private static final int DEFAULT_WIDTH = 100;
    private static final int DEFAULT_OPACITY = 82;
    private static final int MIN_SIZE = 45;
    private static final int MAX_SIZE = 105;
    private static final int MIN_WIDTH = 60;
    private static final int MAX_WIDTH = 100;
    private static final int MIN_OPACITY = 20;
    private static final int MAX_OPACITY = 100;

    private final Activity activity;
    private final FrameLayout host;
    private final InputSink sink;
    private final SharedPreferences preferences;

    private LinearLayout rootLayout;
    private LinearLayout keyArea;
    private TextView stateView;
    private Runnable dismissListener;
    private int ctrlState;
    private int shiftState;
    private boolean showPurpose;
    private int keyboardSizePercent;
    private int keyboardWidthPercent;
    private int keyboardOpacityPercent;
    private boolean keyboardAtTop;

    public GameKeyboardDialog(Activity activity, FrameLayout host, InputSink sink) {
        this.activity = activity;
        this.host = host;
        this.sink = sink;
        preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        keyboardSizePercent = clamp(preferences.getInt(PREF_SIZE, DEFAULT_SIZE),
                MIN_SIZE, MAX_SIZE);
        keyboardWidthPercent = clamp(preferences.getInt(PREF_WIDTH, DEFAULT_WIDTH),
                MIN_WIDTH, MAX_WIDTH);
        keyboardOpacityPercent = clamp(preferences.getInt(PREF_OPACITY, DEFAULT_OPACITY),
                MIN_OPACITY, MAX_OPACITY);
        keyboardAtTop = preferences.getBoolean(PREF_TOP, false);
        showPurpose = preferences.getBoolean(PREF_PURPOSE, true);
    }

    public void setOnDismissListener(Runnable listener) {
        dismissListener = listener;
    }

    public boolean isShowing() {
        return rootLayout != null && rootLayout.getParent() == host;
    }

    public void show() {
        if (isShowing()) return;
        rootLayout = new LinearLayout(activity);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setElevation(dp(12));
        host.addView(rootLayout);
        refresh();
    }

    public void dismiss() {
        LinearLayout oldRoot = rootLayout;
        rootLayout = null;
        keyArea = null;
        stateView = null;
        if (oldRoot != null && oldRoot.getParent() == host) host.removeView(oldRoot);
        if (dismissListener != null) dismissListener.run();
    }

    private void refresh() {
        if (rootLayout == null) return;
        rootLayout.removeAllViews();
        rootLayout.setPadding(dp(2), dp(2), dp(2), dp(2));
        rootLayout.setBackground(makePanelBackground());

        int headerHeight = scaledDp(30);
        LinearLayout header = newRow();

        stateView = new TextView(activity);
        stateView.setTextColor(Color.WHITE);
        stateView.setTextSize(scaledTextSize(9.5f));
        stateView.setSingleLine(true);
        stateView.setGravity(Gravity.CENTER_VERTICAL);
        stateView.setPadding(dp(4), 0, dp(2), 0);
        stateView.setText(stateDescription());
        stateView.setContentDescription(
                "游戏直通键盘。Ctrl 和 Shift 轻触作用于下一键，长按锁定。");
        header.addView(stateView, new LinearLayout.LayoutParams(0, headerHeight, 1f));

        TouchFeedbackButton adjust = makeButton("调节", 9f);
        adjust.setContentDescription("调节键盘大小、宽度、透明度和位置");
        PressRepeater.bind(adjust, false, this::showKeyboardSettings);
        header.addView(adjust, headerButtonParams(48, headerHeight));

        TouchFeedbackButton purpose = makeButton(showPurpose ? "用途开" : "用途关", 8.5f);
        purpose.setContentDescription("切换按键用途标签");
        PressRepeater.bind(purpose, false, () -> {
            showPurpose = !showPurpose;
            persistKeyboardPreferences();
            refresh();
        });
        header.addView(purpose, headerButtonParams(53, headerHeight));

        TouchFeedbackButton close = makeButton("关闭", 9f);
        close.setContentDescription("关闭大键盘");
        PressRepeater.bind(close, false, this::dismiss);
        header.addView(close, headerButtonParams(46, headerHeight));
        rootLayout.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerHeight));

        keyArea = new LinearLayout(activity);
        keyArea.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(keyArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout specials = newRow();
        addActionKey(specials, "Esc", "取消/返回", "{ESC}", 1f, false);
        addActionKey(specials, "Tab", "切换焦点", "{TAB}", 1f, false);
        addActionKey(specials, "退格", "删除字符", "{BS}", 1f, true);
        addActionKey(specials, "Enter", "确认/执行", "{ENTER}", 1.25f, false);
        keyArea.addView(specials, rowParams(32));

        addCharacterRow("`1234567890-=");
        addCharacterRow("qwertyuiop[]\\");
        addCharacterRow("asdfghjkl;'");
        addCharacterRow("zxcvbnm,./");
        addDirectionRow();

        LinearLayout bottom = newRow();
        TouchFeedbackButton ctrl = makeModifierButton(modifierLabel("Ctrl", ctrlState), ctrlState);
        ctrl.setContentDescription("Ctrl 键：轻触单次，长按锁定");
        ctrl.setOnClickListener(v -> {
            ctrlState = ctrlState == MOD_OFF ? MOD_ONCE : MOD_OFF;
            refresh();
        });
        ctrl.setOnLongClickListener(v -> {
            ctrlState = ctrlState == MOD_LOCKED ? MOD_OFF : MOD_LOCKED;
            refresh();
            return true;
        });
        bottom.addView(ctrl, weightedParams(1.15f));

        TouchFeedbackButton shift = makeModifierButton(modifierLabel("Shift", shiftState), shiftState);
        shift.setContentDescription("Shift 键：轻触单次，长按锁定");
        shift.setOnClickListener(v -> {
            shiftState = shiftState == MOD_OFF ? MOD_ONCE : MOD_OFF;
            refresh();
        });
        shift.setOnLongClickListener(v -> {
            shiftState = shiftState == MOD_LOCKED ? MOD_OFF : MOD_LOCKED;
            refresh();
            return true;
        });
        bottom.addView(shift, weightedParams(1.15f));

        addActionKey(bottom, "空格", "继续/翻页", "{SPACE}", 3.2f, false);
        keyArea.addView(bottom, rowParams(34));

        applyOverlayPreferences();
    }

    private void addCharacterRow(String characters) {
        LinearLayout row = newRow();
        for (int i = 0; i < characters.length(); i++) {
            char base = characters.charAt(i);
            char output = shifted(base, shiftState != MOD_OFF);
            boolean ctrl = ctrlState != MOD_OFF;
            String shown = ctrl && isCtrlCharacter(base)
                    ? "Ctrl+" + Character.toUpperCase(base)
                    : String.valueOf(output);
            String purpose = GameCommandCatalog.label(ctrl ? base : output, ctrl);
            String label = showPurpose ? shown + "\n" + purpose : shown;
            TouchFeedbackButton button = makeButton(label, showPurpose ? 7.2f : 12.5f);
            button.setContentDescription(shown + "，" + purpose + "，按住可连发");
            /* Ctrl/one-shot Shift must be consumed exactly once; locked/plain keys repeat. */
            boolean repeat = ctrlState == MOD_OFF && shiftState != MOD_ONCE;
            PressRepeater.bind(button, repeat, () -> sendCharacter(base));
            row.addView(button, weightedParams(1f));
        }
        keyArea.addView(row, rowParams(31));
    }

    private void addDirectionRow() {
        LinearLayout row = newRow();
        String[] keys = {"7↖", "8↑", "9↗", "4←", "5·", "6→", "1↙", "2↓", "3↘"};
        String[] purposes = {"西北", "向上", "东北", "向左", "等待", "向右", "西南", "向下", "东南"};
        String[] actions = {"7", "8", "9", "4", "5", "6", "1", "2", "3"};
        for (int i = 0; i < keys.length; i++) {
            addActionKey(row, keys[i], purposes[i], actions[i], 1f, true);
        }
        keyArea.addView(row, rowParams(34));
    }

    private void sendCharacter(char base) {
        boolean ctrl = ctrlState != MOD_OFF;
        byte[] bytes;
        if (ctrl && isCtrlCharacter(base)) {
            char upper = Character.toUpperCase(base);
            bytes = new byte[] {(byte) (upper & 0x1F)};
        } else {
            char output = shifted(base, shiftState != MOD_OFF);
            bytes = String.valueOf(output).getBytes(StandardCharsets.UTF_8);
        }
        sink.send(bytes);
        consumeOneShotModifiers();
    }

    private void addActionKey(LinearLayout row, String key, String purpose,
                              String action, float weight, boolean repeat) {
        String label = showPurpose ? key + "\n" + purpose : key;
        TouchFeedbackButton button = makeButton(label, showPurpose ? 7.4f : 11.5f);
        button.setContentDescription(key + "，" + purpose + (repeat ? "，按住可连发" : ""));
        PressRepeater.bind(button, repeat, () -> {
            sink.send(GameKeySequence.encode(action));
            consumeOneShotModifiers();
        });
        row.addView(button, weightedParams(weight));
    }

    private void consumeOneShotModifiers() {
        boolean changed = false;
        if (ctrlState == MOD_ONCE) {
            ctrlState = MOD_OFF;
            changed = true;
        }
        if (shiftState == MOD_ONCE) {
            shiftState = MOD_OFF;
            changed = true;
        }
        if (changed) refresh();
    }

    private void showKeyboardSettings() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(4), dp(18), 0);

        addSeekSetting(panel, "键帽与整体高度", MIN_SIZE, MAX_SIZE, keyboardSizePercent,
                value -> {
                    keyboardSizePercent = value;
                    persistKeyboardPreferences();
                    refresh();
                }, value -> value + "%");
        addSeekSetting(panel, "键盘宽度", MIN_WIDTH, MAX_WIDTH, keyboardWidthPercent,
                value -> {
                    keyboardWidthPercent = value;
                    persistKeyboardPreferences();
                    applyOverlayPreferences();
                    updateStateDescription();
                }, value -> value + "%");
        addSeekSetting(panel, "键盘不透明度", MIN_OPACITY, MAX_OPACITY,
                keyboardOpacityPercent,
                value -> {
                    keyboardOpacityPercent = value;
                    persistKeyboardPreferences();
                    refresh();
                }, value -> value + "%");

        CheckBox top = new CheckBox(activity);
        top.setText("显示在游戏区顶部（不勾选时贴底）");
        top.setChecked(keyboardAtTop);
        top.setOnCheckedChangeListener((button, checked) -> {
            keyboardAtTop = checked;
            persistKeyboardPreferences();
            applyOverlayPreferences();
            updateStateDescription();
        });
        panel.addView(top);

        CheckBox purpose = new CheckBox(activity);
        purpose.setText("在键帽上显示小写、大写和 Ctrl 对应用途");
        purpose.setChecked(showPurpose);
        purpose.setOnCheckedChangeListener((button, checked) -> {
            showPurpose = checked;
            persistKeyboardPreferences();
            refresh();
        });
        panel.addView(purpose);

        AlertDialog settings = new AlertDialog.Builder(activity)
                .setTitle("大键盘显示调节")
                .setMessage("此键盘只覆盖游戏区；游戏区 HUD 按键会临时隐藏，操作区 HUD 按键继续可用。"
                        + "降低不透明度即可看到键帽下的画面。")
                .setView(panel)
                .setPositiveButton("完成", null)
                .setNeutralButton("恢复紧凑默认", (dialog, which) -> {
                    keyboardSizePercent = DEFAULT_SIZE;
                    keyboardWidthPercent = DEFAULT_WIDTH;
                    keyboardOpacityPercent = DEFAULT_OPACITY;
                    keyboardAtTop = false;
                    persistKeyboardPreferences();
                    refresh();
                })
                .create();
        settings.setOnShowListener(ignored -> {
            Window settingsWindow = settings.getWindow();
            if (settingsWindow != null) {
                settingsWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                settingsWindow.setDimAmount(0f);
            }
        });
        settings.show();
    }

    private interface ValueLabel {
        String format(int value);
    }

    private void addSeekSetting(LinearLayout parent, String name, int min, int max,
                                int current, IntConsumer onChange, ValueLabel formatter) {
        TextView label = new TextView(activity);
        label.setTextSize(12f);
        label.setText(name + "：" + formatter.format(current));
        parent.addView(label);

        SeekBar seek = new SeekBar(activity);
        seek.setMax(max - min);
        seek.setProgress(clamp(current, min, max) - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = min + progress;
                label.setText(name + "：" + formatter.format(value));
                if (fromUser) onChange.accept(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        parent.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
    }

    private void persistKeyboardPreferences() {
        preferences.edit()
                .putInt(PREF_SIZE, keyboardSizePercent)
                .putInt(PREF_WIDTH, keyboardWidthPercent)
                .putInt(PREF_OPACITY, keyboardOpacityPercent)
                .putBoolean(PREF_TOP, keyboardAtTop)
                .putBoolean(PREF_PURPOSE, showPurpose)
                .apply();
    }

    private void applyOverlayPreferences() {
        if (rootLayout == null) return;
        int hostWidth = host.getWidth();
        if (hostWidth <= 0) hostWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(220), Math.round(hostWidth * keyboardWidthPercent / 100f));
        width = Math.min(hostWidth, width);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = (keyboardAtTop ? Gravity.TOP : Gravity.BOTTOM)
                | Gravity.CENTER_HORIZONTAL;
        rootLayout.setLayoutParams(params);
        rootLayout.requestLayout();
        updateStateDescription();
    }

    private void updateStateDescription() {
        if (stateView != null) stateView.setText(stateDescription());
    }

    private String stateDescription() {
        String mode;
        if (ctrlState != MOD_OFF) {
            mode = ctrlState == MOD_LOCKED ? "Ctrl 锁定" : "Ctrl 下一键";
        } else if (shiftState != MOD_OFF) {
            mode = shiftState == MOD_LOCKED ? "大写锁定" : "大写下一键";
        } else {
            mode = "小写";
        }
        return mode + " · 高" + keyboardSizePercent + "% · 透" + keyboardOpacityPercent + "%";
    }

    private static String modifierLabel(String name, int state) {
        if (state == MOD_LOCKED) return name + "\n锁定";
        if (state == MOD_ONCE) return name + "\n下一键";
        return name;
    }

    private static boolean isCtrlCharacter(char ch) {
        char upper = Character.toUpperCase(ch);
        return upper >= '@' && upper <= '_';
    }

    private static char shifted(char base, boolean shift) {
        if (!shift) return base;
        if (base >= 'a' && base <= 'z') return Character.toUpperCase(base);
        String from = "`1234567890-=[]\\;',./";
        String to =   "~!@#$%^&*()_+{}|:\"<>?";
        int index = from.indexOf(base);
        return index >= 0 ? to.charAt(index) : base;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams rowParams(int baseHeightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scaledDp(baseHeightDp));
        params.setMargins(0, dp(1), 0, dp(1));
        return params;
    }

    private LinearLayout.LayoutParams weightedParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(dp(1), 0, dp(1), 0);
        return params;
    }

    private LinearLayout.LayoutParams headerButtonParams(int widthDp, int heightPx) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), heightPx);
        params.setMargins(dp(1), 0, dp(1), 0);
        return params;
    }

    private TouchFeedbackButton makeModifierButton(String text, int state) {
        TouchFeedbackButton button = makeButton(text, 9f);
        button.setMaxLines(2);
        if (state != MOD_OFF) {
            button.applyStyle(state == MOD_LOCKED ? 0xFF8A3D00 : 0xFF335D9C,
                    keyboardOpacityPercent, TouchFeedbackButton.SHAPE_ROUNDED, false);
        }
        return button;
    }

    private TouchFeedbackButton makeButton(String text, float textSize) {
        TouchFeedbackButton button = new TouchFeedbackButton(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(scaledTextSize(textSize));
        button.setGravity(Gravity.CENTER);
        button.setLineSpacing(0f, 0.91f);
        button.setMaxLines(showPurpose ? 2 : 1);
        button.setPadding(dp(1), 0, dp(1), 0);
        button.applyStyle(0xFF2B3138, keyboardOpacityPercent,
                TouchFeedbackButton.SHAPE_ROUNDED, false);
        return button;
    }

    private GradientDrawable makePanelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        int panelOpacity = Math.max(8, keyboardOpacityPercent / 3);
        drawable.setColor(withPercentAlpha(0xFF101317, panelOpacity));
        drawable.setCornerRadius(dp(5));
        drawable.setStroke(dp(1), withPercentAlpha(0xFF59636E,
                Math.min(100, keyboardOpacityPercent + 10)));
        return drawable;
    }

    private static int withPercentAlpha(int color, int percent) {
        int alpha = Color.alpha(color) * clamp(percent, 0, 100) / 100;
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int scaledDp(int value) {
        return Math.max(dp(17), Math.round(dp(value) * keyboardSizePercent / 100f));
    }

    private float scaledTextSize(float value) {
        float factor = 0.84f + keyboardSizePercent / 520f;
        return value * factor;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
