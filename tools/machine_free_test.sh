#!/bin/bash
# A replaced machine frees its mounted objects. See tools/machine_free_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -pthread -fsanitize=address -fno-omit-frame-pointer \
    -I "$ROOT/app/src/main/cpp" "$ROOT/tools/machine_free_test.cpp" "$LIB" -o "$DIR/machine_free_test" || exit 1
# The machine still mounted at exit is not a leak worth a report; a cloud is.
ASAN_OPTIONS=detect_leaks=1 "$DIR/machine_free_test" > "$DIR/out" 2>&1
if grep -q "buildCloud\|CloudSet" "$DIR/out"; then
    echo "FAIL: a cloud leaked"; grep -m3 "buildCloud\|CloudSet" "$DIR/out"; exit 1
fi
echo "PASS"
