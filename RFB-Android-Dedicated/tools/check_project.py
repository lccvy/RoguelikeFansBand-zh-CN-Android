#!/usr/bin/env python3
"""Static integrity checks that do not require Android SDK/NDK."""
from __future__ import annotations

from pathlib import Path
import ast
import os
import re
import zipfile

ROOT = Path(os.environ.get("RFB_PROJECT_ROOT", str(Path(__file__).absolute().parents[1]))).absolute()

JAVA_DIR = ROOT / "app/src/main/java/org/roguelikefansband/android"
CPP_DIR = ROOT / "app/src/main/cpp"

REQUIRED = [
    "settings.gradle",
    "build.gradle",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/org/roguelikefansband/android/MainActivity.java",
    "app/src/main/java/org/roguelikefansband/android/RfbNative.java",
    "app/src/main/java/org/roguelikefansband/android/RfbTermView.java",
    "app/src/main/java/org/roguelikefansband/android/RfbAudioManager.java",
    "app/src/main/java/org/roguelikefansband/android/RfbKeyEncoder.java",
    "app/src/main/java/org/roguelikefansband/android/GameKeySequence.java",
    "app/src/main/java/org/roguelikefansband/android/GameCommandCatalog.java",
    "app/src/main/java/org/roguelikefansband/android/GameKeyboardDialog.java",
    "app/src/main/java/org/roguelikefansband/android/VirtualKeyStore.java",
    "app/src/main/java/org/roguelikefansband/android/CustomHudOverlay.java",
    "app/src/main/java/org/roguelikefansband/android/TouchFeedbackButton.java",
    "app/src/main/java/org/roguelikefansband/android/PressRepeater.java",
    "app/src/main/java/org/roguelikefansband/android/AssetInstaller.java",
    "app/src/main/java/org/roguelikefansband/android/StartupDiagnostics.java",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/cpp/config/autoconf.h",
    "app/src/main/cpp/config/rfb_android_compat.h",
    "app/src/main/cpp/backend/rfb_android_backend.h",
    "app/src/main/cpp/backend/main-android-rfb.c",
    "app/src/main/cpp/backend/rfb_jni.c",
    "tools/prepare_source.py",
    "tools/build.py",
    "tools/portable_gui.ps1",
    "tools/portable_bootstrap.ps1",
    "tools/portable_build.ps1",
    "debug-signing/rfb-local-debug.keystore",
    "debug-signing/README.txt",
    "build_gui.bat",
    "capture_android_crash_log.bat",
    "抓取手机崩溃日志.bat",
    "打开图形化构建器.bat",
    "升级安装v7并保留存档.bat",
    "docs/GUI_BUILDER.md",
    "docs/STARTUP_RUNTIME.md",
    "tools/tests/test_touch_hud_and_slots.py",
    "tools/tests/test_save_directory_and_diagnostics.py",
]


def fail(message: str) -> None:
    print("FAIL:", message)
    raise SystemExit(1)


for rel in REQUIRED:
    if not (ROOT / rel).is_file():
        fail(f"missing {rel}")

for path in (ROOT / "tools").glob("*.py"):
    ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

jni = (CPP_DIR / "backend/rfb_jni.c").read_text(encoding="utf-8")
java_native = (JAVA_DIR / "RfbNative.java").read_text(encoding="utf-8")
for name in re.findall(r"Java_org_roguelikefansband_android_RfbNative_(native\w+)", jni):
    if name not in java_native:
        fail(f"JNI function {name} has no Java declaration")

cmake = (CPP_DIR / "CMakeLists.txt").read_text(encoding="utf-8")
if "loader" in cmake.lower() or "angbandroid" in cmake.lower():
    fail("dedicated CMake unexpectedly references loader/angbandroid")
for token in (
    "CURL_USE_MBEDTLS ON",
    "USE_CURL=1",
    "NETWORK_ENABLED=1",
    "vendor/third_party",
    "mbedtls",
):
    if token not in cmake:
        fail(f"native network stack is missing CMake token: {token}")


compat_h = (CPP_DIR / "config/rfb_android_compat.h").read_text(encoding="utf-8")
for token in ("#include <unistd.h>", "#define _read read", "#define _write write", "#define O_BINARY 0"):
    if token not in compat_h:
        fail(f"Android POSIX compatibility shim missing token: {token}")
for token in (
    "BUILD_EXAMPLES OFF",
    "rfb_android_compat.h",
    "-fno-trigraphs",
    "-Werror=cast-function-type-mismatch",
    "-Werror=trigraphs",
):
    if token not in cmake:
        fail(f"native CMake portability setting missing token: {token}")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if "android.permission.INTERNET" not in manifest:
    fail("AndroidManifest.xml is missing INTERNET permission")

autoconf = (CPP_DIR / "config/autoconf.h").read_text(encoding="utf-8")
if "#define USE_CURL 1" not in autoconf or "#define NETWORK_ENABLED 1" not in autoconf:
    fail("autoconf.h does not enable RFB curl/network code")

backend_h = (CPP_DIR / "backend/rfb_android_backend.h").read_text(encoding="utf-8")
backend_c = (CPP_DIR / "backend/main-android-rfb.c").read_text(encoding="utf-8")
term_java = (JAVA_DIR / "RfbTermView.java").read_text(encoding="utf-8")
for token in (
    "tileRow", "tileCol", "terrainRow", "terrainCol", "layoutVersion"
):
    if token not in backend_h:
        fail(f"frame protocol docs missing {token}")
if "FRAME_LAYOUT_VERSION 2" not in backend_c:
    fail("native frame layout version is not 2")
if "FRAME_LAYOUT_VERSION = 2" not in term_java:
    fail("Java frame layout version is not 2")
if "CELL_STRIDE = 11" not in term_java or "FRAME_CELL_INTS 11" not in backend_c:
    fail("native/Java frame cell stride mismatch")
for token in ("16x16.png", "8x8.png", "C_TERRAIN_ROW", "drawAtlasTile"):
    if token not in term_java:
        fail(f"tileset renderer missing token: {token}")
if "WIDE_TRAIL" not in term_java or "cellOffset(x + 1, y)" not in term_java:
    fail("wide-character drawing is not driven by RFB wide-trail cells")


main_java = (JAVA_DIR / "MainActivity.java").read_text(encoding="utf-8")
diagnostics_java = (JAVA_DIR / "StartupDiagnostics.java").read_text(encoding="utf-8")
crash_capture = (ROOT / "capture_android_crash_log.bat").read_text(encoding="utf-8")
for token in (
    "RfbNative.nativePrepare",
    "termView.startPolling()",
    "StartupDiagnostics.initialize",
    "StartupDiagnostics.recordThrowable",
    "showSaveManager",
    "showQuickKeyEditor",
    "showDisplaySettings",
    "GameKeyboardDialog",
    "CustomHudOverlay",
    "restartForSession",
    "PREF_FORCE_NEW_ONCE",
    "PressRepeater.bind",
):
    if token not in main_java:
        fail(f"startup runtime integration missing token: {token}")
if main_java.find("RfbNative.nativePrepare") > main_java.find("termView.startPolling()"):
    fail("frame polling starts before native framebuffer preparation")
for token in (
    "if (!g_cells)",
    "rfb_android_run_core",
    "android_quit_hook",
    "setjmp(g_quit_jmp)",
    "longjmp(g_quit_jmp, 1)",
):
    if token not in backend_c:
        fail(f"native startup/exit safety missing token: {token}")
if "rfb_android_run_core(argc, argv, (uint64_t)sessionToken)" not in jni:
    fail("JNI nativeStart bypasses Android core runner")
for token in (
    "nativeRequestStop", "nativeSessionToken", "destroyed.get()",
):
    if token not in main_java:
        fail(f"Activity lifecycle/core-session integration missing token: {token}")
keyboard_java = (JAVA_DIR / "GameKeyboardDialog.java").read_text(encoding="utf-8")
hud_java = (JAVA_DIR / "CustomHudOverlay.java").read_text(encoding="utf-8")
feedback_java = (JAVA_DIR / "TouchFeedbackButton.java").read_text(encoding="utf-8")
repeat_java = (JAVA_DIR / "PressRepeater.java").read_text(encoding="utf-8")
store_java = (JAVA_DIR / "VirtualKeyStore.java").read_text(encoding="utf-8")
if "extends Dialog" in keyboard_java or "host.addView(rootLayout)" not in keyboard_java:
    fail("big keyboard must be a non-modal in-activity overlay")
for token in ("addDirectionRow", "PressRepeater.bind", '"7", "8", "9"'):
    if token not in keyboard_java:
        fail(f"big keyboard eight-direction/repeat support missing token: {token}")
for token in (
    "onPositionChanged", "bindDrag", "spec.widthDp", "spec.heightDp",
    "bindPanelDrag", "bindPanelResize", "panel.dockRight", "onPanelChanged",
    "setGameAreaKeysHidden", "updateGameAreaKeyVisibility",
    "insideOperationStrip", "child.setEnabled(show)",
):
    if token not in hud_java:
        fail(f"free touch HUD missing token: {token}")
for token in (
    "MAX_KEYS = 64", "shape", "opacity", "repeat", "HudPanelSpec",
    "PREF_UNIFIED_MIGRATION", "dockRight",
):
    if token not in store_java:
        fail(f"persistent HUD customization missing token: {token}")
for token in (
    "panelSpacer", "rootLayout.addView(hudOverlay", "buildHudEditToolbar",
    "hudOverlay.setGameAreaKeysHidden(true)",
    "hudOverlay.setGameAreaKeysHidden(false)",
    "currentHudCoordinateDp", "maximumHudCoordinateDp",
    "normalizedHudCoordinate", "精确坐标（dp，屏幕左上角为 0,0）",
):
    if token not in main_java:
        fail(f"unified reserved HUD layout missing token: {token}")
for forbidden in ("hudCountView", "controlPanel", "hudOverlay.setVisibility(View.INVISIBLE)"):
    if forbidden in main_java:
        fail(f"obsolete fixed or persistent HUD UI remains: {forbidden}")
if "HapticFeedbackConstants.KEYBOARD_TAP" not in feedback_java:
    fail("touch feedback haptics are missing")
if "START_DELAY_MS" not in repeat_java or "INTERVAL_MS" not in repeat_java:
    fail("hold-to-repeat timing contract is missing")
for token in (
    "g_prepared_session_token", "g_running_session_token",
    "rfb_android_request_stop", "g_stop_session_token",
):
    if token not in backend_c:
        fail(f"native lifecycle/core-session safety missing token: {token}")
for token in (
    "rfb-startup.log", "setDefaultUncaughtExceptionHandler",
    "MAX_LOG_BYTES = 256L * 1024L", "clearAfterNormalExit",
    "rotateOversizedLogLocked(bytes.length)",
):
    if token not in diagnostics_java:
        fail(f"startup diagnostics missing token: {token}")
for token in ("run-as org.roguelikefansband.android", "rfb-startup.txt", "logcat -d"):
    if token not in crash_capture:
        fail(f"crash-log capture script missing token: {token}")

sound_java = (JAVA_DIR / "RfbAudioManager.java").read_text(encoding="utf-8")
for token in ("sound.cfg", "SoundPool", "nativeDrainSoundEvents", 'eventIds.put("quest", 32)'):
    if token not in sound_java:
        fail(f"sound backend missing token: {token}")
if "TERM_XTRA_SOUND" not in backend_c or "sound_enqueue" not in backend_c:
    fail("native TERM_XTRA_SOUND event queue is missing")

key_java = (JAVA_DIR / "RfbKeyEncoder.java").read_text(encoding="utf-8")
for token in (
    "TRIGGER_START = 0x1F",
    "TRIGGER_END = 0x0D",
    "KEYCODE_NUMPAD_0",
    "KEYCODE_F12",
    "KEYCODE_CAPS_LOCK",
    "event.isCtrlPressed()",
    "event.isShiftPressed()",
    "event.isAltPressed()",
):
    if token not in key_java:
        fail(f"scan-code macro compatibility layer missing token: {token}")

prep = (ROOT / "tools/prepare_source.py").read_text(encoding="utf-8")
if "patch_corny_format_security" not in prep:
    fail("source preparation is missing the corny.c format-security patch")
for token in (
    "patch_semantic_warning_fixes",
    "patch_cmd4_trigraph_source",
    "patch_wizard1_int_vector_comparator",
    "verify_known_warning_fixes",
):
    if token not in prep:
        fail(f"source preparation warning-audit pipeline missing token: {token}")
for token in (
    "CURL_VERSION",
    "MBEDTLS_VERSION",
    "CURL_SHA256",
    "MBEDTLS_SHA256",
    "RFB_ANDROID_CURL_CA_BUNDLE",
    "RFB_ANDROID_CURL_FORMAT_MACRO_GUARD",
    "CURLOPT_CAINFO",
    "resource_revision",
    "write_zip_file_deterministically",
    "cacert.pem",
    "resolve_source_revision",
    "releases/latest",
    "rfb_build.properties",
    "upstream-compatibility.json",
):
    if token not in prep:
        fail(f"source preparation network support missing token: {token}")



portable_gui = (ROOT / "tools/portable_gui.ps1").read_text(encoding="utf-16")
portable_bootstrap = (ROOT / "tools/portable_bootstrap.ps1").read_text(encoding="utf-16")
portable_build = (ROOT / "tools/portable_build.ps1").read_text(encoding="utf-16")
builder = (ROOT / "tools/build.py").read_text(encoding="utf-8")
app_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
migration = (ROOT / "升级安装v7并保留存档.bat").read_text(encoding="utf-8")
for token, target in (
    ("RoguelikeFansBand Android 便携构建器", portable_gui),
    ("portable_workspace", portable_gui + portable_bootstrap + portable_build + builder),
    ("GRADLE_USER_HOME", portable_bootstrap + portable_build + builder),
    ("ANDROID_USER_HOME", portable_bootstrap + portable_build + builder),
    ("$PythonVersion-embed-amd64.zip", portable_bootstrap),
    ("api.adoptium.net", portable_bootstrap),
    ("commandlinetools-win-14742923_latest.zip", portable_bootstrap),
    ("subst.exe", portable_gui + portable_build),
    ("RFB_PROJECT_ROOT", portable_build + builder),
    ("@@RFB_STAGE@@", builder),
    ("validate_apk", builder),
    ("REQUIRED_ABIS", builder),
    ("universal-debug", builder),
):
    if token not in target:
        fail(f"portable builder integration missing token: {token}")

for token in ("rfb-local-debug.keystore", "signingConfig signingConfigs.localDebug"):
    if token not in app_gradle:
        fail(f"stable local Debug signing is missing token: {token}")
for token in ("exec-out run-as", "rfb-writable-data.tar", "adb", "*-v*-universal-debug.apk"):
    if token not in migration:
        fail(f"v6 save migration script is missing token: {token}")



for name, text in (("portable_bootstrap.ps1", portable_bootstrap), ("portable_build.ps1", portable_build), ("build.py", builder)):
    if 'ANDROID_SDK_HOME =' in text or '"ANDROID_SDK_HOME":' in text:
        fail(f"legacy ANDROID_SDK_HOME must not be assigned in {name}")

font_suffixes = {".ttf", ".otf", ".ttc", ".fon"}
asset_zip = ROOT / "app/src/main/assets/rfb-data.zip"
if asset_zip.is_file():
    with zipfile.ZipFile(asset_zip) as zf:
        bundled_fonts = [name for name in zf.namelist() if Path(name).suffix.lower() in font_suffixes]
    if bundled_fonts:
        fail("font binaries must not be bundled in rfb-data.zip: " + ", ".join(bundled_fonts))

print("静态完整性检查通过：便携 GUI 构建器、隔离式工具链、图块、声音、HTTPS/libcurl、扫描码宏触发器均已接入专用工程。")
