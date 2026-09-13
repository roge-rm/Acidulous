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
LIB=$("$ROOT/tools/host_engine.sh") || exit 1

# Relinked only when something changed: the inner loop here is edit a bank
# file and listen, and it must not pay for a compile.
if [ ! -x "$BIN/audition" ] || [ "$ROOT/tools/audition.cpp" -nt "$BIN/audition" ] ||
   [ "$LIB" -nt "$BIN/audition" ] ||
   [ -n "$(find "$ROOT/tools" -name '*.h' -newer "$BIN/audition" 2>/dev/null | head -1)" ]; then
    g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/audition.cpp" "$LIB" -o "$BIN/audition" || exit 1
fi

ACIDULOUS_ROOT="$ROOT" "$BIN/audition" "$@"
