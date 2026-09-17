#!/bin/bash
# Play a factory patch on a desk, write a wav, and print what it measures.
#
# Not a test and not in all_tests.sh: nothing here passes or fails. The
# assertions about the banks live in bank_test.sh next door.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
BIN="$ROOT/build/audition-bin"

# Where the wavs land. Under build/ by default, because that is the only
# place this repo may assume exists - but the person listening to them is
# usually not at this machine, so tools/local.env (untracked, and the only
# place a path outside the repo belongs) can point them somewhere fetchable.
#
#   ACIDULOUS_AUDITION_OUT=/srv/downloads/temp/debug/audition
#
# A --out on the command line still wins; this only supplies the default.
OUT="$ROOT/build/audition"
# The environment beats the file, so one run can be sent elsewhere without
# editing anything.
FROM_ENV="${ACIDULOUS_AUDITION_OUT:-}"
[ -z "$FROM_ENV" ] && [ -f "$ROOT/tools/local.env" ] && . "$ROOT/tools/local.env"
[ -n "${ACIDULOUS_AUDITION_OUT:-}" ] && OUT="$ACIDULOUS_AUDITION_OUT"
if ! mkdir -p "$OUT" 2>/dev/null; then
    echo "audition: cannot write $OUT, falling back to build/audition" >&2
    OUT="$ROOT/build/audition"
fi
mkdir -p "$OUT" "$BIN"
LIB=$("$ROOT/tools/host_engine.sh") || exit 1

# Relinked only when something changed: the inner loop here is edit a bank
# file and listen, and it must not pay for a compile.
if [ ! -x "$BIN/audition" ] || [ "$ROOT/tools/audition.cpp" -nt "$BIN/audition" ] ||
   [ "$LIB" -nt "$BIN/audition" ] ||
   [ -n "$(find "$ROOT/tools" -name '*.h' -newer "$BIN/audition" 2>/dev/null | head -1)" ]; then
    g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/audition.cpp" "$LIB" -o "$BIN/audition" || exit 1
fi

# A real recording on the input bus, for the machines that want one.
#
# tools/local.env can name a file the repository does not contain:
#
#   ACIDULOUS_INPUT_FILE=/home/you/acidulous-material/voice.wav
#
# Cipher is levelled against a real voice, because a vocoder voiced on
# synthetic speech is voiced on the wrong thing - but a repository is the wrong
# place to keep somebody's voice, so the file stays outside it and the harness
# falls back to `speechPhrase()` when there is none. What that costs is
# reproducibility of the exact numbers; what it buys is a bank levelled against
# speech that has real consonants in it.
export ACIDULOUS_INPUT_FILE="${ACIDULOUS_INPUT_FILE:-}"

# The tool's own --out wins, so only supply one when the caller did not.
want_out=1
for a in "$@"; do [ "$a" = "--out" ] && want_out=0; done
if [ "$want_out" = 1 ]; then
    ACIDULOUS_ROOT="$ROOT" "$BIN/audition" "$@" --out "$OUT"
else
    ACIDULOUS_ROOT="$ROOT" "$BIN/audition" "$@"
fi
