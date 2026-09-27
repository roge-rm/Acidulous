#!/bin/bash
# Tests the gate. See tools/gate_test.cpp.
#
# The gate is rendered because chattering on a signal near the threshold, and
# opening on room noise instead of a note, don't show in a frequency response.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/gate_test.cpp" "$LIB" -o "$DIR/gate_test" || exit 1
"$DIR/gate_test"
