#!/bin/bash
# Sings phrases from written-out sounds into WAVs, for listening. See
# tools/diction_words.cpp.
#   diction_words.sh [folder] [formant]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="${1:-/srv/downloads/temp/debug/acidulous/audition/Diction-words}"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/diction_words.cpp" "$LIB" -o "$DIR/diction_words" || exit 1
mkdir -p "$OUT"
"$DIR/diction_words" "$OUT" "${2:-0}"
