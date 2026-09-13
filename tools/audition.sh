#!/bin/bash
# Play a factory patch on a desk, write a wav, and print what it measures.
#
# Not a test and not in all_tests.sh: nothing here passes or fails. The
# assertions about the banks live in bank_test.sh next door.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/build/audition"
BIN="$ROOT/build/audition-bin"
mkdir -p "$OUT" "$BIN"

# Rebuilt only when something is newer than the binary. The inner loop here is
# edit a bank file and listen, and it must not pay for a compile.
newest=$(find "$ROOT/tools" "$CPP" \( -name '*.cpp' -o -name '*.h' \) -newer "$BIN/audition" 2>/dev/null | head -1)
if [ ! -x "$BIN/audition" ] || [ -n "$newest" ]; then
    SRC=$(find "$CPP/engine/machine" "$CPP/engine/dsp" "$CPP/engine/effect" -name '*.cpp')
    # Molt's analyser, Dice's and Pollen's onsets, and our own output. No
    # AudioSink.cpp: it includes all four writers and would drag in host LAME.
    SRC="$SRC $CPP/engine/core/Utterance.cpp $CPP/engine/core/Take.cpp"
    SRC="$SRC $CPP/engine/core/WavWriter.cpp $CPP/engine/core/WavReader.cpp"
    g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/audition.cpp" $SRC -o "$BIN/audition" || exit 1
fi

ACIDULOUS_ROOT="$ROOT" "$BIN/audition" "$@"
