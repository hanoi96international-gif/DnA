/*
 * JNI für de.ayutv.data.net.Remux: Lesen über ein Kotlin-Objekt (HTTP mit Range über den Proxy der App), Schreiben in
 * ein Kotlin-Objekt (Verbindung zum Fernseher). Ein Handle wird immer nur von einem Thread zugleich benutzt.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "dnaremux.h"

#define CHUNK (256 * 1024)

typedef struct Ctx {
    JavaVM *vm;
    jobject source;       // Remux.Source
    jmethodID read;       // int read(byte[] buf, int len)
    jmethodID seek;       // long seek(long offset, int whence)
    jbyteArray buffer;    // für read und write
    DnaRemux *remux;
} Ctx;

typedef struct SinkCtx {
    JNIEnv *env;
    jobject sink;         // Remux.Sink
    jmethodID write;      // boolean write(byte[] buf, int len)
    jmethodID cue;        // void cue(int stream, double start, double end, byte[] text)
    jbyteArray buffer;
} SinkCtx;

static JNIEnv *env_of(Ctx *c) {
    JNIEnv *env = NULL;
    (*c->vm)->GetEnv(c->vm, (void **)&env, JNI_VERSION_1_6);
    return env;
}

static int j_read(void *opaque, uint8_t *buf, int len) {
    Ctx *c = opaque;
    JNIEnv *env = env_of(c);
    if (!env) return -1;
    if (len > CHUNK) len = CHUNK;
    jint n = (*env)->CallIntMethod(env, c->source, c->read, c->buffer, len);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -1;
    }
    if (n > 0) (*env)->GetByteArrayRegion(env, c->buffer, 0, n, (jbyte *)buf);
    return n;
}

static int64_t j_seek(void *opaque, int64_t offset, int whence) {
    Ctx *c = opaque;
    JNIEnv *env = env_of(c);
    if (!env) return -1;
    jlong r = (*env)->CallLongMethod(env, c->source, c->seek, (jlong)offset, (jint)whence);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -1;
    }
    return r;
}

static int j_write(void *opaque, const uint8_t *buf, int len) {
    SinkCtx *s = opaque;
    JNIEnv *env = s->env;
    while (len > 0) {
        int n = len > CHUNK ? CHUNK : len;
        (*env)->SetByteArrayRegion(env, s->buffer, 0, n, (const jbyte *)buf);
        jboolean ok = (*env)->CallBooleanMethod(env, s->sink, s->write, s->buffer, n);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            return -1;
        }
        if (!ok) return -1;
        buf += n;
        len -= n;
    }
    return 0;
}

static void j_cue(void *opaque, int stream, double start, double end, const uint8_t *text, int len) {
    SinkCtx *s = opaque;
    JNIEnv *env = s->env;
    if (!s->cue || len <= 0 || len > 65536) return;
    jbyteArray bytes = (*env)->NewByteArray(env, len);
    if (!bytes) {
        (*env)->ExceptionClear(env);
        return;
    }
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte *)text);
    (*env)->CallVoidMethod(env, s->sink, s->cue, (jint)stream, (jdouble)start, (jdouble)end, bytes);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, bytes);
}

JNIEXPORT jlong JNICALL Java_de_ayutv_data_net_Remux_nOpen(JNIEnv *env, jclass cls, jobject source, jobjectArray errOut) {
    Ctx *c = calloc(1, sizeof(Ctx));
    (*env)->GetJavaVM(env, &c->vm);
    c->source = (*env)->NewGlobalRef(env, source);
    jclass sc = (*env)->GetObjectClass(env, source);
    c->read = (*env)->GetMethodID(env, sc, "read", "([BI)I");
    c->seek = (*env)->GetMethodID(env, sc, "seek", "(JI)J");
    jbyteArray buffer = (*env)->NewByteArray(env, CHUNK);
    c->buffer = (*env)->NewGlobalRef(env, buffer);
    (*env)->DeleteLocalRef(env, buffer);
    char err[256] = "";
    DnaIo io = {c, j_read, j_seek};
    c->remux = dna_open(io, err, sizeof(err));
    if (!c->remux) {
        if (errOut && (*env)->GetArrayLength(env, errOut) > 0) {
            jstring text = (*env)->NewStringUTF(env, err);
            (*env)->SetObjectArrayElement(env, errOut, 0, text);
        }
        (*env)->DeleteGlobalRef(env, c->source);
        (*env)->DeleteGlobalRef(env, c->buffer);
        free(c);
        return 0;
    }
    return (jlong)(intptr_t)c;
}

JNIEXPORT void JNICALL Java_de_ayutv_data_net_Remux_nClose(JNIEnv *env, jclass cls, jlong handle) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    if (!c) return;
    dna_close(c->remux);
    (*env)->DeleteGlobalRef(env, c->source);
    (*env)->DeleteGlobalRef(env, c->buffer);
    free(c);
}

JNIEXPORT jdouble JNICALL Java_de_ayutv_data_net_Remux_nDuration(JNIEnv *env, jclass cls, jlong handle) {
    return dna_duration(((Ctx *)(intptr_t)handle)->remux);
}

JNIEXPORT jstring JNICALL Java_de_ayutv_data_net_Remux_nDescribe(JNIEnv *env, jclass cls, jlong handle) {
    char text[8192];
    dna_describe(((Ctx *)(intptr_t)handle)->remux, text, sizeof(text));
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jdoubleArray JNICALL Java_de_ayutv_data_net_Remux_nBoundaries(JNIEnv *env, jclass cls, jlong handle, jdouble target) {
    int cap = 20000;
    double *b = malloc(sizeof(double) * cap);
    int n = dna_boundaries(((Ctx *)(intptr_t)handle)->remux, target, b, cap);
    jdoubleArray out = (*env)->NewDoubleArray(env, n);
    (*env)->SetDoubleArrayRegion(env, out, 0, n, b);
    free(b);
    return out;
}

JNIEXPORT jboolean JNICALL Java_de_ayutv_data_net_Remux_nNeedsTranscode(JNIEnv *env, jclass cls, jlong handle, jint audio) {
    return dna_needs_transcode(((Ctx *)(intptr_t)handle)->remux, audio) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_de_ayutv_data_net_Remux_nSegment(JNIEnv *env, jclass cls, jlong handle, jdouble start, jdouble end,
                                                               jint audio, jint transcode, jboolean cues, jobject sink) {
    Ctx *c = (Ctx *)(intptr_t)handle;
    SinkCtx s = {env, sink, NULL, NULL, NULL};
    jclass kc = (*env)->GetObjectClass(env, sink);
    s.write = (*env)->GetMethodID(env, kc, "write", "([BI)Z");
    if (cues) {
        s.cue = (*env)->GetMethodID(env, kc, "cue", "(IDD[B)V");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            s.cue = NULL;
        }
    }
    s.buffer = (*env)->NewByteArray(env, CHUNK);
    char err[256] = "";
    int ret = dna_segment_cues(c->remux, start, end, audio, transcode, j_write, s.cue ? j_cue : NULL, &s, err, sizeof(err));
    (*env)->DeleteLocalRef(env, s.buffer);
    if (ret < 0) return (*env)->NewStringUTF(env, err[0] ? err : "Fehler");
    return NULL;
}
