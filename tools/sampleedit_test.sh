#!/bin/bash
# What a recording needs doing to it. See tools/sampleedit_test.cpp.
#
# Its own runner rather than a line in all_tests.sh's header-only loop,
# because SampleEdit has a translation unit of its own. It is small enough to
# compile here rather than linking the whole host engine: MultiFilter is a
# header and Sample.h is a struct, so this is two files and no archive.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/sampleedit_test.cpp" "$CPP/engine/core/SampleEdit.cpp" \
    -o "$DIR/sampleedit_test" || exit 1
"$DIR/sampleedit_test"
