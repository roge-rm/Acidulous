#!/bin/bash
# Slicing a file across Forage's pads. See tools/slice_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/slice_test.cpp" "$CPP/engine/core/Take.cpp" -o "$DIR/slice_test" || exit 1
"$DIR/slice_test"
