#!/bin/bash
# An insert's mod slot in the whole Engine: every source moves its target,
# renders repeat, the key is a block late, a scene alone moves as in the song,
# and a fast mod on any knob glides across the block. See tools/insert_mod_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/insert_mod_test.cpp" "$LIB" \
    -o "$DIR/insert_mod_test" || exit 1
"$DIR/insert_mod_test" "$@"
