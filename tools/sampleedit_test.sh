#!/bin/bash
# Tests the sample editing functions. See tools/sampleedit_test.cpp.
#
# SampleEdit has its own .cpp, so it gets its own runner. It only needs that
# one file, so it doesn't link the host engine.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/sampleedit_test.cpp" "$CPP/engine/core/SampleEdit.cpp" \
    -o "$DIR/sampleedit_test" || exit 1
"$DIR/sampleedit_test"
