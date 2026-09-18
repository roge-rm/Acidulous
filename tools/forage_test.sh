#!/bin/bash
# Forage's playback modes. See tools/forage_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
BIN="$ROOT/build/forage-test"
mkdir -p "$BIN"
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -I "$CPP" "$ROOT/tools/forage_test.cpp" "$LIB" -o "$BIN/forage_test" || exit 1
"$BIN/forage_test"
