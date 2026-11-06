#!/bin/bash
# Divisi's motion: every shape repeats, moves and doesn't zipper, and a
# synced shape follows the song. See tools/divisi_motion_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/divisi_motion_test.cpp" "$LIB" \
    -o "$DIR/divisi_motion_test" || exit 1
"$DIR/divisi_motion_test" "$@"
