/*
 * Umverpacken von Filmen für den Fernseher (LG webOS & Co.): Film-Datei (MKV, MP4, AVI) als HLS-Stücke im
 * MPEG-TS-Format, Stück für Stück auf Abruf, ohne das Bild neu zu berechnen. Tonformate, die Fernseher oft nicht
 * können (DTS, TrueHD, FLAC, Opus …), werden nebenbei in AC3 (Dolby Digital) umgerechnet.
 *
 * Reines C mit FFmpeg; Lesen und Schreiben über Rückrufe (in der App: HTTP mit Range über den Proxy der App).
 */
#ifndef DNAREMUX_H
#define DNAREMUX_H

#include <stdint.h>

typedef struct DnaIo {
    void *opaque;
    /** Liest bis zu len Bytes; 0 = Ende, < 0 = Fehler. */
    int (*read)(void *opaque, uint8_t *buf, int len);
    /** Wie AVIOContext: whence SEEK_SET/SEEK_CUR/SEEK_END oder 0x10000 (AVSEEK_SIZE: Größe liefern). */
    int64_t (*seek)(void *opaque, int64_t offset, int whence);
} DnaIo;

/** Schreibt einen Teil des Stücks; < 0 = abbrechen (Fernseher hat aufgelegt). */
typedef int (*DnaWrite)(void *opaque, const uint8_t *buf, int len);

/**
 * Eine Zeile eingebetteter Text-Untertitel (SRT, ASS, WebVTT, mov_text), wie sie beim Lesen des Stücks vorbeikommt:
 * Spur-Index, Beginn und Ende in Sekunden (Zeit der Datei), Inhalt des Pakets (bei ASS die Dialogzeile ohne Zeiten).
 */
typedef void (*DnaCue)(void *opaque, int stream, double start, double end, const uint8_t *text, int len);

typedef struct DnaRemux DnaRemux;

/** Öffnen und Kopf lesen. NULL bei Fehler (Grund in err). */
DnaRemux *dna_open(DnaIo io, char *err, int errlen);
void dna_close(DnaRemux *r);

/** Dauer in Sekunden (0, wenn unbekannt). */
double dna_duration(DnaRemux *r);

/**
 * Beschreibung als Text, eine Zeile je Spur:
 *   "video <index> <codec> <breite>x<höhe>"
 *   "audio <index> <codec> <kanäle> <sprache|-> <titel>"   (titel kann Leerzeichen enthalten)
 *   "subtitle <index> <codec> <sprache|-> <forced 0/1> <titel>"  (Untertitelspuren)
 *   "default <bild-index> <ton-index>"                     (Spuren ohne Wahl, -1 = keine)
 * Rückgabe: Länge (ohne Null), abgeschnitten auf cap-1.
 */
int dna_describe(DnaRemux *r, char *out, int cap);

/**
 * Stückgrenzen in Sekunden (an Schlüsselbildern, je etwa target Sekunden): out[0] = 0, out[n] = Dauer.
 * Rückgabe: Anzahl Grenzen (Stücke = Rückgabe - 1), höchstens cap.
 */
int dna_boundaries(DnaRemux *r, double target, double *out, int cap);

/** Muss dieser Ton für Fernseher umgerechnet werden (DTS, TrueHD, FLAC …)? audio = Spur-Index oder -1 (Standard). */
int dna_needs_transcode(DnaRemux *r, int audio);

/**
 * Ein Stück [start, end) als MPEG-TS schreiben. audio = Spur-Index (-1: Standardspur), transcode: 1 = Ton in AC3
 * umrechnen, 0 = unverändert, -1 = automatisch. Zeitstempel bleiben die des Films (+ fester Versatz), damit die Stücke
 * nahtlos aneinander passen. Rückgabe 0 = ok, < 0 = Fehler (Grund in err).
 */
int dna_segment(DnaRemux *r, double start, double end, int audio, int transcode, DnaWrite write, void *opaque, char *err, int errlen);

/** Wie dna_segment; zusätzlich gehen die Zeilen aller Text-Untertitelspuren im gelesenen Bereich an cue (opaque wie write). */
int dna_segment_cues(DnaRemux *r, double start, double end, int audio, int transcode, DnaWrite write, DnaCue cue, void *opaque,
                     char *err, int errlen);

/** Fester Versatz der Zeitstempel in den Stücken (Sekunden), damit nie negative Werte entstehen. */
#define DNA_PTS_OFFSET 10.0

#endif
