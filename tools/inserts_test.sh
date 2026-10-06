#!/bin/bash
# Tests Rotary, Grain and Resonator. See tools/inserts_test.cpp.
#
# Rendered, since a speaker turning, a cloud holding on and a string ringing
# in key don't show in a frequency response.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/inserts_test.cpp" "$LIB" -o "$DIR/inserts_test" || exit 1
"$DIR/inserts_test"
