#!/bin/bash
# Chanter, bagpipes and hurdy-gurdy: tuning, the bag, grace notes, the dog.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/chanter_test.cpp" "$LIB" -o "$DIR/chanter_test" || exit 1
"$DIR/chanter_test"
