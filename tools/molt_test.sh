#!/bin/bash
# Molt, proved against a voice nobody has to sing. See tools/molt_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/molt_test.cpp" "$LIB" -o "$DIR/molt_test" || exit 1
"$DIR/molt_test"
