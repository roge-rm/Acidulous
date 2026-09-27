#!/bin/bash
# Tests Bias, the four-track, by reading back a reel built from a ramp. See
# tools/bias_test.cpp.
#
# Links the host engine library for the registry, but needs no Rack.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/bias_test.cpp" "$LIB" \
    -o "$DIR/bias_test" || exit 1
"$DIR/bias_test"
