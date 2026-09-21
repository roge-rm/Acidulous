#!/bin/bash
# Bias, the four-track, read back off a reel built out of a ramp. See
# tools/bias_test.cpp.
#
# Links the host-engine archive for the registry, as scheduler_test does, but
# needs no Rack: what is being asked here is what the machine reads when it is
# told where the song is, which is a question with an exact answer.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/bias_test.cpp" "$LIB" \
    -o "$DIR/bias_test" || exit 1
"$DIR/bias_test"
