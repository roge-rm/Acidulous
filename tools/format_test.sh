#!/bin/bash
# Round trip: write each format with ours, read it back with ours.
#
# sink_test proves the *writers* against somebody else's decoder (ffmpeg).
# This proves the readers against our own writers, which is the other half and
# the one that needs no tools installed - the whole loop is inside the tree.
set -u
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
LAME="$CPP/third_party/lame"
CACHE="$ROOT/build/lame-host"
ARCHIVE="$CACHE/libmp3lame.a"
mkdir -p "$CACHE"
if [ -n "$(find "$LAME" -name '*.[ch]' -newer "$ARCHIVE" -print -quit 2>/dev/null)" ] || [ ! -f "$ARCHIVE" ]; then
    echo "  .... building LAME for the host (once)"
    for c in "$LAME"/libmp3lame/*.c "$LAME"/mpglib/*.c; do
        gcc -O1 -w -c -DHAVE_CONFIG_H -I "$LAME" -I "$LAME/include" -I "$LAME/libmp3lame" \
            -I "$LAME/mpglib" "$c" -o "$CACHE/$(basename "$c" .c).o" || exit 1
    done
    ar rcs "$ARCHIVE" "$CACHE"/*.o || exit 1
fi

g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" -I "$LAME/include" "$ROOT/tools/format_test.cpp" \
    "$CPP/engine/format/AudioSink.cpp" "$CPP/engine/format/WavWriter.cpp" \
    "$CPP/engine/format/AiffWriter.cpp" "$CPP/engine/format/FlacWriter.cpp" \
    "$CPP/engine/format/Mp3Writer.cpp" \
    "$CPP/engine/format/Decoded.cpp" "$CPP/engine/format/WavReader.cpp" \
    "$CPP/engine/format/WavStream.cpp" \
    "$CPP/engine/format/AiffReader.cpp" "$CPP/engine/format/FlacReader.cpp" \
    "$CPP/engine/format/Mp3Reader.cpp" "$CPP/engine/format/AudioDecoder.cpp" \
    "$ARCHIVE" -lm -o "$DIR/format_test" || exit 1
"$DIR/format_test" "$DIR"
