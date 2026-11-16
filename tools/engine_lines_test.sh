#!/bin/bash
# The engine lines reader on crafted lines (engine_lines_test.cpp).
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/tools/build_dir.sh"
CPP="$ROOT/app/src/main/cpp"
OUT="$BUILD/engine-replay"
mkdir -p "$OUT"
g++ -O1 -std=c++17 -Wall -I "$CPP/platform/web" -I "$CPP" "$ROOT/tools/engine_lines_test.cpp" \
    "$CPP/platform/lines/EngineLines.cpp" -o "$OUT/engine_lines_test" || exit 1
"$OUT/engine_lines_test"
