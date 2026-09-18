#!/bin/bash
# Write each format with ours, read it back with ffmpeg, compare every byte.
# Lossless is the one claim that can be settled rather than argued about, and
# three of these formats make it. MP3 does not, so it is asked instead for the
# right length at the right level - see the bottom of this script.
set -u
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
LAME="$CPP/third_party/lame"

# LAME on the host, built once and kept: twenty files of somebody else's C is
# half a minute, and this harness runs after every change to a sink. The
# archive is rebuilt when any of its sources is newer than it is.
CACHE="$ROOT/build/lame-host"
ARCHIVE="$CACHE/libmp3lame.a"
mkdir -p "$CACHE"
if [ -z "$(find "$LAME" -name '*.[ch]' -newer "$ARCHIVE" -print -quit 2>/dev/null)" ] \
   && [ -f "$ARCHIVE" ]; then
    :
else
    echo "  .... building LAME for the host (once)"
    # mpglib as well as libmp3lame: config.h defines HAVE_MPGLIB now, so
    # mpglib_interface.c calls into the decoder rather than compiling to
    # nothing, and the archive has to carry it or nothing links.
    for c in "$LAME"/libmp3lame/*.c "$LAME"/mpglib/*.c; do
        gcc -O1 -w -c -DHAVE_CONFIG_H -I "$LAME" -I "$LAME/include" -I "$LAME/libmp3lame" \
            -I "$LAME/mpglib" "$c" -o "$CACHE/$(basename "$c" .c).o" || exit 1
    done
    ar rcs "$ARCHIVE" "$CACHE"/*.o || exit 1
fi

g++ -O2 -std=c++17 -I "$CPP" -I "$LAME/include" "$ROOT/tools/sink_test.cpp" \
    "$CPP/engine/format/AudioSink.cpp" "$CPP/engine/format/WavWriter.cpp" \
    "$CPP/engine/format/AiffWriter.cpp" "$CPP/engine/format/FlacWriter.cpp" \
    "$CPP/engine/format/Mp3Writer.cpp" "$ARCHIVE" -lm \
    -o "$DIR/sink_test" || exit 1
"$DIR/sink_test" "$DIR"; FAILED=$?

fail=0
for name in wav24 wav16 aiff24 aiff16 flac24 flac16 wav32 aiff32; do
    case $name in wav*) ext=.wav;; aiff*) ext=.aiff;; flac*) ext=.flac;; esac
    case $name in *32) fmt=f32le;; *24) fmt=s24le;; *16) fmt=s16le;; esac
    if ! ffmpeg -v error -i "$DIR/$name$ext" -f "$fmt" -c:a "pcm_$fmt" "$DIR/$name.decoded" </dev/null; then
        echo "  FAIL ffmpeg could not decode $name"; fail=1; continue
    fi
    if cmp -s "$DIR/$name.decoded" "$DIR/$name.raw"; then
        echo "  ok   $name decodes back byte for byte"
    else
        echo "  FAIL $name differs from the source PCM"
        cmp "$DIR/$name.decoded" "$DIR/$name.raw" | head -2; fail=1
    fi
done

# FLAC carries an MD5 of its own payload, so the header can be checked
# against the samples it claims to describe - a zeroed one would let the
# comparison above pass while saying nothing.
for bits in 24 16; do
    header=$(od -An -tx1 -j26 -N16 "$DIR/flac$bits.flac" | tr -d ' \n')
    payload=$(md5sum "$DIR/flac$bits.raw" | cut -d' ' -f1)
    if [ "$header" = "$payload" ]; then
        echo "  ok   flac$bits header md5 matches its payload"
    else
        echo "  FAIL flac$bits header md5 $header, payload $payload"; fail=1
    fi
done

# MP3, which cannot be compared byte for byte. What can be settled is that it
# is a real MP3 at the sample rate we asked for, that the Xing header the
# writer adds gets the length right to within a frame (1152), and that what
# comes back is at the level that went in - a channel dropped, a gain wrong by
# a factor of two or samples handed to the wrong LAME entry point would all
# show here and none of them would show in a file size.
frames=$(( 4096 * 5 + 1234 ))

level() {  # mean volume in dB of a decoded stream, through ffmpeg's own meter
    ffmpeg -hide_banner -nostats -v info "$@" -af volumedetect -f null - 2>&1 \
        | sed -n 's/.*mean_volume: \([-0-9.]*\) dB.*/\1/p'
}

loud=$(level -f f32le -ar 48000 -ac 2 -i "$DIR/mp3256.raw")
for name in mp3256 mp3128; do
    info=$(ffprobe -v error -select_streams a:0 \
           -show_entries stream=codec_name,sample_rate,channels \
           -of default=nw=1:nk=1 "$DIR/$name.mp3" 2>/dev/null | tr '\n' ' ')
    if [ "$info" != "mp3 48000 2 " ]; then
        echo "  FAIL $name is not 48 kHz stereo mp3 ($info)"; fail=1; continue
    fi
    echo "  ok   $name is 48 kHz stereo mp3"
    # Decoded rather than asked: a length is only as good as the Xing header,
    # and that header is the part of the writer most likely to be wrong.
    ffmpeg -v error -i "$DIR/$name.mp3" -f f32le "$DIR/$name.decoded" </dev/null
    got=$(( $(stat -c %s "$DIR/$name.decoded") / 8 ))
    if [ "$got" -gt $(( frames - 1152 )) ] && [ "$got" -lt $(( frames + 1152 )) ]; then
        echo "  ok   $name decodes to $got samples, wanted $frames"
    else
        echo "  FAIL $name decodes to $got samples, wanted $frames"; fail=1
    fi
    # A decibel and a half either way. The fifth of this signal that is
    # noise loses its top octave at 128 kbit, which is the encoder working,
    # not failing; a dropped channel or a wrong scale is decibels out, not
    # fractions of one.
    back=$(level -i "$DIR/$name.mp3")
    if [ -n "$loud" ] && [ -n "$back" ] &&
       awk -v a="$loud" -v b="$back" 'BEGIN { exit !((a - b) ^ 2 < 2.25) }'; then
        echo "  ok   $name comes back at $back dB, in at $loud dB"
    else
        echo "  FAIL $name comes back at ${back:-no} dB, in at ${loud:-no} dB"; fail=1
    fi
done
exit $(( fail + FAILED ))
