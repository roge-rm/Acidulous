#!/bin/bash
# Checks an effect on the input ends up in the recording. See
# tools/inputfx_test.cpp.
#
# Builds a whole Engine, because the test is about the order of the input
# chain, the input bus and the recorder.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/inputfx_test.cpp" \
    "$CPP/engine/rack/Engine.cpp" "$CPP/engine/rack/Rack.cpp" "$CPP/engine/rack/MasterBus.cpp" \
    "$CPP/engine/core/Capture.cpp" "$CPP"/engine/inputmod/*.cpp \
    "$LIB" -o "$DIR/inputfx_test" || exit 1
TMPDIR="$DIR" "$DIR/inputfx_test"
