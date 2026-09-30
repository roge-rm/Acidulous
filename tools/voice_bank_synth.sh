#!/bin/bash
# A stand-in singer: a whole voice bank sung by Diction's own voice, for
# checking what was tuned on one real voice. See tools/voice_bank_synth.cpp.
#   voice_bank_synth.sh <folder> [note, 57] [formant, 4] [seed, 7]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="${1:?folder}"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$ROOT/app/src/main/cpp" "$ROOT/tools/voice_bank_synth.cpp" "$LIB" -o "$DIR/voice_bank_synth" || exit 1
mkdir -p "$OUT"
"$DIR/voice_bank_synth" "$OUT" "${2:-57}" "${3:-4}" "${4:-7}"
