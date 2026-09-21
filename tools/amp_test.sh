#!/bin/bash
# The amp and its cabinet, measured rather than rendered. See tools/amp_test.cpp.
#
# Header-only and dependency-free: `dsp::Wsola` asks the material nothing, so
# the harness needs neither the engine archive nor a file on disk.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/amp_test.cpp" "$LIB" -o "$DIR/amp_test" || exit 1
"$DIR/amp_test"
