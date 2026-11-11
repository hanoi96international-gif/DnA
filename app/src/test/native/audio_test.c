/*
 * Test des Ton-Decoders der DNA Engine (dnaaudio.c) auf dem Rechner: Tonspur einer Datei paketweise dekodieren (auch
 * mehrere Pakete auf einmal, wie ein PES-Paket mit mehreren Rahmen) und prüfen, dass die ganze Dauer als PCM herauskommt.
 * Aufruf: audio_test <datei> <decoder> <kanäle erwartet>
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavutil/mem.h>

#include "dnaaudio.h"

/* Die App-eigene FFmpeg-Bibliothek hat kein „file“-Protokoll: Datei selbst lesen (wie die App über ihr eigenes Netz). */
static int f_read(void *opaque, uint8_t *buf, int size) {
    size_t n = fread(buf, 1, size, (FILE *)opaque);
    return n > 0 ? (int)n : AVERROR_EOF;
}

static int64_t f_seek(void *opaque, int64_t offset, int whence) {
    FILE *f = opaque;
    if (whence == AVSEEK_SIZE) {
        long at = ftell(f);
        fseek(f, 0, SEEK_END);
        long size = ftell(f);
        fseek(f, at, SEEK_SET);
        return size;
    }
    return fseek(f, offset, whence & ~AVSEEK_FORCE) == 0 ? ftell(f) : -1;
}

int main(int argc, char **argv) {
    if (argc < 4) return 2;
    FILE *in = fopen(argv[1], "rb");
    if (!in) {
        fprintf(stderr, "kann %s nicht lesen\n", argv[1]);
        return 1;
    }
    const int io_size = 64 * 1024;
    AVIOContext *io = avio_alloc_context(av_malloc(io_size), io_size, 0, in, f_read, NULL, f_seek);
    AVFormatContext *fmt = avformat_alloc_context();
    fmt->pb = io;
    if (avformat_open_input(&fmt, NULL, NULL, NULL) < 0 || avformat_find_stream_info(fmt, NULL) < 0) {
        fprintf(stderr, "kann %s nicht öffnen\n", argv[1]);
        return 1;
    }
    int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (idx < 0) return 1;
    AVStream *st = fmt->streams[idx];
    AVCodecParameters *par = st->codecpar;
    DnaAudio *a = dna_audio_open(argv[2], par->sample_rate, par->ch_layout.nb_channels, par->extradata, par->extradata_size);
    if (!a) {
        fprintf(stderr, "Decoder %s öffnet nicht\n", argv[2]);
        return 1;
    }
    AVPacket *pkt = av_packet_alloc();
    long long samples = 0;
    int packets = 0;
    // Drei Pakete hintereinander auf einmal (Parser muss sie trennen) – nicht bei FLAC/Opus/Vorbis, die nur aus
    // MKV/MP4 kommen (ein Rahmen je Paket, ohne Parser).
    int per_batch = (!strcmp(argv[2], "flac") || !strcmp(argv[2], "opus") || !strcmp(argv[2], "libopus") || !strcmp(argv[2], "vorbis")) ? 1 : 3;
    uint8_t *batch = NULL;
    int batch_len = 0, batch_count = 0;
    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == idx) {
            packets++;
            batch = realloc(batch, batch_len + pkt->size);
            memcpy(batch + batch_len, pkt->data, pkt->size);
            batch_len += pkt->size;
            if (++batch_count == per_batch) {
                int n = dna_audio_decode(a, batch, batch_len);
                if (n > 0) samples += n / (2 * dna_audio_channels(a));
                batch_len = 0;
                batch_count = 0;
            }
        }
        av_packet_unref(pkt);
    }
    if (batch_len > 0) {
        int n = dna_audio_decode(a, batch, batch_len);
        if (n > 0) samples += n / (2 * dna_audio_channels(a));
    }
    double duration = fmt->duration > 0 ? fmt->duration / (double)AV_TIME_BASE : 0;
    double got = dna_audio_rate(a) > 0 ? samples / (double)dna_audio_rate(a) : 0;
    int want_channels = atoi(argv[3]);
    printf("%s: %d Pakete, %.2f s Ton von %.2f s, %d Hz, %d Kanäle (erwartet %d)\n", argv[1], packets, got, duration,
           dna_audio_rate(a), dna_audio_channels(a), want_channels);
    int ok = duration > 0 && got > duration * 0.97 && got < duration * 1.03 && dna_audio_channels(a) == want_channels;
    dna_audio_close(a);
    free(batch);
    av_packet_free(&pkt);
    avformat_close_input(&fmt);
    return ok ? 0 : 1;
}
