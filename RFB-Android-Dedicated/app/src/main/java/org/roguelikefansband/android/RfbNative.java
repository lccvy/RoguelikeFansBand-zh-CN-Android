package org.roguelikefansband.android;

import java.nio.charset.StandardCharsets;

/** Thin JNI boundary for the dedicated RoguelikeFansBand Android frontend. */
public final class RfbNative {
    public static final int GRAPHICS_NONE = 0;
    public static final int GRAPHICS_ORIGINAL = 1;
    public static final int GRAPHICS_ADAM_BOLT = 2;

    static {
        System.loadLibrary("rfb_android");
    }

    private RfbNative() {
    }

    public static native long nativePrepare(
            int cols,
            int rows,
            int graphicsMode,
            boolean soundEnabled,
            boolean bigtileEnabled);

    public static native int nativeStart(
            String rootPath,
            String playerName,
            boolean forceNewGame,
            long sessionToken,
            int cols,
            int rows,
            int graphicsMode,
            boolean soundEnabled,
            boolean bigtileEnabled);

    public static native void nativeRequestStop(long sessionToken);
    public static native String nativeLastExitMessage();
    public static native void nativeSendBytes(byte[] bytes);
    public static native void nativeRequestGraphicsMode(int mode);
    public static native void nativeRequestSoundEnabled(boolean enabled);
    public static native void nativeRequestBigtileEnabled(boolean enabled);
    public static native long nativeGeneration();
    public static native int nativeFrameIntCount();
    public static native int nativeCopyFrame(int[] frame);
    public static native int nativeCols();
    public static native int nativeRows();
    public static native int nativeDrainSoundEvents(int[] events);
    public static native int nativeSoundEventCount();
    public static native String nativeSoundEventName(int eventId);

    public static void sendText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        nativeSendBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void sendByte(int value) {
        nativeSendBytes(new byte[] {(byte) (value & 0xFF)});
    }

    public static void sendBytes(byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            nativeSendBytes(bytes);
        }
    }
}
