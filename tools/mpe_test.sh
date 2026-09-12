#!/bin/bash
# Two notes, move one, and require the other to be unchanged bit for bit.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
SRC=$(find "$CPP/engine/machine" "$CPP/engine/dsp" -name '*.cpp')
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/mpe_test.cpp" $SRC -o "$DIR/mpe_test" || exit 1
"$DIR/mpe_test"
