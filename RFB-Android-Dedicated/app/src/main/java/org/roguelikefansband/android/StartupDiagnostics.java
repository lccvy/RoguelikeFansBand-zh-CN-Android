package org.roguelikefansband.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bounded startup trace that is retained only when a session fails or crashes. */
public final class StartupDiagnostics {
    private static final String TAG = "RFB-Startup";
    private static final long MAX_LOG_BYTES = 256L * 1024L;
    private static final Object LOCK = new Object();
    private static File logFile;
    private static Thread.UncaughtExceptionHandler previousHandler;
    private static boolean handlerInstalled;

    private StartupDiagnostics() {
    }

    public static void initialize(Context context) {
        synchronized (LOCK) {
            File base = chooseWritableLogDirectory(context);
            logFile = new File(base, "rfb-startup.log");
            trimOversizedPreviousLocked();
            rotateOversizedLogLocked(0L);
            appendLocked("=== launch " + timestamp() + " ===\n");

            if (!handlerInstalled) {
                previousHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                    recordThrowable("uncaught thread=" + thread.getName(), error);
                    if (previousHandler != null) {
                        previousHandler.uncaughtException(thread, error);
                    }
                });
                handlerInstalled = true;
            }
        }
    }

    private static File chooseWritableLogDirectory(Context context) {
        try {
            File external = context.getExternalFilesDir(null);
            if (external != null
                    && (external.isDirectory() || external.mkdirs())
                    && external.canWrite()) {
                return external;
            }
        } catch (Throwable unavailableExternalStorage) {
            Log.w(TAG, "External diagnostics directory is unavailable; using internal files",
                    unavailableExternalStorage);
        }

        File internal = context.getFilesDir();
        if (!internal.isDirectory() && !internal.mkdirs()) {
            Log.w(TAG, "Unable to create internal diagnostics directory");
        }
        return internal;
    }

    public static void mark(String stage) {
        String line = timestamp() + " [STAGE] " + stage + "\n";
        Log.i(TAG, stage);
        synchronized (LOCK) {
            appendLocked(line);
        }
    }

    public static void recordThrowable(String where, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(timestamp() + " [ERROR] " + where);
        if (error != null) error.printStackTrace(pw);
        pw.flush();
        Log.e(TAG, where, error);
        synchronized (LOCK) {
            appendLocked(sw.toString());
        }
    }

    public static String getLogPath() {
        synchronized (LOCK) {
            return logFile == null ? "尚未创建" : logFile.getAbsolutePath();
        }
    }

    /** A clean core return needs no retained diagnostics and should consume no storage. */
    public static void clearAfterNormalExit() {
        synchronized (LOCK) {
            if (logFile == null) return;
            deleteOrTruncateLocked(logFile);
            deleteOrTruncateLocked(previousLogFileLocked());
        }
    }

    private static void appendLocked(String text) {
        if (logFile == null) return;
        try {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            byte[] bytes = boundedUtf8(text);
            rotateOversizedLogLocked(bytes.length);
            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(bytes);
                out.flush();
            }
        } catch (Exception ignored) {
            Log.e(TAG, "Unable to write startup diagnostics", ignored);
        }
    }

    private static void rotateOversizedLogLocked(long incomingBytes) {
        if (logFile == null || !logFile.isFile()) return;
        if (logFile.length() > MAX_LOG_BYTES) {
            deleteOrTruncateLocked(logFile);
            return;
        }
        if (logFile.length() + Math.max(0L, incomingBytes) <= MAX_LOG_BYTES) return;
        File previous = previousLogFileLocked();
        if (previous.exists() && !previous.delete()) {
            Log.w(TAG, "Unable to delete previous startup log");
        }
        if (!logFile.renameTo(previous)) {
            Log.w(TAG, "Unable to rotate oversized startup log");
        }
    }

    private static byte[] boundedUtf8(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_LOG_BYTES / 2L) return bytes;
        int keep = (int) Math.min(text.length(), MAX_LOG_BYTES / 16L);
        String shortened = text.substring(0, keep)
                + "\n... diagnostic entry truncated ...\n"
                + text.substring(Math.max(keep, text.length() - keep));
        return shortened.getBytes(StandardCharsets.UTF_8);
    }

    private static void trimOversizedPreviousLocked() {
        File previous = previousLogFileLocked();
        if (previous != null && previous.isFile() && previous.length() > MAX_LOG_BYTES) {
            deleteOrTruncateLocked(previous);
        }
    }

    private static File previousLogFileLocked() {
        return logFile == null ? null
                : new File(logFile.getParentFile(), "rfb-startup.previous.log");
    }

    private static void deleteOrTruncateLocked(File file) {
        if (file == null || !file.exists()) return;
        if (file.delete()) return;
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            // Truncation is the fallback when a device refuses deletion.
            out.flush();
        } catch (Exception error) {
            Log.w(TAG, "Unable to clear healthy-session diagnostics", error);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
