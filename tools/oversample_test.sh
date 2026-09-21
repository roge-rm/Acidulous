#!/bin/bash
# Oversampling: what it costs and what it buys. See tools/oversample_test.cpp.
#
# Header-only and dependency-free: `dsp::Wsola` asks the material nothing, so
# the harness needs neither the engine archive nor a file on disk.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/oversample_test.cpp" -o "$DIR/oversample_test" || exit 1
"$DIR/oversample_test"
