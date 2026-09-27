#!/bin/bash
# Tests time-stretch on its own. See tools/stretch_test.cpp.
#
# Header-only, so no engine library or file on disk is needed.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/stretch_test.cpp" -o "$DIR/stretch_test" || exit 1
"$DIR/stretch_test"
