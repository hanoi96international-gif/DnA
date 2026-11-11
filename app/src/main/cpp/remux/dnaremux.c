/*
 * Umverpacken von Filmen für den Fernseher, siehe dnaremux.h.
 *
 * Ein Stück = Bild ab einem Schlüsselbild bis vor das nächste Stück-Schlüsselbild, Ton im selben Zeitraum. Die
 * Zeitstempel bleiben die des Films (+ DNA_PTS_OFFSET), so passen die Stücke nahtlos aneinander und der Fernseher
 * kann jedes Stück einzeln holen (Spulen = anderes Stück).
 */
#include "dnaremux.h"

#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#define IO_BUFFER (256 * 1024)
#define OUT_BUFFER (64 * 1024)
#define EPS 0.002
/** So lange nach dem Ende des Bildes höchstens noch Ton des Stücks suchen (Sekunden). */
#define AUDIO_TAIL 4.0
/** Umgerechnet: so weit über den Schnitt hinaus Ton dekodieren (letzter Rahmen vollständig, Decoder-Verzug). */
#define AUDIO_TAIL_TRANSCODE 0.5
/** Vorlauf beim Umrechnen und wie weit das Schlüsselbild dafür höchstens zurückliegen darf. */
#define PREROLL 0.3
#define MAX_PREROLL 6.0
/** Wartende Tonpakete, bis das erste Schlüsselbild des Stücks kommt. */
#define MAX_PENDING 4096
/** Höchstens so viele Bilder am Stückanfang zurückhalten (vorgezogene Bilder bei offener GOP). */
#define MAX_HOLD 32

struct DnaRemux {
    DnaIo io;
    AVIOContext *avio;
    AVFormatContext *fmt;
    int video;
    int audio;
    double duration;
    double start;
    /** MKV kennt nur Anzeigezeiten: Dekodierzeiten der Bilder selbst berechnen (wie FFmpeg beim Schreiben). */
    int synth_dts;
    /**
     * Zähler der TS-Pakete je PID (continuity_counter) über die Stücke hinweg: Folgt ein Stück direkt auf das vorige,
     * laufen sie weiter – für den Fernseher sieht es aus wie ein durchgehender Strom (sonst hielte sein Demuxer den
     * Sprung zurück auf 0 an jeder Stückgrenze für verlorene Pakete).
     */
    uint8_t cc[8192];
    double last_end;
    int have_cc;
};

static int io_read(void *opaque, uint8_t *buf, int size) {
    DnaRemux *r = opaque;
    int n = r->io.read(r->io.opaque, buf, size);
    if (n == 0) return AVERROR_EOF;
    if (n < 0) return AVERROR(EIO);
    return n;
}

static int64_t io_seek(void *opaque, int64_t offset, int whence) {
    DnaRemux *r = opaque;
    return r->io.seek(r->io.opaque, offset, whence);
}

static void set_err(char *err, int errlen, const char *what, int code) {
    if (!err || errlen <= 0) return;
    char text[128] = "";
    if (code < 0) av_strerror(code, text, sizeof(text));
    snprintf(err, errlen, "%s%s%s", what, code < 0 ? ": " : "", text);
}

DnaRemux *dna_open(DnaIo io, char *err, int errlen) {
    DnaRemux *r = av_mallocz(sizeof(DnaRemux));
    if (!r) return NULL;
    r->io = io;
    uint8_t *buffer = av_malloc(IO_BUFFER);
    r->avio = avio_alloc_context(buffer, IO_BUFFER, 0, r, io_read, NULL, io.seek ? io_seek : NULL);
    r->fmt = avformat_alloc_context();
    if (!r->avio || !r->fmt) {
        set_err(err, errlen, "kein Speicher", 0);
        dna_close(r);
        return NULL;
    }
    r->fmt->pb = r->avio;
    r->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    // Nicht zu viel vorab lesen: jedes Megabyte geht über das Netz.
    r->fmt->probesize = 8 * 1024 * 1024;
    r->fmt->max_analyze_duration = 3 * AV_TIME_BASE;
    int ret = avformat_open_input(&r->fmt, NULL, NULL, NULL);
    if (ret < 0) {
        r->fmt = NULL;  // bei Fehler schon freigegeben
        set_err(err, errlen, "Datei nicht lesbar", ret);
        dna_close(r);
        return NULL;
    }
    ret = avformat_find_stream_info(r->fmt, NULL);
    if (ret < 0) {
        set_err(err, errlen, "Spuren nicht erkannt", ret);
        dna_close(r);
        return NULL;
    }
    r->video = av_find_best_stream(r->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    r->audio = av_find_best_stream(r->fmt, AVMEDIA_TYPE_AUDIO, -1, r->video >= 0 ? r->video : -1, NULL, 0);
    if (r->video < 0 && r->audio < 0) {
        set_err(err, errlen, "weder Bild noch Ton", 0);
        dna_close(r);
        return NULL;
    }
    r->synth_dts = r->video >= 0 && (strstr(r->fmt->iformat->name, "matroska") || strstr(r->fmt->iformat->name, "avi"));
    r->start = r->fmt->start_time != AV_NOPTS_VALUE ? r->fmt->start_time / (double)AV_TIME_BASE : 0.0;
    r->duration = r->fmt->duration > 0 ? r->fmt->duration / (double)AV_TIME_BASE : 0.0;
    // MKV liest das Verzeichnis der Schlüsselbilder (Cues, meist am Dateiende) erst beim ersten Springen: jetzt einmal
    // springen, damit die Stückgrenzen an den echten Schlüsselbildern liegen.
    if (r->video >= 0 && avformat_index_get_entries_count(r->fmt->streams[r->video]) < 2) {
        AVStream *st = r->fmt->streams[r->video];
        int64_t ts = st->start_time != AV_NOPTS_VALUE ? st->start_time : 0;
        if (av_seek_frame(r->fmt, r->video, ts, AVSEEK_FLAG_BACKWARD) >= 0) avformat_flush(r->fmt);
    }
    return r;
}

void dna_close(DnaRemux *r) {
    if (!r) return;
    if (r->fmt) avformat_close_input(&r->fmt);
    if (r->avio) {
        av_freep(&r->avio->buffer);
        avio_context_free(&r->avio);
    }
    av_free(r);
}

double dna_duration(DnaRemux *r) { return r->duration; }

int dna_describe(DnaRemux *r, char *out, int cap) {
    int used = 0;
    if (cap <= 0) return 0;
    out[0] = 0;
    for (unsigned i = 0; i < r->fmt->nb_streams; i++) {
        AVStream *st = r->fmt->streams[i];
        AVCodecParameters *p = st->codecpar;
        const char *codec = avcodec_get_name(p->codec_id);
        int n = 0;
        if (p->codec_type == AVMEDIA_TYPE_VIDEO) {
            n = snprintf(out + used, cap - used, "video %u %s %dx%d\n", i, codec, p->width, p->height);
        } else if (p->codec_type == AVMEDIA_TYPE_AUDIO) {
            AVDictionaryEntry *lang = av_dict_get(st->metadata, "language", NULL, 0);
            AVDictionaryEntry *title = av_dict_get(st->metadata, "title", NULL, 0);
            n = snprintf(out + used, cap - used, "audio %u %s %d %s %s\n", i, codec, p->ch_layout.nb_channels,
                         lang && lang->value[0] ? lang->value : "-", title ? title->value : "");
        } else if (p->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            AVDictionaryEntry *lang = av_dict_get(st->metadata, "language", NULL, 0);
            AVDictionaryEntry *title = av_dict_get(st->metadata, "title", NULL, 0);
            n = snprintf(out + used, cap - used, "subtitle %u %s %s %d %s\n", i, codec, lang && lang->value[0] ? lang->value : "-",
                         (st->disposition & AV_DISPOSITION_FORCED) ? 1 : 0, title ? title->value : "");
        }
        if (n < 0 || n >= cap - used) break;
        used += n;
    }
    // Welche Spuren ohne Wahl genommen werden.
    int n = snprintf(out + used, cap - used, "default %d %d\n", r->video, r->audio);
    if (n > 0 && n < cap - used) used += n;
    return used;
}

static double ts_seconds(AVStream *st, int64_t ts) { return ts * av_q2d(st->time_base); }

int dna_boundaries(DnaRemux *r, double target, double *out, int cap) {
    if (cap < 2) return 0;
    double end = r->start + r->duration;
    int count = 0;
    out[count++] = r->start;
    if (r->video >= 0) {
        AVStream *st = r->fmt->streams[r->video];
        int entries = avformat_index_get_entries_count(st);
        int keyframes = 0;
        double last = r->start;
        for (int i = 0; i < entries && count < cap - 1; i++) {
            const AVIndexEntry *e = avformat_index_get_entry(st, i);
            if (!e || !(e->flags & AVINDEX_KEYFRAME)) continue;
            keyframes++;
            double t = ts_seconds(st, e->timestamp);
            if (t - last >= target && (end <= 0 || t < end - 0.5)) {
                out[count++] = t;
                last = t;
            }
        }
        if (keyframes >= 2) {
            if (end > out[count - 1] + 0.05) out[count++] = end;
            return count;
        }
        count = 1;  // kein brauchbares Verzeichnis: gleichmäßig (Stückanfang = erstes Schlüsselbild danach)
    }
    if (end <= r->start) return 0;
    for (double t = r->start + target; t < end - 0.5 && count < cap - 1; t += target) out[count++] = t;
    out[count++] = end;
    return count;
}

static int is_tv_audio(enum AVCodecID id) {
    return id == AV_CODEC_ID_AAC || id == AV_CODEC_ID_AC3 || id == AV_CODEC_ID_EAC3 || id == AV_CODEC_ID_MP3 ||
           id == AV_CODEC_ID_MP2;
}

static int pick_audio(DnaRemux *r, int audio) {
    if (audio >= 0 && (unsigned)audio < r->fmt->nb_streams && r->fmt->streams[audio]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
        return audio;
    return r->audio;
}

int dna_needs_transcode(DnaRemux *r, int audio) {
    int a = pick_audio(r, audio);
    if (a < 0) return 0;
    return !is_tv_audio(r->fmt->streams[a]->codecpar->codec_id);
}

// ------------------------------------------------------------------ Ton umrechnen (AC3)

typedef struct Transcoder {
    AVCodecContext *dec;
    AVCodecContext *enc;
    SwrContext *swr;
    AVAudioFifo *fifo;
    AVFrame *frame;
    AVFrame *out;
    int64_t next_pts;  // in Abtastwerten (enc->time_base): Beginn des nächsten AC3-Rahmens
    int have_pts;
    /**
     * Festes Raster der AC3-Rahmen ab Filmanfang: Jedes Stück gibt genau die Rahmen aus, die in seiner Zeit beginnen –
     * so passen die Stücke ohne Lücke und ohne Doppel aneinander. Davor dekodierter Ton (Vorlauf) wird verworfen.
     */
    int64_t pos;           // Position des nächsten dekodierten Abtastwerts
    int64_t first_sample;  // erster Rahmen dieses Stücks (Rasterpunkt)
    int64_t stop_sample;   // Rahmen ab hier gehören zum nächsten Stück
    int aligned;           // FIFO beginnt am ersten Rahmen
    int64_t origin;        // Filmanfang in Abtastwerten
} Transcoder;

/** Rasterpunkt (Beginn eines AC3-Rahmens) bei oder nach Sekunde sec. */
static int64_t grid_at(Transcoder *t, double film_start, double sec) {
    int fs = t->enc->frame_size;
    double frames = (sec - film_start) * t->enc->sample_rate / fs;
    int64_t k = (int64_t)ceil(frames - 1e-6);
    if (k < 0) k = 0;
    return t->origin + k * fs;
}

static void transcoder_free(Transcoder *t) {
    avcodec_free_context(&t->dec);
    avcodec_free_context(&t->enc);
    swr_free(&t->swr);
    if (t->fifo) av_audio_fifo_free(t->fifo);
    t->fifo = NULL;
    av_frame_free(&t->frame);
    av_frame_free(&t->out);
}

static int transcoder_open(Transcoder *t, AVStream *in, char *err, int errlen) {
    memset(t, 0, sizeof(*t));
    const AVCodec *decoder = avcodec_find_decoder(in->codecpar->codec_id);
    const AVCodec *encoder = avcodec_find_encoder(AV_CODEC_ID_AC3);
    if (!decoder || !encoder) {
        set_err(err, errlen, decoder ? "AC3-Kodierer fehlt" : "Tondecoder fehlt", 0);
        return -1;
    }
    t->dec = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(t->dec, in->codecpar);
    t->dec->pkt_timebase = in->time_base;
    int ret = avcodec_open2(t->dec, decoder, NULL);
    if (ret < 0) {
        set_err(err, errlen, "Ton nicht dekodierbar", ret);
        return ret;
    }
    t->enc = avcodec_alloc_context3(encoder);
    int channels = t->dec->ch_layout.nb_channels;
    if (channels >= 6) av_channel_layout_from_mask(&t->enc->ch_layout, AV_CH_LAYOUT_5POINT1);
    else av_channel_layout_from_mask(&t->enc->ch_layout, AV_CH_LAYOUT_STEREO);
    int rate = t->dec->sample_rate;
    t->enc->sample_rate = (rate == 48000 || rate == 44100 || rate == 32000) ? rate : 48000;
    t->enc->sample_fmt = AV_SAMPLE_FMT_FLTP;
    t->enc->bit_rate = channels >= 6 ? 640000 : 224000;
    t->enc->time_base = (AVRational){1, t->enc->sample_rate};
    ret = avcodec_open2(t->enc, encoder, NULL);
    if (ret < 0) {
        set_err(err, errlen, "AC3-Kodierer startet nicht", ret);
        return ret;
    }
    ret = swr_alloc_set_opts2(&t->swr, &t->enc->ch_layout, t->enc->sample_fmt, t->enc->sample_rate, &t->dec->ch_layout,
                              t->dec->sample_fmt, t->dec->sample_rate, 0, NULL);
    if (ret < 0 || swr_init(t->swr) < 0) {
        set_err(err, errlen, "Ton-Umwandlung startet nicht", ret);
        return -1;
    }
    t->fifo = av_audio_fifo_alloc(t->enc->sample_fmt, t->enc->ch_layout.nb_channels, t->enc->frame_size * 4);
    t->frame = av_frame_alloc();
    t->out = av_frame_alloc();
    return 0;
}

// ------------------------------------------------------------------ Ausgabe (MPEG-TS)

typedef struct Output {
    AVFormatContext *oc;
    AVStream *video;
    AVStream *audio;
    int64_t offset;  // in 1/90000
    DnaWrite write;
    void *opaque;
    int aborted;
    DnaRemux *r;            // für die Paketzähler (DnaRemux.cc)
    uint8_t carry[188];     // angefangenes TS-Paket am Ende eines Schreibaufrufs
    int ncarry;
    /** Letzter Dekodier-Zeitstempel je Ausgabespur (MKV hat keine eigenen; geschätzte springen nach dem Springen). */
    int64_t last_dts[2];
    /** Dekodierzeit des Bildes aus den Anzeigezeiten (Umsortier-Puffer, wie compute_muxer_pkt_fields in FFmpeg). */
    int synth;
    int delay;
    int64_t frame;  // Bilddauer in Ausgabe-Zeitbasis
    int64_t pts_buffer[17];
    /**
     * Bilder nach dem ersten Schlüsselbild, die davor angezeigt werden (offene GOP). Mit ihnen werden die
     * Dekodierzeiten am Stückanfang so gesetzt, wie sie im durchgehenden Film wären – nahtlos zum vorigen Stück.
     */
    int lead;
    /**
     * H.264/HEVC aus MKV/MP4 (Längenpräfix) selbst nach Annex B wandeln: So bekommt jedes Stück am Anfang die
     * Parameter (SPS/PPS/VPS) – bei offener GOP (H.264 ohne IDR) fügt der TS-Muxer sie sonst nur am Filmanfang ein,
     * und ein Fernseher, der nach dem Springen mitten im Film einsteigt, bliebe schwarz.
     */
    AVBSFContext *bsf;
    int video_written;
    int hevc;
} Output;

/** Paketzähler eines TS-Pakets fortführen (siehe DnaRemux.cc). */
static void renumber(DnaRemux *r, uint8_t *p) {
    if (p[0] != 0x47) return;
    int pid = ((p[1] & 0x1f) << 8) | p[2];
    if (pid == 0x1fff) return;
    if (p[3] & 0x10) r->cc[pid] = (r->cc[pid] + 1) & 0x0f;  // mit Nutzdaten: nächster Wert
    p[3] = (p[3] & 0xf0) | r->cc[pid];
}

static int out_write(void *opaque, uint8_t *buf, int len) {
    Output *o = opaque;
    int used = 0;
    // Angefangenes Paket vom letzten Aufruf vervollständigen.
    if (o->ncarry > 0) {
        int need = 188 - o->ncarry;
        int take = len < need ? len : need;
        memcpy(o->carry + o->ncarry, buf, take);
        o->ncarry += take;
        used = take;
        if (o->ncarry < 188) return len;
        renumber(o->r, o->carry);
        if (o->write(o->opaque, o->carry, 188) < 0) {
            o->aborted = 1;
            return AVERROR(EPIPE);
        }
        o->ncarry = 0;
    }
    int whole = (len - used) / 188 * 188;
    for (int i = 0; i < whole; i += 188) renumber(o->r, buf + used + i);
    if (whole > 0 && o->write(o->opaque, buf + used, whole) < 0) {
        o->aborted = 1;
        return AVERROR(EPIPE);
    }
    o->ncarry = len - used - whole;
    memcpy(o->carry, buf + used + whole, o->ncarry);
    return len;
}

static int write_packet(Output *o, AVPacket *pkt, AVStream *out, AVRational from) {
    av_packet_rescale_ts(pkt, from, out->time_base);
    int64_t shift = av_rescale_q(o->offset, (AVRational){1, 90000}, out->time_base);
    if (pkt->pts != AV_NOPTS_VALUE) pkt->pts += shift;
    if (pkt->dts != AV_NOPTS_VALUE) pkt->dts += shift;
    pkt->stream_index = out->index;
    pkt->pos = -1;
    if (o->synth && out == o->video && pkt->pts != AV_NOPTS_VALUE) {
        int64_t d = pkt->duration > 0 ? pkt->duration : o->frame;
        o->pts_buffer[0] = pkt->pts;
        // Am Stückanfang: gedachte frühere Bilder, so dass das Schlüsselbild die Dekodierzeit bekommt, die es im
        // durchgehenden Film hätte: um Umsortier-Tiefe plus vorgezogene Bilder (offene GOP) vor seiner Anzeigezeit.
        for (int i = 1; i <= o->delay && o->pts_buffer[i] == AV_NOPTS_VALUE; i++) o->pts_buffer[i] = pkt->pts + (i - o->delay - 1 - o->lead) * d;
        for (int i = 0; i < o->delay && o->pts_buffer[i] > o->pts_buffer[i + 1]; i++) {
            int64_t tmp = o->pts_buffer[i];
            o->pts_buffer[i] = o->pts_buffer[i + 1];
            o->pts_buffer[i + 1] = tmp;
        }
        pkt->dts = o->pts_buffer[0];
    }
    // Streng aufsteigend, nie nach der Anzeigezeit (sonst lehnt der TS-Muxer das Paket ab).
    int64_t *last = &o->last_dts[out->index & 1];
    if (pkt->dts == AV_NOPTS_VALUE) pkt->dts = *last != AV_NOPTS_VALUE ? *last + 1 : pkt->pts;
    if (*last != AV_NOPTS_VALUE && pkt->dts <= *last) pkt->dts = *last + 1;
    if (pkt->pts != AV_NOPTS_VALUE && pkt->dts > pkt->pts) {
        if (pkt->pts <= *last) {  // passt nicht mehr dazwischen: Paket auslassen statt das Stück abzubrechen
            av_packet_unref(pkt);
            return 0;
        }
        pkt->dts = pkt->pts;
    }
    if (pkt->dts != AV_NOPTS_VALUE) *last = pkt->dts;
    return av_interleaved_write_frame(o->oc, pkt);
}

/** Enthält das Annex-B-Paket schon Parameter (H.264: SPS, HEVC: VPS/SPS)? */
static int has_parameter_sets(const uint8_t *p, int size, int hevc) {
    for (int i = 0; i + 3 < size; i++) {
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
            int type = hevc ? (p[i + 3] >> 1) & 0x3f : p[i + 3] & 0x1f;
            if (hevc ? (type == 32 || type == 33) : type == 7) return 1;
            i += 2;
        }
    }
    return 0;
}

/** Ein Bild schreiben (über die Annex-B-Wandlung; das erste des Stücks mit Parametern). */
static int write_video(Output *o, AVPacket *pkt, AVRational from) {
    if (!o->bsf) return write_packet(o, pkt, o->video, from);
    int ret = av_bsf_send_packet(o->bsf, pkt);
    if (ret < 0) {
        av_packet_unref(pkt);
        return 0;  // kaputtes Paket: überspringen
    }
    AVPacket *out = av_packet_alloc();
    while ((ret = av_bsf_receive_packet(o->bsf, out)) >= 0) {
        const AVCodecParameters *par = o->bsf->par_out;
        if (!o->video_written && par->extradata_size > 0 && !has_parameter_sets(out->data, out->size, o->hevc)) {
            AVPacket *joined = av_packet_alloc();
            if (av_new_packet(joined, par->extradata_size + out->size) >= 0) {
                memcpy(joined->data, par->extradata, par->extradata_size);
                memcpy(joined->data + par->extradata_size, out->data, out->size);
                av_packet_copy_props(joined, out);
                av_packet_unref(out);
                av_packet_move_ref(out, joined);
            }
            av_packet_free(&joined);
        }
        o->video_written = 1;
        ret = write_packet(o, out, o->video, from);
        av_packet_unref(out);
        if (ret < 0) break;
    }
    av_packet_free(&out);
    return ret == AVERROR(EAGAIN) || ret == AVERROR_EOF ? 0 : ret;
}

/** Umgerechneten Ton aus dem FIFO in ganzen AC3-Rahmen schreiben. */
static int drain_fifo(Transcoder *t, Output *o) {
    int frame_size = t->enc->frame_size;
    while (t->aligned && av_audio_fifo_size(t->fifo) >= frame_size && t->next_pts < t->stop_sample) {
        av_frame_unref(t->out);
        t->out->nb_samples = frame_size;
        t->out->format = t->enc->sample_fmt;
        t->out->sample_rate = t->enc->sample_rate;
        av_channel_layout_copy(&t->out->ch_layout, &t->enc->ch_layout);
        if (av_frame_get_buffer(t->out, 0) < 0) return -1;
        av_audio_fifo_read(t->fifo, (void **)t->out->data, frame_size);
        t->out->pts = t->next_pts;
        t->next_pts += frame_size;
        int ret = avcodec_send_frame(t->enc, t->out);
        if (ret < 0) return ret;
        AVPacket *pkt = av_packet_alloc();
        while ((ret = avcodec_receive_packet(t->enc, pkt)) >= 0) {
            ret = write_packet(o, pkt, o->audio, t->enc->time_base);
            av_packet_unref(pkt);
            if (ret < 0) {
                av_packet_free(&pkt);
                return ret;
            }
        }
        av_packet_free(&pkt);
    }
    return 0;
}

/** Stille in den FIFO (Lücke im Ton oder letzter, unvollständiger Rahmen). */
static void fifo_silence(Transcoder *t, int n) {
    if (n <= 0) return;
    uint8_t **buf = NULL;
    if (av_samples_alloc_array_and_samples(&buf, NULL, t->enc->ch_layout.nb_channels, n, t->enc->sample_fmt, 0) < 0) return;
    av_samples_set_silence(buf, 0, n, t->enc->ch_layout.nb_channels, t->enc->sample_fmt);
    av_audio_fifo_write(t->fifo, (void **)buf, n);
    av_freep(&buf[0]);
    av_freep(&buf);
}

/** got umgerechnete Abtastwerte ab t->pos: vor dem ersten Rahmen verwerfen, sonst in den FIFO. */
static void fifo_put(Transcoder *t, uint8_t **buf, int got) {
    if (got <= 0) return;
    int skip = 0;
    if (!t->aligned) {
        if (t->pos + got <= t->first_sample) {
            t->pos += got;
            return;
        }
        if (t->pos < t->first_sample) skip = (int)(t->first_sample - t->pos);
        else if (t->pos - t->first_sample < t->enc->sample_rate) fifo_silence(t, (int)(t->pos - t->first_sample));  // kurze Lücke am Anfang
        else t->first_sample = t->pos;  // Ton beginnt viel später (Filmanfang ohne Ton): ab da
        t->next_pts = t->first_sample;
        t->aligned = 1;
    }
    if (skip > 0) {
        av_audio_fifo_write(t->fifo, (void **)buf, got);
        av_audio_fifo_drain(t->fifo, skip);
    } else {
        av_audio_fifo_write(t->fifo, (void **)buf, got);
    }
    t->pos += got;
}

static int transcode_packet(Transcoder *t, Output *o, AVPacket *pkt) {
    int ret = avcodec_send_packet(t->dec, pkt);
    if (ret < 0 && ret != AVERROR(EAGAIN)) return 0;  // kaputtes Paket: überspringen
    while ((ret = avcodec_receive_frame(t->dec, t->frame)) >= 0) {
        int64_t ts = t->frame->best_effort_timestamp != AV_NOPTS_VALUE ? t->frame->best_effort_timestamp : t->frame->pts;
        if (!t->have_pts) {
            t->pos = ts != AV_NOPTS_VALUE ? av_rescale_q(ts, t->dec->pkt_timebase, t->enc->time_base) : t->first_sample;
            t->have_pts = 1;
        } else if (ts != AV_NOPTS_VALUE) {
            // Lücke im Ton (fehlende Pakete): mit Stille auffüllen, damit die Zeit stimmt. Kleine Abweichungen bleiben.
            int64_t at = av_rescale_q(ts, t->dec->pkt_timebase, t->enc->time_base);
            int64_t gap = at - t->pos;
            if (gap > t->enc->frame_size && gap < 10LL * t->enc->sample_rate) {
                if (t->aligned) fifo_silence(t, (int)gap);
                t->pos = at;
            }
        }
        int max_out = swr_get_out_samples(t->swr, t->frame->nb_samples);
        uint8_t **buf = NULL;
        if (av_samples_alloc_array_and_samples(&buf, NULL, t->enc->ch_layout.nb_channels, max_out, t->enc->sample_fmt, 0) < 0) {
            av_frame_unref(t->frame);
            return -1;
        }
        int got = swr_convert(t->swr, buf, max_out, (const uint8_t **)t->frame->extended_data, t->frame->nb_samples);
        fifo_put(t, buf, got);
        av_freep(&buf[0]);
        av_freep(&buf);
        av_frame_unref(t->frame);
        if ((ret = drain_fifo(t, o)) < 0) return ret;
    }
    return 0;
}

/** Stück fertig: restliche Rahmen bis zum Ende (am Filmende den letzten mit Stille aufgefüllt), Kodierer leeren. */
static int transcoder_finish(Transcoder *t, Output *o, int film_end) {
    int ret = drain_fifo(t, o);
    if (ret < 0) return ret;
    int left = av_audio_fifo_size(t->fifo);
    if (film_end && t->aligned && left > 0 && t->next_pts < t->stop_sample) {
        fifo_silence(t, t->enc->frame_size - left);
        if ((ret = drain_fifo(t, o)) < 0) return ret;
    }
    ret = avcodec_send_frame(t->enc, NULL);
    AVPacket *pkt = av_packet_alloc();
    while (ret >= 0 && (ret = avcodec_receive_packet(t->enc, pkt)) >= 0) {
        ret = write_packet(o, pkt, o->audio, t->enc->time_base);
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    return ret == AVERROR_EOF || ret == AVERROR(EAGAIN) ? 0 : ret;
}

// ------------------------------------------------------------------ Ein Stück

/** Text-Untertitel (keine Bild-Untertitel wie PGS/DVD: die kann der Fernseher als WebVTT nicht bekommen). */
static int is_text_subtitle(enum AVCodecID id) {
    return id == AV_CODEC_ID_SUBRIP || id == AV_CODEC_ID_SRT || id == AV_CODEC_ID_ASS || id == AV_CODEC_ID_SSA ||
           id == AV_CODEC_ID_WEBVTT || id == AV_CODEC_ID_MOV_TEXT || id == AV_CODEC_ID_TEXT;
}

int dna_segment(DnaRemux *r, double start, double end, int audio, int transcode, DnaWrite write, void *opaque, char *err, int errlen) {
    return dna_segment_cues(r, start, end, audio, transcode, write, NULL, opaque, err, errlen);
}

int dna_segment_cues(DnaRemux *r, double start, double end, int audio, int transcode, DnaWrite write, DnaCue cue, void *opaque,
                     char *err, int errlen) {
    int a = pick_audio(r, audio);
    if (transcode < 0) transcode = a >= 0 && dna_needs_transcode(r, a);
    Output o = {.write = write, .opaque = opaque, .offset = (int64_t)(DNA_PTS_OFFSET * 90000), .last_dts = {AV_NOPTS_VALUE, AV_NOPTS_VALUE}, .r = r};
    // Folgt dieses Stück direkt auf das zuletzt erzeugte, laufen die Paketzähler weiter, sonst beginnen sie neu
    // (der Fernseher ist dann ohnehin gesprungen).
    if (!r->have_cc || fabs(start - r->last_end) > EPS) memset(r->cc, 15, sizeof(r->cc));
    r->have_cc = 0;
    Transcoder t;
    memset(&t, 0, sizeof(t));
    AVPacket *pkt = av_packet_alloc();
    AVPacket **pending = av_calloc(MAX_PENDING, sizeof(AVPacket *));
    int npending = 0;
    // Ton ab „end“, solange das Ende des Bildes (erstes Schlüsselbild des nächsten Stücks) noch nicht bekannt ist.
    AVPacket **late = av_calloc(MAX_PENDING, sizeof(AVPacket *));
    int nlate = 0;
    int ret = 0;

    ret = avformat_alloc_output_context2(&o.oc, NULL, "mpegts", NULL);
    if (ret < 0 || !o.oc) {
        set_err(err, errlen, "MPEG-TS nicht verfügbar", ret);
        goto done;
    }
    uint8_t *obuf = av_malloc(OUT_BUFFER);
    o.oc->pb = avio_alloc_context(obuf, OUT_BUFFER, 1, &o, NULL, out_write, NULL);
    o.oc->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (r->video >= 0) {
        AVStream *in = r->fmt->streams[r->video];
        o.video = avformat_new_stream(o.oc, NULL);
        avcodec_parameters_copy(o.video->codecpar, in->codecpar);
        o.video->codecpar->codec_tag = 0;
        o.video->time_base = in->time_base;
        enum AVCodecID id = in->codecpar->codec_id;
        // Längenpräfix (avcC/hvcC beginnen mit 1): selbst nach Annex B wandeln (siehe Output.bsf).
        if ((id == AV_CODEC_ID_H264 || id == AV_CODEC_ID_HEVC) && in->codecpar->extradata_size > 0 && in->codecpar->extradata[0] == 1) {
            const AVBitStreamFilter *f = av_bsf_get_by_name(id == AV_CODEC_ID_H264 ? "h264_mp4toannexb" : "hevc_mp4toannexb");
            if (f && av_bsf_alloc(f, &o.bsf) >= 0) {
                avcodec_parameters_copy(o.bsf->par_in, in->codecpar);
                o.bsf->time_base_in = in->time_base;
                if (av_bsf_init(o.bsf) >= 0) {
                    avcodec_parameters_copy(o.video->codecpar, o.bsf->par_out);
                    o.video->codecpar->codec_tag = 0;
                    o.hevc = id == AV_CODEC_ID_HEVC;
                } else av_bsf_free(&o.bsf);
            }
        }
    }
    if (a >= 0) {
        AVStream *in = r->fmt->streams[a];
        o.audio = avformat_new_stream(o.oc, NULL);
        if (transcode) {
            if ((ret = transcoder_open(&t, in, err, errlen)) < 0) goto done;
            t.origin = (int64_t)llround(r->start * t.enc->sample_rate);
            t.stop_sample = INT64_MAX;
            avcodec_parameters_from_context(o.audio->codecpar, t.enc);
            o.audio->time_base = t.enc->time_base;
        } else {
            avcodec_parameters_copy(o.audio->codecpar, in->codecpar);
            o.audio->codecpar->codec_tag = 0;
            o.audio->time_base = in->time_base;
        }
        AVDictionaryEntry *lang = av_dict_get(in->metadata, "language", NULL, 0);
        if (lang) av_dict_set(&o.audio->metadata, "language", lang->value, 0);
    }
    AVDictionary *opts = NULL;
    av_dict_set(&opts, "mpegts_copyts", "1", 0);
    ret = avformat_write_header(o.oc, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        set_err(err, errlen, "TS-Kopf", ret);
        goto done;
    }
    if (o.video && r->synth_dts) {
        AVStream *in = r->fmt->streams[r->video];
        AVRational rate = in->avg_frame_rate.num > 0 ? in->avg_frame_rate : (in->r_frame_rate.num > 0 ? in->r_frame_rate : (AVRational){25, 1});
        o.synth = 1;
        o.delay = FFMIN(FFMAX(in->codecpar->video_delay, 3), 16);  // B-Bild-Pyramiden: mindestens 3
        o.frame = av_rescale_q(1, av_inv_q(rate), o.video->time_base);
        for (int i = 0; i < 17; i++) o.pts_buffer[i] = AV_NOPTS_VALUE;
    }

    // Zum Stückanfang springen (Schlüsselbild an oder vor start). Beim Umrechnen etwas früher (voriges
    // Schlüsselbild): Manche Tonformate (TrueHD, Opus …) brauchen Vorlauf, bis der Decoder Ton liefert.
    int seek_stream = r->video >= 0 ? r->video : a;
    AVStream *ss = r->fmt->streams[seek_stream];
    double seek_to = start;
    if (transcode && start > r->start + PREROLL) {
        seek_to = start - PREROLL;
        if (r->video >= 0) {
            int idx = av_index_search_timestamp(ss, (int64_t)((start - PREROLL) / av_q2d(ss->time_base)), AVSEEK_FLAG_BACKWARD);
            const AVIndexEntry *e = idx >= 0 ? avformat_index_get_entry(ss, idx) : NULL;
            // Liegt das vorige Schlüsselbild weit zurück (lange GOP), lieber ohne Vorlauf als die doppelte Menge holen.
            if (!e || start - ts_seconds(ss, e->timestamp) > MAX_PREROLL) seek_to = start;
        }
    }
    int64_t target = (int64_t)(seek_to / av_q2d(ss->time_base));
    ret = av_seek_frame(r->fmt, seek_stream, target, AVSEEK_FLAG_BACKWARD);
    if (ret < 0 && start > r->start + EPS) {
        set_err(err, errlen, "Springen nicht möglich", ret);
        goto done;
    }
    avformat_flush(r->fmt);

    // Stückanfang (MKV): Schlüsselbild und die direkt folgenden, davor angezeigten Bilder zurückhalten, bis klar ist,
    // wie viele es sind (siehe Output.lead).
    AVPacket *hold[MAX_HOLD];
    int nhold = 0, holding = 0;
    int64_t hold_key_pts = 0;
    int video_started = r->video < 0;
    int video_done = r->video < 0;
    double video_start = start;  // tatsächlicher Anfang des Bildes in diesem Stück
    double cut = end;            // tatsächliches Ende (erstes Schlüsselbild des nächsten Stücks)
    int is_last = r->duration > 0 && end >= r->start + r->duration - 0.05;
    int film_end = is_last;
    if (transcode) {
        t.first_sample = grid_at(&t, r->start, start);
        t.stop_sample = is_last ? INT64_MAX : grid_at(&t, r->start, end);
    }

    while (1) {
        ret = av_read_frame(r->fmt, pkt);
        if (ret == AVERROR_EOF) {
            ret = 0;
            film_end = 1;
            if (transcode) t.stop_sample = INT64_MAX;
            // Dateiende: gesammelter Ton gehört noch hierher.
            for (int i = 0; i < nlate; i++) {
                AVStream *ps = r->fmt->streams[late[i]->stream_index];
                if (ret >= 0) ret = transcode ? transcode_packet(&t, &o, late[i]) : write_packet(&o, late[i], o.audio, ps->time_base);
                av_packet_free(&late[i]);
            }
            nlate = 0;
            break;
        }
        if (ret < 0) {
            set_err(err, errlen, "Lesen", ret);
            break;
        }
        AVStream *in = r->fmt->streams[pkt->stream_index];
        int64_t ts = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
        double sec = ts != AV_NOPTS_VALUE ? ts_seconds(in, ts) : -1;
        if (in->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            // Eingebettete Text-Untertitel: an den Aufrufer (für die Untertitelspur des Fernsehers), nicht ins Stück.
            if (cue && sec >= 0 && pkt->size > 0 && is_text_subtitle(in->codecpar->codec_id)) {
                double duration = pkt->duration > 0 ? ts_seconds(in, pkt->duration) : 3.0;
                cue(opaque, pkt->stream_index, sec, sec + duration, pkt->data, pkt->size);
            }
            av_packet_unref(pkt);
            continue;
        }
        if (pkt->stream_index == r->video && holding) {
            int key = pkt->flags & AV_PKT_FLAG_KEY;
            if (!key && pkt->pts != AV_NOPTS_VALUE && pkt->pts < hold_key_pts && nhold < MAX_HOLD) {
                hold[nhold++] = av_packet_clone(pkt);
                av_packet_unref(pkt);
                continue;
            }
            holding = 0;
            o.lead = nhold - 1;
            for (int i = 0; i < nhold; i++) {
                if (ret >= 0) ret = write_video(&o, hold[i], in->time_base);
                av_packet_free(&hold[i]);
            }
            nhold = 0;
            if (ret < 0) break;
        }
        if (pkt->stream_index == r->video) {
            int key = pkt->flags & AV_PKT_FLAG_KEY;
            if (!video_started) {
                if (!key || sec < start - EPS) {
                    av_packet_unref(pkt);
                    continue;
                }
                video_started = 1;
                video_start = sec;
                if (transcode && !t.aligned) t.first_sample = grid_at(&t, r->start, video_start);
                // Ton, der schon da ist, ab dem Bildanfang ausgeben (umgerechnet: alles als Vorlauf in den Decoder,
                // der Umrechner verwirft, was vor dem ersten Rahmen liegt).
                for (int i = 0; i < npending; i++) {
                    AVPacket *p = pending[i];
                    AVStream *ps = r->fmt->streams[p->stream_index];
                    int64_t pts = p->pts != AV_NOPTS_VALUE ? p->pts : p->dts;
                    if (pts != AV_NOPTS_VALUE && ts_seconds(ps, pts) >= video_start - EPS && ret >= 0) {
                        ret = write_packet(&o, p, o.audio, ps->time_base);
                    }
                    av_packet_free(&pending[i]);
                }
                npending = 0;
                if (ret < 0) break;
            } else if (!video_done && key && sec >= end - EPS && !is_last) {
                video_done = 1;
                cut = sec;
                if (transcode) {
                    t.stop_sample = grid_at(&t, r->start, cut);
                    if ((ret = drain_fifo(&t, &o)) < 0) break;
                }
                // Gesammelten Ton bis zum Schnitt ausgeben, den Rest gehört zum nächsten Stück.
                for (int i = 0; i < nlate; i++) {
                    AVPacket *p = late[i];
                    AVStream *ps = r->fmt->streams[p->stream_index];
                    int64_t pts = p->pts != AV_NOPTS_VALUE ? p->pts : p->dts;
                    if (pts != AV_NOPTS_VALUE && ts_seconds(ps, pts) < cut - EPS && ret >= 0) {
                        ret = transcode ? transcode_packet(&t, &o, p) : write_packet(&o, p, o.audio, ps->time_base);
                    }
                    av_packet_free(&late[i]);
                }
                nlate = 0;
                if (ret < 0) break;
            }
            if (!video_done && o.synth && video_start == sec && nhold == 0 && !o.lead && o.pts_buffer[0] == AV_NOPTS_VALUE) {
                // Erstes Schlüsselbild: zurückhalten (siehe oben).
                holding = 1;
                hold_key_pts = pkt->pts;
                hold[nhold++] = av_packet_clone(pkt);
            } else if (!video_done) {
                ret = write_video(&o, pkt, in->time_base);
                if (ret < 0) break;
            }
        } else if (pkt->stream_index == a) {
            if (transcode) {
                // Umgerechnet: alles in den Umrechner (auch der Vorlauf vor dem Bild), er schneidet genau am Raster:
                // die Rahmen ab dem Bildanfang bis zum Schnitt.
                if (!video_done || sec < cut + AUDIO_TAIL_TRANSCODE) {
                    ret = transcode_packet(&t, &o, pkt);
                    if (ret < 0) break;
                } else {
                    av_packet_unref(pkt);
                    break;
                }
            } else if (!video_started) {
                if (npending < MAX_PENDING && sec >= start - 2.0) pending[npending++] = av_packet_clone(pkt);
            } else if (sec < video_start - EPS) {
                // vor dem Bildanfang: gehört zum vorigen Stück
            } else if (sec < end - EPS || (video_done && sec < cut - EPS)) {
                ret = transcode ? transcode_packet(&t, &o, pkt) : write_packet(&o, pkt, o.audio, in->time_base);
                if (ret < 0) break;
            } else if (!video_done) {
                if (nlate < MAX_PENDING) late[nlate++] = av_packet_clone(pkt);
            } else {
                av_packet_unref(pkt);
                break;  // Ton erreicht das nächste Stück
            }
        }
        av_packet_unref(pkt);
        // Bild fertig und kein Ton mehr für dieses Stück zu erwarten.
        if (video_done && (a < 0 || sec > cut + (transcode ? AUDIO_TAIL_TRANSCODE : AUDIO_TAIL))) break;
        if (o.aborted) break;
    }
    // Noch zurückgehaltene Bilder (sehr kurzes Stück, Dateiende).
    if (nhold > 0) {
        o.lead = holding ? nhold - 1 : o.lead;
        for (int i = 0; i < nhold; i++) {
            if (ret >= 0) ret = write_video(&o, hold[i], r->fmt->streams[r->video]->time_base);
            av_packet_free(&hold[i]);
        }
        nhold = 0;
    }
    if (o.aborted) {
        set_err(err, errlen, "Fernseher hat aufgelegt", 0);
        ret = -1;
    }
    if (ret >= 0 && transcode && !o.aborted) ret = transcoder_finish(&t, &o, film_end);
    if (ret >= 0) {
        ret = av_write_trailer(o.oc);
        if (ret < 0) set_err(err, errlen, "TS-Ende", ret);
        else avio_flush(o.oc->pb);
        if (ret >= 0 && o.aborted) {
            set_err(err, errlen, "Fernseher hat aufgelegt", 0);
            ret = -1;
        }
        if (ret >= 0) {
            r->last_end = end;
            r->have_cc = 1;
        }
    }

done:
    for (int i = 0; i < npending; i++) av_packet_free(&pending[i]);
    av_free(pending);
    for (int i = 0; i < nlate; i++) av_packet_free(&late[i]);
    av_free(late);
    av_packet_free(&pkt);
    transcoder_free(&t);
    av_bsf_free(&o.bsf);
    if (o.oc) {
        if (o.oc->pb) {
            av_freep(&o.oc->pb->buffer);
            avio_context_free(&o.oc->pb);
        }
        avformat_free_context(o.oc);
    }
    return ret < 0 ? (ret == -1 ? -1 : ret) : 0;
}
