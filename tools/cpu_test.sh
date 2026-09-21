#!/bin/bash
# What the machines and effects cost per block, worst case. See tools/cpu_test.cpp.
#
#
# -O2 and no sanitisers, deliberately: this is the one harness whose numbers
# are the point, and a sanitised build reports several times the real figure.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/cpu_test.cpp" "$LIB" -o "$DIR/cpu_test" || exit 1
ACIDULOUS_ROOT="$ROOT" "$DIR/cpu_test" "$@"
