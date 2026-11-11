# FFmpeg-Auswahl für das Umverpacken (libdnaremux): nur Container lesen, MPEG-TS schreiben, Ton für Fernseher in AC3
# umrechnen. Gemeinsam für den Android-Bau (ci/build-remux.sh) und den Test auf dem Rechner (ci/test-remux.sh).
# Ohne --enable-small: der eigene HEVC-Decoder (4K-Rückfall auf dem Handy) ist so spürbar schneller (CABAC, -O statt -Os).
REMUX_FFMPEG_CONFIG=(
  --disable-everything --disable-programs --disable-doc --disable-network --disable-avdevice --disable-avfilter
  --disable-postproc --disable-autodetect --enable-static --disable-shared --enable-pic
  --enable-swresample --enable-swscale
  --enable-demuxer=matroska,mov,avi,mpegts,flv
  --enable-muxer=mpegts,adts
  --enable-decoder=dca,truehd,mlp,flac,opus,vorbis,mp3,mp2,aac,ac3,eac3,pcm_s16le,pcm_s24le,pcm_s16be,hevc,h264
  --enable-encoder=ac3
  --enable-parser=h264,hevc,aac,ac3,dca,mpegaudio,mpeg4video,mlp,flac,opus,vorbis
  --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc
)
