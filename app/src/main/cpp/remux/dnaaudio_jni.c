/*
 * JNI für de.ayutv.player.dna.engine.NativeAudio (Ton-Decoder der DNA Engine, siehe dnaaudio.c).
 */
#include <jni.h>
#include <stdint.h>

#include "dnaaudio.h"

// ------------------------------------------------------------------ JNI (de.ayutv.player.dna.engine.NativeAudio)

JNIEXPORT jint JNICALL Java_de_ayutv_player_dna_engine_NativeAudio_nativeVersion(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return 1;
}

JNIEXPORT jlong JNICALL Java_de_ayutv_player_dna_engine_NativeAudio_nativeOpen(JNIEnv *env, jclass cls, jstring codec, jint rate,
                                                                            jint channels, jbyteArray extra) {
    (void)cls;
    const char *name = (*env)->GetStringUTFChars(env, codec, NULL);
    if (!name) return 0;
    jbyte *extra_bytes = NULL;
    jsize extra_len = 0;
    if (extra) {
        extra_len = (*env)->GetArrayLength(env, extra);
        extra_bytes = (*env)->GetByteArrayElements(env, extra, NULL);
    }
    DnaAudio *a = dna_audio_open(name, rate, channels, (const uint8_t *)extra_bytes, extra_len);
    if (extra_bytes) (*env)->ReleaseByteArrayElements(env, extra, extra_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, codec, name);
    return (jlong)(intptr_t)a;
}

JNIEXPORT jbyteArray JNICALL Java_de_ayutv_player_dna_engine_NativeAudio_nativeDecode(JNIEnv *env, jclass cls, jlong handle,
                                                                                    jbyteArray data, jint len) {
    (void)cls;
    DnaAudio *a = (DnaAudio *)(intptr_t)handle;
    if (!a || !data || len <= 0) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return NULL;
    int n = dna_audio_decode(a, (const uint8_t *)bytes, len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (n <= 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)dna_audio_output(a));
    return out;
}

JNIEXPORT jint JNICALL Java_de_ayutv_player_dna_engine_NativeAudio_nativeRate(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    return dna_audio_rate((DnaAudio *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL Java_de_ayutv_player_dna_engine_NativeAudio_nativeChannels(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    return dna_audio_channels((DnaAudio *)(intptr_t)handle);
}

JNIEXPORT void JNICALL Java_de_ayutv_player_dna_engine_NativeAudio_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    dna_audio_close((DnaAudio *)(intptr_t)handle);
}
