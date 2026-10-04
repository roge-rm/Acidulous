#!/bin/bash
# Tongue, the jaw harp: in tune, its reeds on their chord, mouth mode and
# patterns. See tools/tongue_test.cpp; how it compares with the recordings is
# tools/tongue_reference/fit.py.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/tongue_test.cpp" "$LIB" -o "$DIR/tongue_test" || exit 1
"$DIR/tongue_test"
