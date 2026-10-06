#!/bin/bash
# Tests Swell. See tools/swell_test.cpp.
#
# Rendered, since how far a sound comes up and whether a peak gets past the
# look-ahead don't show in a frequency response.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/swell_test.cpp" "$LIB" -o "$DIR/swell_test" || exit 1
"$DIR/swell_test"
