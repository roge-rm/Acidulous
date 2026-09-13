#!/bin/bash
# Is every factory patch a patch? The floor under the banks.
#
# Pass a unit name to check one: tools/bank_test.sh Trinity, or fx.Delay.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/bank_test.cpp" "$LIB" -o "$DIR/bank_test" || exit 1
ACIDULOUS_ROOT="$ROOT" "$DIR/bank_test" "$@"
