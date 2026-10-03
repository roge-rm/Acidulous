#!/bin/bash
# A fingerprint of a busy song through the whole engine: run it before and
# after a change to how blocks are rendered, and the two must match. See
# tools/song_fingerprint.cpp.
#
#   song_fingerprint.sh [seconds]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/song_fingerprint.cpp" "$LIB" -o "$DIR/song_fingerprint" || exit 1
"$DIR/song_fingerprint" "$@"
