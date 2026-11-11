/*
 * Eigener Software-Video-Decoder (FFmpeg), siehe dnavideo.h.
 */
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>

#include "dnavideo.h"

struct DnaVideo {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
    int held;
    struct SwsContext *sws;
    int max_w, max_h;
    int src_w, src_h, src_fmt;
    int out_w, out_h;
};

DnaVideo *dna_video_open(const char *codec_name, const uint8_t *extra, int extra_len, int max_w, int max_h, int threads) {
    const AVCodec *codec = avcodec_find_decoder_by_name(codec_name);
    if (!codec) return NULL;
    DnaVideo *v = calloc(1, sizeof(DnaVideo));
    if (!v) return NULL;
    v->max_w = max_w > 0 ? max_w : 1920;
    v->max_h = max_h > 0 ? max_h : 1080;
    v->ctx = avcodec_alloc_context3(codec);
    v->pkt = av_packet_alloc();
    v->frame = av_frame_alloc();
    if (!v->ctx || !v->pkt || !v->frame) goto fail;
    if (extra && extra_len > 0) {
        v->ctx->extradata = av_mallocz(extra_len + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!v->ctx->extradata) goto fail;
        memcpy(v->ctx->extradata, extra, extra_len);
        v->ctx->extradata_size = extra_len;
    }
    // Alle Kerne: Bild-Threads (mehrere Bilder gleichzeitig) und Scheiben-Threads.
    v->ctx->thread_count = threads;
    v->ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    v->ctx->pkt_timebase = (AVRational){1, 1000000};
    if (avcodec_open2(v->ctx, codec, NULL) < 0) goto fail;
    return v;
fail:
    dna_video_close(v);
    return NULL;
}

int dna_video_send(DnaVideo *v, const uint8_t *data, int len, int64_t pts) {
    if (!v || !data || len <= 0) return DNA_VIDEO_SEND_ERROR;
    if (av_new_packet(v->pkt, len) < 0) return DNA_VIDEO_SEND_ERROR;
    memcpy(v->pkt->data, data, len);
    v->pkt->pts = pts;
    v->pkt->dts = AV_NOPTS_VALUE;
    int ret = avcodec_send_packet(v->ctx, v->pkt);
    av_packet_unref(v->pkt);
    if (ret == AVERROR(EAGAIN)) return DNA_VIDEO_SEND_FULL;
    // Einzelne kaputte Einheiten auslassen (wie VLC); nur ein echter Ausfall bricht ab.
    if (ret < 0 && ret != AVERROR_INVALIDDATA) return DNA_VIDEO_SEND_ERROR;
    return DNA_VIDEO_SEND_OK;
}

int dna_video_receive(DnaVideo *v, int64_t *pts) {
    if (!v) return 0;
    if (!v->held) {
        int ret = avcodec_receive_frame(v->ctx, v->frame);
        if (ret < 0) return 0;
        v->held = 1;
    }
    // Zeitstempel der Einheit, aus der das Bild stammt (nicht geschätzt: die Uhr der Engine braucht die echten).
    int64_t t = v->frame->pts;
    if (t == AV_NOPTS_VALUE) t = v->frame->best_effort_timestamp;
    if (pts) *pts = t;
    return 1;
}

void dna_video_drop(DnaVideo *v) {
    if (!v || !v->held) return;
    av_frame_unref(v->frame);
    v->held = 0;
}

static void fit(DnaVideo *v, int w, int h, int *ow, int *oh) {
    int tw = w, th = h;
    if (tw > v->max_w || th > v->max_h) {
        // Seitenverhältnis halten.
        if ((int64_t)w * v->max_h > (int64_t)h * v->max_w) {
            tw = v->max_w;
            th = (int)((int64_t)h * v->max_w / w);
        } else {
            th = v->max_h;
            tw = (int)((int64_t)w * v->max_h / h);
        }
    }
    *ow = tw & ~1;
    *oh = th & ~1;
    if (*ow < 2) *ow = 2;
    if (*oh < 2) *oh = 2;
}

/*
 * Schneller Weg für den Normalfall (4K → Full HD, genau halbe Größe): je 2×2 Bildpunkte mitteln und dabei gleich auf
 * 8 Bit bringen. Eine einfache Schleife, die der Compiler vektorisiert – ein Mehrfaches schneller als der allgemeine
 * Weg über swscale, der bei 4K 10 Bit sonst einen guten Teil der Zeit je Bild kostet.
 */
static void halve_plane16(const uint8_t *src, int src_stride, uint8_t *dst, int ow, int oh) {
    for (int r = 0; r < oh; r++) {
        const uint16_t *s0 = (const uint16_t *)(src + (size_t)(2 * r) * src_stride);
        const uint16_t *s1 = (const uint16_t *)(src + (size_t)(2 * r + 1) * src_stride);
        uint8_t *d = dst + (size_t)r * ow;
        for (int x = 0; x < ow; x++) {
            unsigned sum = (unsigned)s0[2 * x] + s0[2 * x + 1] + s1[2 * x] + s1[2 * x + 1];
            unsigned val = (sum + 8) >> 4; // Mittel (÷4) und 10 → 8 Bit (÷4)
            d[x] = (uint8_t)(val > 255 ? 255 : val);
        }
    }
}

static void halve_plane8(const uint8_t *src, int src_stride, uint8_t *dst, int ow, int oh) {
    for (int r = 0; r < oh; r++) {
        const uint8_t *s0 = src + (size_t)(2 * r) * src_stride;
        const uint8_t *s1 = s0 + src_stride;
        uint8_t *d = dst + (size_t)r * ow;
        for (int x = 0; x < ow; x++) d[x] = (uint8_t)((s0[2 * x] + s0[2 * x + 1] + s1[2 * x] + s1[2 * x + 1] + 2) >> 2);
    }
}

static int halve_fast(AVFrame *f, int ow, int oh, uint8_t *y, uint8_t *u, uint8_t *vv) {
    if (f->width / 2 != ow || f->height / 2 != oh || (ow & 1) || (oh & 1)) return 0;
    if (f->format == AV_PIX_FMT_YUV420P10LE) {
        halve_plane16(f->data[0], f->linesize[0], y, ow, oh);
        halve_plane16(f->data[1], f->linesize[1], u, ow / 2, oh / 2);
        halve_plane16(f->data[2], f->linesize[2], vv, ow / 2, oh / 2);
        return 1;
    }
    if (f->format == AV_PIX_FMT_YUV420P) {
        halve_plane8(f->data[0], f->linesize[0], y, ow, oh);
        halve_plane8(f->data[1], f->linesize[1], u, ow / 2, oh / 2);
        halve_plane8(f->data[2], f->linesize[2], vv, ow / 2, oh / 2);
        return 1;
    }
    return 0;
}

int dna_video_convert(DnaVideo *v, uint8_t *y, int y_cap, uint8_t *u, uint8_t *vv, int uv_cap, int32_t *info) {
    if (!v || !v->held || !info) return -1;
    AVFrame *f = v->frame;
    int w = f->width, h = f->height;
    if (w <= 0 || h <= 0) {
        dna_video_drop(v);
        return -1;
    }
    int ow, oh;
    fit(v, w, h, &ow, &oh);
    info[0] = ow;
    info[1] = oh;
    info[2] = f->colorspace;
    info[3] = f->color_trc;
    info[4] = f->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    int cw = ow / 2, ch = oh / 2;
    if (!y || !u || !vv || y_cap < ow * oh || uv_cap < cw * ch) return 0;
    if (halve_fast(f, ow, oh, y, u, vv)) {
        dna_video_drop(v);
        v->out_w = ow;
        v->out_h = oh;
        return 1;
    }
    if (!v->sws || v->src_w != w || v->src_h != h || v->src_fmt != f->format || v->out_w != ow || v->out_h != oh) {
        // Verkleinern: schnelles bilineares Verfahren reicht (die GPU vergrößert ohnehin aufs Display).
        v->sws = sws_getCachedContext(v->sws, w, h, f->format, ow, oh, AV_PIX_FMT_YUV420P,
                                      (ow == w && oh == h) ? SWS_POINT : SWS_FAST_BILINEAR, NULL, NULL, NULL);
        v->src_w = w;
        v->src_h = h;
        v->src_fmt = f->format;
        v->out_w = ow;
        v->out_h = oh;
    }
    if (!v->sws) {
        dna_video_drop(v);
        return -1;
    }
    uint8_t *dst[4] = {y, u, vv, NULL};
    int stride[4] = {ow, cw, cw, 0};
    sws_scale(v->sws, (const uint8_t *const *)f->data, f->linesize, 0, h, dst, stride);
    dna_video_drop(v);
    return 1;
}

/* ---------------------------------------------------------------- HDR: 10 Bit als Halbfließkomma (GPU-Textur R16F) */

static uint16_t half_of[1024];
static int half_ready = 0;

/* float (0..1) → IEEE 754 binary16 (genug für 10-Bit-Werte, ohne Sonderfälle). */
static uint16_t to_half(float f) {
    if (f <= 0.0f) return 0;
    union { float f; uint32_t u; } in = {f};
    uint32_t sign = (in.u >> 16) & 0x8000;
    int32_t exp = (int32_t)((in.u >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = in.u & 0x7FFFFF;
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;
        mant |= 0x800000;
        uint32_t shift = (uint32_t)(14 - exp);
        uint32_t value = mant >> shift;
        if ((mant >> (shift - 1)) & 1) value++;
        return (uint16_t)(sign | value);
    }
    if (exp >= 31) return (uint16_t)(sign | 0x7BFF);
    uint32_t value = sign | ((uint32_t)exp << 10) | (mant >> 13);
    if (mant & 0x1000) value++;
    return (uint16_t)value;
}

static void init_half(void) {
    if (half_ready) return;
    for (int i = 0; i < 1024; i++) half_of[i] = to_half((float)i / 1023.0f);
    half_ready = 1;
}

static void halve_plane_half(const uint8_t *src, int src_stride, uint16_t *dst, int ow, int oh) {
    for (int r = 0; r < oh; r++) {
        const uint16_t *s0 = (const uint16_t *)(src + (size_t)(2 * r) * src_stride);
        const uint16_t *s1 = (const uint16_t *)(src + (size_t)(2 * r + 1) * src_stride);
        uint16_t *d = dst + (size_t)r * ow;
        for (int x = 0; x < ow; x++) {
            unsigned val = ((unsigned)s0[2 * x] + s0[2 * x + 1] + s1[2 * x] + s1[2 * x + 1] + 2) >> 2;
            d[x] = half_of[val > 1023 ? 1023 : val];
        }
    }
}

static void copy_plane_half(const uint8_t *src, int src_stride, uint16_t *dst, int ow, int oh) {
    for (int r = 0; r < oh; r++) {
        const uint16_t *s = (const uint16_t *)(src + (size_t)r * src_stride);
        uint16_t *d = dst + (size_t)r * ow;
        for (int x = 0; x < ow; x++) {
            unsigned val = s[x];
            d[x] = half_of[val > 1023 ? 1023 : val];
        }
    }
}

int dna_video_held_format(DnaVideo *v, int32_t *info) {
    if (!v || !v->held || !info) return 0;
    AVFrame *f = v->frame;
    info[0] = f->format == AV_PIX_FMT_YUV420P10LE ? 10 : 8;
    info[1] = f->colorspace;
    info[2] = f->color_trc;
    return 1;
}

int dna_video_convert_half(DnaVideo *v, uint8_t *y, int y_cap, uint8_t *u, uint8_t *vv, int uv_cap, int32_t *info) {
    if (!v || !v->held || !info) return -1;
    AVFrame *f = v->frame;
    if (f->format != AV_PIX_FMT_YUV420P10LE) return -2;
    int w = f->width, h = f->height;
    int ow, oh;
    fit(v, w, h, &ow, &oh);
    int halve = (w / 2 == ow && h / 2 == oh);
    int same = (w == ow && h == oh);
    if (!halve && !same) return -2;
    info[0] = ow;
    info[1] = oh;
    info[2] = f->colorspace;
    info[3] = f->color_trc;
    info[4] = f->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    int cw = ow / 2, ch = oh / 2;
    if (!y || !u || !vv || y_cap < ow * oh * 2 || uv_cap < cw * ch * 2) return 0;
    init_half();
    if (halve) {
        halve_plane_half(f->data[0], f->linesize[0], (uint16_t *)y, ow, oh);
        halve_plane_half(f->data[1], f->linesize[1], (uint16_t *)u, cw, ch);
        halve_plane_half(f->data[2], f->linesize[2], (uint16_t *)vv, cw, ch);
    } else {
        copy_plane_half(f->data[0], f->linesize[0], (uint16_t *)y, ow, oh);
        copy_plane_half(f->data[1], f->linesize[1], (uint16_t *)u, cw, ch);
        copy_plane_half(f->data[2], f->linesize[2], (uint16_t *)vv, cw, ch);
    }
    dna_video_drop(v);
    v->out_w = ow;
    v->out_h = oh;
    return 1;
}

void dna_video_hurry(DnaVideo *v, int level) {
    if (!v) return;
    // 1: Entblockung/SAO nur bei Bildern ohne Bezug aus (wie VLC auf schnellen Handys – ohne sichtbare Folgen),
    // 2: Entblockung/SAO ganz aus (spart am meisten; leichte Unschärfe bis zum nächsten Schlüsselbild),
    // 3: zusätzlich Bilder ohne Bezug auslassen. DNA_VIDEO_SKIP_SEEK: nach einem Sprung bis zum Ziel – ohne Folgen
    // für die Bilder danach.
    if (level == DNA_VIDEO_SKIP_SEEK) {
        v->ctx->skip_loop_filter = AVDISCARD_NONREF;
        v->ctx->skip_frame = AVDISCARD_NONREF;
        return;
    }
    v->ctx->skip_loop_filter = level >= 2 ? AVDISCARD_ALL : level >= 1 ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
    v->ctx->skip_frame = level >= 3 ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
}

void dna_video_flush(DnaVideo *v) {
    if (!v) return;
    dna_video_drop(v);
    avcodec_flush_buffers(v->ctx);
}

int dna_video_width(DnaVideo *v) { return v ? v->out_w : 0; }
int dna_video_height(DnaVideo *v) { return v ? v->out_h : 0; }

void dna_video_close(DnaVideo *v) {
    if (!v) return;
    if (v->sws) sws_freeContext(v->sws);
    if (v->ctx) avcodec_free_context(&v->ctx);
    if (v->pkt) av_packet_free(&v->pkt);
    if (v->frame) av_frame_free(&v->frame);
    free(v);
}
