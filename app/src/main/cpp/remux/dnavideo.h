/*
 * Eigener Software-Video-Decoder der DNA Engine (FFmpeg HEVC/H.264, alle Kerne) – wie VLC, wenn kein Decoder des
 * Geräts ein Bild liefert (etwa 4K HDR10 auf MediaTek). Ausgabe: 8-Bit-YUV 4:2:0 in drei Ebenen, auf höchstens
 * max_w × max_h verkleinert; angezeigt wird es von der Engine über die GPU (Farbumrechnung, HDR → SDR).
 */
#ifndef DNAVIDEO_H
#define DNAVIDEO_H

#include <stdint.h>

typedef struct DnaVideo DnaVideo;

#define DNA_VIDEO_SEND_OK 0
#define DNA_VIDEO_SEND_FULL 1
#define DNA_VIDEO_SEND_ERROR (-1)

/** codec: "hevc" oder "h264". extra: Parametersätze (Annex B) oder NULL. threads: Decoder-Threads (0 = alle Kerne). */
DnaVideo *dna_video_open(const char *codec, const uint8_t *extra, int extra_len, int max_w, int max_h, int threads);

/**
 * Eine Zugriffseinheit (Annex B) mit Zeitstempel hinein. DNA_VIDEO_SEND_FULL: erst Bilder mit dna_video_receive
 * abholen, dann dieselbe Einheit noch einmal. Kaputte Einheiten werden ausgelassen (DNA_VIDEO_SEND_OK).
 */
int dna_video_send(DnaVideo *v, const uint8_t *data, int len, int64_t pts);

/** Fertiges Bild bereit? 1 = ja (Zeitstempel in *pts, das Bild wird gehalten), 0 = nein. */
int dna_video_receive(DnaVideo *v, int64_t *pts);

/**
 * Gehaltenes Bild umrechnen in y/u/v (dicht gepackt, Breite = Zeilenlänge). info[0..1]: Ausgabegröße, info[2]:
 * Farbraum (AVColorSpace), info[3]: Übertragung (AVColorTransferCharacteristic), info[4]: 1 = voller Wertebereich.
 * Rückgabe: 1 = umgerechnet (Bild freigegeben), 0 = Ebenen zu klein (info nennt die nötige Größe), -1 = Fehler.
 */
int dna_video_convert(DnaVideo *v, uint8_t *y, int y_cap, uint8_t *u, uint8_t *vv, int uv_cap, int32_t *info);

/** Gehaltenes Bild verwerfen (zu spät). */
void dna_video_drop(DnaVideo *v);

/**
 * Nicht hinterherkommen: 0 = alles, 1 = ohne Entblockungsfilter bei Bildern ohne Bezug, 2 = ganz ohne
 * Entblockungsfilter, 3 = zusätzlich Bilder ohne Bezug auslassen, DNA_VIDEO_SKIP_SEEK = bis zum Sprungziel.
 */
#define DNA_VIDEO_SKIP_SEEK 9
void dna_video_hurry(DnaVideo *v, int level);

void dna_video_flush(DnaVideo *v);
int dna_video_width(DnaVideo *v);
int dna_video_height(DnaVideo *v);
void dna_video_close(DnaVideo *v);

#endif
