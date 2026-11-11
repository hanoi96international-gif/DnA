/*
 * Test des Umverpackens auf dem Rechner: Datei öffnen, Spuren und Stückgrenzen ausgeben, alle Stücke als TS schreiben
 * und eine HLS-Liste dazu. Prüfen dann mit ffprobe/ffmpeg (ci/test-remux.sh).
 *   remux_test <datei> <ausgabeordner> [ziel-sekunden] [tonspur]
 */
#include "../../main/cpp/remux/dnaremux.h"
#include <stdio.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <string.h>

static int f_read(void *o, uint8_t *b, int n) { return (int)fread(b, 1, n, (FILE *)o); }
static int64_t f_seek(void *o, int64_t off, int whence) {
    FILE *f = o;
    if (whence == 0x10000) {
        long cur = ftell(f);
        fseek(f, 0, SEEK_END);
        long size = ftell(f);
        fseek(f, cur, SEEK_SET);
        return size;
    }
    if (fseek(f, off, whence) != 0) return -1;
    return ftell(f);
}
typedef struct Out { FILE *ts; FILE *cues; } Out;
static int f_write(void *o, const uint8_t *b, int n) { return fwrite(b, 1, n, ((Out *)o)->ts) == (size_t)n ? 0 : -1; }
/** Eingebettete Untertitel: eine Zeile je Paket in cues.txt (Spur, Beginn, Ende, Text ohne Zeilenumbrüche). */
static void f_cue(void *o, int stream, double start, double end, const uint8_t *text, int len) {
    FILE *f = ((Out *)o)->cues;
    if (!f) return;
    fprintf(f, "%d\t%.3f\t%.3f\t", stream, start, end);
    for (int i = 0; i < len; i++) fputc(text[i] == '\n' || text[i] == '\r' ? ' ' : text[i], f);
    fputc('\n', f);
}

int main(int argc, char **argv) {
    if (argc < 3) return 2;
    mkdir(argv[2], 0755);
    double target = argc > 3 ? atof(argv[3]) : 4.0;
    int audio = argc > 4 ? atoi(argv[4]) : -1;
    FILE *in = fopen(argv[1], "rb");
    if (!in) return 3;
    char err[256] = "";
    DnaIo io = {in, f_read, f_seek};
    DnaRemux *r = dna_open(io, err, sizeof(err));
    if (!r) { fprintf(stderr, "open: %s\n", err); return 4; }
    char desc[8192];
    dna_describe(r, desc, sizeof(desc));
    printf("duration %.3f\n%s", dna_duration(r), desc);
    printf("transcode %d\n", dna_needs_transcode(r, audio));
    double b[4096];
    int n = dna_boundaries(r, target, b, 4096);
    printf("boundaries %d\n", n);
    char path[1024];
    snprintf(path, sizeof(path), "%s/list.m3u8", argv[2]);
    FILE *list = fopen(path, "w");
    snprintf(path, sizeof(path), "%s/cues.txt", argv[2]);
    FILE *cues = fopen(path, "w");
    double maxd = 0;
    for (int i = 0; i + 1 < n; i++) if (b[i + 1] - b[i] > maxd) maxd = b[i + 1] - b[i];
    fprintf(list, "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n", (int)(maxd + 0.999));
    // Absichtlich nicht der Reihe nach (wie Spulen): erst Stück 3, dann alle.
    for (int pass = 0; pass < 2; pass++) {
        for (int i = (pass == 0 ? (n > 4 ? 3 : 0) : 0); i + 1 < n; i++) {
            snprintf(path, sizeof(path), "%s/%03d.ts", argv[2], i);
            Out out = {fopen(path, "wb"), pass == 1 ? cues : NULL};
            int ret = dna_segment_cues(r, b[i], b[i + 1], audio, -1, f_write, f_cue, &out, err, sizeof(err));
            fclose(out.ts);
            if (ret < 0) { fprintf(stderr, "segment %d: %s\n", i, err); return 5; }
            if (pass == 1) fprintf(list, "#EXTINF:%.3f,\n%03d.ts\n", b[i + 1] - b[i], i);
            if (pass == 0) break;
        }
    }
    fprintf(list, "#EXT-X-ENDLIST\n");
    fclose(list);
    fclose(cues);
    dna_close(r);
    fclose(in);
    printf("ok\n");
    return 0;
}
