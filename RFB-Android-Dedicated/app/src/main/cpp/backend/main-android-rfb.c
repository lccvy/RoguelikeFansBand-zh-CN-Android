#include "angband.h"
#include "rfb_android_backend.h"

#include <android/log.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "RFB-Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

#define RFB_MIN_COLS 80
#define RFB_MIN_ROWS 27
#define INPUT_CAPACITY 8192
#define SOUND_QUEUE_CAPACITY 256

#define FRAME_LAYOUT_VERSION 2
#define FRAME_HEADER_INTS 10
#define FRAME_CELL_INTS 11

#define CELL_TILE_VALID 0x01u

typedef struct {
    uint32_t cp;
    uint32_t fg;
    uint32_t bg;
    uint32_t border_rgb;
    uint8_t border;
    uint8_t legacy;
    uint8_t tile_flags;
    uint8_t tile_row;
    uint8_t tile_col;
    uint8_t terrain_row;
    uint8_t terrain_col;
} android_cell;

typedef struct {
    term t;
} android_term_data;

static android_term_data g_td;
static int g_cols = RFB_MIN_COLS;
static int g_rows = RFB_MIN_ROWS;
static android_cell *g_cells = NULL;
static int g_cursor_x = 0;
static int g_cursor_y = 0;
static int g_cursor_visible = 0;
static int g_cursor_big = 0;
static uint64_t g_generation = 1;
static int g_frame_graphics_mode = GRAPHICS_NONE;
static int g_frame_bigtile_enabled = 0;

static int g_initial_graphics_mode = GRAPHICS_NONE;
static int g_initial_sound_enabled = 1;
static int g_initial_bigtile_enabled = 0;

static pthread_mutex_t g_frame_mu = PTHREAD_MUTEX_INITIALIZER;

static uint8_t g_input[INPUT_CAPACITY];
static size_t g_input_head = 0;
static size_t g_input_tail = 0;
static size_t g_input_size = 0;
static int g_pending_graphics_mode = -1;
static int g_pending_sound_enabled = -1;
static int g_pending_bigtile_enabled = -1;
static uint64_t g_input_session_token = 0;
static uint64_t g_stop_session_token = 0;
static pthread_mutex_t g_input_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_input_cv = PTHREAD_COND_INITIALIZER;

static int32_t g_sound_events[SOUND_QUEUE_CAPACITY];
static size_t g_sound_head = 0;
static size_t g_sound_tail = 0;
static size_t g_sound_size = 0;
static pthread_mutex_t g_sound_mu = PTHREAD_MUTEX_INITIALIZER;

/* An Activity instance owns one opaque session token. A stale Activity may
 * still finish queued Java work after a replacement Activity has started; the
 * token prevents that work from entering or stopping the replacement core. */
static pthread_mutex_t g_session_mu = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_next_session_token = 1;
static uint64_t g_prepared_session_token = 0;
static uint64_t g_running_session_token = 0;

/* RFB is historically a standalone process: quit() always calls exit().
 * A JNI library must never terminate the hosting Android app process.  The
 * Android quit hook therefore records the result, cleans up Term objects and
 * longjmps back to rfb_android_run_core(), which returns normally to Java.
 * The game core runs on exactly one dedicated executor thread. */
static jmp_buf g_quit_jmp;
static volatile int g_quit_jmp_ready = 0;
static int g_quit_code = 0;
static int g_quit_in_progress = 0;
static char g_quit_message[1024];

extern int rfb_core_main(int argc, char *argv[]);

static void android_quit_hook(cptr s)
{
    int j;

    g_quit_code = 0;
    g_quit_message[0] = '\0';
    if (s && *s) {
        if (s[0] == '+' || s[0] == '-') g_quit_code = atoi(s);
        else g_quit_code = 1;
        snprintf(g_quit_message, sizeof(g_quit_message), "%s", s);
    }

    LOGI("RFB quit intercepted: code=%d message=%s",
         g_quit_code, g_quit_message[0] ? g_quit_message : "(normal)");

    if (!g_quit_in_progress) {
        g_quit_in_progress = 1;
        for (j = 7; j >= 0; --j) {
            if (!angband_term[j]) continue;
            term_nuke(angband_term[j]);
            angband_term[j] = NULL;
        }
        Term = NULL;
        g_quit_in_progress = 0;
    }

    if (g_quit_jmp_ready) longjmp(g_quit_jmp, 1);
}

static void finish_core_session(uint64_t session_token)
{
    pthread_mutex_lock(&g_session_mu);
    if (g_running_session_token == session_token) g_running_session_token = 0;
    pthread_mutex_lock(&g_input_mu);
    if (g_input_session_token == session_token) g_input_session_token = 0;
    if (g_stop_session_token == session_token) g_stop_session_token = 0;
    pthread_mutex_unlock(&g_input_mu);
    pthread_mutex_unlock(&g_session_mu);
}

int rfb_android_run_core(int argc, char *argv[], uint64_t session_token)
{
    int result;

    pthread_mutex_lock(&g_session_mu);
    if (!session_token || g_running_session_token ||
            g_prepared_session_token != session_token) {
        pthread_mutex_unlock(&g_session_mu);
        LOGE("Rejected stale or duplicate core session: %llu",
             (unsigned long long)session_token);
        return -4;
    }
    g_prepared_session_token = 0;
    g_running_session_token = session_token;
    pthread_mutex_lock(&g_input_mu);
    g_input_session_token = session_token;
    pthread_mutex_unlock(&g_input_mu);
    pthread_mutex_unlock(&g_session_mu);

    g_quit_code = 0;
    g_quit_message[0] = '\0';
    g_quit_in_progress = 0;

    if (setjmp(g_quit_jmp) != 0) {
        g_quit_jmp_ready = 0;
        finish_core_session(session_token);
        return g_quit_code;
    }

    g_quit_jmp_ready = 1;
    quit_aux = android_quit_hook;
    result = rfb_core_main(argc, argv);
    g_quit_jmp_ready = 0;
    finish_core_session(session_token);
    return result;
}

const char *rfb_android_last_exit_message(void)
{
    return g_quit_message;
}

static int clamp_graphics_mode(int mode)
{
    if (mode == GRAPHICS_ORIGINAL || mode == GRAPHICS_ADAM_BOLT) return mode;
    return GRAPHICS_NONE;
}

static uint32_t attr_rgb(byte a)
{
    unsigned idx = ((unsigned)a) & COLOR_MASK;
    uint32_t r = angband_color_table[idx][1];
    uint32_t g = angband_color_table[idx][2];
    uint32_t b = angband_color_table[idx][3];
    return (r << 16) | (g << 8) | b;
}

static void clear_tile(android_cell *cell)
{
    cell->tile_flags = 0;
    cell->tile_row = 0;
    cell->tile_col = 0;
    cell->terrain_row = 0;
    cell->terrain_col = 0;
}

static void cell_set_default(android_cell *cell, uint32_t cp, byte a, uint8_t legacy)
{
    cell->cp = cp ? cp : (uint32_t)' ';
    cell->fg = attr_rgb(a);
    cell->bg = 0x000000u;
    cell->border_rgb = 0;
    cell->border = 0;
    cell->legacy = legacy;
    clear_tile(cell);
}

static void apply_rgb_overlay(int x, int y, android_cell *cell)
{
    u32b fg = 0;
    u32b bg = 0;
    byte border = 0;
    u32b border_rgb = 0;

    if (Term_rgb_at(x, y, &fg, &bg)) {
        cell->fg = (uint32_t)fg;
        cell->bg = (uint32_t)bg;
    }
    if (Term_rgb_border_at(x, y, &border, &border_rgb)) {
        cell->border = (uint8_t)border;
        cell->border_rgb = (uint32_t)border_rgb;
    }
}

static android_cell *frame_cell(int x, int y)
{
    if (!g_cells || x < 0 || y < 0 || x >= g_cols || y >= g_rows) return NULL;
    return &g_cells[y * g_cols + x];
}

static void frame_clear_locked(void)
{
    int x, y;
    if (!g_cells) return;
    for (y = 0; y < g_rows; y++) {
        for (x = 0; x < g_cols; x++) {
            android_cell *c = &g_cells[y * g_cols + x];
            cell_set_default(c, (uint32_t)' ', TERM_WHITE, (uint8_t)' ');
        }
    }
}

static int rfb_android_configure(int cols, int rows)
{
    android_cell *new_cells;
    if (cols < RFB_MIN_COLS) cols = RFB_MIN_COLS;
    if (rows < RFB_MIN_ROWS) rows = RFB_MIN_ROWS;
    if (cols > 255) cols = 255;
    if (rows > 255) rows = 255;

    new_cells = (android_cell *)calloc((size_t)cols * (size_t)rows,
                                       sizeof(android_cell));
    if (!new_cells) return 0;

    pthread_mutex_lock(&g_frame_mu);
    free(g_cells);
    g_cells = new_cells;
    g_cols = cols;
    g_rows = rows;
    frame_clear_locked();
    g_cursor_x = 0;
    g_cursor_y = 0;
    g_cursor_visible = 0;
    g_cursor_big = 0;
    g_generation++;
    pthread_mutex_unlock(&g_frame_mu);
    return 1;
}

int rfb_android_prepare_session(int cols, int rows, int graphics_mode,
                                int sound_enabled, int bigtile_enabled,
                                uint64_t *session_token)
{
    uint64_t token;
    if (!session_token) return -1;

    pthread_mutex_lock(&g_session_mu);
    if (g_running_session_token) {
        pthread_mutex_unlock(&g_session_mu);
        return 0;
    }
    token = g_next_session_token++;
    if (!token) token = g_next_session_token++;
    g_prepared_session_token = token;

    pthread_mutex_lock(&g_input_mu);
    g_input_head = 0;
    g_input_tail = 0;
    g_input_size = 0;
    g_pending_graphics_mode = -1;
    g_pending_sound_enabled = -1;
    g_pending_bigtile_enabled = -1;
    g_input_session_token = 0;
    g_stop_session_token = 0;
    pthread_mutex_unlock(&g_input_mu);

    pthread_mutex_lock(&g_sound_mu);
    g_sound_head = 0;
    g_sound_tail = 0;
    g_sound_size = 0;
    pthread_mutex_unlock(&g_sound_mu);

    if (!rfb_android_configure(cols, rows)) {
        if (g_prepared_session_token == token) g_prepared_session_token = 0;
        pthread_mutex_unlock(&g_session_mu);
        return -1;
    }

    g_initial_graphics_mode = clamp_graphics_mode(graphics_mode);
    g_initial_sound_enabled = sound_enabled ? 1 : 0;
    g_initial_bigtile_enabled = bigtile_enabled ? 1 : 0;
    pthread_mutex_lock(&g_frame_mu);
    g_frame_graphics_mode = g_initial_graphics_mode;
    g_frame_bigtile_enabled = g_initial_bigtile_enabled;
    pthread_mutex_unlock(&g_frame_mu);

    *session_token = token;
    pthread_mutex_unlock(&g_session_mu);
    return 1;
}

void rfb_android_request_stop(uint64_t session_token)
{
    int matches;
    if (!session_token) return;

    pthread_mutex_lock(&g_session_mu);
    matches = session_token == g_prepared_session_token ||
              session_token == g_running_session_token;
    if (matches) {
        pthread_mutex_lock(&g_input_mu);
        g_stop_session_token = session_token;
        pthread_cond_broadcast(&g_input_cv);
        pthread_mutex_unlock(&g_input_mu);
    }
    pthread_mutex_unlock(&g_session_mu);
}

void rfb_android_push_input(const uint8_t *data, size_t len)
{
    size_t i;
    if (!data || !len) return;

    pthread_mutex_lock(&g_input_mu);
    for (i = 0; i < len; i++) {
        /* Preserve the newest input if the producer outruns the game. */
        if (g_input_size == INPUT_CAPACITY) {
            g_input_head = (g_input_head + 1u) % INPUT_CAPACITY;
            g_input_size--;
        }
        g_input[g_input_tail] = data[i];
        g_input_tail = (g_input_tail + 1u) % INPUT_CAPACITY;
        g_input_size++;
    }
    pthread_cond_signal(&g_input_cv);
    pthread_mutex_unlock(&g_input_mu);
}

void rfb_android_flush_input(void)
{
    pthread_mutex_lock(&g_input_mu);
    g_input_head = 0;
    g_input_tail = 0;
    g_input_size = 0;
    pthread_mutex_unlock(&g_input_mu);
}

void rfb_android_request_graphics_mode(int graphics_mode)
{
    pthread_mutex_lock(&g_input_mu);
    g_pending_graphics_mode = clamp_graphics_mode(graphics_mode);
    pthread_cond_broadcast(&g_input_cv);
    pthread_mutex_unlock(&g_input_mu);
}

void rfb_android_request_sound_enabled(int enabled)
{
    pthread_mutex_lock(&g_input_mu);
    g_pending_sound_enabled = enabled ? 1 : 0;
    pthread_cond_broadcast(&g_input_cv);
    pthread_mutex_unlock(&g_input_mu);
}

void rfb_android_request_bigtile_enabled(int enabled)
{
    pthread_mutex_lock(&g_input_mu);
    g_pending_bigtile_enabled = enabled ? 1 : 0;
    pthread_cond_broadcast(&g_input_cv);
    pthread_mutex_unlock(&g_input_mu);
}

static int controls_pending_locked(void)
{
    return g_pending_graphics_mode >= 0 ||
           g_pending_sound_enabled >= 0 ||
           g_pending_bigtile_enabled >= 0;
}

static int stop_pending_locked(void)
{
    return g_input_session_token != 0 &&
           g_stop_session_token == g_input_session_token;
}

/* 0=no event, 1=input byte, 2=control request, 3=lifecycle stop */
static int input_pop_or_control(int wait, uint8_t *out)
{
    int result = 0;
    pthread_mutex_lock(&g_input_mu);
    while (wait && g_input_size == 0 && !controls_pending_locked() &&
            !stop_pending_locked()) {
        pthread_cond_wait(&g_input_cv, &g_input_mu);
    }
    if (stop_pending_locked()) {
        g_stop_session_token = 0;
        result = 3;
    } else if (controls_pending_locked()) {
        result = 2;
    } else if (g_input_size > 0) {
        *out = g_input[g_input_head];
        g_input_head = (g_input_head + 1u) % INPUT_CAPACITY;
        g_input_size--;
        result = 1;
    }
    pthread_mutex_unlock(&g_input_mu);
    return result;
}

static void sound_enqueue(int32_t event_id)
{
    pthread_mutex_lock(&g_sound_mu);
    if (g_sound_size == SOUND_QUEUE_CAPACITY) {
        g_sound_head = (g_sound_head + 1u) % SOUND_QUEUE_CAPACITY;
        g_sound_size--;
    }
    g_sound_events[g_sound_tail] = event_id;
    g_sound_tail = (g_sound_tail + 1u) % SOUND_QUEUE_CAPACITY;
    g_sound_size++;
    pthread_mutex_unlock(&g_sound_mu);
}

int rfb_android_drain_sound_events(int32_t *out, int capacity)
{
    int count = 0;
    if (!out || capacity <= 0) return 0;
    pthread_mutex_lock(&g_sound_mu);
    while (count < capacity && g_sound_size > 0) {
        out[count++] = g_sound_events[g_sound_head];
        g_sound_head = (g_sound_head + 1u) % SOUND_QUEUE_CAPACITY;
        g_sound_size--;
    }
    pthread_mutex_unlock(&g_sound_mu);
    return count;
}

int rfb_android_sound_event_count(void)
{
    return SOUND_MAX;
}

const char *rfb_android_sound_event_name(int event_id)
{
    if (event_id < 0 || event_id >= SOUND_MAX) return NULL;
    return angband_sound_name[event_id];
}

uint64_t rfb_android_generation(void)
{
    uint64_t value;
    pthread_mutex_lock(&g_frame_mu);
    value = g_generation;
    pthread_mutex_unlock(&g_frame_mu);
    return value;
}

int rfb_android_frame_int_count(void)
{
    int needed;
    pthread_mutex_lock(&g_frame_mu);
    needed = g_cells ? (FRAME_HEADER_INTS + g_cols * g_rows * FRAME_CELL_INTS) : 0;
    pthread_mutex_unlock(&g_frame_mu);
    return needed;
}

int rfb_android_cols(void)
{
    int value;
    pthread_mutex_lock(&g_frame_mu);
    value = g_cols;
    pthread_mutex_unlock(&g_frame_mu);
    return value;
}

int rfb_android_rows(void)
{
    int value;
    pthread_mutex_lock(&g_frame_mu);
    value = g_rows;
    pthread_mutex_unlock(&g_frame_mu);
    return value;
}

int rfb_android_copy_frame(int32_t *out, int out_count)
{
    int needed;
    int x, y, p = FRAME_HEADER_INTS;

    pthread_mutex_lock(&g_frame_mu);
    if (!g_cells) {
        pthread_mutex_unlock(&g_frame_mu);
        return 0;
    }
    needed = FRAME_HEADER_INTS + g_cols * g_rows * FRAME_CELL_INTS;
    if (!out || out_count < needed) {
        pthread_mutex_unlock(&g_frame_mu);
        return -needed;
    }

    out[0] = g_cols;
    out[1] = g_rows;
    out[2] = g_cursor_x;
    out[3] = g_cursor_y;
    out[4] = g_cursor_visible;
    out[5] = g_cursor_big;
    out[6] = (int32_t)(g_generation & 0x7FFFFFFFu);
    out[7] = (int32_t)g_frame_graphics_mode;
    out[8] = g_frame_bigtile_enabled;
    out[9] = FRAME_LAYOUT_VERSION;

    for (y = 0; y < g_rows; y++) {
        for (x = 0; x < g_cols; x++) {
            android_cell *c = &g_cells[y * g_cols + x];
            out[p++] = (int32_t)c->cp;
            out[p++] = (int32_t)c->fg;
            out[p++] = (int32_t)c->bg;
            out[p++] = (int32_t)c->border_rgb;
            out[p++] = (int32_t)c->border;
            out[p++] = (int32_t)c->legacy;
            out[p++] = (int32_t)c->tile_flags;
            out[p++] = (int32_t)c->tile_row;
            out[p++] = (int32_t)c->tile_col;
            out[p++] = (int32_t)c->terrain_row;
            out[p++] = (int32_t)c->terrain_col;
        }
    }
    pthread_mutex_unlock(&g_frame_mu);
    return needed;
}

static void set_graphics_suffix(int mode)
{
    if (mode == GRAPHICS_ADAM_BOLT) ANGBAND_GRAF = "new";
    else if (mode == GRAPHICS_ORIGINAL) ANGBAND_GRAF = "old";
    else ANGBAND_GRAF = "ascii";
}

static void apply_pending_controls(void)
{
    int graphics_mode;
    int sound_enabled;
    int bigtile_enabled;
    int visuals_changed = 0;

    pthread_mutex_lock(&g_input_mu);
    graphics_mode = g_pending_graphics_mode;
    sound_enabled = g_pending_sound_enabled;
    bigtile_enabled = g_pending_bigtile_enabled;
    g_pending_graphics_mode = -1;
    g_pending_sound_enabled = -1;
    g_pending_bigtile_enabled = -1;
    pthread_mutex_unlock(&g_input_mu);

    if (graphics_mode >= 0 && graphics_mode != (int)use_graphics) {
        graphics_mode = clamp_graphics_mode(graphics_mode);
        arg_graphics = (byte)graphics_mode;
        use_graphics = (bool)graphics_mode;
        set_graphics_suffix(graphics_mode);
        visuals_changed = 1;
        LOGI("Graphics mode -> %d", graphics_mode);
    }
    if (bigtile_enabled >= 0 && (bigtile_enabled ? TRUE : FALSE) != use_bigtile) {
        arg_bigtile = bigtile_enabled ? TRUE : FALSE;
        use_bigtile = arg_bigtile;
        visuals_changed = 1;
        LOGI("Bigtile -> %d", bigtile_enabled);
    }
    if (sound_enabled >= 0) {
        arg_sound = sound_enabled ? TRUE : FALSE;
        use_sound = arg_sound;
        LOGI("Sound -> %d", sound_enabled);
    }

    if (visuals_changed) {
        /* reset_visuals() depends on loaded visual tables, so do not call it
         * during the very early platform initialization phase. */
        if (initialized) reset_visuals();
        pthread_mutex_lock(&g_frame_mu);
        g_frame_graphics_mode = (int)use_graphics;
        g_frame_bigtile_enabled = use_bigtile ? 1 : 0;
        frame_clear_locked();
        g_generation++;
        pthread_mutex_unlock(&g_frame_mu);
        if (initialized && Term) Term_redraw();
    }
}

static void Term_init_android(term *t)
{
    (void)t;
    LOGI("Android Term initialized: %dx%d", g_cols, g_rows);
}

static void Term_nuke_android(term *t)
{
    (void)t;
    LOGI("Android Term destroyed");
}

static errr Term_curs_android(int x, int y)
{
    pthread_mutex_lock(&g_frame_mu);
    g_cursor_x = x;
    g_cursor_y = y;
    g_cursor_big = 0;
    pthread_mutex_unlock(&g_frame_mu);
    return 0;
}

static errr Term_bigcurs_android(int x, int y)
{
    pthread_mutex_lock(&g_frame_mu);
    g_cursor_x = x;
    g_cursor_y = y;
    g_cursor_big = 1;
    pthread_mutex_unlock(&g_frame_mu);
    return 0;
}

static errr Term_wipe_android(int x, int y, int n)
{
    int i;
    pthread_mutex_lock(&g_frame_mu);
    for (i = 0; i < n; i++) {
        int xx = x + i;
        android_cell *c = frame_cell(xx, y);
        if (c) {
            cell_set_default(c, (uint32_t)' ', TERM_WHITE, (uint8_t)' ');
            /* RGB metadata is copied into Term->old before wipe_hook runs. A
             * visually blank cell may still carry background/border styling. */
            apply_rgb_overlay(xx, y, c);
        }
    }
    pthread_mutex_unlock(&g_frame_mu);
    return 0;
}

static errr Term_text_android(int x, int y, int n, byte a, cptr s)
{
    int i;
    pthread_mutex_lock(&g_frame_mu);
    for (i = 0; i < n; i++) {
        int xx = x + i;
        android_cell *c = frame_cell(xx, y);
        u32b cp = 0;
        uint8_t legacy = (uint8_t)s[i];
        if (!c) continue;

        /* RFB's Unicode truth lives in z-term's parallel code-point plane.
         * This deliberately mirrors the RFB Windows frontend instead of
         * re-decoding text_hook's legacy byte stream. */
        if (Term && Term->old && Term->old->uc) cp = Term->old->uc[y][xx];
        if (!cp) cp = legacy;
        if ((cp == TERM_UC_WIDE_TRAIL) && legacy != (uint8_t)' ') cp = legacy;

        cell_set_default(c, (uint32_t)cp, a, legacy);
        apply_rgb_overlay(xx, y, c);
    }
    pthread_mutex_unlock(&g_frame_mu);
    return 0;
}

static errr Term_pict_android(int x, int y, int n,
                              const byte *ap, const char *cp,
                              const byte *tap, const char *tcp)
{
    int i;

    pthread_mutex_lock(&g_frame_mu);
    for (i = 0; i < n; i++) {
        int xx = x + i;
        android_cell *c = frame_cell(xx, y);
        u32b full_cp = 0;
        uint8_t legacy = (uint8_t)cp[i];
        if (!c) continue;

        if (Term && Term->old && Term->old->uc) full_cp = Term->old->uc[y][xx];
        if (!full_cp) full_cp = legacy;
        cell_set_default(c, (uint32_t)full_cp, ap[i], legacy);
        apply_rgb_overlay(xx, y, c);

        /* RFB/FCB graphics use the high bit as the pict marker and the low
         * seven bits as atlas row/column. The ADAM_BOLT mode also supplies a
         * terrain layer which must be painted before the actor/object layer. */
        c->tile_flags = CELL_TILE_VALID;
        c->tile_row = (uint8_t)(ap[i] & 0x7Fu);
        c->tile_col = (uint8_t)(((uint8_t)cp[i]) & 0x7Fu);
        c->terrain_row = tap ? (uint8_t)(tap[i] & 0x7Fu) : c->tile_row;
        c->terrain_col = tcp ? (uint8_t)(((uint8_t)tcp[i]) & 0x7Fu) : c->tile_col;
    }
    pthread_mutex_unlock(&g_frame_mu);
    return 0;
}

static errr Term_xtra_android(int n, int v)
{
    uint8_t key;
    int event_kind;

    switch (n) {
        case TERM_XTRA_EVENT:
            event_kind = input_pop_or_control(v ? 1 : 0, &key);
            if (event_kind == 3) {
                /* Ctrl-X follows the game's normal save-and-exit path once a
                 * character exists. During early startup there is no state to
                 * save, so unwind the embedded core immediately. */
                if (character_generated) Term_keypress(KTRL('X'));
                else quit(NULL);
                return 0;
            }
            if (event_kind == 2) {
                apply_pending_controls();
                return 0;
            }
            if (event_kind == 0) return 1;
            if (key != 0) Term_keypress((int)key);
            return 0;

        case TERM_XTRA_BORED:
            event_kind = input_pop_or_control(0, &key);
            if (event_kind == 3) {
                if (character_generated) Term_keypress(KTRL('X'));
                else quit(NULL);
            }
            else if (event_kind == 2) apply_pending_controls();
            else if (event_kind == 1 && key != 0) Term_keypress((int)key);
            return 0;

        case TERM_XTRA_FLUSH:
            rfb_android_flush_input();
            return 0;

        case TERM_XTRA_CLEAR:
            pthread_mutex_lock(&g_frame_mu);
            frame_clear_locked();
            pthread_mutex_unlock(&g_frame_mu);
            return 0;

        case TERM_XTRA_SHAPE:
            pthread_mutex_lock(&g_frame_mu);
            g_cursor_visible = v ? 1 : 0;
            pthread_mutex_unlock(&g_frame_mu);
            return 0;

        case TERM_XTRA_FROSH:
            return 0;

        case TERM_XTRA_FRESH:
            pthread_mutex_lock(&g_frame_mu);
            g_generation++;
            pthread_mutex_unlock(&g_frame_mu);
            return 0;

        case TERM_XTRA_NOISE:
            sound_enqueue(-1);
            return 0;

        case TERM_XTRA_SOUND:
            if (!use_sound || v < 0 || v >= SOUND_MAX) return 1;
            sound_enqueue((int32_t)v);
            return 0;

        case TERM_XTRA_REACT:
            /* RFB's option menu may update arg_* itself; mirror those settings
             * into the active backend in the same way as the Windows frontend. */
            use_sound = arg_sound;
            if (use_graphics != arg_graphics) {
                use_graphics = arg_graphics;
                set_graphics_suffix((int)arg_graphics);
                if (initialized) reset_visuals();
            }
            if (use_bigtile != arg_bigtile) use_bigtile = arg_bigtile;
            pthread_mutex_lock(&g_frame_mu);
            g_frame_graphics_mode = (int)use_graphics;
            g_frame_bigtile_enabled = use_bigtile ? 1 : 0;
            g_generation++;
            pthread_mutex_unlock(&g_frame_mu);
            return 0;

        case TERM_XTRA_ALIVE:
        case TERM_XTRA_LEVEL:
            return 0;

        case TERM_XTRA_DELAY:
            if (v > 0) usleep((useconds_t)v * 1000u);
            return 0;

        default:
            return 1;
    }
}

static void android_plog(cptr s)
{
    LOGE("%s", s ? s : "(null)");
}

errr init_android(int argc, char *argv[])
{
    term *t = &g_td.t;
    int i;
    (void)argc;
    (void)argv;

    /* main.c installs its standalone quit hook before selecting a frontend.
     * Restore the Android hook before any frontend failure can call quit(). */
    quit_aux = android_quit_hook;

    if (!g_cells && !rfb_android_configure(RFB_MIN_COLS, RFB_MIN_ROWS)) return -1;

    memset(&g_td, 0, sizeof(g_td));
    if (term_init(t, g_cols, g_rows, 1024) != 0) return -1;

    t->soft_cursor = TRUE;
    t->icky_corner = FALSE;
    t->higher_pict = TRUE;
    t->attr_blank = TERM_WHITE;
    t->char_blank = ' ';
    t->init_hook = Term_init_android;
    t->nuke_hook = Term_nuke_android;
    t->curs_hook = Term_curs_android;
    t->bigcurs_hook = Term_bigcurs_android;
    t->wipe_hook = Term_wipe_android;
    t->text_hook = Term_text_android;
    t->pict_hook = Term_pict_android;
    t->xtra_hook = Term_xtra_android;
    t->data = &g_td;

    for (i = 0; i < 8; i++) angband_term[i] = NULL;
    angband_term[0] = t;

    arg_graphics = (byte)g_initial_graphics_mode;
    use_graphics = (bool)g_initial_graphics_mode;
    set_graphics_suffix(g_initial_graphics_mode);
    arg_sound = g_initial_sound_enabled ? TRUE : FALSE;
    use_sound = arg_sound;
    arg_bigtile = g_initial_bigtile_enabled ? TRUE : FALSE;
    use_bigtile = arg_bigtile;
    pthread_mutex_lock(&g_frame_mu);
    g_frame_graphics_mode = (int)use_graphics;
    g_frame_bigtile_enabled = use_bigtile ? 1 : 0;
    pthread_mutex_unlock(&g_frame_mu);

    Term_activate(t);
    plog_aux = android_plog;

    LOGI("RFB dedicated Android frontend ready: graphics=%d sound=%d bigtile=%d",
         g_initial_graphics_mode, g_initial_sound_enabled, g_initial_bigtile_enabled);
    return 0;
}
