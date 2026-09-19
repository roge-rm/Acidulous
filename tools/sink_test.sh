#!/bin/bash
# Write each format with ours, read it back with ours, compare every sample.
# Lossless is the one claim that can be settled rather than argued about, and
# three of these formats make it. MP3 does not, so it is asked instead for the
# right length at the right level.
#
# **It used to decode with ffmpeg.** That was the right answer while the app
# could write four formats and read one: something else had to be the
# authority. M49 ended that, and M49's own row said this loop should close
# inside the repository once it had - so it has. The harness no longer needs a
# program the machine may not have, and it checks twice as much, because the
# decoder is now under test beside the encoder. What a shared bug could hide,
# the FLAC md5 below still catches: the file carries a checksum of its own
# payload, computed by the encoder and verified here by md5sum, which is
# nobody's opinion.
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
    "$CPP/engine/format/Mp3Writer.cpp" \
    "$CPP/engine/format/AudioDecoder.cpp" "$CPP/engine/format/WavReader.cpp" \
    "$CPP/engine/format/AiffReader.cpp" "$CPP/engine/format/FlacReader.cpp" \
    "$CPP/engine/format/Mp3Reader.cpp" "$CPP/engine/format/Decoded.cpp" \
    "$ARCHIVE" -lm \
    -o "$DIR/sink_test" || exit 1
"$DIR/sink_test" "$DIR"; FAILED=$?

fail=0

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
