#!/bin/bash
# The engine's leaf sources, compiled for the host once and kept.
#
# reset_test, mpe_test, bank_test and audition all want the same hundred-odd
# translation units - every machine, every effect, the dsp and a little of
# core - and compiling them takes the best part of a minute. Compiling them
# four times in one run of all_tests.sh took four. So they are built into an
# archive here, and each harness links that instead: the first run pays once
# and every run after it pays nothing at all.
#
# Prints the path to the archive on stdout; build noise goes to stderr.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/build/host-engine"
LIB="$OUT/libacidulous-engine.a"
mkdir -p "$OUT"

SRC=$(find "$CPP/engine/machine" "$CPP/engine/dsp" "$CPP/engine/effect" -name '*.cpp')
# Molt's analyser, the onsets Dice and Pollen read, and our own file writing.
SRC="$SRC $CPP/engine/core/Utterance.cpp $CPP/engine/core/Take.cpp"
SRC="$SRC $CPP/engine/core/WavWriter.cpp $CPP/engine/core/WavReader.cpp"
# Deliberately not AudioSink.cpp: it includes all four writers and would pull
# in host LAME, which only sink_test has any use for.

# A header nobody tracks is a stale object file that fails in a way nobody can
# read, so any header newer than the archive rebuilds everything. Coarse, and
# right - the alternative is a dependency graph for a build that takes a
# minute from cold.
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
        g++ -O2 -std=c++17 -I "$CPP" -c "$src" -o "$obj" >&2 || exit 1
        built=$((built + 1))
    fi
done
if [ "$built" -gt 0 ] || [ ! -f "$LIB" ]; then
    echo "  host engine: compiled $built translation units" >&2
    ar rcs "$LIB" $objs || exit 1
fi
echo "$LIB"
