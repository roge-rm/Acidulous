#!/bin/bash
# Renders every machine, panics it, renders the same thing again, and checks
# the two are identical bit for bit. See tools/reset_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

# Cipher's input and Molt's take use the recording named in local.env if
# there is one, like audition.sh. An empty ACIDULOUS_INPUT_FILE uses the
# synthetic phrase.
if [ -z "${ACIDULOUS_INPUT_FILE+set}" ] && [ -f "$ROOT/tools/local.env" ]; then
    # shellcheck disable=SC1091
    . "$ROOT/tools/local.env"
fi
export ACIDULOUS_INPUT_FILE="${ACIDULOUS_INPUT_FILE:-}"

# Every machine, effect and dsp file, compiled once and cached. See
# tools/host_engine.sh.
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/reset_test.cpp" "$LIB" -o "$DIR/reset_test" || exit 1
"$DIR/reset_test" "$ROOT/tools/banks"
