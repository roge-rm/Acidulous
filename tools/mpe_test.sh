#!/bin/bash
# Two notes, move one, and require the other to be unchanged bit for bit.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/mpe_test.cpp" "$LIB" -o "$DIR/mpe_test" || exit 1
"$DIR/mpe_test"
