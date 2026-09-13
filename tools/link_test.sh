#!/bin/bash
# The Link milestone, at a desk: the arithmetic of following, and the
# vendored library actually finding a peer and agreeing with it.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O2 -std=c++17 -w -DLINK_PLATFORM_LINUX=1 -DASIO_STANDALONE \
    -I "$CPP" -I "$CPP/third_party/link/include" -I "$CPP/third_party/asio/include" \
    "$ROOT/tools/link_test.cpp" -pthread -o "$DIR/link_test" || exit 1
"$DIR/link_test"
