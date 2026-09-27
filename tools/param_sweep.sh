#!/bin/bash
# Every knob of every machine and effect at its ends and at random. See
# tools/param_sweep.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$ROOT/app/src/main/cpp" "$ROOT/tools/param_sweep.cpp" "$LIB" -o "$DIR/param_sweep" || exit 1
"$DIR/param_sweep"
