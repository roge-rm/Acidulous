#!/bin/bash
# Tests the input modifiers on live notes and clip notes. See tools/inputmod_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/inputmod_test.cpp" "$LIB" -o "$DIR/inputmod_test" || exit 1
"$DIR/inputmod_test"
