#!/bin/bash
# Drives the scene scheduler the way the engine does. See
# tools/scheduler_test.cpp.
#
# Not header-only: it needs live Racks, Machines and the registry, so it links
# the host engine archive plus the rack and the modifier chain.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/scheduler_test.cpp" \
    "$CPP/engine/rack/Rack.cpp" "$CPP"/engine/inputmod/*.cpp "$LIB" \
    -o "$DIR/scheduler_test" || exit 1
"$DIR/scheduler_test"
