#!/bin/bash
# The scene scheduler, driven the way the engine drives it. See
# tools/scheduler_test.cpp.
#
# Its own runner rather than a line in all_tests.sh's header-only loop,
# because this is the first sequencer harness that is *not* header-only: a
# SceneScheduler wants live Racks, a Rack wants a Machine, and a Machine wants
# the registry. So it links the same host-engine archive reset_test and
# bank_test use, plus the rack and the eventor chain a note travels down.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/scheduler_test.cpp" \
    "$CPP/engine/rack/Rack.cpp" "$CPP"/engine/eventor/*.cpp "$LIB" \
    -o "$DIR/scheduler_test" || exit 1
"$DIR/scheduler_test"
