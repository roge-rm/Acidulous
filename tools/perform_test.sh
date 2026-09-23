#!/bin/bash
# The held effects on the master, sample by sample. See tools/perform_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O2 -std=c++17 -fsanitize=address,undefined -I "$ROOT/app/src/main/cpp" "$ROOT/tools/perform_test.cpp" -o "$DIR/perform_test" || exit 1
"$DIR/perform_test"
