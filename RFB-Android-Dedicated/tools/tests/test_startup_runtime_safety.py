#!/usr/bin/env python3
from pathlib import Path
import os

ROOT = Path(os.environ.get("RFB_PROJECT_ROOT", Path(__file__).resolve().parents[2]))
java = (ROOT / "app/src/main/java/org/roguelikefansband/android/MainActivity.java").read_text(encoding="utf-8")
backend = (ROOT / "app/src/main/cpp/backend/main-android-rfb.c").read_text(encoding="utf-8")
jni = (ROOT / "app/src/main/cpp/backend/rfb_jni.c").read_text(encoding="utf-8")

prepare = java.find("RfbNative.nativePrepare")
poll = java.find("termView.startPolling()")
assert prepare >= 0 and poll >= 0 and prepare < poll, "polling must start after nativePrepare"
assert java.index("buildUi();") < java.index("keepScreenOnAndGoFullscreen();"), (
    "fullscreen insets must be requested only after DecorView/content creation")
assert "decorView.getWindowInsetsController()" in java
assert "getWindow().getInsetsController()" not in java

count_start = backend.index("int rfb_android_frame_int_count(void)")
count_end = backend.index("int rfb_android_cols(void)", count_start)
count_block = backend[count_start:count_end]
assert "g_cells ?" in count_block, "frame count must be zero before allocation"

copy_start = backend.index("int rfb_android_copy_frame")
copy_end = backend.index("static void set_graphics_suffix", copy_start)
copy_block = backend[copy_start:copy_end]
assert "if (!g_cells)" in copy_block and "return 0;" in copy_block
assert copy_block.index("pthread_mutex_lock(&g_frame_mu)") < copy_block.index("if (!g_cells)")

assert "setjmp(g_quit_jmp)" in backend
assert "longjmp(g_quit_jmp, 1)" in backend
assert "quit_aux = android_quit_hook" in backend
assert "rfb_android_run_core(argc, argv, (uint64_t)sessionToken)" in jni
assert "g_prepared_session_token" in backend
assert "g_running_session_token" in backend
assert "g_stop_session_token" in backend
assert "event_kind == 3" in backend
assert "nativeRequestStop" in java
assert "destroyed.set(true)" in java
assert java.index("requestNativeStop();", java.index("protected void onDestroy")) < java.index(
    "gameExecutor.shutdownNow();", java.index("protected void onDestroy"))

print("startup runtime safety tests passed")
