#!/bin/bash
# Timber's jets in tune on the octave and twelfth registers, bottom octave included.
#   timber_test.sh [measure PATCH REG | wav PATCH OUT.wav]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -pthread -I "$CPP" "$ROOT/tools/timber_test.cpp" "$LIB" -o "$DIR/timber_test" || exit 1
ACIDULOUS_ROOT="$ROOT" "$DIR/timber_test" "$@"
