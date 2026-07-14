package org.roguelikefansband.android;

import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent high-freedom floating button configuration. */
public final class VirtualKeyStore {
    private static final String PREF_KEYS = "custom_quick_keys_v1";
    private static final String PREF_PANEL = "custom_hud_panel_v2";
    private static final String PREF_UNIFIED_MIGRATION = "unified_hud_v2_migrated";
    private static final String PREF_LEGACY_PANEL_WIDTH = "control_panel_width_dp";
    private static final AtomicLong NEXT_ID = new AtomicLong(System.currentTimeMillis());

    public static final int MAX_KEYS = 64;
    public static final int MIN_WIDTH_DP = 36;
    public static final int MAX_WIDTH_DP = 220;
    public static final int MIN_HEIGHT_DP = 28;
    public static final int MAX_HEIGHT_DP = 160;
    public static final int MIN_PANEL_WIDTH_DP = 80;
    public static final int MAX_PANEL_WIDTH_DP = 360;
    public static final int MIN_GAME_AREA_DP = 240;

    public static final int[] COLORS = {
            0xFF2B3138, 0xFF174A7E, 0xFF1B6B4B, 0xFF6A3D8F,
            0xFF8A4A14, 0xFF7A2638, 0xFF3D5366, 0xFF4F4F4F
    };
    public static final String[] COLOR_NAMES = {
            "深灰", "蓝色", "绿色", "紫色", "橙色", "红色", "蓝灰", "中性灰"
    };
    public static final String[] SHAPE_NAMES = {
            "直角矩形", "圆角矩形", "圆形 / 椭圆", "胶囊形"
    };

    private final SharedPreferences prefs;

    public VirtualKeyStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Loads both the new free-layout format and the old label/action-only format. */
    public List<KeySpec> load() {
        String raw = prefs.getString(PREF_KEYS, null);
        if (raw == null || raw.trim().isEmpty()) {
            List<KeySpec> result = defaults();
            prefs.edit().putBoolean(PREF_UNIFIED_MIGRATION, true).apply();
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            ArrayList<KeySpec> result = new ArrayList<>();
            for (int i = 0; i < array.length() && result.size() < MAX_KEYS; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String label = sanitizeLabel(item.optString("label", ""));
                String action = sanitizeAction(item.optString("action", ""));
                if (label.isEmpty() || action.isEmpty()) continue;

                int layoutIndex = result.size();
                KeySpec fallback = create(label, action, layoutIndex);
                String id = item.optString("id", fallback.id);
                float x = finite01(item.optDouble("x", fallback.x), fallback.x);
                float y = finite01(item.optDouble("y", fallback.y), fallback.y);
                int width = clamp(item.optInt("widthDp", fallback.widthDp),
                        MIN_WIDTH_DP, MAX_WIDTH_DP);
                int height = clamp(item.optInt("heightDp", fallback.heightDp),
                        MIN_HEIGHT_DP, MAX_HEIGHT_DP);
                int shape = clamp(item.optInt("shape", fallback.shape),
                        TouchFeedbackButton.SHAPE_RECTANGLE,
                        TouchFeedbackButton.SHAPE_CAPSULE);
                int opacity = clamp(item.optInt("opacity", fallback.opacity), 20, 100);
                int color = opaque(item.optInt("color", fallback.color));
                boolean repeat = item.has("repeat")
                        ? item.optBoolean("repeat", false)
                        : isRepeatRecommended(action);
                result.add(new KeySpec(id, label, action, x, y, width, height,
                        shape, opacity, color, repeat));
            }
            /* An explicitly saved empty array means the user wanted zero old floating keys. */
            if (result.isEmpty() && array.length() > 0) result.addAll(defaults());
            migrateLegacyFixedPanel(result);
            return result;
        } catch (JSONException | IllegalArgumentException ignored) {
            List<KeySpec> result = defaults();
            prefs.edit().putBoolean(PREF_UNIFIED_MIGRATION, true).apply();
            return result;
        }
    }

    /**
     * v7.2 kept the direction pad and display controls outside the editable HUD.
     * Add those formerly fixed controls exactly once when an old preference file
     * is opened, without disturbing any button the player already customized.
     */
    private void migrateLegacyFixedPanel(List<KeySpec> result) {
        if (prefs.getBoolean(PREF_UNIFIED_MIGRATION, false)) return;
        String[][] legacy = {
                {"西北 7", "7"}, {"向上 8", "8"}, {"东北 9", "9"},
                {"向左 4", "4"}, {"等待 5", "5"}, {"向右 6", "6"},
                {"西南 1", "1"}, {"向下 2", "2"}, {"东南 3", "3"},
                {"游戏菜单", "{ANDROID-MENU}"},
                {"大键盘", "{ANDROID-KEYBOARD}"},
                {"屏幕设置", "{ANDROID-DISPLAY}"},
                {"图形模式", "{ANDROID-GRAPHICS}"},
                {"声音开关", "{ANDROID-SOUND}"},
                {"大图块", "{ANDROID-BIGTILE}"}
        };
        int index = 0;
        for (String[] item : legacy) {
            if (result.size() >= MAX_KEYS) break;
            if (!containsAction(result, item[1])) {
                KeySpec added = fixedPanelKey(item[0], item[1], index);
                added.id = "unified-v2-" + index;
                result.add(added);
            }
            index++;
        }
        save(result);
        prefs.edit().putBoolean(PREF_UNIFIED_MIGRATION, true).apply();
    }

    public void save(List<KeySpec> keys) {
        JSONArray array = new JSONArray();
        int count = Math.min(keys == null ? 0 : keys.size(), MAX_KEYS);
        for (int i = 0; i < count; i++) {
            KeySpec key = keys.get(i);
            JSONObject item = new JSONObject();
            try {
                item.put("id", key.id == null ? newId() : key.id);
                item.put("label", sanitizeLabel(key.label));
                item.put("action", sanitizeAction(key.action));
                item.put("x", clamp01(key.x));
                item.put("y", clamp01(key.y));
                item.put("widthDp", clamp(key.widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP));
                item.put("heightDp", clamp(key.heightDp, MIN_HEIGHT_DP, MAX_HEIGHT_DP));
                item.put("shape", clamp(key.shape, TouchFeedbackButton.SHAPE_RECTANGLE,
                        TouchFeedbackButton.SHAPE_CAPSULE));
                item.put("opacity", clamp(key.opacity, 20, 100));
                item.put("color", opaque(key.color));
                item.put("repeat", key.repeat);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            array.put(item);
        }
        prefs.edit().putString(PREF_KEYS, array.toString()).apply();
    }

    public List<KeySpec> reset() {
        List<KeySpec> result = defaults();
        save(result);
        prefs.edit().putBoolean(PREF_UNIFIED_MIGRATION, true).apply();
        return result;
    }

    public HudPanelSpec loadPanel() {
        String raw = prefs.getString(PREF_PANEL, null);
        if (raw == null || raw.trim().isEmpty()) {
            HudPanelSpec migrated = HudPanelSpec.defaults();
            migrated.widthDp = clamp(prefs.getInt(PREF_LEGACY_PANEL_WIDTH, migrated.widthDp),
                    MIN_PANEL_WIDTH_DP, MAX_PANEL_WIDTH_DP);
            savePanel(migrated);
            return migrated;
        }
        try {
            JSONObject item = new JSONObject(raw);
            HudPanelSpec fallback = HudPanelSpec.defaults();
            return new HudPanelSpec(
                    item.optBoolean("visible", fallback.visible),
                    item.has("dockRight")
                            ? item.optBoolean("dockRight", fallback.dockRight)
                            : item.optDouble("x", 1d) >= 0.5d,
                    clamp(item.optInt("widthDp", fallback.widthDp),
                            MIN_PANEL_WIDTH_DP, MAX_PANEL_WIDTH_DP),
                    clamp(item.optInt("shape", fallback.shape),
                            TouchFeedbackButton.SHAPE_RECTANGLE,
                            TouchFeedbackButton.SHAPE_CAPSULE),
                    clamp(item.optInt("opacity", fallback.opacity), 5, 100),
                    opaque(item.optInt("color", fallback.color)));
        } catch (JSONException | IllegalArgumentException ignored) {
            return HudPanelSpec.defaults();
        }
    }

    public void savePanel(HudPanelSpec panel) {
        HudPanelSpec value = panel == null ? HudPanelSpec.defaults() : panel;
        JSONObject item = new JSONObject();
        try {
            item.put("visible", value.visible);
            item.put("dockRight", value.dockRight);
            item.put("widthDp", clamp(value.widthDp,
                    MIN_PANEL_WIDTH_DP, MAX_PANEL_WIDTH_DP));
            item.put("shape", clamp(value.shape, TouchFeedbackButton.SHAPE_RECTANGLE,
                    TouchFeedbackButton.SHAPE_CAPSULE));
            item.put("opacity", clamp(value.opacity, 5, 100));
            item.put("color", opaque(value.color));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        prefs.edit().putString(PREF_PANEL, item.toString()).apply();
    }

    public HudPanelSpec resetPanel() {
        HudPanelSpec result = HudPanelSpec.defaults();
        savePanel(result);
        return result;
    }

    public static String sanitizeLabel(String value) {
        String text = value == null ? "" : value.trim().replace('\n', ' ');
        if (text.length() > 18) text = text.substring(0, 18);
        return text;
    }

    public static String sanitizeAction(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 256) throw new IllegalArgumentException("动作过长");
        if (!isFrontendAction(text)) {
            GameKeySequence.encode(text); // Validate game tokens before persisting.
        }
        return text;
    }

    public static boolean isFrontendAction(String action) {
        if (action == null) return false;
        String value = action.trim().toUpperCase(Locale.ROOT);
        return value.equals("{ANDROID-MENU}")
                || value.equals("{ANDROID-SAVES}")
                || value.equals("{ANDROID-KEYBOARD}")
                || value.equals("{ANDROID-DISPLAY}")
                || value.equals("{ANDROID-GRAPHICS}")
                || value.equals("{ANDROID-SOUND}")
                || value.equals("{ANDROID-BIGTILE}")
                || value.equals("{ANDROID-HUD-EDIT}");
    }

    public static KeySpec create(String label, String action, int index) {
        int column = Math.floorMod(index, 5);
        int row = Math.floorMod(index / 5, 4);
        float x = column / 4f;
        float y = 0.62f + row * 0.126f;
        return new KeySpec(newId(), label, action, x, Math.min(1f, y),
                56, 34, TouchFeedbackButton.SHAPE_ROUNDED, 72,
                COLORS[Math.floorMod(row, COLORS.length)], isRepeatRecommended(action));
    }

    public static List<KeySpec> defaults() {
        ArrayList<KeySpec> keys = new ArrayList<>();
        String[][] fixed = {
                {"西北 7", "7"}, {"向上 8", "8"}, {"东北 9", "9"},
                {"向左 4", "4"}, {"等待 5", "5"}, {"向右 6", "6"},
                {"西南 1", "1"}, {"向下 2", "2"}, {"东南 3", "3"},
                {"游戏菜单", "{ANDROID-MENU}"}, {"大键盘", "{ANDROID-KEYBOARD}"},
                {"存档槽", "{ANDROID-SAVES}"}, {"布局", "{ANDROID-HUD-EDIT}"},
                {"屏幕", "{ANDROID-DISPLAY}"}, {"声音", "{ANDROID-SOUND}"}
        };
        for (int i = 0; i < fixed.length; i++) {
            KeySpec spec = fixedPanelKey(fixed[i][0], fixed[i][1], i);
            spec.id = "default-panel-" + i;
            keys.add(spec);
        }
        addAt(keys, "Esc", "{ESC}", 0.02f, 0.78f);
        addAt(keys, "回车", "{ENTER}", 0.13f, 0.78f);
        addAt(keys, "空格", "{SPACE}", 0.24f, 0.78f);
        addAt(keys, "拾取 g", "g", 0.35f, 0.94f);
        addAt(keys, "背包 i", "i", 0.46f, 0.94f);
        addAt(keys, "装备 e", "e", 0.57f, 0.94f);
        addAt(keys, "施法 m", "m", 0.68f, 0.94f);
        return keys;
    }

    private static KeySpec fixedPanelKey(String label, String action, int index) {
        int column = index % 3;
        int row = index / 3;
        float x = 0.80f + column * 0.095f;
        float y = 0.025f + row * 0.17f;
        return new KeySpec(newId(), label, action, Math.min(1f, x), Math.min(1f, y),
                48, 36, TouchFeedbackButton.SHAPE_ROUNDED, 78,
                COLORS[0], isRepeatRecommended(action));
    }

    private static void addAt(List<KeySpec> list, String label, String action, float x, float y) {
        KeySpec spec = new KeySpec(newId(), label, action, x, y, 58, 34,
                TouchFeedbackButton.SHAPE_ROUNDED, 72, COLORS[0],
                isRepeatRecommended(action));
        spec.id = "default-free-" + list.size();
        list.add(spec);
    }

    private static boolean containsAction(List<KeySpec> keys, String action) {
        String expected = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        for (KeySpec key : keys) {
            String actual = key.action == null ? "" : key.action.trim().toUpperCase(Locale.ROOT);
            if (actual.equals(expected)) return true;
        }
        return false;
    }

    public static boolean isRepeatRecommended(String action) {
        String value = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (value.matches("[1-9]")) return true;
        return value.matches("\\{(NW|UP|NE|LEFT|WAIT|RIGHT|SW|DOWN|SE|NORTH|SOUTH|EAST|WEST)\\}")
                || value.equals("{BS}") || value.equals("{BACKSPACE}");
    }

    public static int colorIndex(int color) {
        int opaqueColor = opaque(color);
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == opaqueColor) return i;
        }
        return 0;
    }

    private static String newId() {
        return "key-" + Long.toString(NEXT_ID.incrementAndGet(), 36);
    }

    private static int opaque(int color) {
        if ((color & 0x00FFFFFF) == 0) return COLORS[0];
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float finite01(double value, float fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        return clamp01((float) value);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class KeySpec {
        public String id;
        public String label;
        public String action;
        /** Normalized top-left position within the available travel distance. */
        public float x;
        public float y;
        public int widthDp;
        public int heightDp;
        public int shape;
        public int opacity;
        public int color;
        public boolean repeat;

        public KeySpec(String label, String action) {
            this(create(label, action, 0));
        }

        public KeySpec(KeySpec other) {
            this(other.id, other.label, other.action, other.x, other.y,
                    other.widthDp, other.heightDp, other.shape, other.opacity,
                    other.color, other.repeat);
        }

        public KeySpec(String id, String label, String action, float x, float y,
                       int widthDp, int heightDp, int shape, int opacity,
                       int color, boolean repeat) {
            this.id = id;
            this.label = label;
            this.action = action;
            this.x = clamp01(x);
            this.y = clamp01(y);
            this.widthDp = clamp(widthDp, MIN_WIDTH_DP, MAX_WIDTH_DP);
            this.heightDp = clamp(heightDp, MIN_HEIGHT_DP, MAX_HEIGHT_DP);
            this.shape = clamp(shape, TouchFeedbackButton.SHAPE_RECTANGLE,
                    TouchFeedbackButton.SHAPE_CAPSULE);
            this.opacity = clamp(opacity, 20, 100);
            this.color = opaque(color);
            this.repeat = repeat;
        }
    }

    /** A movable visual background; buttons remain independent full-screen HUD items. */
    public static final class HudPanelSpec {
        public boolean visible;
        public boolean dockRight;
        public int widthDp;
        public int shape;
        public int opacity;
        public int color;

        public static HudPanelSpec defaults() {
            return new HudPanelSpec(true, true, 166,
                    TouchFeedbackButton.SHAPE_ROUNDED, 34, 0xFF161B20);
        }

        public HudPanelSpec(HudPanelSpec other) {
            this(other.visible, other.dockRight, other.widthDp,
                    other.shape, other.opacity, other.color);
        }

        public HudPanelSpec(boolean visible, boolean dockRight, int widthDp,
                            int shape, int opacity, int color) {
            this.visible = visible;
            this.dockRight = dockRight;
            this.widthDp = clamp(widthDp, MIN_PANEL_WIDTH_DP, MAX_PANEL_WIDTH_DP);
            this.shape = clamp(shape, TouchFeedbackButton.SHAPE_RECTANGLE,
                    TouchFeedbackButton.SHAPE_CAPSULE);
            this.opacity = clamp(opacity, 5, 100);
            this.color = opaque(color);
        }
    }
}
