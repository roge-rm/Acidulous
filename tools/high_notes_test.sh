#!/bin/bash
# Plays every machine at the top of the keyboard and checks it falls silent. See tools/high_notes_test.cpp.
#
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/high_notes_test.cpp" "$LIB" -o "$DIR/high_notes_test" || exit 1
"$DIR/high_notes_test"
