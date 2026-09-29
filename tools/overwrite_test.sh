#!/bin/bash
# Every machine writes its whole block, whatever was in the buffer. See
# tools/overwrite_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/overwrite_test.cpp" "$LIB" -o "$DIR/overwrite_test" || exit 1
"$DIR/overwrite_test"
