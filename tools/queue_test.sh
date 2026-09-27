#!/bin/bash
# Several threads pushing into one engine queue. See tools/queue_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O2 -g -std=c++17 -pthread -I "$ROOT/app/src/main/cpp" "$ROOT/tools/queue_test.cpp" -o "$DIR/queue_test" || exit 1
"$DIR/queue_test"
