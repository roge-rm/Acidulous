#!/bin/bash
# An object mount for another machine type is refused. See tools/mount_type_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -pthread -fsanitize=address -fno-omit-frame-pointer \
    -I "$ROOT/app/src/main/cpp" "$ROOT/tools/mount_type_test.cpp" "$LIB" -o "$DIR/mount_type_test" || exit 1
# The last machine is still mounted at exit, which isn't a leak worth a report.
ASAN_OPTIONS=detect_leaks=0 "$DIR/mount_type_test"
