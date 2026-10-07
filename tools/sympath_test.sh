#!/bin/bash
# Sympath, strings over a buzzing bridge: tuning, bridge, sympathy, tanpura, hands.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/sympath_test.cpp" "$LIB" -o "$DIR/sympath_test" || exit 1
"$DIR/sympath_test"
