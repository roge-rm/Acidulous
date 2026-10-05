#!/bin/bash
# Works out Draw's harmonica tables: tuning, bends and overblows. See tools/draw_harp.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/draw_harp.cpp" -o "$DIR/draw_harp" || exit 1
"$DIR/draw_harp" "$CPP/engine/machine/draw/DrawHarp.h"
