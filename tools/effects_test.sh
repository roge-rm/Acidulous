#!/bin/bash
# Tests the character effects. See tools/effects_test.cpp.
#
# Rendered, since what each is for (squashing, squelching, talking,
# wobbling, stuttering) doesn't show in a frequency response.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/effects_test.cpp" "$LIB" -o "$DIR/effects_test" || exit 1
"$DIR/effects_test"
