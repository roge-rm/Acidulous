#!/bin/bash
# Renders Tongue to a WAV file. See tools/tongue_render.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
BIN="$ROOT/build/tongue-render"
mkdir -p "$BIN"
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/tongue_render.cpp" "$LIB" -o "$BIN/tongue_render" || exit 1
"$BIN/tongue_render" "$@"
