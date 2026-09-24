#!/bin/bash
# Dice following the song's tempo. See tools/dice_follow_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -I "$CPP" "$ROOT/tools/dice_follow_test.cpp" "$LIB" -o "$DIR/dice_follow_test" || exit 1
"$DIR/dice_follow_test"
