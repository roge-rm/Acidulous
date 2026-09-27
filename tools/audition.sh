#!/bin/bash
# Plays a factory patch, writes a wav, and prints what it measures.
#
# Not a test, so it's not in all_tests.sh. The bank checks are in
# bank_test.sh.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
BIN="$ROOT/build/audition-bin"

# Where the wavs go. build/ by default, but tools/local.env (untracked) can
# point them somewhere else, e.g. a folder you can download from:
#
#   ACIDULOUS_AUDITION_OUT=~/audition
#
# --out on the command line still wins over this.
OUT="$ROOT/build/audition"
# The environment variable wins over the file.
FROM_ENV="${ACIDULOUS_AUDITION_OUT:-}"
[ -z "$FROM_ENV" ] && [ -f "$ROOT/tools/local.env" ] && . "$ROOT/tools/local.env"
[ -n "${ACIDULOUS_AUDITION_OUT:-}" ] && OUT="$ACIDULOUS_AUDITION_OUT"
if ! mkdir -p "$OUT" 2>/dev/null; then
    echo "audition: cannot write $OUT, falling back to build/audition" >&2
    OUT="$ROOT/build/audition"
fi
mkdir -p "$OUT" "$BIN"
LIB=$("$ROOT/tools/host_engine.sh") || exit 1

# Only rebuilds when something changed, so editing a bank file and listening
# again doesn't wait for a compile.
if [ ! -x "$BIN/audition" ] || [ "$ROOT/tools/audition.cpp" -nt "$BIN/audition" ] ||
   [ "$LIB" -nt "$BIN/audition" ] ||
   [ -n "$(find "$ROOT/tools" -name '*.h' -newer "$BIN/audition" 2>/dev/null | head -1)" ]; then
    g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/audition.cpp" "$LIB" -o "$BIN/audition" || exit 1
fi

# A real recording for the input bus, for machines that use one.
# tools/local.env can point at a file outside the repo:
#
#   ACIDULOUS_INPUT_FILE=/home/you/acidulous-material/voice.wav
#
# Cipher is levelled against a real voice, since synthetic speech is missing
# real consonants. Without the file the harness uses `speechPhrase()`, so the
# exact numbers depend on which one was used.
export ACIDULOUS_INPUT_FILE="${ACIDULOUS_INPUT_FILE:-}"

# Only add --out if the caller didn't pass one.
want_out=1
for a in "$@"; do [ "$a" = "--out" ] && want_out=0; done
if [ "$want_out" = 1 ]; then
    ACIDULOUS_ROOT="$ROOT" "$BIN/audition" "$@" --out "$OUT"
else
    ACIDULOUS_ROOT="$ROOT" "$BIN/audition" "$@"
fi
