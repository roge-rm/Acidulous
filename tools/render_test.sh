#!/bin/bash
# A whole song rendered off a phone: does it repeat, and does a render ignore
# the quality setting. See tools/render_test.cpp.
#
# The first harness here that runs the Engine rather than a piece of it, which
# is why it needs `engine/rack/Engine.cpp` and `MasterBus.cpp` in the archive.
# Sanitised like the rest: these questions are about what the audio *is*, and
# a render that is fast and wrong is no use.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/render_test.cpp" "$LIB" \
    -o "$DIR/render_test" || exit 1
"$DIR/render_test"
