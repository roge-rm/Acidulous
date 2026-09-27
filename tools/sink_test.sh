#!/bin/bash
# Writes each format with our writers, reads it back with our readers and
# compares every sample. WAV, AIFF and FLAC must match exactly. MP3 is lossy,
# so it's checked for the right length and level instead.
#
# The FLAC MD5 check below catches bugs shared by the encoder and decoder.
set -u
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
LAME="$CPP/third_party/lame"

# LAME for the host, built once and kept. It's rebuilt when any of its
# sources is newer than the archive.
CACHE="$ROOT/build/lame-host"
ARCHIVE="$CACHE/libmp3lame.a"
mkdir -p "$CACHE"
if [ -z "$(find "$LAME" -name '*.[ch]' -newer "$ARCHIVE" -print -quit 2>/dev/null)" ] \
   && [ -f "$ARCHIVE" ]; then
    :
else
    echo "  .... building LAME for the host (once)"
    # mpglib as well as libmp3lame: config.h defines HAVE_MPGLIB, so
    # mpglib_interface.c calls into the decoder and it has to be linked.
    for c in "$LAME"/libmp3lame/*.c "$LAME"/mpglib/*.c; do
        gcc -O1 -w -c -DHAVE_CONFIG_H -I "$LAME" -I "$LAME/include" -I "$LAME/libmp3lame" \
            -I "$LAME/mpglib" "$c" -o "$CACHE/$(basename "$c" .c).o" || exit 1
    done
    ar rcs "$ARCHIVE" "$CACHE"/*.o || exit 1
fi

g++ -O2 -std=c++17 -I "$CPP" -I "$LAME/include" "$ROOT/tools/sink_test.cpp" \
    "$CPP/engine/format/AudioSink.cpp" "$CPP/engine/format/WavWriter.cpp" \
    "$CPP/engine/format/AiffWriter.cpp" "$CPP/engine/format/FlacWriter.cpp" \
    "$CPP/engine/format/Mp3Writer.cpp" \
    "$CPP/engine/format/AudioDecoder.cpp" "$CPP/engine/format/WavReader.cpp" \
    "$CPP/engine/format/AiffReader.cpp" "$CPP/engine/format/FlacReader.cpp" \
    "$CPP/engine/format/Mp3Reader.cpp" "$CPP/engine/format/Decoded.cpp" \
    "$ARCHIVE" -lm \
    -o "$DIR/sink_test" || exit 1
"$DIR/sink_test" "$DIR"; FAILED=$?

fail=0

# FLAC stores an MD5 of its own samples, so check the header against the raw
# samples. A zeroed MD5 would otherwise pass unnoticed.
for bits in 24 16; do
    header=$(od -An -tx1 -j26 -N16 "$DIR/flac$bits.flac" | tr -d ' \n')
    payload=$(md5sum "$DIR/flac$bits.raw" | cut -d' ' -f1)
    if [ "$header" = "$payload" ]; then
        echo "  ok   flac$bits header md5 matches its payload"
    else
        echo "  FAIL flac$bits header md5 $header, payload $payload"; fail=1
    fi
done

exit $(( fail + FAILED ))
