#!/bin/bash
# Renders Draw to a WAV file. See tools/draw_render.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
BIN="$ROOT/build/draw-render"
mkdir -p "$BIN"
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/draw_render.cpp" "$LIB" -o "$BIN/draw_render" || exit 1
"$BIN/draw_render" "$@"
