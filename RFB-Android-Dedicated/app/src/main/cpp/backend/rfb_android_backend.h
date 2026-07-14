#ifndef RFB_ANDROID_BACKEND_H
#define RFB_ANDROID_BACKEND_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Allocate a framebuffer and create a fresh, uniquely identified core session.
 * A later prepare supersedes an older prepared (but not running) session, so a
 * destroyed Activity can never accidentally start the replacement Activity's
 * core. Returns 1 on success, 0 when a core is already running, and -1 on OOM. */
int rfb_android_prepare_session(int cols, int rows, int graphics_mode,
                                int sound_enabled, int bigtile_enabled,
                                uint64_t *session_token);

/* JNI embedding wrapper. RFB's historical quit() calls exit(), which is not
 * acceptable inside an Android app process. The runner installs an Android
 * quit hook and unwinds back to JNI instead. */
int rfb_android_run_core(int argc, char *argv[], uint64_t session_token);
const char *rfb_android_last_exit_message(void);

/* Wake a blocked Term event and request a clean save-and-exit for this exact
 * session. Requests from stale Activities are deliberately ignored. */
void rfb_android_request_stop(uint64_t session_token);

/* Android UI -> native input queue. Bytes must be the exact input byte stream
 * consumed by RFB's Term_keypress(). This includes UTF-8 text and RFB/Windows
 * macro trigger byte sequences. */
void rfb_android_push_input(const uint8_t *data, size_t len);
void rfb_android_flush_input(void);

/* Runtime controls are queued and applied by the RFB game thread when its Term
 * event hook runs. This avoids mutating core globals from Android's UI thread. */
void rfb_android_request_graphics_mode(int graphics_mode);
void rfb_android_request_sound_enabled(int enabled);
void rfb_android_request_bigtile_enabled(int enabled);

/* Native terminal -> Android UI snapshot.
 * Layout: 10 metadata ints, then 11 ints per cell:
 *   cp, fgRGB, bgRGB, borderRGB, borderFlags, legacyByte,
 *   tileFlags, tileRow, tileCol, terrainRow, terrainCol
 * Header:
 *   cols, rows, cursorX, cursorY, cursorVisible, cursorBig,
 *   generationLow31, graphicsMode, bigtileEnabled, layoutVersion
 */
uint64_t rfb_android_generation(void);
int rfb_android_frame_int_count(void);
int rfb_android_copy_frame(int32_t *out, int out_count);
int rfb_android_cols(void);
int rfb_android_rows(void);

/* Native sound event queue. Event -1 is the terminal bell/noise. */
int rfb_android_drain_sound_events(int32_t *out, int capacity);
int rfb_android_sound_event_count(void);
const char *rfb_android_sound_event_name(int event_id);

#ifdef __cplusplus
}
#endif

#endif
