#!/bin/bash
# Hammer, the modelled piano: in tune, settles, dampers, the blow, voices,
# reset and extremes. See tools/hammer_test.cpp; how it compares with the
# recordings is tools/hammer_reference.sh.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/hammer_test.cpp" "$LIB" -o "$DIR/hammer_test" || exit 1
"$DIR/hammer_test"
