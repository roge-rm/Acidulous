#!/bin/bash
# Fret, electric guitars and basses: tuning, pickups, the hands, the amp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/fret_test.cpp" "$LIB" -o "$DIR/fret_test" || exit 1
"$DIR/fret_test"
