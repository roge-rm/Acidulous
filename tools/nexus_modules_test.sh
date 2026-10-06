#!/bin/bash
# Tests Nexus's modules one at a time. See tools/nexus_modules_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/nexus_modules_test.cpp" "$LIB" -o "$DIR/nexus_modules_test" || exit 1
"$DIR/nexus_modules_test"
