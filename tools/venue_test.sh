#!/bin/bash
# Tests Venue and its convolver. See tools/venue_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

# The effect and the convolver are compiled here, sanitised, ahead of the
# archive, so theirs aren't used.
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/venue_test.cpp" "$CPP/engine/effect/Venue.cpp" "$CPP/engine/dsp/Convolver.cpp" \
    "$LIB" -o "$DIR/venue_test" || exit 1
"$DIR/venue_test"
