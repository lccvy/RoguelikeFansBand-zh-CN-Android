package org.roguelikefansband.android;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** Dedicated, configurable Android frontend for RoguelikeFansBand. */
public final class MainActivity extends Activity {
    private static final int TERM_COLS = 80;
    private static final int TERM_ROWS = 27;

    private static final String PREFS = "rfb_frontend";
    private static final String PREF_GRAPHICS = "graphics_mode";
    private static final String PREF_SOUND = "sound_enabled";
    private static final String PREF_BIGTILE = "bigtile_enabled";
    private static final String PREF_PLAYER = "player_slot";
    private static final String PREF_FORCE_NEW_ONCE = "force_new_game_once";
    private static final String PREF_KNOWN_SLOTS = "known_player_slots";
    private static final String PREF_FONT_SCALE = "term_font_scale";
    private static final String PREF_TERM_SCALE_X = "term_scale_x";
    private static final String PREF_TERM_SCALE_Y = "term_scale_y";
    private static final String PREF_HUD_INTRO_SHOWN = "unified_hud_intro_shown_v1";

    private final ExecutorService gameExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicBoolean gameLoopScheduled = new AtomicBoolean();
    private final AtomicBoolean processRestartScheduled = new AtomicBoolean();
    private final AtomicReference<SessionRequest> requestedSession = new AtomicReference<>();

    private SharedPreferences frontendPrefs;
    private FrameLayout rootLayout;
    private LinearLayout contentRow;
    private FrameLayout termContainer;
    private View panelSpacer;
    private RfbTermView termView;
    private TextView statusView;
    private LinearLayout hudEditToolbar;
    private CustomHudOverlay hudOverlay;
    private RfbAudioManager audioManager;
    private GameKeyboardDialog gameKeyboard;
    private VirtualKeyStore keyStore;
    private List<VirtualKeyStore.KeySpec> quickKeys = new ArrayList<>();
    private VirtualKeyStore.HudPanelSpec hudPanel = VirtualKeyStore.HudPanelSpec.defaults();
    private volatile File runtimeRoot;
    private volatile long nativeSessionToken;

    private volatile int graphicsMode = RfbNative.GRAPHICS_ADAM_BOLT;
    private volatile boolean soundEnabled = true;
    private volatile boolean bigtileEnabled;
    private volatile String currentPlayer = "ANDROID";
    private float fontScale = 1f;
    private float termScaleX = 1f;
    private float termScaleY = 1f;
    private boolean startupForceNew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupDiagnostics.initialize(getApplicationContext());
        StartupDiagnostics.mark("activity.onCreate.begin");
        try {
            frontendPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            restoreFrontendPreferences();
            buildUi();
            keepScreenOnAndGoFullscreen();
            StartupDiagnostics.mark("activity.ui.ready");
            scheduleSession(currentPlayer, startupForceNew);
        } catch (Throwable error) {
            StartupDiagnostics.recordThrowable("activity.onCreate", error);
            showFatalStartupError(error);
        }
    }

    private void restoreFrontendPreferences() {
        graphicsMode = frontendPrefs.getInt(PREF_GRAPHICS, RfbNative.GRAPHICS_ADAM_BOLT);
        if (graphicsMode < RfbNative.GRAPHICS_NONE
                || graphicsMode > RfbNative.GRAPHICS_ADAM_BOLT) {
            graphicsMode = RfbNative.GRAPHICS_ADAM_BOLT;
        }
        soundEnabled = frontendPrefs.getBoolean(PREF_SOUND, true);
        bigtileEnabled = frontendPrefs.getBoolean(PREF_BIGTILE, false);
        currentPlayer = sanitizeSlot(frontendPrefs.getString(PREF_PLAYER, "ANDROID"));
        if (currentPlayer == null) currentPlayer = "ANDROID";
        startupForceNew = frontendPrefs.getBoolean(PREF_FORCE_NEW_ONCE, false);
        if (startupForceNew) {
            /* One-shot flag must never create another new character on a later launch. */
            frontendPrefs.edit().remove(PREF_FORCE_NEW_ONCE).commit();
        }
        fontScale = clamp(frontendPrefs.getFloat(PREF_FONT_SCALE, 1f), 0.70f, 1.35f);
        termScaleX = clamp(frontendPrefs.getFloat(PREF_TERM_SCALE_X, 1f), 0.75f, 1f);
        termScaleY = clamp(frontendPrefs.getFloat(PREF_TERM_SCALE_Y, 1f), 0.75f, 1f);
        keyStore = new VirtualKeyStore(frontendPrefs);
        quickKeys = new ArrayList<>(keyStore.load());
        hudPanel = keyStore.loadPanel();
        rememberSlot(currentPlayer);
    }

    private void persistFrontendPreferences() {
        frontendPrefs.edit()
                .putInt(PREF_GRAPHICS, graphicsMode)
                .putBoolean(PREF_SOUND, soundEnabled)
                .putBoolean(PREF_BIGTILE, bigtileEnabled)
                .putString(PREF_PLAYER, currentPlayer)
                .putFloat(PREF_FONT_SCALE, fontScale)
                .putFloat(PREF_TERM_SCALE_X, termScaleX)
                .putFloat(PREF_TERM_SCALE_Y, termScaleY)
                .apply();
    }

    @SuppressWarnings("deprecation")
    private void keepScreenOnAndGoFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !destroyed.get()) keepScreenOnAndGoFullscreen();
    }

    private void buildUi() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.addView(contentRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        termContainer = new FrameLayout(this);
        panelSpacer = new View(this);
        panelSpacer.setBackgroundColor(Color.BLACK);
        applyHudPanelLayout();

        termView = new RfbTermView(this);
        termView.setKeyboardRequestListener(this::showGameKeyboard);
        termContainer.addView(termView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyDisplayPreferences();

        hudOverlay = new CustomHudOverlay(this, new CustomHudOverlay.Listener() {
            @Override
            public void onAction(VirtualKeyStore.KeySpec spec) {
                sendQuickAction(spec);
            }

            @Override
            public void onEditRequested(int index) {
                showEditQuickKeyDialog(index);
            }

            @Override
            public void onPanelEditRequested() {
                showEditHudPanelDialog();
            }

            @Override
            public void onPositionChanged() {
                keyStore.save(quickKeys);
            }

            @Override
            public void onPanelChanged() {
                normalizeHudPanelWidth();
                keyStore.savePanel(hudPanel);
                applyHudPanelLayout();
            }
        });
        rootLayout.addView(hudOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        hudOverlay.setPanel(hudPanel);
        hudOverlay.setKeys(quickKeys);
        buildHudEditToolbar();

        statusView = new TextView(this);
        statusView.setText("正在准备最新稳定版游戏资源……");
        statusView.setTextColor(Color.WHITE);
        statusView.setBackgroundColor(0xE6000000);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextSize(17f);
        statusView.setPadding(dp(18), dp(18), dp(18), dp(18));
        statusView.setOnClickListener(v -> {
            if (nativeSessionToken == 0 && runtimeRoot != null) showSaveManager();
        });
        rootLayout.addView(statusView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
        showHudIntroOnce();
    }

    private void applyHudPanelLayout() {
        if (contentRow == null || termContainer == null || panelSpacer == null) return;
        contentRow.removeAllViews();
        LinearLayout.LayoutParams termParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                effectiveHudPanelWidthPx(), LinearLayout.LayoutParams.MATCH_PARENT);
        if (hudPanel.dockRight) {
            contentRow.addView(termContainer, termParams);
            contentRow.addView(panelSpacer, panelParams);
        } else {
            contentRow.addView(panelSpacer, panelParams);
            contentRow.addView(termContainer, termParams);
        }
        contentRow.requestLayout();
    }

    private void buildHudEditToolbar() {
        hudEditToolbar = new LinearLayout(this);
        hudEditToolbar.setOrientation(LinearLayout.HORIZONTAL);
        hudEditToolbar.setPadding(dp(3), dp(3), dp(3), dp(3));
        hudEditToolbar.setBackgroundColor(0xE8171B20);

        addHudToolbarButton("完成", this::toggleHudEditing);
        addHudToolbarButton("＋按键", this::requestAddHudKey);
        addHudToolbarButton("按键管理", this::showQuickKeyEditor);
        addHudToolbarButton("操作区", this::showEditHudPanelDialog);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(4);
        rootLayout.addView(hudEditToolbar, params);
        hudEditToolbar.setVisibility(View.GONE);
    }

    private void addHudToolbarButton(String label, Runnable action) {
        TouchFeedbackButton button = makeButton(label, 10f);
        PressRepeater.bind(button, false, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(76), dp(36));
        params.setMargins(dp(1), 0, dp(1), 0);
        hudEditToolbar.addView(button, params);
    }

    private void requestAddHudKey() {
        if (quickKeys.size() >= VirtualKeyStore.MAX_KEYS) {
            Toast.makeText(this, "最多可设置 " + VirtualKeyStore.MAX_KEYS + " 个按键。",
                    Toast.LENGTH_LONG).show();
        } else {
            showEditQuickKeyDialog(-1);
        }
    }

    private void showHudIntroOnce() {
        if (frontendPrefs.getBoolean(PREF_HUD_INTRO_SHOWN, false)) return;
        frontendPrefs.edit().putBoolean(PREF_HUD_INTRO_SHOWN, true).apply();
        rootLayout.postDelayed(() -> Toast.makeText(this,
                "按手机返回键可随时打开游戏菜单；在“编辑快捷键与布局”中调整操作区和按键。",
                Toast.LENGTH_LONG).show(), 1800L);
    }

    private void toggleHudEditing() {
        if (hudOverlay == null) return;
        boolean editing = !hudOverlay.isEditing();
        if (editing && gameKeyboard != null && gameKeyboard.isShowing()) gameKeyboard.dismiss();
        hudOverlay.setEditing(editing);
        if (hudEditToolbar != null) {
            hudEditToolbar.setVisibility(editing ? View.VISIBLE : View.GONE);
            if (editing) hudEditToolbar.bringToFront();
        }
        Toast.makeText(this, editing
                        ? "布局模式：拖按键可跨越游戏区和操作区；拖操作区主体可换边，拖黄色分界可调宽。"
                        : "布局已保存，恢复游戏操作。",
                Toast.LENGTH_LONG).show();
    }

    private void refreshHud() {
        keyStore.save(quickKeys);
        if (hudOverlay != null) hudOverlay.setKeys(quickKeys);
    }

    private void refreshHudPanel() {
        normalizeHudPanelWidth();
        keyStore.savePanel(hudPanel);
        applyHudPanelLayout();
        if (hudOverlay != null) hudOverlay.setPanel(hudPanel);
    }

    private int effectiveHudPanelWidthPx() {
        int screenWidth = contentRow != null && contentRow.getWidth() > 0
                ? contentRow.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int minimum = dp(VirtualKeyStore.MIN_PANEL_WIDTH_DP);
        int maximum = Math.max(minimum, screenWidth - dp(VirtualKeyStore.MIN_GAME_AREA_DP));
        return Math.max(minimum, Math.min(dp(hudPanel.widthDp), maximum));
    }

    private void normalizeHudPanelWidth() {
        float density = getResources().getDisplayMetrics().density;
        hudPanel.widthDp = Math.max(VirtualKeyStore.MIN_PANEL_WIDTH_DP,
                Math.round(effectiveHudPanelWidthPx() / density));
    }

    private void sendQuickAction(VirtualKeyStore.KeySpec spec) {
        try {
            String action = spec.action == null ? "" : spec.action.trim()
                    .toUpperCase(java.util.Locale.ROOT);
            switch (action) {
                case "{ANDROID-MENU}": showGameMenu(); return;
                case "{ANDROID-SAVES}": showSaveManager(); return;
                case "{ANDROID-KEYBOARD}": showGameKeyboard(); return;
                case "{ANDROID-DISPLAY}": showDisplaySettings(); return;
                case "{ANDROID-GRAPHICS}": cycleGraphicsMode(); return;
                case "{ANDROID-SOUND}": toggleSound(); return;
                case "{ANDROID-BIGTILE}": toggleBigtile(); return;
                case "{ANDROID-HUD-EDIT}": toggleHudEditing(); return;
                default: break;
            }
            RfbNative.sendBytes(GameKeySequence.encode(spec.action));
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "快捷键动作无效：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TouchFeedbackButton makeButton(String text, float textSize) {
        TouchFeedbackButton button = new TouchFeedbackButton(this);
        button.setText(text);
        button.setTextSize(textSize);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(1), 0, dp(1), 0);
        button.applyStyle(0xFF2B3138, 96,
                TouchFeedbackButton.SHAPE_ROUNDED, false);
        return button;
    }

    private void showGameKeyboard() {
        if (isFinishing() || destroyed.get()) return;
        if (gameKeyboard != null && gameKeyboard.isShowing()) return;
        try {
            gameKeyboard = new GameKeyboardDialog(this, termContainer, RfbNative::sendBytes);
            gameKeyboard.setOnDismissListener(() -> {
                if (hudOverlay != null) hudOverlay.setGameAreaKeysHidden(false);
                if (termView != null) termView.requestFocus();
                gameKeyboard = null;
            });
            gameKeyboard.show();
            if (hudOverlay != null) hudOverlay.setGameAreaKeysHidden(true);
        } catch (Throwable error) {
            if (hudOverlay != null) hudOverlay.setGameAreaKeysHidden(false);
            gameKeyboard = null;
            StartupDiagnostics.recordThrowable("keyboard.overlay", error);
            Toast.makeText(this, "无法打开游戏键盘：" + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showGameMenu() {
        String[] items = {
                "存档槽 / 打开存档 / 新游戏…",
                "游戏设置（=）",
                "原版帮助（?）",
                "知识菜单（~）",
                "历史消息（Ctrl+P）",
                "任务状态（Ctrl+Q）",
                "立即保存（Ctrl+S）",
                "保存并退出（Ctrl+X）",
                "版本信息（V）",
                "视觉设置（%）",
                "颜色设置（&）",
                "编辑快捷键与布局…",
                "Android 屏幕与输入设置…"
        };
        new AlertDialog.Builder(this)
                .setTitle("RFB 游戏菜单 · 当前存档槽：" + currentPlayer)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: showSaveManager(); break;
                        case 1: RfbNative.sendText("="); break;
                        case 2: RfbNative.sendText("?"); break;
                        case 3: RfbNative.sendText("~"); break;
                        case 4: RfbNative.sendBytes(GameKeySequence.encode("{CTRL-P}")); break;
                        case 5: RfbNative.sendBytes(GameKeySequence.encode("{CTRL-Q}")); break;
                        case 6: RfbNative.sendBytes(GameKeySequence.encode("{CTRL-S}")); break;
                        case 7: RfbNative.sendBytes(GameKeySequence.encode("{CTRL-X}")); break;
                        case 8: RfbNative.sendText("V"); break;
                        case 9: RfbNative.sendText("%"); break;
                        case 10: RfbNative.sendText("&"); break;
                        case 11: showQuickKeyEditor(); break;
                        case 12: showDisplaySettings(); break;
                        default: break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSaveManager() {
        List<String> slots = discoverSaveSlots();
        LinearLayout directory = new LinearLayout(this);
        directory.setOrientation(LinearLayout.VERTICAL);
        directory.setPadding(dp(14), dp(8), dp(14), dp(10));

        TextView summary = new TextView(this);
        summary.setText("当前存档：" + currentPlayer
                + "\n“打开”会先保存当前游戏再载入；“新游戏”会覆盖对应槽位。");
        summary.setTextSize(12f);
        summary.setPadding(0, 0, 0, dp(8));
        directory.addView(summary);

        TouchFeedbackButton create = makeButton("＋ 新建存档并开始新游戏", 11f);
        directory.addView(create, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView existingTitle = new TextView(this);
        existingTitle.setText("已有存档槽");
        existingTitle.setTextSize(13f);
        existingTitle.setPadding(0, dp(10), 0, dp(4));
        directory.addView(existingTitle);

        final AlertDialog[] owner = new AlertDialog[1];
        for (String slot : slots) {
            addSaveSlotRow(directory, slot, owner);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(directory);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("存档目录")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        owner[0] = dialog;
        PressRepeater.bind(create, false, () -> {
            dialog.dismiss();
            promptNewSlot();
        });
        dialog.show();
    }

    private void addSaveSlotRow(LinearLayout parent, String slot, AlertDialog[] owner) {
        boolean current = slot.equals(currentPlayer);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(5), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        rowParams.setMargins(0, dp(2), 0, dp(2));
        parent.addView(row, rowParams);

        TextView name = new TextView(this);
        name.setText((current ? "● " : "○ ") + slot + (current ? "\n当前槽" : ""));
        name.setTextSize(current ? 13f : 12f);
        name.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TouchFeedbackButton open = makeButton(
                current && nativeSessionToken > 0 ? "继续" : "打开", 10f);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(dp(66), dp(42));
        openParams.setMargins(dp(2), 0, dp(2), 0);
        row.addView(open, openParams);
        PressRepeater.bind(open, false, () -> {
            if (owner[0] != null) owner[0].dismiss();
            if (current && nativeSessionToken > 0) {
                if (termView != null) termView.requestFocus();
            } else if (current) {
                requestSessionSwitch(slot, false);
            } else {
                confirmSwitchSlot(slot);
            }
        });

        TouchFeedbackButton fresh = makeButton("新游戏", 9.5f);
        fresh.applyStyle(0xFF63362F, 96,
                TouchFeedbackButton.SHAPE_ROUNDED, false);
        LinearLayout.LayoutParams freshParams = new LinearLayout.LayoutParams(dp(76), dp(42));
        freshParams.setMargins(dp(2), 0, 0, 0);
        row.addView(fresh, freshParams);
        PressRepeater.bind(fresh, false, () -> {
            if (owner[0] != null) owner[0].dismiss();
            confirmForceNew(slot);
        });
    }

    private List<String> discoverSaveSlots() {
        Set<String> unique = new LinkedHashSet<>();
        unique.add(currentPlayer);
        Set<String> remembered = frontendPrefs.getStringSet(PREF_KNOWN_SLOTS,
                Collections.emptySet());
        if (remembered != null) {
            for (String entry : remembered) {
                String clean = sanitizeSlot(entry);
                if (clean != null) unique.add(clean);
            }
        }
        File root = runtimeRoot;
        File saveDir = root == null ? null : new File(root, "lib/save");
        File[] files = saveDir == null ? null : saveDir.listFiles(File::isFile);
        if (files != null) {
            List<File> ordered = new ArrayList<>();
            Collections.addAll(ordered, files);
            ordered.sort(Comparator.comparingLong(File::lastModified).reversed());
            for (File file : ordered) {
                String name = slotFromSaveFile(file.getName());
                if (name != null) unique.add(name);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String slotFromSaveFile(String fileName) {
        if (fileName == null || fileName.startsWith(".")) return null;
        String value = fileName;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String[] transientSuffixes = {".new", ".old", ".bak", ".tmp", ".lock"};
        for (String suffix : transientSuffixes) {
            if (lower.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        /* Also accepts cores configured with SAVEFILE_USE_UID (for example 1000.HERO). */
        value = value.replaceFirst("^[0-9]+\\.", "");
        return sanitizeSlot(value);
    }

    private void rememberSlot(String slot) {
        String clean = sanitizeSlot(slot);
        if (clean == null || frontendPrefs == null) return;
        Set<String> old = frontendPrefs.getStringSet(PREF_KNOWN_SLOTS,
                Collections.emptySet());
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        if (old != null) updated.addAll(old);
        updated.add(clean);
        frontendPrefs.edit().putStringSet(PREF_KNOWN_SLOTS, updated).apply();
    }

    private void promptNewSlot() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如 HERO2（1～24 位）");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("新建存档并开始游戏")
                .setMessage("先为新角色取一个存档槽名称。仅使用英文字母、数字、下划线或短横线。")
                .setView(input)
                .setPositiveButton("开始新游戏", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String slot = sanitizeSlot(input.getText().toString());
                    if (slot == null) {
                        input.setError("请输入 1～24 位英文字母、数字、_ 或 -");
                        return;
                    }
                    if (discoverSaveSlots().contains(slot)) {
                        input.setError("该存档槽已存在；请从列表打开或换一个名字");
                        return;
                    }
                    dialog.dismiss();
                    requestSessionSwitch(slot, true);
                }));
        dialog.show();
    }

    private void confirmSwitchSlot(String slot) {
        new AlertDialog.Builder(this)
                .setTitle("打开存档 " + slot + "？")
                .setMessage("当前存档会先安全保存，然后载入所选存档。")
                .setPositiveButton("保存并切换", (dialog, which) -> requestSessionSwitch(slot, false))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmForceNew(String slot) {
        new AlertDialog.Builder(this)
                .setTitle("在 " + slot + " 重新开局？")
                .setMessage("这会让原版核心以 -n 模式重新建立该槽角色。旧角色将被覆盖，请确认已经不再需要。")
                .setPositiveButton("确认重新开局", (dialog, which) -> requestSessionSwitch(slot, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestSessionSwitch(String player, boolean forceNew) {
        String clean = sanitizeSlot(player);
        if (clean == null) return;
        SessionRequest request = new SessionRequest(clean, forceNew);
        requestedSession.set(request);
        rememberSlot(clean);
        statusView.setText("正在安全保存当前游戏并切换到存档槽：" + clean + "……");
        statusView.setVisibility(View.VISIBLE);
        if (nativeSessionToken > 0) {
            requestNativeStop();
        } else if (gameLoopScheduled.get()) {
            waitForSessionSwitchWindow(request);
        } else {
            requestedSession.compareAndSet(request, null);
            restartForSession(request);
        }
    }

    private void waitForSessionSwitchWindow(SessionRequest request) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (destroyed.get() || requestedSession.get() != request) return;
            if (nativeSessionToken > 0) {
                requestNativeStop();
            } else if (gameLoopScheduled.get()) {
                waitForSessionSwitchWindow(request);
            } else if (requestedSession.compareAndSet(request, null)) {
                restartForSession(request);
            }
        }, 80L);
    }

    private static String sanitizeSlot(String input) {
        if (input == null) return null;
        String value = input.trim();
        return value.matches("[A-Za-z0-9_-]{1,24}") ? value : null;
    }

    private void showQuickKeyEditor() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(8), dp(14), 0);
        TextView help = new TextView(this);
        help.setText("可以保存 0～" + VirtualKeyStore.MAX_KEYS
                + " 个 HUD 按键。所有按键共享整块屏幕，可自由放在游戏区或保留的操作区。"
                + "轻触下方项目可改文字、动作、宽高、形状、颜色、透明度和长按连发。\n"
                + GameKeySequence.syntaxHelp()
                + " 界面动作还可用 {ANDROID-MENU}/{ANDROID-SAVES}/"
                + "{ANDROID-KEYBOARD}/{ANDROID-DISPLAY}/{ANDROID-GRAPHICS}/"
                + "{ANDROID-SOUND}/{ANDROID-BIGTILE}/{ANDROID-HUD-EDIT}。");
        help.setTextSize(12f);
        root.addView(help);

        TouchFeedbackButton drag = makeButton(
                hudOverlay != null && hudOverlay.isEditing() ? "完成拖拽布局" : "进入拖拽布局", 11f);
        root.addView(drag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        TouchFeedbackButton panelSettings = makeButton("设置操作区背景、宽度与停靠位置", 10.5f);
        root.addView(panelSettings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        ListView list = new ListView(this);
        String[] labels = new String[quickKeys.size()];
        for (int i = 0; i < quickKeys.size(); i++) {
            VirtualKeyStore.KeySpec key = quickKeys.get(i);
            labels[i] = (i + 1) + ".  " + key.label + "    →    " + key.action;
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义悬浮键 · " + quickKeys.size() + "/"
                        + VirtualKeyStore.MAX_KEYS)
                .setView(root)
                .setPositiveButton("新增", null)
                .setNeutralButton("恢复默认", null)
                .setNegativeButton("关闭", null)
                .create();
        PressRepeater.bind(drag, false, () -> {
            dialog.dismiss();
            toggleHudEditing();
        });
        PressRepeater.bind(panelSettings, false, () -> {
            dialog.dismiss();
            showEditHudPanelDialog();
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            showEditQuickKeyDialog(position);
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (quickKeys.size() >= VirtualKeyStore.MAX_KEYS) {
                    Toast.makeText(this, "最多可设置 " + VirtualKeyStore.MAX_KEYS + " 个快捷键。",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                showEditQuickKeyDialog(-1);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(this)
                            .setTitle("恢复默认快捷键？")
                            .setMessage("你的自定义按键、位置、尺寸与样式都会被默认布局替换。")
                            .setPositiveButton("恢复", (d, w) -> {
                                quickKeys = new ArrayList<>(keyStore.reset());
                                refreshHud();
                                dialog.dismiss();
                            })
                            .setNegativeButton("取消", null)
                            .show());
        });
        dialog.show();
    }

    private void showEditQuickKeyDialog(int index) {
        boolean existing = index >= 0 && index < quickKeys.size();
        VirtualKeyStore.KeySpec original = existing ? quickKeys.get(index) : null;
        VirtualKeyStore.KeySpec working = original == null
                ? VirtualKeyStore.create("", "", quickKeys.size())
                : new VirtualKeyStore.KeySpec(original);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(6), dp(18), dp(8));

        TextView labelTitle = new TextView(this);
        labelTitle.setText("按钮文字");
        form.addView(labelTitle);
        EditText labelInput = new EditText(this);
        labelInput.setSingleLine(true);
        labelInput.setText(original == null ? "" : original.label);
        form.addView(labelInput);

        TextView actionTitle = new TextView(this);
        actionTitle.setText("发送动作");
        form.addView(actionTitle);
        EditText actionInput = new EditText(this);
        actionInput.setSingleLine(true);
        actionInput.setText(original == null ? "" : original.action);
        form.addView(actionInput);

        TextView presetTitle = new TextView(this);
        presetTitle.setText("常用按键模板（选择后自动填入）");
        form.addView(presetTitle);
        Spinner presetSpinner = new Spinner(this);
        String[] presetNames = {
                "不套用模板", "↖ 西北 7", "↑ 向上 8", "↗ 东北 9",
                "← 向左 4", "· 等待 5", "→ 向右 6", "↙ 西南 1",
                "↓ 向下 2", "↘ 东南 3", "拾取 g", "背包 i", "装备 e",
                "施法 m", "设置 =", "帮助 ?", "立即保存 Ctrl+S",
                "游戏菜单", "存档槽", "大键盘", "屏幕设置", "图形模式",
                "声音开关", "大图块", "拖拽布局"
        };
        String[] presetLabels = {
                "", "西北 7", "向上 8", "东北 9", "向左 4", "等待 5",
                "向右 6", "西南 1", "向下 2", "东南 3", "拾取 g", "背包 i",
                "装备 e", "施法 m", "设置 =", "帮助 ?", "保存 ^S", "游戏菜单",
                "存档槽", "大键盘", "屏幕设置", "图形模式", "声音开关",
                "大图块", "拖拽布局"
        };
        String[] presetActions = {
                "", "7", "8", "9", "4", "5", "6", "1", "2", "3", "g", "i", "e", "m",
                "=", "?", "{CTRL-S}", "{ANDROID-MENU}", "{ANDROID-SAVES}",
                "{ANDROID-KEYBOARD}", "{ANDROID-DISPLAY}", "{ANDROID-GRAPHICS}",
                "{ANDROID-SOUND}", "{ANDROID-BIGTILE}", "{ANDROID-HUD-EDIT}"
        };
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, presetNames);
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(presetAdapter);
        presetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (position <= 0) return;
                labelInput.setText(presetLabels[position]);
                actionInput.setText(presetActions[position]);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        form.addView(presetSpinner);

        TextView syntax = new TextView(this);
        syntax.setText(GameKeySequence.syntaxHelp()
                + "\n界面动作：{ANDROID-MENU}/{ANDROID-SAVES}/{ANDROID-KEYBOARD}/"
                + "{ANDROID-DISPLAY}/{ANDROID-GRAPHICS}/{ANDROID-SOUND}/"
                + "{ANDROID-BIGTILE}/{ANDROID-HUD-EDIT}。");
        syntax.setTextSize(11f);
        form.addView(syntax);

        final int[] width = {working.widthDp};
        final int[] height = {working.heightDp};
        final int[] opacity = {working.opacity};
        addSeekSetting(form, "按钮宽度", VirtualKeyStore.MAX_WIDTH_DP - VirtualKeyStore.MIN_WIDTH_DP,
                width[0] - VirtualKeyStore.MIN_WIDTH_DP, value ->
                        width[0] = VirtualKeyStore.MIN_WIDTH_DP + value,
                value -> (VirtualKeyStore.MIN_WIDTH_DP + value) + "dp");
        addSeekSetting(form, "按钮高度", VirtualKeyStore.MAX_HEIGHT_DP - VirtualKeyStore.MIN_HEIGHT_DP,
                height[0] - VirtualKeyStore.MIN_HEIGHT_DP, value ->
                        height[0] = VirtualKeyStore.MIN_HEIGHT_DP + value,
                value -> (VirtualKeyStore.MIN_HEIGHT_DP + value) + "dp");
        addSeekSetting(form, "不透明度", 80, opacity[0] - 20,
                value -> opacity[0] = 20 + value, value -> (20 + value) + "%");

        TextView shapeTitle = new TextView(this);
        shapeTitle.setText("按钮形状");
        form.addView(shapeTitle);
        Spinner shapeSpinner = new Spinner(this);
        ArrayAdapter<String> shapeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, VirtualKeyStore.SHAPE_NAMES);
        shapeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        shapeSpinner.setAdapter(shapeAdapter);
        shapeSpinner.setSelection(working.shape);
        form.addView(shapeSpinner);

        TextView colorTitle = new TextView(this);
        colorTitle.setText("按钮颜色");
        form.addView(colorTitle);
        Spinner colorSpinner = new Spinner(this);
        ArrayAdapter<String> colorAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, VirtualKeyStore.COLOR_NAMES);
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        colorSpinner.setAdapter(colorAdapter);
        colorSpinner.setSelection(VirtualKeyStore.colorIndex(working.color));
        form.addView(colorSpinner);

        CheckBox repeat = new CheckBox(this);
        repeat.setText("按住后连续触发（方向/移动键建议开启）");
        repeat.setChecked(working.repeat);
        form.addView(repeat);

        TextView coordinateTitle = new TextView(this);
        coordinateTitle.setText("精确坐标（dp，屏幕左上角为 0,0）");
        form.addView(coordinateTitle);

        TextView xTitle = new TextView(this);
        xTitle.setText("X 坐标（向右）");
        form.addView(xTitle);
        EditText xInput = new EditText(this);
        xInput.setSingleLine(true);
        xInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        xInput.setText(String.valueOf(currentHudCoordinateDp(
                working.x, working.widthDp, true)));
        xInput.setHint("0～" + maximumHudCoordinateDp(working.widthDp, true));
        form.addView(xInput);

        TextView yTitle = new TextView(this);
        yTitle.setText("Y 坐标（向下）");
        form.addView(yTitle);
        EditText yInput = new EditText(this);
        yInput.setSingleLine(true);
        yInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        yInput.setText(String.valueOf(currentHudCoordinateDp(
                working.y, working.heightDp, false)));
        yInput.setHint("0～" + maximumHudCoordinateDp(working.heightDp, false));
        form.addView(yInput);

        TextView positionHelp = new TextView(this);
        positionHelp.setText("相同 X 可纵向对齐，相同 Y 可横向对齐；按钮尺寸改变后会按新范围检查。"
                + "保存后仍可进入拖拽布局微调。");
        positionHelp.setTextSize(11f);
        form.addView(positionHelp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing ? "编辑悬浮按键" : "新增悬浮按键")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNeutralButton(existing ? "删除" : null, null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String label = VirtualKeyStore.sanitizeLabel(labelInput.getText().toString());
                    String action = VirtualKeyStore.sanitizeAction(actionInput.getText().toString());
                    if (label.isEmpty()) {
                        labelInput.setError("按钮文字不能为空");
                        return;
                    }
                    if (action.isEmpty()) {
                        actionInput.setError("动作不能为空");
                        return;
                    }
                    int maximumX = maximumHudCoordinateDp(width[0], true);
                    int maximumY = maximumHudCoordinateDp(height[0], false);
                    Integer xDp = readHudCoordinate(xInput, maximumX);
                    Integer yDp = readHudCoordinate(yInput, maximumY);
                    if (xDp == null || yDp == null) return;
                    working.label = label;
                    working.action = action;
                    working.widthDp = width[0];
                    working.heightDp = height[0];
                    working.x = normalizedHudCoordinate(xDp, width[0], true);
                    working.y = normalizedHudCoordinate(yDp, height[0], false);
                    working.opacity = opacity[0];
                    working.shape = shapeSpinner.getSelectedItemPosition();
                    working.color = VirtualKeyStore.COLORS[colorSpinner.getSelectedItemPosition()];
                    working.repeat = repeat.isChecked();
                    if (existing) quickKeys.set(index, working);
                    else quickKeys.add(working);
                    refreshHud();
                    dialog.dismiss();
                } catch (IllegalArgumentException error) {
                    actionInput.setError(error.getMessage());
                }
            });
            if (existing) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    quickKeys.remove(index);
                    refreshHud();
                    dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showEditHudPanelDialog() {
        VirtualKeyStore.HudPanelSpec working = new VirtualKeyStore.HudPanelSpec(hudPanel);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(6), dp(18), dp(8));

        CheckBox visible = new CheckBox(this);
        visible.setText("显示操作区背景（关闭背景仍保留操作区宽度）");
        visible.setChecked(working.visible);
        form.addView(visible);

        TextView dockTitle = new TextView(this);
        dockTitle.setText("停靠位置");
        form.addView(dockTitle);
        Spinner dockSpinner = new Spinner(this);
        String[] dockNames = {"右侧", "左侧"};
        ArrayAdapter<String> dockAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, dockNames);
        dockAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dockSpinner.setAdapter(dockAdapter);
        dockSpinner.setSelection(working.dockRight ? 0 : 1);
        form.addView(dockSpinner);

        final int[] width = {working.widthDp};
        final int[] opacity = {working.opacity};
        addSeekSetting(form, "操作区宽度",
                VirtualKeyStore.MAX_PANEL_WIDTH_DP - VirtualKeyStore.MIN_PANEL_WIDTH_DP,
                width[0] - VirtualKeyStore.MIN_PANEL_WIDTH_DP,
                value -> width[0] = VirtualKeyStore.MIN_PANEL_WIDTH_DP + value,
                value -> (VirtualKeyStore.MIN_PANEL_WIDTH_DP + value) + "dp");
        addSeekSetting(form, "背景不透明度", 95, opacity[0] - 5,
                value -> opacity[0] = 5 + value, value -> (5 + value) + "%");

        TextView shapeTitle = new TextView(this);
        shapeTitle.setText("背景形状");
        form.addView(shapeTitle);
        Spinner shapeSpinner = new Spinner(this);
        ArrayAdapter<String> shapeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, VirtualKeyStore.SHAPE_NAMES);
        shapeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        shapeSpinner.setAdapter(shapeAdapter);
        shapeSpinner.setSelection(working.shape);
        form.addView(shapeSpinner);

        TextView colorTitle = new TextView(this);
        colorTitle.setText("背景颜色");
        form.addView(colorTitle);
        Spinner colorSpinner = new Spinner(this);
        ArrayAdapter<String> colorAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, VirtualKeyStore.COLOR_NAMES);
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        colorSpinner.setAdapter(colorAdapter);
        colorSpinner.setSelection(VirtualKeyStore.colorIndex(working.color));
        form.addView(colorSpinner);

        TextView hint = new TextView(this);
        hint.setText("操作区只负责为游戏画面留出空间，不单独拥有按钮。进入布局模式后，"
                + "可拖主体切换左右停靠，拖黄色分界调整宽度；任意按钮都能跨越分界线。");
        hint.setTextSize(11f);
        form.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("操作区设置")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNeutralButton("恢复右侧默认", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                working.visible = visible.isChecked();
                working.dockRight = dockSpinner.getSelectedItemPosition() == 0;
                working.widthDp = width[0];
                working.opacity = opacity[0];
                working.shape = shapeSpinner.getSelectedItemPosition();
                working.color = VirtualKeyStore.COLORS[colorSpinner.getSelectedItemPosition()];
                hudPanel = working;
                refreshHudPanel();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                hudPanel = keyStore.resetPanel();
                refreshHudPanel();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showDisplaySettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(4), dp(18), 0);
        addSeekSetting(root, "终端字体", 65, Math.round(fontScale * 100) - 70,
                value -> {
                    fontScale = (70 + value) / 100f;
                    applyAndPersistDisplay();
                }, value -> (70 + value) + "%");
        addSeekSetting(root, "终端显示宽度", 25, Math.round(termScaleX * 100) - 75,
                value -> {
                    termScaleX = (75 + value) / 100f;
                    applyAndPersistDisplay();
                }, value -> (75 + value) + "%");
        addSeekSetting(root, "终端显示高度", 25, Math.round(termScaleY * 100) - 75,
                value -> {
                    termScaleY = (75 + value) / 100f;
                    applyAndPersistDisplay();
                }, value -> (75 + value) + "%");
        new AlertDialog.Builder(this)
                .setTitle("游戏画面显示")
                .setMessage("调整会立即预览并自动保存。这里仅控制保留操作区之外的游戏终端；操作区宽度和 HUD 按键请从“编辑快捷键与布局”设置。")
                .setView(root)
                .setPositiveButton("完成", null)
                .setNeutralButton("恢复显示默认值", (dialog, which) -> {
                    fontScale = 1f;
                    termScaleX = 1f;
                    termScaleY = 1f;
                    applyAndPersistDisplay();
                })
                .show();
    }

    private interface ValueLabel {
        String format(int value);
    }

    private void addSeekSetting(LinearLayout root, String name, int max, int progress,
                                IntConsumer onChange, ValueLabel formatter) {
        TextView label = new TextView(this);
        label.setTextSize(12f);
        label.setText(name + "：" + formatter.format(progress));
        root.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(clamp(progress, 0, max));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                label.setText(name + "：" + formatter.format(value));
                if (fromUser) onChange.accept(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
    }

    private void applyAndPersistDisplay() {
        applyDisplayPreferences();
        persistFrontendPreferences();
    }

    private void applyDisplayPreferences() {
        if (termView != null) {
            termView.setFontScale(fontScale);
            termView.setScaleX(termScaleX);
            termView.setScaleY(termScaleY);
        }
    }

    private void cycleGraphicsMode() {
        graphicsMode = (graphicsMode + 1) % 3;
        RfbNative.nativeRequestGraphicsMode(graphicsMode);
        persistFrontendPreferences();
        Toast.makeText(this, "图形模式：" + graphicsLabel(), Toast.LENGTH_SHORT).show();
    }

    private String graphicsLabel() {
        if (graphicsMode == RfbNative.GRAPHICS_ORIGINAL) return "图块旧";
        if (graphicsMode == RfbNative.GRAPHICS_ADAM_BOLT) return "图块新";
        return "字符";
    }

    private void toggleSound() {
        soundEnabled = !soundEnabled;
        RfbNative.nativeRequestSoundEnabled(soundEnabled);
        if (audioManager != null) audioManager.setEnabled(soundEnabled);
        persistFrontendPreferences();
        Toast.makeText(this, soundEnabled ? "声音已开启" : "声音已关闭",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleBigtile() {
        bigtileEnabled = !bigtileEnabled;
        RfbNative.nativeRequestBigtileEnabled(bigtileEnabled);
        persistFrontendPreferences();
        Toast.makeText(this, bigtileEnabled ? "大图块已开启" : "大图块已关闭",
                Toast.LENGTH_SHORT).show();
    }

    private void scheduleSession(String player, boolean forceNew) {
        requestedSession.set(new SessionRequest(player, forceNew));
        if (gameLoopScheduled.compareAndSet(false, true)) {
            gameExecutor.execute(this::runPendingSessions);
        }
    }

    private void runPendingSessions() {
        try {
            SessionRequest request = requestedSession.getAndSet(null);
            if (request != null && !destroyed.get()) runOneSession(request);
        } finally {
            gameLoopScheduled.set(false);
        }
    }

    private void runOneSession(SessionRequest request) {
        try {
            File root = runtimeRoot;
            if (root == null) {
                StartupDiagnostics.mark("startup.assets.install.begin");
                root = AssetInstaller.install(getApplicationContext());
                runtimeRoot = root;
                StartupDiagnostics.mark("startup.assets.install.done");
            }
            if (destroyed.get() || Thread.currentThread().isInterrupted()) return;

            nativeSessionToken = RfbNative.nativePrepare(
                    TERM_COLS, TERM_ROWS, graphicsMode, soundEnabled, bigtileEnabled);
            StartupDiagnostics.mark("startup.native.prepare.done token=" + nativeSessionToken);
            if (destroyed.get() || Thread.currentThread().isInterrupted()) {
                requestNativeStop();
                return;
            }

            CountDownLatch frontendReady = new CountDownLatch(1);
            AtomicReference<Throwable> frontendFailure = new AtomicReference<>();
            File finalRoot = root;
            runOnUiThread(() -> {
                try {
                    if (destroyed.get()) return;
                    if (audioManager != null) audioManager.release();
                    termView.configureRuntimeRoot(finalRoot);
                    audioManager = new RfbAudioManager(finalRoot, soundEnabled);
                    audioManager.start();
                    termView.startPolling();
                    statusView.setText("正在启动存档槽：" + request.player
                            + (request.forceNew ? "（新游戏）" : ""));
                    statusView.setVisibility(View.GONE);
                    termView.requestFocus();
                    StartupDiagnostics.mark("startup.frontend.ready");
                } catch (Throwable error) {
                    StartupDiagnostics.recordThrowable("startup.frontend", error);
                    frontendFailure.set(error);
                } finally {
                    frontendReady.countDown();
                }
            });
            frontendReady.await();
            if (destroyed.get() || Thread.currentThread().isInterrupted()) {
                requestNativeStop();
                return;
            }
            if (frontendFailure.get() != null) {
                throw new IllegalStateException("Android 前端初始化失败", frontendFailure.get());
            }

            currentPlayer = request.player;
            rememberSlot(currentPlayer);
            frontendPrefs.edit().putString(PREF_PLAYER, currentPlayer).apply();
            StartupDiagnostics.mark("startup.core.enter player=" + currentPlayer
                    + " forceNew=" + request.forceNew);
            int result = RfbNative.nativeStart(
                    root.getAbsolutePath(), currentPlayer, request.forceNew, nativeSessionToken,
                    TERM_COLS, TERM_ROWS, graphicsMode, soundEnabled, bigtileEnabled);
            String nativeMessage = RfbNative.nativeLastExitMessage();
            nativeSessionToken = 0;
            SessionRequest replacement = requestedSession.getAndSet(null);
            StartupDiagnostics.mark("startup.core.return code=" + result
                    + " message=" + String.valueOf(nativeMessage));
            boolean normalExit = result == 0;
            if (normalExit && replacement == null) {
                StartupDiagnostics.clearAfterNormalExit();
            }

            if (!destroyed.get()) runOnUiThread(() -> {
                if (destroyed.get()) return;
                if (replacement != null) {
                    statusView.setText("当前游戏已安全保存，正在以全新核心进程切换……");
                    statusView.setVisibility(View.VISIBLE);
                    restartForSession(replacement);
                    return;
                }
                statusView.setVisibility(View.VISIBLE);
                if (normalExit) {
                    statusView.setText("游戏已保存并正常退出。\n\n"
                            + "轻触这里打开存档目录，继续游戏或开始新游戏。");
                    return;
                }
                StringBuilder message = new StringBuilder();
                message.append("游戏异常结束，返回码：").append(result);
                if (nativeMessage != null && !nativeMessage.isEmpty()) {
                    message.append("\n").append(nativeMessage);
                }
                message.append("\n\n轻触此画面可打开存档目录。")
                        .append("\n诊断日志：\n").append(StartupDiagnostics.getLogPath());
                statusView.setText(message.toString());
            });
        } catch (Throwable error) {
            StartupDiagnostics.recordThrowable("startup.worker", error);
            requestNativeStop();
            nativeSessionToken = 0;
            SessionRequest replacement = requestedSession.getAndSet(null);
            if (!destroyed.get()) runOnUiThread(() -> {
                if (destroyed.get()) return;
                if (replacement != null) restartForSession(replacement);
                else showFatalStartupError(error);
            });
        }
    }

    /**
     * The upstream game core owns process-wide static state and is not reentrant.
     * Persist the target, let Android start a clean process, then terminate this
     * fully-saved one.  This makes every slot map to its real -u save filename.
     */
    private void restartForSession(SessionRequest request) {
        if (request == null || isFinishing()) return;
        if (!processRestartScheduled.compareAndSet(false, true)) return;
        currentPlayer = request.player;
        rememberSlot(currentPlayer);
        SharedPreferences.Editor editor = frontendPrefs.edit()
                .putString(PREF_PLAYER, currentPlayer);
        if (request.forceNew) editor.putBoolean(PREF_FORCE_NEW_ONCE, true);
        else editor.remove(PREF_FORCE_NEW_ONCE);
        if (!editor.commit()) {
            processRestartScheduled.set(false);
            showFatalStartupError(new IllegalStateException("无法保存存档槽切换请求"));
            return;
        }

        try {
            Intent launch = new Intent(this, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pending = PendingIntent.getActivity(this, 7201, launch,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarms == null) throw new IllegalStateException("系统重启服务不可用");
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 650L, pending);
            StartupDiagnostics.mark("session.clean_process_restart player=" + currentPlayer
                    + " forceNew=" + request.forceNew);
            statusView.setText("存档已安全写入。正在重启游戏核心并载入：" + currentPlayer);
            statusView.setVisibility(View.VISIBLE);
            finishAffinity();
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 250L);
        } catch (Throwable error) {
            processRestartScheduled.set(false);
            StartupDiagnostics.recordThrowable("session.clean_process_restart", error);
            showFatalStartupError(error);
        }
    }

    private void requestNativeStop() {
        long token = nativeSessionToken;
        if (token <= 0) return;
        try {
            RfbNative.nativeRequestStop(token);
            StartupDiagnostics.mark("lifecycle.native.stop.requested token=" + token);
        } catch (Throwable error) {
            StartupDiagnostics.recordThrowable("lifecycle.native.stop", error);
        }
    }

    private void showFatalStartupError(Throwable error) {
        if (statusView == null) {
            statusView = new TextView(this);
            statusView.setTextColor(Color.WHITE);
            statusView.setBackgroundColor(Color.BLACK);
            statusView.setGravity(Gravity.CENTER);
            statusView.setPadding(dp(24), dp(24), dp(24), dp(24));
            setContentView(statusView);
        }
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("启动失败，但应用进程已保留：\n"
                + error.getClass().getSimpleName() + "\n"
                + String.valueOf(error.getMessage()) + "\n\n诊断日志：\n"
                + StartupDiagnostics.getLogPath());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int hudExtentPx(boolean horizontal) {
        int measured = 0;
        if (hudOverlay != null) {
            measured = horizontal ? hudOverlay.getWidth() : hudOverlay.getHeight();
        }
        if (measured > 0) return measured;
        return horizontal ? getResources().getDisplayMetrics().widthPixels
                : getResources().getDisplayMetrics().heightPixels;
    }

    private int hudTravelPx(int sizeDp, boolean horizontal) {
        return Math.max(0, hudExtentPx(horizontal) - dp(sizeDp));
    }

    private int currentHudCoordinateDp(float normalized, int sizeDp, boolean horizontal) {
        float density = getResources().getDisplayMetrics().density;
        int positionPx = Math.round(clamp(normalized, 0f, 1f)
                * hudTravelPx(sizeDp, horizontal));
        return Math.round(positionPx / density);
    }

    private int maximumHudCoordinateDp(int sizeDp, boolean horizontal) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(hudTravelPx(sizeDp, horizontal) / density);
    }

    private Integer readHudCoordinate(EditText input, int maximum) {
        String raw = input.getText().toString().trim();
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || value > maximum) throw new NumberFormatException();
            input.setError(null);
            return value;
        } catch (NumberFormatException error) {
            input.setError("请输入 0～" + maximum + " 的整数");
            return null;
        }
    }

    private float normalizedHudCoordinate(int coordinateDp, int sizeDp, boolean horizontal) {
        int travelPx = hudTravelPx(sizeDp, horizontal);
        if (travelPx <= 0) return 0f;
        return clamp(dp(coordinateDp) / (float) travelPx, 0f, 1f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onPause() {
        if (audioManager != null) audioManager.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audioManager != null) audioManager.onResume();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (gameKeyboard != null && gameKeyboard.isShowing()) {
            gameKeyboard.dismiss();
        } else if (hudOverlay != null && hudOverlay.isEditing()) {
            toggleHudEditing();
        } else {
            showGameMenu();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed.set(true);
        if (gameKeyboard != null) gameKeyboard.dismiss();
        requestNativeStop();
        if (termView != null) termView.stopPolling();
        if (audioManager != null) {
            audioManager.release();
            audioManager = null;
        }
        gameExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class SessionRequest {
        final String player;
        final boolean forceNew;

        SessionRequest(String player, boolean forceNew) {
            this.player = player;
            this.forceNew = forceNew;
        }
    }
}
