/*
 * JNI für de.ayutv.player.dna.engine.NativeVideo
 */
#include <jni.h>
#include <stdint.h>

#include "dnavideo.h"

#define FN(name) Java_de_ayutv_player_dna_engine_NativeVideo_##name

JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jclass cls, jstring codec, jbyteArray extra, jint maxW, jint maxH, jint threads) {
    (void)cls;
    const char *name = (*env)->GetStringUTFChars(env, codec, NULL);
    if (!name) return 0;
    jbyte *extra_bytes = NULL;
    jsize extra_len = 0;
    if (extra) {
        extra_len = (*env)->GetArrayLength(env, extra);
        extra_bytes = (*env)->GetByteArrayElements(env, extra, NULL);
    }
    DnaVideo *v = dna_video_open(name, (const uint8_t *)extra_bytes, extra_len, maxW, maxH, threads);
    if (extra_bytes) (*env)->ReleaseByteArrayElements(env, extra, extra_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, codec, name);
    return (jlong)(intptr_t)v;
}

JNIEXPORT jint JNICALL FN(nativeSend)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data, jint len, jlong pts) {
    (void)cls;
    DnaVideo *v = (DnaVideo *)(intptr_t)handle;
    if (!v || !data || len <= 0) return DNA_VIDEO_SEND_ERROR;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return DNA_VIDEO_SEND_ERROR;
    int ret = dna_video_send(v, (const uint8_t *)bytes, len, pts);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return ret;
}

JNIEXPORT jlong JNICALL FN(nativeReceive)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    int64_t pts = 0;
    if (!dna_video_receive((DnaVideo *)(intptr_t)handle, &pts)) return (jlong)INT64_MIN;
    return (jlong)pts;
}

JNIEXPORT jint JNICALL FN(nativeConvert)(JNIEnv *env, jclass cls, jlong handle, jobject y, jobject u, jobject v, jintArray info) {
    (void)cls;
    DnaVideo *dv = (DnaVideo *)(intptr_t)handle;
    if (!dv || !info) return -1;
    uint8_t *py = y ? (*env)->GetDirectBufferAddress(env, y) : NULL;
    uint8_t *pu = u ? (*env)->GetDirectBufferAddress(env, u) : NULL;
    uint8_t *pv = v ? (*env)->GetDirectBufferAddress(env, v) : NULL;
    int y_cap = y ? (int)(*env)->GetDirectBufferCapacity(env, y) : 0;
    int u_cap = u ? (int)(*env)->GetDirectBufferCapacity(env, u) : 0;
    int v_cap = v ? (int)(*env)->GetDirectBufferCapacity(env, v) : 0;
    int32_t out[5] = {0};
    int ret = dna_video_convert(dv, py, y_cap, pu, pv, u_cap < v_cap ? u_cap : v_cap, out);
    (*env)->SetIntArrayRegion(env, info, 0, 5, (const jint *)out);
    return ret;
}

JNIEXPORT void JNICALL FN(nativeDrop)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    dna_video_drop((DnaVideo *)(intptr_t)handle);
}

JNIEXPORT void JNICALL FN(nativeHurry)(JNIEnv *env, jclass cls, jlong handle, jint level) {
    (void)env; (void)cls;
    dna_video_hurry((DnaVideo *)(intptr_t)handle, level);
}

JNIEXPORT void JNICALL FN(nativeFlush)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    dna_video_flush((DnaVideo *)(intptr_t)handle);
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    dna_video_close((DnaVideo *)(intptr_t)handle);
}
