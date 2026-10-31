#!/bin/bash
# Plays phrases through a Mouth with its words up into WAVs, for listening.
# See tools/mouth_words.cpp.
#   mouth_words.sh [folder] [tongue]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="${1:-/srv/downloads/temp/debug/acidulous/audition/mouth-words}"
DIR=$(mktemp -d)
trap 'rm -rf "${DIR:?}"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/mouth_words.cpp" "$LIB" -o "$DIR/mouth_words" || exit 1
mkdir -p "$OUT"
"$DIR/mouth_words" "$OUT" "${2:-}"
