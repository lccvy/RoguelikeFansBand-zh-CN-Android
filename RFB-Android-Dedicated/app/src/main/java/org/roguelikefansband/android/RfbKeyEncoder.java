package org.roguelikefansband.android;

import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;

/**
 * Encodes Android hardware-key events into the same macro-trigger protocol
 * used by RFB's Windows frontend:
 *
 *   0x1F [C][S][A] x [K] HH 0x0D
 *
 * HH is the two-digit uppercase hexadecimal PC set-1 scan code.  K marks a
 * physical numeric-keypad key. Printable keys are intentionally left to the
 * normal Unicode/IME path so localized keyboards keep producing real text.
 */
public final class RfbKeyEncoder {
    private static final int TRIGGER_START = 0x1F;
    private static final int TRIGGER_END = 0x0D;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private RfbKeyEncoder() {
    }

    /**
     * @return null when the caller should try normal Unicode translation;
     *         an empty byte array when the event is a standalone modifier and
     *         should be consumed; otherwise encoded bytes to send to RFB.
     */
    public static byte[] encode(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return null;
        }
        if (isModifierKey(event.getKeyCode())) {
            return new byte[0];
        }

        Mapping mapping = mapSpecialKey(event.getKeyCode());
        if (mapping == null) {
            return null;
        }
        return makeTrigger(mapping.scanCode, mapping.keypad,
                event.isCtrlPressed(), event.isShiftPressed(), event.isAltPressed());
    }

    static byte[] makeTrigger(int scanCode, boolean keypad,
                              boolean ctrl, boolean shift, boolean alt) {
        if (scanCode < 0 || scanCode > 0xFF) {
            throw new IllegalArgumentException("scanCode out of range: " + scanCode);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        out.write(TRIGGER_START);
        if (ctrl) out.write('C');
        if (shift) out.write('S');
        if (alt) out.write('A');
        out.write('x');
        if (keypad) out.write('K');
        out.write(HEX[(scanCode >>> 4) & 0x0F]);
        out.write(HEX[scanCode & 0x0F]);
        out.write(TRIGGER_END);
        return out.toByteArray();
    }

    private static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private static Mapping mapSpecialKey(int keyCode) {
        switch (keyCode) {
            // Navigation cluster: extended PC keys, therefore no K marker.
            case KeyEvent.KEYCODE_MOVE_HOME:    return new Mapping(0x47, false);
            case KeyEvent.KEYCODE_DPAD_UP:      return new Mapping(0x48, false);
            case KeyEvent.KEYCODE_PAGE_UP:      return new Mapping(0x49, false);
            case KeyEvent.KEYCODE_DPAD_LEFT:    return new Mapping(0x4B, false);
            case KeyEvent.KEYCODE_DPAD_CENTER:  return new Mapping(0x4C, false);
            case KeyEvent.KEYCODE_DPAD_RIGHT:   return new Mapping(0x4D, false);
            case KeyEvent.KEYCODE_MOVE_END:     return new Mapping(0x4F, false);
            case KeyEvent.KEYCODE_DPAD_DOWN:    return new Mapping(0x50, false);
            case KeyEvent.KEYCODE_PAGE_DOWN:    return new Mapping(0x51, false);
            case KeyEvent.KEYCODE_INSERT:       return new Mapping(0x52, false);
            case KeyEvent.KEYCODE_FORWARD_DEL:  return new Mapping(0x53, false);

            // Other members of RFB Windows special_key_list that have stable
            // Android equivalents and stable PC set-1 scan codes.
            case KeyEvent.KEYCODE_CAPS_LOCK:    return new Mapping(0x3A, false);
            case KeyEvent.KEYCODE_BREAK:        return new Mapping(0x45, false);
            case KeyEvent.KEYCODE_SYSRQ:        return new Mapping(0x37, false);
            case KeyEvent.KEYCODE_MENU:         return new Mapping(0x5D, false);

            // Function keys.
            case KeyEvent.KEYCODE_F1:  return new Mapping(0x3B, false);
            case KeyEvent.KEYCODE_F2:  return new Mapping(0x3C, false);
            case KeyEvent.KEYCODE_F3:  return new Mapping(0x3D, false);
            case KeyEvent.KEYCODE_F4:  return new Mapping(0x3E, false);
            case KeyEvent.KEYCODE_F5:  return new Mapping(0x3F, false);
            case KeyEvent.KEYCODE_F6:  return new Mapping(0x40, false);
            case KeyEvent.KEYCODE_F7:  return new Mapping(0x41, false);
            case KeyEvent.KEYCODE_F8:  return new Mapping(0x42, false);
            case KeyEvent.KEYCODE_F9:  return new Mapping(0x43, false);
            case KeyEvent.KEYCODE_F10: return new Mapping(0x44, false);
            case KeyEvent.KEYCODE_F11: return new Mapping(0x57, false);
            case KeyEvent.KEYCODE_F12: return new Mapping(0x58, false);
            case KeyEvent.KEYCODE_NUM_LOCK: return new Mapping(0x45, false);
            case KeyEvent.KEYCODE_SCROLL_LOCK: return new Mapping(0x46, false);

            // Numeric keypad: same scan codes as the non-extended navigation
            // positions, plus the K marker used by the Windows frontend.
            case KeyEvent.KEYCODE_NUMPAD_7:        return new Mapping(0x47, true);
            case KeyEvent.KEYCODE_NUMPAD_8:        return new Mapping(0x48, true);
            case KeyEvent.KEYCODE_NUMPAD_9:        return new Mapping(0x49, true);
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return new Mapping(0x4A, true);
            case KeyEvent.KEYCODE_NUMPAD_4:        return new Mapping(0x4B, true);
            case KeyEvent.KEYCODE_NUMPAD_5:        return new Mapping(0x4C, true);
            case KeyEvent.KEYCODE_NUMPAD_6:        return new Mapping(0x4D, true);
            case KeyEvent.KEYCODE_NUMPAD_ADD:      return new Mapping(0x4E, true);
            case KeyEvent.KEYCODE_NUMPAD_1:        return new Mapping(0x4F, true);
            case KeyEvent.KEYCODE_NUMPAD_2:        return new Mapping(0x50, true);
            case KeyEvent.KEYCODE_NUMPAD_3:        return new Mapping(0x51, true);
            case KeyEvent.KEYCODE_NUMPAD_0:        return new Mapping(0x52, true);
            case KeyEvent.KEYCODE_NUMPAD_DOT:
            case KeyEvent.KEYCODE_NUMPAD_COMMA:    return new Mapping(0x53, true);
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:   return new Mapping(0x35, true);
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return new Mapping(0x37, true);
            default:
                return null;
        }
    }

    private static final class Mapping {
        final int scanCode;
        final boolean keypad;

        Mapping(int scanCode, boolean keypad) {
            this.scanCode = scanCode;
            this.keypad = keypad;
        }
    }
}
