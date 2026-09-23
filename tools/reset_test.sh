#!/bin/bash
# Render every machine, panic it, render the same performance again, and
# require the two to be identical bit for bit. See tools/reset_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

# Every machine, effect and dsp file, compiled once and cached; see
# tools/host_engine.sh.
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/reset_test.cpp" "$LIB" -o "$DIR/reset_test" || exit 1
"$DIR/reset_test" "$ROOT/tools/banks"
