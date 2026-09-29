#!/bin/bash
# The cutter finds the singing, the steady vowel and the consonant in a take.
# See tools/voice_cut_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/voice_cut_test.cpp" "$LIB" -o "$DIR/voice_cut_test" || exit 1
"$DIR/voice_cut_test"
