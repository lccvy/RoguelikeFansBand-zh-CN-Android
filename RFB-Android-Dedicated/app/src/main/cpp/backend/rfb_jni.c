#include <jni.h>
#include <android/log.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rfb_android_backend.h"

#define LOG_TAG "RFB-JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

static void set_arg(char *dst, size_t cap, const char *prefix, const char *root, const char *suffix)
{
    int n = snprintf(dst, cap, "%s%s%s", prefix, root, suffix);
    if (n < 0 || (size_t)n >= cap) {
        LOGE("Path argument truncated: prefix=%s suffix=%s", prefix, suffix);
        dst[cap - 1] = '\0';
    }
}

static int configure_ca_bundle(const char *root)
{
    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), "%s/lib/xtra/curl/cacert.pem", root);
    if (n < 0 || (size_t)n >= sizeof(path)) return 0;
    if (setenv("RFB_CURL_CA_BUNDLE", path, 1) != 0) {
        LOGE("Unable to set RFB_CURL_CA_BUNDLE");
        return 0;
    }
    LOGI("libcurl CA bundle: %s", path);
    return 1;
}

static void throw_java(JNIEnv *env, const char *class_name, const char *message)
{
    jclass error_class = (*env)->FindClass(env, class_name);
    if (error_class) (*env)->ThrowNew(env, error_class, message);
}

JNIEXPORT jlong JNICALL
Java_org_roguelikefansband_android_RfbNative_nativePrepare(
        JNIEnv *env, jclass clazz, jint cols, jint rows, jint graphicsMode,
        jboolean soundEnabled, jboolean bigtileEnabled)
{
    uint64_t session_token = 0;
    int result;
    (void)clazz;
    result = rfb_android_prepare_session((int)cols, (int)rows, (int)graphicsMode,
                                         soundEnabled == JNI_TRUE,
                                         bigtileEnabled == JNI_TRUE,
                                         &session_token);
    if (result == 0) {
        throw_java(env, "java/lang/IllegalStateException",
                   "RFB core is already running in this process");
        return 0;
    }
    if (result < 0) {
        throw_java(env, "java/lang/OutOfMemoryError",
                   "Unable to allocate the RFB terminal framebuffer");
        return 0;
    }
    return (jlong)session_token;
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeStart(
        JNIEnv *env, jclass clazz, jstring rootPath, jstring playerName,
        jboolean forceNewGame, jlong sessionToken, jint cols, jint rows, jint graphicsMode,
        jboolean soundEnabled, jboolean bigtileEnabled)
{
    (void)clazz;
    const char *root = NULL;
    const char *player = NULL;
    int result;

    enum { ARG_COUNT_MAX = 20, ARG_LEN = PATH_MAX + 64 };
    char args[ARG_COUNT_MAX][ARG_LEN];
    char *argv[ARG_COUNT_MAX];
    int argc = 0;

    if (!rootPath || !playerName) return -2;
    root = (*env)->GetStringUTFChars(env, rootPath, NULL);
    player = (*env)->GetStringUTFChars(env, playerName, NULL);
    if (!root || !player) {
        if (root) (*env)->ReleaseStringUTFChars(env, rootPath, root);
        if (player) (*env)->ReleaseStringUTFChars(env, playerName, player);
        return -3;
    }

#define ADD_LITERAL(s) do { \
    snprintf(args[argc], ARG_LEN, "%s", (s)); \
    argv[argc] = args[argc]; \
    argc++; \
} while (0)
#define ADD_PATH(prefix, suffix) do { \
    set_arg(args[argc], ARG_LEN, (prefix), root, (suffix)); \
    argv[argc] = args[argc]; \
    argc++; \
} while (0)

    ADD_LITERAL("rfb-android");
    if (forceNewGame == JNI_TRUE) ADD_LITERAL("-n");
    snprintf(args[argc], ARG_LEN, "-u%s", (player && *player) ? player : "PLAYER");
    argv[argc] = args[argc];
    argc++;

    ADD_PATH("-dedit=",   "/lib/edit");
    ADD_PATH("-dpref=",   "/lib/pref");
    ADD_PATH("-dfile=",   "/lib/file");
    ADD_PATH("-dhelp=",   "/lib/help");
    ADD_PATH("-dinfo=",   "/lib/info");
    ADD_PATH("-dxtra=",   "/lib/xtra");
    ADD_PATH("-duser=",   "/lib/user");
    ADD_PATH("-dsave=",   "/lib/save");
    ADD_PATH("-dapex=",   "/lib/apex");
    ADD_PATH("-dbone=",   "/lib/bone");
    ADD_PATH("-ddata=",   "/lib/data");
    ADD_PATH("-dscript=", "/lib/script");
    ADD_LITERAL("-mandroid");
    argv[argc] = NULL;

    configure_ca_bundle(root);
    LOGI("Starting RFB core at root=%s player=%s new=%d term=%dx%d graphics=%d sound=%d bigtile=%d",
         root, (player && *player) ? player : "PLAYER", forceNewGame == JNI_TRUE,
         (int)cols, (int)rows, (int)graphicsMode,
         soundEnabled == JNI_TRUE, bigtileEnabled == JNI_TRUE);

    (*env)->ReleaseStringUTFChars(env, rootPath, root);
    (*env)->ReleaseStringUTFChars(env, playerName, player);

    result = rfb_android_run_core(argc, argv, (uint64_t)sessionToken);
    return (jint)result;
}

JNIEXPORT void JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeRequestStop(
        JNIEnv *env, jclass clazz, jlong sessionToken)
{
    (void)env;
    (void)clazz;
    if (sessionToken > 0) rfb_android_request_stop((uint64_t)sessionToken);
}

JNIEXPORT jstring JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeLastExitMessage(
        JNIEnv *env, jclass clazz)
{
    const char *message;
    (void)clazz;
    message = rfb_android_last_exit_message();
    if (!message || !*message) return NULL;
    return (*env)->NewStringUTF(env, message);
}

JNIEXPORT void JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeSendBytes(
        JNIEnv *env, jclass clazz, jbyteArray bytes)
{
    (void)clazz;
    if (!bytes) return;
    jsize len = (*env)->GetArrayLength(env, bytes);
    if (len <= 0) return;
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!data) return;
    rfb_android_push_input((const uint8_t *)data, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeRequestGraphicsMode(
        JNIEnv *env, jclass clazz, jint mode)
{
    (void)env;
    (void)clazz;
    rfb_android_request_graphics_mode((int)mode);
}

JNIEXPORT void JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeRequestSoundEnabled(
        JNIEnv *env, jclass clazz, jboolean enabled)
{
    (void)env;
    (void)clazz;
    rfb_android_request_sound_enabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeRequestBigtileEnabled(
        JNIEnv *env, jclass clazz, jboolean enabled)
{
    (void)env;
    (void)clazz;
    rfb_android_request_bigtile_enabled(enabled == JNI_TRUE);
}

JNIEXPORT jlong JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeGeneration(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (jlong)rfb_android_generation();
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeFrameIntCount(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (jint)rfb_android_frame_int_count();
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeCopyFrame(
        JNIEnv *env, jclass clazz, jintArray frame)
{
    (void)clazz;
    if (!frame) return -1;
    jsize count = (*env)->GetArrayLength(env, frame);
    jint *dst = (*env)->GetIntArrayElements(env, frame, NULL);
    int result;
    if (!dst) return -2;
    result = rfb_android_copy_frame((int32_t *)dst, (int)count);
    (*env)->ReleaseIntArrayElements(env, frame, dst, 0);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeCols(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (jint)rfb_android_cols();
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeRows(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (jint)rfb_android_rows();
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeDrainSoundEvents(
        JNIEnv *env, jclass clazz, jintArray events)
{
    (void)clazz;
    if (!events) return 0;
    jsize capacity = (*env)->GetArrayLength(env, events);
    if (capacity <= 0) return 0;
    jint *dst = (*env)->GetIntArrayElements(env, events, NULL);
    int count;
    if (!dst) return 0;
    count = rfb_android_drain_sound_events((int32_t *)dst, (int)capacity);
    (*env)->ReleaseIntArrayElements(env, events, dst, 0);
    return (jint)count;
}

JNIEXPORT jint JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeSoundEventCount(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (jint)rfb_android_sound_event_count();
}

JNIEXPORT jstring JNICALL
Java_org_roguelikefansband_android_RfbNative_nativeSoundEventName(
        JNIEnv *env, jclass clazz, jint eventId)
{
    const char *name;
    (void)clazz;
    name = rfb_android_sound_event_name((int)eventId);
    if (!name) return NULL;
    return (*env)->NewStringUTF(env, name);
}
