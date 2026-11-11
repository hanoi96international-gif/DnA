/*
 * Ton-Decoder der DNA Engine (siehe dnaaudio.c): Pakete aus dem Container → PCM 16 Bit verschachtelt.
 */
#ifndef DNAAUDIO_H
#define DNAAUDIO_H

#include <stdint.h>

typedef struct DnaAudio DnaAudio;

/** codec: FFmpeg-Decodername (dca, truehd, ac3, eac3, mp2, mp3, flac, opus, aac). extra: Decoder-Daten oder NULL. */
DnaAudio *dna_audio_open(const char *codec, int sample_rate, int channels, const uint8_t *extra, int extra_len);

/** Ein Paket dekodieren; Rückgabe: Bytes PCM in dna_audio_output (0 = noch nichts, < 0 = Fehler). */
int dna_audio_decode(DnaAudio *a, const uint8_t *data, int len);

const uint8_t *dna_audio_output(DnaAudio *a);
/** Tonformat der Ausgabe (nach dem ersten dekodierten Rahmen). */
int dna_audio_rate(DnaAudio *a);
int dna_audio_channels(DnaAudio *a);

void dna_audio_close(DnaAudio *a);

#endif
