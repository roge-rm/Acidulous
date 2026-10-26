#!/bin/bash
# Dice's patches play without steps, and its gate and drive. See tools/dice_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/dice_test.cpp" "$LIB" -o "$DIR/dice_test" || exit 1
ACIDULOUS_ROOT="$ROOT" "$DIR/dice_test" "$@"
