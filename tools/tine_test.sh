#!/bin/bash
# Tine, bars, tines and pans: tuning, partials, tube, motor, dampers, bloom.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/tine_test.cpp" "$LIB" -o "$DIR/tine_test" || exit 1
"$DIR/tine_test"
