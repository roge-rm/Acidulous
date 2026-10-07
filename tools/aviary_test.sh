#!/bin/bash
# Aviary, birdsong: whistle, chirps, the syrinx, calls.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/aviary_test.cpp" "$LIB" -o "$DIR/aviary_test" || exit 1
"$DIR/aviary_test"
