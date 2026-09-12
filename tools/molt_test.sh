#!/bin/bash
# Molt, on a voice nobody has to sing: the analyser against a known pitch, and
# the machine against the one claim it exists to make - that pitch and formant
# move apart. See tools/molt_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/molt_test.cpp" \
    "$CPP/engine/core/Utterance.cpp" "$CPP/engine/machine/molt/Molt.cpp" \
    "$CPP"/engine/dsp/*.cpp -o "$DIR/molt_test" || exit 1
"$DIR/molt_test"
