#!/bin/bash
# Tests the Link tempo-following maths, and that the vendored library finds a
# peer and agrees with it.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O2 -std=c++17 -w -DLINK_PLATFORM_LINUX=1 -DASIO_STANDALONE \
    -I "$CPP" -I "$CPP/third_party/link/include" -I "$CPP/third_party/asio/include" \
    "$ROOT/tools/link_test.cpp" -pthread -o "$DIR/link_test" || exit 1
"$DIR/link_test"
