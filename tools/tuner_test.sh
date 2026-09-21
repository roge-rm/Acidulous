#!/bin/bash
# The gate. See tools/tuner_test.cpp.
#
# Rendered rather than evaluated: the two things that separate a good gate from
# a bad one - chattering on a signal that sits on the threshold, and opening
# for a room instead of for a note - are not visible in a frequency response.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/tuner_test.cpp" "$LIB" -o "$DIR/tuner_test" || exit 1
"$DIR/tuner_test"
