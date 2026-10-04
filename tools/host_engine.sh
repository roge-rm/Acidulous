#!/bin/bash
# Compiles the engine's leaf sources for the host once and keeps them.
#
# reset_test, mpe_test, bank_test and audition all need the same hundred-odd
# files (every machine, every effect, the dsp and some of core), which take
# about a minute to compile. They're built into one archive here that each
# harness links, so only the first run pays for it.
#
# Prints the path to the archive on stdout; build noise goes to stderr.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
# HOST_ENGINE_FLAGS builds a variant (denormal_probe.sh's, with the web build's
# guards) into its own folder, HOST_ENGINE_OUT.
# Otherwise it builds under acidulous.buildRoot if set (see the root
# build.gradle.kts), and uses ccache where there is one.
BUILD_ROOT=$(sed -n 's/^acidulous\.buildRoot=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | tail -1)
OUT="${HOST_ENGINE_OUT:-${BUILD_ROOT:+$BUILD_ROOT/$(basename "$ROOT")/host-engine}}"
OUT="${OUT:-$ROOT/build/host-engine}"
[ -d /usr/lib/ccache ] && export PATH="/usr/lib/ccache:$PATH"
LIB="$OUT/libacidulous-engine.a"
mkdir -p "$OUT"

SRC=$(find "$CPP/engine/machine" "$CPP/engine/dsp" "$CPP/engine/effect" -name '*.cpp')
# Molt's analyser, the onsets Dice and Pollen read, and our own file writing.
SRC="$SRC $CPP/engine/core/Utterance.cpp $CPP/engine/core/Take.cpp $CPP/engine/core/SampleEdit.cpp"
SRC="$SRC $CPP/engine/core/Tuner.cpp"
# The modifiers and the rack that runs them, for inputmod_test.
SRC="$SRC $(find "$CPP/engine/inputmod" -name '*.cpp') $CPP/engine/rack/Rack.cpp"
# The Engine itself, for render_test. The sequencer is all headers and
# LinkFollower.h only needs Constants and Timebase, so no Link comes with it.
SRC="$SRC $CPP/engine/rack/Engine.cpp $CPP/engine/rack/TrackPool.cpp $CPP/engine/rack/MasterBus.cpp $CPP/engine/core/Capture.cpp"
SRC="$SRC $CPP/engine/core/ReelCache.cpp $CPP/engine/format/WavStream.cpp"
SRC="$SRC $CPP/engine/format/WavWriter.cpp $CPP/engine/format/WavReader.cpp"
SRC="$SRC $CPP/engine/format/Decoded.cpp $CPP/engine/format/AiffReader.cpp $CPP/engine/format/FlacReader.cpp"
SRC="$SRC $CPP/engine/format/AudioDecoder.cpp"
# Not AudioSink.cpp: it includes all four writers and would pull in LAME,
# which only sink_test needs.

# If any header is newer than the archive, rebuild everything. Coarse, but it
# avoids stale object files without tracking dependencies.
newest_header=$(find "$CPP" -name '*.h' -newer "$LIB" 2>/dev/null | head -1)
if [ -n "$newest_header" ]; then
    rm -f "$OUT"/*.o "$LIB"
fi

objs=""
built=0
for src in $SRC; do
    obj="$OUT/$(echo "${src#$CPP/}" | tr '/' '_' | sed 's/\.cpp$/.o/')"
    objs="$objs $obj"
    if [ ! -f "$obj" ] || [ "$src" -nt "$obj" ]; then
        g++ -O2 -std=c++17 ${HOST_ENGINE_FLAGS:-} -I "$CPP" -c "$src" -o "$obj" >&2 || exit 1
        built=$((built + 1))
    fi
done
if [ "$built" -gt 0 ] || [ ! -f "$LIB" ]; then
    echo "  host engine: compiled $built translation units" >&2
    ar rcs "$LIB" $objs || exit 1
fi
echo "$LIB"
