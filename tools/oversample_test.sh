#!/bin/bash
# Measures what oversampling costs and how much aliasing it removes.
# See tools/oversample_test.cpp. Header-only, so no engine library is needed.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/oversample_test.cpp" -o "$DIR/oversample_test" || exit 1
"$DIR/oversample_test"
