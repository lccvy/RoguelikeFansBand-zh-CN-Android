package org.roguelikefansband.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Encodes editable quick-key actions into bytes understood by the RFB core. */
public final class GameKeySequence {
    private GameKeySequence() {
    }

    /**
     * Literal text is sent as UTF-8. Named keys use braces, for example
     * {@code {ESC}}, {@code {ENTER}}, {@code {CTRL-S}} or {@code {UP}}.
     */
    public static byte[] encode(String action) {
        if (action == null) return new byte[0];
        if (action.length() > 256) {
            throw new IllegalArgumentException("按键动作不能超过 256 个字符");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(action.length() + 8);
        int literalStart = 0;
        int i = 0;
        while (i < action.length()) {
            if (action.charAt(i) != '{') {
                i++;
                continue;
            }
            int close = action.indexOf('}', i + 1);
            if (close < 0) {
                i++;
                continue;
            }
            writeUtf8(out, action.substring(literalStart, i));
            String token = action.substring(i + 1, close).trim();
            byte[] encoded = encodeToken(token);
            if (encoded == null) {
                throw new IllegalArgumentException("未知按键标记：{" + token + "}");
            }
            out.write(encoded, 0, encoded.length);
            i = close + 1;
            literalStart = i;
        }
        writeUtf8(out, action.substring(literalStart));
        return out.toByteArray();
    }

    public static String syntaxHelp() {
        return "可直接填写 g、m、? 等字符；特殊键可写 {ESC}、{ENTER}、{SPACE}、"
                + "{TAB}、{BS}、{CTRL-S}、{CTRL-X}、{NW}/{UP}/{NE}/{LEFT}/"
                + "{WAIT}/{RIGHT}/{SW}/{DOWN}/{SE}。";
    }

    private static byte[] encodeToken(String raw) {
        String token = raw.toUpperCase(Locale.ROOT);
        switch (token) {
            case "ESC": return one(27);
            case "ENTER":
            case "RETURN": return one(13);
            case "SPACE": return one(32);
            case "TAB": return one(9);
            case "BS":
            case "BACKSPACE": return one(8);
            case "DEL": return one(127);
            /*
             * RFB is an eight-direction roguelike.  Feeding DOS/Windows scan
             * code escape sequences here bypassed the Android core's normal
             * key path and did not move the character.  Use the game's native
             * numeric-keypad commands instead; these are also repeat-safe.
             */
            case "NW":
            case "NORTHWEST":
            case "HOME": return one('7');
            case "UP":
            case "NORTH": return one('8');
            case "NE":
            case "NORTHEAST":
            case "PGUP":
            case "PAGEUP": return one('9');
            case "LEFT":
            case "WEST": return one('4');
            case "WAIT":
            case "CENTER": return one('5');
            case "RIGHT":
            case "EAST": return one('6');
            case "SW":
            case "SOUTHWEST":
            case "END": return one('1');
            case "DOWN":
            case "SOUTH": return one('2');
            case "SE":
            case "SOUTHEAST":
            case "PGDN":
            case "PAGEDOWN": return one('3');
            case "INSERT": return trigger(0x52);
            case "DELETE": return trigger(0x53);
            default:
                break;
        }
        String value = token;
        if (value.startsWith("CTRL-")) value = value.substring(5);
        else if (value.startsWith("C-")) value = value.substring(2);
        else if (value.startsWith("^") && value.length() == 2) value = value.substring(1);
        else return null;

        if (value.length() != 1) return null;
        char ch = value.charAt(0);
        if (ch >= 'A' && ch <= 'Z') return one(ch - 'A' + 1);
        if (ch >= '@' && ch <= '_') return one(ch & 0x1F);
        return null;
    }

    private static void writeUtf8(ByteArrayOutputStream out, String text) {
        if (text.isEmpty()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] one(int value) {
        return new byte[] {(byte) (value & 0xFF)};
    }

    private static byte[] trigger(int scanCode) {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        return new byte[] {
                0x1F, 'x', (byte) hex[(scanCode >>> 4) & 15],
                (byte) hex[scanCode & 15], 0x0D
        };
    }
}
