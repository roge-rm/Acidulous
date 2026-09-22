#!/bin/bash
# Does every modulation source actually reach the sound? See tools/modsource_test.cpp.
#
#   tools/modsource_test.sh [Machine]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/modsource_test.cpp" "$LIB" -o "$DIR/modsource_test" || exit 1
ACIDULOUS_ROOT="$ROOT" "$DIR/modsource_test" "$@"
