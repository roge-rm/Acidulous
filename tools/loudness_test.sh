#!/bin/bash
# The loudness meter against the EBU's own test signals. See tools/loudness_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O2 -std=c++17 -fsanitize=address,undefined -I "$ROOT/app/src/main/cpp" "$ROOT/tools/loudness_test.cpp" -o "$DIR/loudness_test" || exit 1
"$DIR/loudness_test"
