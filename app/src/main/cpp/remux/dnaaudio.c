/*
 * Ton-Decoder der DNA Engine für Formate, die das Gerät selbst nicht dekodiert (DTS, Dolby TrueHD, auf manchen Geräten
 * auch AC-3/E-AC-3 und MP2): FFmpeg aus der App-eigenen Bibliothek (siehe ci/remux-config.sh), Ausgabe PCM 16 Bit
 * verschachtelt, 1/2/6/8 Kanäle (Mono, Stereo, 5.1, 7.1 wie Android sie spielt).
 *
 * Eingabe sind Pakete aus dem Container (MKV-Block, PES-Inhalt …); wo FFmpeg einen Parser hat, werden sie damit in
 * einzelne Rahmen zerlegt (ein PES-Paket enthält oft mehrere DTS-Rahmen).
 */
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>

#include "dnaaudio.h"

struct DnaAudio {
    AVCodecContext *ctx;
    AVCodecParserContext *parser;
    AVPacket *pkt;
    AVFrame *frame;
    SwrContext *swr;
    int swr_rate;
    int swr_format;
    AVChannelLayout swr_in;
    int out_rate;
    int out_channels;
    uint8_t *out;
    int out_cap;
    int out_len;
};

static int out_channels_for(int in) {
    if (in <= 1) return 1;
    if (in == 2) return 2;
    if (in <= 6) return 6;
    return 8;
}

DnaAudio *dna_audio_open(const char *codec_name, int sample_rate, int channels, const uint8_t *extra, int extra_len) {
    const AVCodec *codec = avcodec_find_decoder_by_name(codec_name);
    if (!codec) return NULL;
    DnaAudio *a = calloc(1, sizeof(DnaAudio));
    if (!a) return NULL;
    a->ctx = avcodec_alloc_context3(codec);
    a->pkt = av_packet_alloc();
    a->frame = av_frame_alloc();
    if (!a->ctx || !a->pkt || !a->frame) goto fail;
    if (sample_rate > 0) a->ctx->sample_rate = sample_rate;
    if (channels > 0) av_channel_layout_default(&a->ctx->ch_layout, channels);
    if (extra && extra_len > 0) {
        a->ctx->extradata = av_mallocz(extra_len + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!a->ctx->extradata) goto fail;
        memcpy(a->ctx->extradata, extra, extra_len);
        a->ctx->extradata_size = extra_len;
    }
    if (avcodec_open2(a->ctx, codec, NULL) < 0) goto fail;
    // Parser nur, wo ein Paket mehrere Rahmen tragen kann (PES im MPEG-TS, gesammelte TrueHD-Pakete: DTS, AC-3,
    // E-AC-3, MPEG-Audio, TrueHD). FLAC, Opus und Vorbis kommen nur aus MKV/MP4/WebM (ein Rahmen je Paket; der
    // FLAC-Parser hielte dort Rahmen zurück), AAC kommt von der Engine immer roh ohne ADTS-Kopf (ein Rahmen).
    if (codec->id != AV_CODEC_ID_FLAC && codec->id != AV_CODEC_ID_OPUS && codec->id != AV_CODEC_ID_VORBIS &&
        codec->id != AV_CODEC_ID_AAC)
        a->parser = av_parser_init(codec->id);
    a->swr_rate = -1;
    a->swr_format = -1;
    return a;
fail:
    dna_audio_close(a);
    return NULL;
}

void dna_audio_close(DnaAudio *a) {
    if (!a) return;
    if (a->parser) av_parser_close(a->parser);
    if (a->swr) swr_free(&a->swr);
    av_channel_layout_uninit(&a->swr_in);
    if (a->ctx) avcodec_free_context(&a->ctx);
    if (a->pkt) av_packet_free(&a->pkt);
    if (a->frame) av_frame_free(&a->frame);
    free(a->out);
    free(a);
}

static int ensure(DnaAudio *a, int more) {
    if (a->out_len + more <= a->out_cap) return 0;
    int cap = a->out_cap ? a->out_cap : 64 * 1024;
    while (cap < a->out_len + more) cap *= 2;
    uint8_t *grown = realloc(a->out, cap);
    if (!grown) return -1;
    a->out = grown;
    a->out_cap = cap;
    return 0;
}

/** Einen dekodierten Rahmen in PCM 16 Bit mit Android-Kanalbelegung umrechnen und anhängen. */
static int convert(DnaAudio *a, AVFrame *f) {
    int in_channels = f->ch_layout.nb_channels;
    if (in_channels <= 0 || f->sample_rate <= 0 || f->nb_samples <= 0) return 0;
    int out_ch = out_channels_for(in_channels);
    if (!a->swr || a->swr_rate != f->sample_rate || a->swr_format != f->format ||
        av_channel_layout_compare(&a->swr_in, &f->ch_layout) != 0 || a->out_channels != out_ch) {
        if (a->swr) swr_free(&a->swr);
        AVChannelLayout out_layout;
        av_channel_layout_default(&out_layout, out_ch);
        AVChannelLayout in_layout;
        if (f->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) av_channel_layout_default(&in_layout, in_channels);
        else av_channel_layout_copy(&in_layout, &f->ch_layout);
        int r = swr_alloc_set_opts2(&a->swr, &out_layout, AV_SAMPLE_FMT_S16, f->sample_rate, &in_layout, f->format, f->sample_rate, 0, NULL);
        av_channel_layout_uninit(&out_layout);
        if (r < 0 || swr_init(a->swr) < 0) {
            av_channel_layout_uninit(&in_layout);
            if (a->swr) swr_free(&a->swr);
            return -1;
        }
        av_channel_layout_uninit(&a->swr_in);
        av_channel_layout_copy(&a->swr_in, &f->ch_layout);
        av_channel_layout_uninit(&in_layout);
        a->swr_rate = f->sample_rate;
        a->swr_format = f->format;
        a->out_rate = f->sample_rate;
        a->out_channels = out_ch;
    }
    int max_samples = swr_get_out_samples(a->swr, f->nb_samples);
    if (max_samples <= 0) return 0;
    int bytes = max_samples * out_ch * 2;
    if (ensure(a, bytes) < 0) return -1;
    uint8_t *dst = a->out + a->out_len;
    int done = swr_convert(a->swr, &dst, max_samples, (const uint8_t **)f->extended_data, f->nb_samples);
    if (done < 0) return -1;
    a->out_len += done * out_ch * 2;
    return 0;
}

static void decode_packet(DnaAudio *a, uint8_t *data, int size) {
    a->pkt->data = data;
    a->pkt->size = size;
    if (avcodec_send_packet(a->ctx, a->pkt) < 0) {
        // Kaputter Rahmen: auslassen, der nächste geht wieder.
        return;
    }
    while (avcodec_receive_frame(a->ctx, a->frame) == 0) {
        convert(a, a->frame);
        av_frame_unref(a->frame);
    }
}

int dna_audio_decode(DnaAudio *a, const uint8_t *data, int len) {
    if (!a) return -1;
    a->out_len = 0;
    if (!data || len <= 0) return 0;
    if (!a->parser) {
        // FFmpeg liest bis zu AV_INPUT_BUFFER_PADDING_SIZE über das Ende hinaus: mit Polster kopieren.
        uint8_t *copy = av_malloc(len + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!copy) return -1;
        memcpy(copy, data, len);
        memset(copy + len, 0, AV_INPUT_BUFFER_PADDING_SIZE);
        decode_packet(a, copy, len);
        av_free(copy);
        return a->out_len;
    }
    const uint8_t *in = data;
    int left = len;
    while (left > 0) {
        uint8_t *frame = NULL;
        int frame_size = 0;
        int used = av_parser_parse2(a->parser, a->ctx, &frame, &frame_size, in, left, AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
        if (used < 0) break;
        in += used;
        left -= used;
        if (frame_size > 0) decode_packet(a, frame, frame_size);
        if (used == 0 && frame_size == 0) break;
    }
    return a->out_len;
}

const uint8_t *dna_audio_output(DnaAudio *a) { return a ? a->out : NULL; }
int dna_audio_rate(DnaAudio *a) { return a ? a->out_rate : 0; }
int dna_audio_channels(DnaAudio *a) { return a ? a->out_channels : 0; }
