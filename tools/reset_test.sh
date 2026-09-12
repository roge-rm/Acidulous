#!/bin/bash
# Render every machine, panic it, render the same performance again, and
# require the two to be identical bit for bit. See tools/reset_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

SRC=$(find "$CPP/engine/machine" "$CPP/engine/dsp" "$CPP/engine/effect" -name "*.cpp")

g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/reset_test.cpp" $SRC -o "$DIR/reset_test" || exit 1
"$DIR/reset_test"
