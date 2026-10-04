#!/bin/bash
# A fingerprint of a busy song through the whole engine: run it before and
# after a change to how blocks are rendered, and the two must match. See
# tools/song_fingerprint.cpp.
#
#   song_fingerprint.sh [seconds] [workers]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
# TSAN=1 builds the engine and this with ThreadSanitizer, into its own
# folder, to check the workers share nothing they shouldn't.
FLAGS="-O2"
if [ "${TSAN:-0}" = 1 ]; then
    FLAGS="-O1 -g -fsanitize=thread"
    export HOST_ENGINE_FLAGS="$FLAGS" HOST_ENGINE_OUT="$ROOT/build/host-engine-tsan"
fi
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ $FLAGS -std=c++17 -pthread -I "$CPP" "$ROOT/tools/song_fingerprint.cpp" "$LIB" -o "$DIR/song_fingerprint" || exit 1
# ThreadSanitizer needs the address layout unrandomised on this kernel.
if [ "${TSAN:-0}" = 1 ]; then setarch "$(uname -m)" -R "$DIR/song_fingerprint" "$@"; else "$DIR/song_fingerprint" "$@"; fi
