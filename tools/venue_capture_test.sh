#!/bin/bash
# Tests capturing a room: sweep, a made-up room, deconvolve. See
# tools/venue_capture_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

# RoomCapture is compiled here, sanitised, ahead of the archive.
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/venue_capture_test.cpp" "$CPP/engine/dsp/RoomCapture.cpp" \
    "$LIB" -o "$DIR/venue_capture_test" || exit 1
"$DIR/venue_capture_test"
