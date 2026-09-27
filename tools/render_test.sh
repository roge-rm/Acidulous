#!/bin/bash
# Renders a whole song on the desktop and checks it repeats exactly and that a
# render ignores the quality setting. See tools/render_test.cpp.
#
# Needs Engine.cpp and MasterBus.cpp in the host engine archive.
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
