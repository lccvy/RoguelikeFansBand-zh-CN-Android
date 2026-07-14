package org.roguelikefansband.android;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RFB sound.cfg-compatible low-latency sound backend.
 *
 * The native Term backend only queues RFB SOUND_* event ids.  This class keeps
 * the original RFB data-driven mapping: event name -> up to eight WAV files,
 * then chooses one loaded sample at random when that event fires.
 */
public final class RfbAudioManager {
    private static final String TAG = "RFB-Audio";
    private static final long POLL_MS = 20L;
    private static final int MAX_SAMPLES_PER_EVENT = 8;

    private final File soundDir;
    private final SoundPool soundPool;
    private final ToneGenerator bell;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] nativeEvents = new int[64];
    private final Map<Integer, List<Integer>> samplesByEvent = new HashMap<>();
    private final Set<Integer> loadedSampleIds = ConcurrentHashMap.newKeySet();

    private boolean enabled;
    private boolean polling;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            try {
                drainAndPlay();
            } catch (Throwable error) {
                polling = false;
                Log.e(TAG, "Sound polling stopped after JNI/Java failure", error);
                StartupDiagnostics.recordThrowable("audio.poll", error);
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    public RfbAudioManager(File runtimeRoot, boolean initiallyEnabled) {
        soundDir = new File(runtimeRoot, "lib/xtra/sound");
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(attributes)
                .build();
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (status == 0) {
                loadedSampleIds.add(sampleId);
            } else {
                Log.w(TAG, "SoundPool load failed: id=" + sampleId + " status=" + status);
            }
        });
        bell = new ToneGenerator(AudioManager.STREAM_MUSIC, 65);
        enabled = initiallyEnabled;
        loadConfiguration();
    }

    public void start() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) soundPool.autoPause();
        else soundPool.autoResume();
    }

    public void onPause() {
        soundPool.autoPause();
    }

    public void onResume() {
        if (enabled) soundPool.autoResume();
    }

    public void release() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        soundPool.release();
        bell.release();
        samplesByEvent.clear();
        loadedSampleIds.clear();
    }

    private void loadConfiguration() {
        File cfg = new File(soundDir, "sound.cfg");
        if (!cfg.isFile()) {
            Log.w(TAG, "sound.cfg not found: " + cfg);
            return;
        }

        Map<String, Integer> eventIds = new HashMap<>();
        int eventCount = Math.max(0, RfbNative.nativeSoundEventCount());
        for (int i = 0; i < eventCount; i++) {
            String name = RfbNative.nativeSoundEventName(i);
            if (name != null && !name.isEmpty()) {
                eventIds.put(normalizeEventName(name), i);
            }
        }

        /* RFB v1.3.x has one data/code naming mismatch: sound.cfg uses
         * "quest", while angband_sound_name[32] is localized as "任务".
         * The Windows config intends that entry to be the quest event, so keep
         * the data file working without mutating the RFB core table. */
        if (eventCount > 32) {
            eventIds.put("quest", 32);
        }

        boolean inSoundSection = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(cfg), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = stripComment(line).trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inSoundSection = "Sound".equalsIgnoreCase(
                            trimmed.substring(1, trimmed.length() - 1).trim());
                    continue;
                }
                if (!inSoundSection) continue;

                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;
                String eventName = normalizeEventName(trimmed.substring(0, equals).trim());
                Integer eventId = eventIds.get(eventName);
                if (eventId == null) {
                    Log.w(TAG, "Unknown sound event in sound.cfg: " + eventName);
                    continue;
                }

                String rhs = trimmed.substring(equals + 1).trim();
                if (rhs.isEmpty()) continue;
                String[] filenames = rhs.split("\\s+");
                List<Integer> ids = new ArrayList<>();
                for (String filename : filenames) {
                    if (ids.size() >= MAX_SAMPLES_PER_EVENT) break;
                    File sample = new File(soundDir, filename);
                    if (!sample.isFile()) {
                        Log.w(TAG, "Missing sound sample: " + sample);
                        continue;
                    }
                    int soundId = soundPool.load(sample.getAbsolutePath(), 1);
                    if (soundId != 0) ids.add(soundId);
                }
                if (!ids.isEmpty()) samplesByEvent.put(eventId, ids);
            }
            Log.i(TAG, "Loaded sound mappings: " + samplesByEvent.size());
        } catch (Exception error) {
            Log.e(TAG, "Unable to parse sound.cfg", error);
        }
    }

    private static String normalizeEventName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int semicolon = line.indexOf(';');
        int cut = -1;
        if (hash >= 0) cut = hash;
        if (semicolon >= 0 && (cut < 0 || semicolon < cut)) cut = semicolon;
        return cut >= 0 ? line.substring(0, cut) : line;
    }

    private void drainAndPlay() {
        int count = RfbNative.nativeDrainSoundEvents(nativeEvents);
        for (int i = 0; i < count; i++) {
            int eventId = nativeEvents[i];
            if (!enabled) continue;
            if (eventId < 0) {
                bell.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
                continue;
            }
            List<Integer> choices = samplesByEvent.get(eventId);
            if (choices == null || choices.isEmpty()) continue;

            /* SoundPool loading is asynchronous. Choose randomly among samples
             * that have actually completed loading instead of occasionally
             * dropping an event because the random pick is still pending. */
            int size = choices.size();
            int start = ThreadLocalRandom.current().nextInt(size);
            int soundId = 0;
            for (int offset = 0; offset < size; offset++) {
                int candidate = choices.get((start + offset) % size);
                if (loadedSampleIds.contains(candidate)) {
                    soundId = candidate;
                    break;
                }
            }
            if (soundId != 0) {
                soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
            }
        }
    }
}
