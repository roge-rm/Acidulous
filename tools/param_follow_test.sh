#!/bin/bash
# Every machine follows a parameter set while it plays. See tools/param_follow_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/param_follow_test.cpp" "$LIB" -o "$DIR/param_follow_test" || exit 1
"$DIR/param_follow_test"
