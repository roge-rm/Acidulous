#!/bin/bash
# Draw, the free reeds: tuning, pressure, the attack, threshold and
# release. See tools/draw_test.cpp; how it compares with the recordings is
# tools/draw_reference/fit.py.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/draw_test.cpp" "$LIB" -o "$DIR/draw_test" || exit 1
"$DIR/draw_test"
