#!/bin/bash
# Write each format with ours, read it back with ffmpeg, compare every byte.
# Lossless is the one claim that can be settled rather than argued about, and
# all three of these formats make it.
set -u
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"

g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/sink_test.cpp" \
    "$CPP/engine/core/AudioSink.cpp" "$CPP/engine/core/WavWriter.cpp" \
    "$CPP/engine/core/AiffWriter.cpp" "$CPP/engine/core/FlacWriter.cpp" \
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
exit $(( fail + FAILED ))
