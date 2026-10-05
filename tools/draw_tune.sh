#!/bin/bash
# Works out Draw's reed tuning table. See tools/draw_tune.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/draw_tune.cpp" -o "$DIR/draw_tune" || exit 1
"$DIR/draw_tune" "$CPP/engine/machine/draw/DrawTuning.h"
